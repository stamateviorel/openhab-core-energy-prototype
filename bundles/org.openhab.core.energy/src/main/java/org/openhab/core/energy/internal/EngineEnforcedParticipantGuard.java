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

import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.DecisionKind;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.ParticipantState;

/**
 * Where a decision meets the engine-owned prohibitions. Every decision passes here, whichever algorithm produced it.
 * <p>
 * <strong>The prohibitions are engine-owned and the list is closed.</strong> A contributed algorithm cannot set one
 * aside, cannot lower one, and cannot promote a reading of its own into one. The three this guard can decide from a
 * single decision plus the cycle snapshot are:
 * <ul>
 * <li><strong>Hands-off.</strong> A consumer of any of the four profile classes may declare itself hands-off, after
 * which the engine neither starts, stops, trims nor re-modes it - while still reading everything it declares.
 * Withheld in either direction: leaving a device alone is not the same as being allowed to switch it off.</li>
 * <li><strong>The user-declared level gate.</strong> "Run at level &ge; N" is enforced here rather than left to the
 * algorithm that happens to consult it, so a contributed algorithm cannot start a gated device by ignoring the gate.
 * A decision that stops or reduces the device is never blocked by a gate.</li>
 * <li><strong>The readiness interlock.</strong> "Not ready, not started" - an engine-initiated <em>start</em> is
 * withheld while the interlock is open. Stopping, reducing, or adjusting one already running is not a start.</li>
 * </ul>
 * The remaining two members of the closed list are enforced where a <em>load</em> rather than a decision meets them:
 * device protections in {@link DeviceProtectionAlgorithm} and {@link EngineProhibitions#protectionHolding}, and the
 * enumeration of safety inputs in {@link EnergyContextFactory}, which is the only thing that decides what counts as
 * one. What stays overridable is the engine's own level-derived steering, which is an algorithm input, not a
 * prohibition.
 * <p>
 * The prototype shipped a second reading of this - a guard that vetoed nothing, on the argument that the
 * engine-contract enumeration of engine-owned concerns did not mention either prohibition - selected by a
 * {@code participantGuard} configuration parameter. The owner's decision closes that question, so the alternative
 * implementation and the parameter are both gone. A reviewer should see that as the reduction in flexibility it is:
 * an algorithm can no longer be trusted with these by configuration.
 * <p>
 * The guard runs after conflict resolution and the electrical-limit floor, so it can only ever withhold a decision,
 * never create or strengthen one. What it cannot do is stop the floor <em>booking</em> a hands-off load - that is
 * deliberate and lives in {@link ElectricalLimitFloor}, because a device the engine may not steer still draws power
 * the others have to be trimmed against.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EngineEnforcedParticipantGuard {

    /**
     * The id of this guard, used in log lines.
     */
    public static final String ID = "engine-enforced";

    /**
     * Returns the id of this guard.
     *
     * @return the guard id
     */
    public String getId() {
        return ID;
    }

    /**
     * Tests a decision against the addressed participant's own declaration.
     *
     * @param decision the decision about to be dispatched
     * @param context the snapshot of the current cycle
     * @return the reason the decision must be withheld, or empty when no prohibition forbids it
     */
    public Optional<String> veto(Decision decision, EnergyContext context) {
        ParticipantState state = context.participants().get(decision.participantId());
        if (state == null || state.consumer().isEmpty()) {
            return Optional.empty();
        }
        if (EngineProhibitions.isHandsOff(state)) {
            return Optional.of("the participant is marked hands-off and is to be left alone");
        }
        if (belowTheGateRung(decision) && wouldRun(decision.action())) {
            Optional<String> gate = EngineProhibitions.levelGateClosed(state, context.level());
            if (gate.isPresent()) {
                return gate;
            }
        }
        if (!state.ready() && isStart(decision.action(), state)) {
            return Optional.of("the readiness interlock of the participant is open");
        }
        return Optional.empty();
    }

    /**
     * Tells whether this action would start a participant that is not currently running.
     * <p>
     * "Start" has no definition in the corpus; the reading used here is that a participant which does not report
     * itself as running is being started by any action other than an off or a hold.
     *
     * @param action the proposed action
     * @param state the participant's state in this cycle
     * @return {@code true} if the action is an engine-initiated start
     */
    private boolean isStart(ControlAction action, ParticipantState state) {
        if (state.isReportedOn()) {
            return false;
        }
        return switch (action) {
            case ControlAction.Switch onOff -> onOff.on();
            case ControlAction.Hold hold -> false;
            default -> true;
        };
    }

    /**
     * Tells whether this action would run the participant - the only thing a closed level gate forbids. Switching it
     * off, or holding it where it is, never is.
     *
     * @param action the proposed action
     * @return {@code true} if the gate has to be consulted
     */
    private boolean wouldRun(ControlAction action) {
        return switch (action) {
            case ControlAction.Switch onOff -> onOff.on();
            case ControlAction.Hold hold -> false;
            default -> true;
        };
    }

    /**
     * Tells whether the ladder lets the level gate outrank this decision.
     * <p>
     * The ladder is <em>electrical limits &gt; device protections &gt; level gates &gt; optimization</em>, so a
     * decision from a stronger rung is above the gate and the gate may not withhold it: a compressor whose duty-cycle
     * guarantee has expired starts even in a blocked hour. Reading the rung from {@link ConstraintLadder} rather than
     * naming the two kinds here is what keeps the order in one place.
     *
     * @param decision the decision
     * @return {@code true} if the gate may withhold it
     */
    private boolean belowTheGateRung(Decision decision) {
        return ConstraintLadder.rank(decision.kind()) >= ConstraintLadder.rank(DecisionKind.LEVEL_GATE);
    }
}
