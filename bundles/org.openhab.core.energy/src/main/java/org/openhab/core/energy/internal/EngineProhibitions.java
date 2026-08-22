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
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.BatchProfile;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.LevelGate;
import org.openhab.core.energy.ParticipantState;
import org.openhab.core.energy.SimpleProfile;

/**
 * The engine-owned prohibitions, in the two shapes the electrical-limit rung and the safe state need them: which
 * participants they may not steer, and which ones a declared device protection is currently holding.
 * <p>
 * The list of prohibitions is closed and engine-owned - the hands-off flag, the user-declared level gate, the
 * readiness interlock, device protections, and which readings count as safety inputs.
 * {@link EngineEnforcedParticipantGuard} is where a <em>decision</em> meets them; this class is where a <em>load</em>
 * does, because the electrical rung and the safe state have to reason about participants no algorithm proposed
 * anything for.
 * <p>
 * <strong>One model gap is concentrated here on purpose, so that closing it is a one-file change.</strong>
 * {@link #isRunningBatch(ParticipantState)} recognises a Batch programme mid-run from the steered Item reporting
 * itself on. The model carries no explicit "programme running" state and no start moment, so a Batch consumer whose
 * Item reports nothing is treated as not running - the conservative reading, since it makes the engine steerable
 * rather than making an unknown device untouchable.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
final class EngineProhibitions {

    private EngineProhibitions() {
    }

    /**
     * Tells whether this participant is a consumer its owner marked hands-off.
     * <p>
     * The flag lives on the consumer rather than on one profile class, so a wallbox, a dishwasher and a heat pump can
     * all say it - and saying it never costs the electrical rung its sight of the load, because the engine goes on
     * reading everything the declaration names.
     *
     * @param state the participant state
     * @return {@code true} if the engine must leave it alone
     */
    static boolean isHandsOff(ParticipantState state) {
        return state.consumer().filter(EnergyConsumer::handsOff).isPresent();
    }

    /**
     * Returns the reason the user-declared level gate currently forbids running this consumer, if it does.
     * <p>
     * The gate is engine-owned: it holds for every algorithm, built-in or contributed, rather than only for the one
     * that happens to consult it. It forbids <em>starting</em> or <em>keeping</em> a consumer running below its
     * declared level; a decision that stops or reduces it is never blocked by a gate, since the gate exists to keep
     * the device off.
     *
     * @param state the participant state
     * @param level the site level of this cycle
     * @return the reason, or empty when the gate is open or the consumer declares none
     */
    static Optional<String> levelGateClosed(ParticipantState state, EnergyLevel level) {
        Optional<EnergyConsumer> consumer = state.consumer();
        if (consumer.isEmpty()) {
            return Optional.empty();
        }
        Optional<LevelGate> gate = consumer.get().levelGate();
        if (gate.isEmpty() || gate.get().permits(level)) {
            return Optional.empty();
        }
        return Optional.of("the site level " + level + " is below the declared gate of " + gate.get().minimumLevel()
                + " and the gate is enforced for every algorithm");
    }

    /**
     * Tells whether this participant is a Batch programme that has already started.
     *
     * @param state the participant state
     * @return {@code true} if a programme is mid-run and must not be interrupted
     */
    static boolean isRunningBatch(ParticipantState state) {
        return state.consumer().filter(consumer -> consumer.profile() instanceof BatchProfile).isPresent()
                && state.isReportedOn();
    }

    /**
     * Tells whether the electrical rung and the safe state must leave this participant untouched, booking its draw
     * as load everything else is trimmed against.
     * <p>
     * These are the ladder's own two stated exceptions and there are no others.
     *
     * @param state the participant state
     * @return {@code true} if the participant may not be trimmed, deferred or shed
     */
    static boolean isExemptFromShedding(ParticipantState state) {
        return isHandsOff(state) || isRunningBatch(state);
    }

    /**
     * Returns the reason a declared device protection currently forbids switching this participant off, if one does.
     * <p>
     * Only the minimum runtime can hold a load <em>against</em> a reduction; the other three protections either ask
     * for a reduction themselves or apply to a device that is already off. The elapsed time is
     * {@link ParticipantState#protectionElapsed(java.time.Instant)} - the participant Item's own last state change,
     * and where the site does not persist it, the first-observation clock the snapshot stamped. Reading only the
     * device history here would make an unpersisted Item <em>unprotected</em> at this enforcement point while
     * {@link DeviceProtectionAlgorithm} reports it as protected on the fallback clock, which is how a fridge inside
     * its minimum runtime gets shed by the floor in the same cycle the engine says it is holding it.
     *
     * @param state the participant state
     * @param context the snapshot of the current cycle
     * @return the reason, or empty when no declared protection holds it
     */
    static Optional<String> protectionHolding(ParticipantState state, EnergyContext context) {
        Optional<EnergyConsumer> consumer = state.consumer();
        if (consumer.isEmpty() || !(consumer.get().profile() instanceof SimpleProfile profile)) {
            return Optional.empty();
        }
        Duration minOn = profile.minOn();
        if (minOn == null || !state.isReportedOn()) {
            return Optional.empty();
        }
        Optional<Duration> elapsed = state.protectionElapsed(context.timestamp());
        if (elapsed.isEmpty() || elapsed.get().compareTo(minOn) >= 0) {
            return Optional.empty();
        }
        return Optional.of("it has run for only " + elapsed.get() + " of its declared minOn of " + minOn);
    }
}
