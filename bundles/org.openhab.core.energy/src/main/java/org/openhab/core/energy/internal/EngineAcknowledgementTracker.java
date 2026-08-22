/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.core.energy.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.ParticipantState;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The core-owned acknowledgement window: the engine remembers what it last sent to each participant and does not
 * send it again until the participant reports the commanded value, or until the window runs out.
 * <p>
 * This is what keeps a device whose reported state lags its command - an OCPP charger with
 * {@code autoupdate="false"}, whose current-limit Item only changes once the charger has confirmed the
 * SetChargingProfile - from being commanded again on every cycle. Without it, an engine that compares its intent
 * against the Item's state re-sends forever.
 * <p>
 * <strong>What counts as an acknowledgement.</strong> The reported value, compared unit-aware, either equals the
 * commanded one or lies inside a tolerance band the participant declared. The band is an absolute quantity in the
 * control Item's own dimension, never a fraction of the commanded value: 15.999 A acknowledges 16 A on a charger
 * that declared a 0.01 A band, and does not on one that declared none. See
 * {@link EngineUnits#acknowledges(double, double, Double)}.
 * <p>
 * <strong>What expiry means.</strong> The command lapses and control resumes: the outstanding command is dropped,
 * logged, and the next cycle is free to decide afresh. It does not mean the participant is withheld from further
 * commands - the "safety-conservative" reading, under which a flaky charger silently drops out of management, is a
 * preserved alternative and is not implemented. The window is 60 s unless the participant declares its own.
 * <p>
 * A <em>different</em> command arriving while one is outstanding is not a repeat and is allowed through by default;
 * {@code ackSuppressChangedCommands} makes the window absolute instead. That question is still open in the corpus -
 * it only forbids re-sending "a command ... while the previous command is still unacknowledged" - so the parameter
 * stays.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EngineAcknowledgementTracker implements AcknowledgementTracker {

    /**
     * The id under which this tracker is selected in configuration.
     */
    public static final String ID = "engine";

    private final Logger logger = LoggerFactory.getLogger(EngineAcknowledgementTracker.class);

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Duration defaultWindow;
    private final boolean suppressChangedCommands;

    /**
     * Creates the tracker.
     *
     * @param defaultWindow how long to wait for an acknowledgement from a participant that declares no window of its
     *            own
     * @param suppressChangedCommands whether a command that differs from the outstanding one is suppressed too
     */
    public EngineAcknowledgementTracker(Duration defaultWindow, boolean suppressChangedCommands) {
        this.defaultWindow = defaultWindow;
        this.suppressChangedCommands = suppressChangedCommands;
    }

    /**
     * Tells whether a participant reporting this state has carried out exactly this action, under the same unit-aware
     * comparison the acknowledgement window uses and with no tolerance band.
     * <p>
     * The safe state uses it to avoid re-issuing a floor command a device is already sitting at, which would turn a
     * stale reading into one identical command per cycle.
     *
     * @param action the action in question
     * @param reportedState the participant's reported state
     * @return {@code true} if the reported state already is the action
     */
    public static boolean reportsExactly(ControlAction action, String reportedState) {
        return matches(action, reportedState, null);
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public void observe(EnergyContext context) {
        Instant now = context.timestamp();
        for (Map.Entry<String, Pending> entry : Map.copyOf(pending).entrySet()) {
            String participantId = entry.getKey();
            Pending outstanding = entry.getValue();
            ParticipantState state = context.participants().get(participantId);
            AcknowledgementTerms terms = termsFor(state);
            String reported = state == null ? null : state.reportedState();
            if (reported != null && matches(outstanding.action(), reported, terms.tolerance())) {
                pending.remove(participantId);
                logger.trace("Participant '{}' acknowledged {}", participantId, outstanding.action().describe());
            } else if (now.isAfter(outstanding.sentAt().plus(terms.window()))) {
                pending.remove(participantId);
                logger.debug(
                        "Acknowledgement window of {} expired for participant '{}' and command {}; the command lapses "
                                + "and control resumes",
                        terms.window(), participantId, outstanding.action().describe());
            }
        }
    }

    @Override
    public boolean isSuppressed(Decision decision, EnergyContext context) {
        Pending outstanding = pending.get(decision.participantId());
        if (outstanding == null) {
            return false;
        }
        if (outstanding.action().equals(decision.action())) {
            return true;
        }
        return suppressChangedCommands;
    }

    @Override
    public void recordDispatch(Decision decision, Instant at) {
        if (decision.action() instanceof ControlAction.Hold) {
            return;
        }
        pending.put(decision.participantId(), new Pending(decision.action(), at));
    }

    @Override
    public Set<String> pendingParticipants() {
        return Set.copyOf(pending.keySet());
    }

    @Override
    public void reset() {
        pending.clear();
    }

    @Override
    public void adopt(AcknowledgementTracker previous) {
        if (previous instanceof EngineAcknowledgementTracker engineTracker && !equals(engineTracker)) {
            pending.putAll(engineTracker.pending);
            logger.trace("Carried {} outstanding command(s) over to the rebuilt acknowledgement tracker",
                    engineTracker.pending.size());
        }
    }

    /**
     * Exposes the command outstanding for a participant, for tests and diagnostics.
     *
     * @param participantId the participant id
     * @return the outstanding command, or {@code null} when there is none
     */
    public @Nullable ControlAction outstanding(String participantId) {
        Pending outstanding = pending.get(participantId);
        return outstanding == null ? null : outstanding.action();
    }

    /**
     * Returns the acknowledgement terms in force for a participant: its own declared window and tolerance band where
     * it declares them, this tracker's defaults where it does not.
     *
     * @param state the participant's state in this cycle, or {@code null} when the cycle no longer knows it
     * @return the terms
     */
    private AcknowledgementTerms termsFor(@Nullable ParticipantState state) {
        return state == null ? new AcknowledgementTerms(defaultWindow, null)
                : AcknowledgementTerms.declaredBy(state.participant(), defaultWindow);
    }

    /**
     * Tests whether a reported state matches a command, under a unit-aware comparison and an optional absolute
     * tolerance band.
     *
     * @param action the outstanding command
     * @param reported the participant's reported state
     * @param tolerance the declared band as declared, or {@code null} for an exact comparison
     * @return {@code true} if the command counts as acknowledged
     */
    private static boolean matches(ControlAction action, String reported, @Nullable QuantityType<?> tolerance) {
        return switch (action) {
            case ControlAction.Switch on -> reported.trim().equalsIgnoreCase(on.on() ? "ON" : "OFF");
            case ControlAction.SetMode mode -> reported.trim().equalsIgnoreCase(mode.mode());
            case ControlAction.SetPower power ->
                matches(reported, EngineUnits.watts(power.power()), Units.WATT, bandIn(tolerance, EngineUnits::watts));
            case ControlAction.SetCurrent current -> matches(reported, EngineUnits.amperes(current.current()),
                    Units.AMPERE, bandIn(tolerance, EngineUnits::amperes));
            case ControlAction.Hold hold -> true;
        };
    }

    /**
     * Converts a declared tolerance band into the unit the comparison is made in.
     * <p>
     * A band declared in a dimension the command does not use - amps against a power setpoint - is not applicable to
     * that command and yields {@code null}, which the comparison reads as "exact". That is deliberately the strict
     * outcome: a band nobody can interpret must not widen what counts as an acknowledgement.
     *
     * @param band the declared band, or {@code null} when the participant declares none
     * @param conversion the conversion into the comparison's unit
     * @return the band in that unit, or {@code null} when none is declared or it does not convert
     */
    private static @Nullable Double bandIn(@Nullable QuantityType<?> band,
            Function<QuantityType<?>, OptionalDouble> conversion) {
        if (band == null) {
            return null;
        }
        OptionalDouble converted = conversion.apply(band);
        return converted.isPresent() ? converted.getAsDouble() : null;
    }

    /**
     * Compares a reported state against a commanded figure.
     *
     * @param reported the reported state as text
     * @param commanded the commanded value, or empty if it could not be converted
     * @param unit the unit both figures are compared in
     * @param tolerance the declared band in that unit, or {@code null} for an exact comparison
     * @return {@code true} if the reported state acknowledges the command
     */
    private static boolean matches(String reported, OptionalDouble commanded, Unit<?> unit,
            @Nullable Double tolerance) {
        OptionalDouble observed = EngineUnits.parse(reported, unit);
        return commanded.isPresent() && observed.isPresent()
                && EngineUnits.acknowledges(observed.getAsDouble(), commanded.getAsDouble(), tolerance);
    }

    /**
     * One outstanding command.
     *
     * @param action what was sent
     * @param sentAt when it was sent
     *
     * @author Stamate Viorel - Initial contribution
     */
    private record Pending(ControlAction action, Instant sentAt) {
    }
}
