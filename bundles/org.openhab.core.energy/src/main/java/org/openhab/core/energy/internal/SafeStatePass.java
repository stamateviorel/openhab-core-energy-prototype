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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.BatchProfile;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.ControllableProfile;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.DecisionKind;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.ModeControllableProfile;
import org.openhab.core.energy.ParticipantState;
import org.openhab.core.energy.SimpleProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Freeze and floor: what the engine does while a reading it counts as a safety input is stale.
 * <p>
 * The behaviour is not a policy the site selects - the prototype's {@code degradeSafeOnStaleMeasurements}
 * parameter is gone, and a stale safety input always produces this. Four things happen at once, one per profile
 * class, and each is a <em>reduction</em>, so nothing here can push the site past a limit:
 * <ul>
 * <li>every increase is refused - that half lives in {@link ElectricalLimitFloor}, because it is a property of the
 * allocation loop rather than of a participant;</li>
 * <li>a Controllable load is floored at its declared minimum rather than left at whatever it was last allowed;</li>
 * <li>a ModeControllable load drops to its most restricted mode;</li>
 * <li>a Simple load is switched off.</li>
 * </ul>
 * <strong>Three things it must not do, and getting any of them wrong is a safety bug.</strong> It is subject to
 * device protections - a Simple load inside its minimum runtime is <em>held</em>, not shed, the freeze refusing
 * increases immediately while shedding waits for the ladder. It never interrupts a Batch programme already running.
 * And it never sheds a consumer its owner marked hands-off, whose draw is still counted by the floor.
 * <p>
 * A decision an algorithm already produced for a participant is left alone when it reduces load: the safe state
 * exists to floor a site nobody is steering down, not to overrule a reduction somebody else asked for.
 * <p>
 * <strong>Where a decision produced here sits on the ladder.</strong> It carries
 * {@link DecisionKind#ELECTRICAL_LIMIT}, the strongest rung, because a degraded safety input is the one condition
 * under which the engine cannot prove it is inside its limits. The corpus has no separate kind for it and adding one
 * would change an exported enum, so this is an inference, stated here rather than buried.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class SafeStatePass {

    /**
     * The algorithm id the safe state's own decisions carry.
     */
    public static final String ID = "safe-state";

    /**
     * The priority they carry - the strongest available, since nothing may outbid a degraded safety input.
     */
    public static final int PRIORITY = 0;

    private final Logger logger = LoggerFactory.getLogger(SafeStatePass.class);

    /**
     * Floors every steered participant the cycle has not already decided about.
     *
     * @param context the snapshot of the cycle
     * @param floored what the electrical-limit floor admitted
     * @return the admissions to dispatch, the floor's own plus the safe state's
     */
    public List<ElectricalLimitFloor.Admission> apply(EnergyContext context,
            List<ElectricalLimitFloor.Admission> floored) {
        if (!context.measurementsStale()) {
            return floored;
        }
        Set<String> decided = new HashSet<>();
        for (ElectricalLimitFloor.Admission admission : floored) {
            decided.add(admission.decision().participantId());
        }
        List<ElectricalLimitFloor.Admission> result = new ArrayList<>(floored);
        for (ParticipantState state : context.consumers()) {
            if (decided.contains(state.id()) || EngineProhibitions.isExemptFromShedding(state)) {
                continue;
            }
            floorOf(state, context).ifPresent(action -> result
                    .add(new ElectricalLimitFloor.Admission(decision(state, action), null, "safety input is stale")));
        }
        return result;
    }

    /**
     * Returns the action that floors one participant, or empty when it is already there, has nothing to be floored
     * to, or a declared protection is holding it.
     *
     * @param state the participant state
     * @param context the snapshot of the cycle
     * @return the action, or empty
     */
    private Optional<ControlAction> floorOf(ParticipantState state, EnergyContext context) {
        Optional<EnergyConsumer> maybeConsumer = state.consumer();
        if (maybeConsumer.isEmpty()) {
            return Optional.empty();
        }
        return switch (maybeConsumer.get().profile()) {
            case ControllableProfile controllable ->
                floorTo(state, controllable.isPowerBased() ? ControlAction.watts(controllable.min().doubleValue())
                        : ControlAction.amperes(controllable.min().doubleValue()));
            case ModeControllableProfile modes -> floorTo(state, ControlAction.mode(modes.mostRestricted()));
            case SimpleProfile simple -> switchOff(state, context);
            // a Batch programme that has not started is simply not started: the floor refuses every increase
            case BatchProfile batch -> Optional.empty();
        };
    }

    /**
     * Switches a Simple load off unless a declared device protection is currently holding it on.
     *
     * @param state the participant state
     * @param context the snapshot of the cycle
     * @return the OFF action, or empty
     */
    private Optional<ControlAction> switchOff(ParticipantState state, EnergyContext context) {
        if (!state.isReportedOn()) {
            return Optional.empty();
        }
        Optional<String> held = EngineProhibitions.protectionHolding(state, context);
        if (held.isPresent()) {
            logger.debug("Holding '{}' on while the safety input is stale: {}", state.id(), held.get());
            return Optional.empty();
        }
        return Optional.of(ControlAction.off());
    }

    /**
     * Returns the action unless the participant already reports it, so a stale reading does not turn into one
     * identical command per cycle.
     *
     * @param state the participant state
     * @param action the action that would floor it
     * @return the action, or empty when it would change nothing
     */
    private Optional<ControlAction> floorTo(ParticipantState state, ControlAction action) {
        String reported = state.reportedState();
        if (reported != null && EngineAcknowledgementTracker.reportsExactly(action, reported)) {
            return Optional.empty();
        }
        return Optional.of(action);
    }

    private Decision decision(ParticipantState state, ControlAction action) {
        return new Decision(state.id(), action, ID, PRIORITY, DecisionKind.ELECTRICAL_LIMIT,
                "a safety input is stale, so the site is frozen and floored");
    }
}
