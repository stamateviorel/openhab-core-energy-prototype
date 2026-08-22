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
package org.openhab.core.energy;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * One participant as it was observed at the start of a cycle: the declaration plus everything the engine read about
 * it, frozen.
 * <p>
 * {@code reportedState} is the steered Item's state rendered as a string rather than as an openHAB
 * {@code State}, on purpose: it keeps the algorithm-facing API free of core type hierarchies and therefore usable
 * from a script. The engine compares it against the last command it sent when it decides whether that command has
 * been acknowledged - devices whose reported state lags the command (an {@code autoupdate="false"} OCPP charger is
 * the canonical case) are recognised by this lag, not by a binding-specific callback.
 * <p>
 * {@code phases} carries what the participant's own declaration says it draws on, copied here so that the floor
 * reasons about one frozen object. An empty set means "not declared", and such a participant is counted against the
 * site total only - never attributed to all three phases nor to a guessed one.
 * <p>
 * <strong>Where a protection's elapsed time comes from, and what happens when there is none.</strong>
 * {@code lastChangedAt} is the steered Item's own last state change, which is where every protection duration
 * ({@code minOn}, {@code maxOn}, {@code minOff}, {@code maxOff}) is measured from: the engine keeps no protection
 * timers, so a compressor's cooldown survives a restart wherever the Item is persisted, and an uncommanded
 * OFF&rarr;ON transition starts a minimum runtime exactly as an engine-initiated start would. When the Item has no
 * readable history the clock starts at {@code firstObservedAt}, the first cycle that saw the participant, and
 * {@link #protectionHistoryUnknown()} says so - a degraded guarantee that is reported rather than assumed.
 * {@code protectionHistory} says <strong>which</strong> degraded condition it is, because "nothing is keeping this
 * Item's history" and "the history is kept and holds no change yet" have opposite remedies and only one of them is a
 * fault - see {@link ProtectionHistory} and owner decision D28.
 * <p>
 * The two are deliberately one field pair read through one accessor, {@link #protectionElapsed(Instant)}. Every
 * enforcement point - the algorithm that proposes protections, the electrical-limit floor and the safe state - has
 * to answer "how long has this been in this state" the same way, or a site that does not persist an Item would find
 * its fridge protected by one of them and shed by another.
 *
 * @param participant the declaration this state belongs to
 * @param measuredPower the measured draw, or {@code null} when the participant declares no measurement or the
 *            measurement is unavailable; positive means consumption for a consumer, and follows the provider sign
 *            convention for a provider
 * @param reportedState the steered Item's state as reported, or {@code null} when unknown
 * @param ready whether the readiness interlock permits an engine-initiated start
 * @param commandPending whether a command sent in an earlier cycle is still unacknowledged
 * @param phases the phase numbers this participant draws on, empty when not declared
 * @param lastChangedAt when the steered Item last changed state, or {@code null} when the site does not persist it
 * @param firstObservedAt the first cycle that observed this participant, used as the protection clock only while
 *            {@code lastChangedAt} is {@code null}; {@code null} when it has never had to be
 * @param protectionHistory where the protection clock comes from, and which condition applies when it is not the
 *            device's own history
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record ParticipantState(EnergyParticipant participant, @Nullable QuantityType<Power> measuredPower,
        @Nullable String reportedState, boolean ready, boolean commandPending, Set<Integer> phases,
        @Nullable Instant lastChangedAt, @Nullable Instant firstObservedAt, ProtectionHistory protectionHistory) {

    /**
     * Validates the state and takes an immutable, numerically ordered copy of the phase set.
     *
     * @throws IllegalArgumentException if the measurement is not a power or a phase number is below one
     */
    public ParticipantState {
        QuantityType<Power> measured = measuredPower;
        if (measured != null) {
            ModelChecks.requireCompatible(measured, Units.WATT, "measuredPower");
        }
        for (Integer phase : phases) {
            if (phase < 1) {
                throw new IllegalArgumentException("phase numbers start at 1 but was " + phase);
            }
        }
        phases = Collections.unmodifiableSet(new TreeSet<>(phases));
    }

    /**
     * Creates the state of a participant about which nothing has been read yet: no measurement, no reported state,
     * ready, nothing pending, no phases declared.
     *
     * @param participant the declaration
     * @return the state
     */
    public static ParticipantState of(EnergyParticipant participant) {
        return new ParticipantState(participant, null, null, true, false, Set.of(), null, null,
                ProtectionHistory.FROM_DEVICE);
    }

    /**
     * Returns a copy carrying a measured power.
     *
     * @param watts the measured draw in watts
     * @return the copy
     */
    public ParticipantState withMeasuredWatts(double watts) {
        return new ParticipantState(participant, new QuantityType<>(watts, Units.WATT), reportedState, ready,
                commandPending, phases, lastChangedAt, firstObservedAt, protectionHistory);
    }

    /**
     * Returns a copy carrying a reported state.
     *
     * @param state the steered Item's state as reported
     * @return the copy
     */
    public ParticipantState withReportedState(String state) {
        return new ParticipantState(participant, measuredPower, state, ready, commandPending, phases, lastChangedAt,
                firstObservedAt, protectionHistory);
    }

    /**
     * Returns a copy carrying a readiness flag.
     *
     * @param isReady whether the readiness interlock permits an engine-initiated start
     * @return the copy
     */
    public ParticipantState withReady(boolean isReady) {
        return new ParticipantState(participant, measuredPower, reportedState, isReady, commandPending, phases,
                lastChangedAt, firstObservedAt, protectionHistory);
    }

    /**
     * Returns a copy carrying a pending-command flag.
     *
     * @param pending whether an earlier command is still unacknowledged
     * @return the copy
     */
    public ParticipantState withCommandPending(boolean pending) {
        return new ParticipantState(participant, measuredPower, reportedState, ready, pending, phases, lastChangedAt,
                firstObservedAt, protectionHistory);
    }

    /**
     * Returns a copy carrying a phase assignment.
     *
     * @param assignedPhases the phase numbers this participant draws on
     * @return the copy
     */
    public ParticipantState withPhases(Set<Integer> assignedPhases) {
        return new ParticipantState(participant, measuredPower, reportedState, ready, commandPending, assignedPhases,
                lastChangedAt, firstObservedAt, protectionHistory);
    }

    /**
     * Returns a copy carrying the moment the steered Item last changed state.
     *
     * @param changedAt when the steered Item last changed state
     * @return the copy
     */
    public ParticipantState withLastChangedAt(Instant changedAt) {
        return new ParticipantState(participant, measuredPower, reportedState, ready, commandPending, phases, changedAt,
                firstObservedAt, ProtectionHistory.FROM_DEVICE);
    }

    /**
     * Returns a copy carrying the moment this participant was first observed - the fallback protection clock for an
     * Item whose state history cannot be read.
     *
     * @param observedAt the moment of the first cycle that saw it
     * @return the copy
     */
    public ParticipantState withFirstObservedAt(Instant observedAt) {
        return new ParticipantState(participant, measuredPower, reportedState, ready, commandPending, phases,
                lastChangedAt, observedAt, protectionHistory);
    }

    /**
     * Returns a copy carrying which of the two conditions produced an unreadable state history.
     * <p>
     * It is a separate step from {@link #withFirstObservedAt(Instant)} because the fallback clock and the reason for
     * it come from two different places: the clock is stamped by the snapshot builder, the reason is read from the
     * site's persistence configuration. Source: owner decision D28.
     *
     * @param condition where the protection clock is coming from
     * @return the copy
     */
    public ParticipantState withProtectionHistory(ProtectionHistory condition) {
        return new ParticipantState(participant, measuredPower, reportedState, ready, commandPending, phases,
                lastChangedAt, firstObservedAt, condition);
    }

    /**
     * Returns how long the participant has been in its current state at the given moment, from the device's own
     * history alone.
     *
     * @param now the moment of the snapshot
     * @return the time since the last state change, or empty when that moment is unknown
     */
    public Optional<Duration> timeInState(Instant now) {
        Instant changed = lastChangedAt;
        if (changed == null || changed.isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(changed, now));
    }

    /**
     * Returns the elapsed time every device protection is measured against: the device's own history where it is
     * readable, and otherwise the time since this participant was first observed.
     * <p>
     * This is the accessor an enforcement point uses. Reading {@link #timeInState(Instant)} directly would answer
     * "unknown" for an Item the site does not persist, and an unknown that one enforcement point reads as "no
     * protection" while another measures it from first observation is how a fridge gets shed by the floor in the same
     * cycle the engine reports it as protected.
     *
     * @param now the moment of the snapshot
     * @return the elapsed time, or empty when neither clock is available
     */
    public Optional<Duration> protectionElapsed(Instant now) {
        Optional<Duration> fromHistory = timeInState(now);
        if (fromHistory.isPresent()) {
            return fromHistory;
        }
        Instant observed = firstObservedAt;
        if (observed == null || observed.isAfter(now)) {
            return observed == null ? Optional.empty() : Optional.of(Duration.ZERO);
        }
        return Optional.of(Duration.between(observed, now));
    }

    /**
     * Tells whether the protections of this participant are running on the first-observation clock because its state
     * history cannot be read - a degraded guarantee the engine reports rather than assumes away.
     * <p>
     * {@link #protectionHistory()} says which of the three degraded conditions it is, which is what a site needs in
     * order to know whether there is anything to fix.
     *
     * @return {@code true} if the device's own history is unavailable
     */
    public boolean protectionHistoryUnknown() {
        return lastChangedAt == null && firstObservedAt != null;
    }

    /**
     * Returns the participant id, for convenience.
     *
     * @return the id
     */
    public String id() {
        return participant.id();
    }

    /**
     * Tests whether a measurement is available.
     *
     * @return {@code true} if {@link #measuredPower()} is present
     */
    public boolean hasMeasurement() {
        return measuredPower != null;
    }

    /**
     * Returns the measured draw in watts, or zero when no measurement is available.
     *
     * @return the measured draw in watts
     */
    public double measuredWatts() {
        QuantityType<Power> measured = measuredPower;
        return measured == null ? 0 : ModelChecks.toDouble(measured, Units.WATT, "measuredPower");
    }

    /**
     * Tests whether the reported state reads as switched on.
     *
     * @return {@code true} if the reported state is "ON"
     */
    public boolean isReportedOn() {
        return "ON".equalsIgnoreCase(reportedState);
    }

    /**
     * Returns this participant as a consumer, if it is one.
     *
     * @return the consumer, or empty for a provider
     */
    public Optional<EnergyConsumer> consumer() {
        return participant instanceof EnergyConsumer consumer ? Optional.of(consumer) : Optional.empty();
    }

    /**
     * Returns this participant as a provider, if it is one.
     *
     * @return the provider, or empty for a consumer
     */
    public Optional<EnergyProvider> provider() {
        return participant instanceof EnergyProvider provider ? Optional.of(provider) : Optional.empty();
    }
}
