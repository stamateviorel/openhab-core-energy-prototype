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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.DecisionKind;
import org.openhab.core.energy.EnergyAlgorithm;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.ParticipantState;
import org.openhab.core.energy.ProtectionHistory;
import org.openhab.core.energy.SimpleProfile;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the Simple-consumer protection parameters into decisions, which is what makes the duty-cycle guarantee and
 * the cooldown observable behaviour rather than declared metadata.
 * <p>
 * Four rules, one per declared parameter, each the literal requirement wording:
 * <ul>
 * <li>{@code maxOff} - the duty-cycle guarantee: a device that has been off longer than this is switched back on
 * "regardless of price or surplus".</li>
 * <li>{@code maxOn} - a device that has run longer than this is switched off.</li>
 * <li>{@code minOn} - a device that has been on for less than this is kept on, so an optimizer cannot cut a
 * compressor's run short.</li>
 * <li>{@code minOff} - the cooldown: a device that went off less than this ago is kept off.</li>
 * </ul>
 * The last two produce a decision only when the requirement is <em>at risk</em>: the algorithm proposes the state
 * the device is already in, and conflict resolution then lets that proposal outrank any optimizer proposal for the
 * same participant, by {@link ConstraintLadder} - the one fixed ladder. That is deliberate: it is the only way a
 * protection can beat an optimization without this algorithm having to know what the optimizer wants.
 * <p>
 * <strong>This is the only producer of {@link DecisionKind#DEVICE_PROTECTION} in the bundle, and therefore the only
 * thing that makes the ladder's protections-versus-limits rung reachable in a real cycle.</strong> Without it that
 * rung could only ever be exercised by hand-constructed decisions in a test.
 * <p>
 * <strong>Where the elapsed time comes from, and what happens when it is unknown.</strong> It comes from
 * {@link ParticipantState#protectionElapsed(java.time.Instant)}, which is the steered Item's own last state change
 * and, only where that is unreadable, the moment the snapshot first observed the participant. <em>The engine keeps no
 * protection timers</em>, which is what lets a compressor's cooldown survive a restart wherever the Item is
 * persisted, and what makes an uncommanded OFF&rarr;ON transition start the minimum runtime exactly as an
 * engine-initiated start would - the algorithm never learns, and never needs to learn, who switched the device on.
 * <p>
 * A participant on the fallback clock is <strong>reported as protection-unknown</strong>. That fallback is not a
 * timer: it is a first-<em>observation</em> stamp, it measures nothing about the device, it is taken once in
 * {@link EnergyContextFactory} so that this algorithm, the electrical-limit floor and the safe state all read the
 * same number, and the only thing it can do is keep a protection conservative until real history exists. It is
 * emphatically not the prototype's behaviour, which was to disable every protection on that participant silently - a
 * fridge whose Item is not persisted would have lost its duty-cycle guarantee and nobody would have been told.
 * <p>
 * <strong>What privilege this one does and does not have.</strong> It is registered on the same whiteboard as any
 * contributed algorithm and carries an ordinary priority, so it competes for its participants like anything else. The
 * one thing it has that a contribution cannot get is {@link EngineOwnedAlgorithm}: its {@code DEVICE_PROTECTION}
 * claim is believed, because the marker is a type in a package this bundle does not export. Two things follow that a
 * reviewer should know. Registering another algorithm under this one's id does <em>not</em> displace it - contributed
 * services and registered algorithms live in separate collections and both would run - and stopping the bundle that
 * carries this component would leave {@code maxOn}, {@code minOff} and {@code maxOff} unenforced. Only the
 * <em>prohibition</em> half, the {@code minOn} hold in {@link EngineProhibitions#protectionHolding}, is enforced by
 * code that cannot be unloaded, because a prohibition is a "no" and the engine owns every "no".
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(service = EnergyAlgorithm.class)
public class DeviceProtectionAlgorithm implements EnergyAlgorithm, EngineOwnedAlgorithm {

    /**
     * The id of this algorithm, as it appears in decisions and log lines.
     */
    public static final String ID = "device-protection";

    /**
     * The priority protection decisions carry. Stronger than the built-in optimizer so that, under a precedence
     * strategy which ranks by priority alone, a protection still wins its participant.
     */
    public static final int PRIORITY = 0;

    private final Logger logger = LoggerFactory.getLogger(DeviceProtectionAlgorithm.class);

    private final Set<String> protectionUnknown = ConcurrentHashMap.newKeySet();

    /**
     * Which condition each protection-unknown participant is in, so the report can say whether the site has anything
     * to fix. Owner decision D28; before it, the two causes were indistinguishable and both were reported as a fault.
     */
    private final Map<String, ProtectionHistory> conditions = new ConcurrentHashMap<>();

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public List<Decision> evaluate(EnergyContext context) {
        List<Decision> decisions = new ArrayList<>();
        Map<String, ProtectionHistory> unknownThisCycle = new TreeMap<>();
        for (ParticipantState state : context.consumers()) {
            Optional<EnergyConsumer> maybeConsumer = state.consumer();
            if (maybeConsumer.isEmpty()) {
                continue;
            }
            EnergyConsumer consumer = maybeConsumer.get();
            if (!(consumer.profile() instanceof SimpleProfile profile)) {
                continue;
            }
            if (EngineProhibitions.isHandsOff(state)) {
                continue;
            }
            if (state.protectionHistoryUnknown() && profile.declaresProtections()) {
                unknownThisCycle.put(state.id(), state.protectionHistory());
            }
            Optional<Duration> maybeElapsed = state.protectionElapsed(context.timestamp());
            if (maybeElapsed.isEmpty()) {
                continue;
            }
            protect(consumer, profile, state.isReportedOn(), maybeElapsed.get()).ifPresent(decisions::add);
        }
        report(unknownThisCycle);
        return decisions;
    }

    /**
     * Returns the participants whose declared protections are running on a first-observation clock because the engine
     * cannot read their state history - a degraded guarantee, reported so that it is visible rather than assumed.
     *
     * @return the participant ids, as of the last cycle
     */
    public Set<String> protectionUnknown() {
        return Set.copyOf(protectionUnknown);
    }

    /**
     * Publishes the protection-unknown set, logging only what changed so that a permanently unpersisted Item does not
     * produce one line per cycle.
     * <p>
     * The two conditions are logged apart: an Item nothing keeps is a fault the site can fix and is a warning; an
     * Item that is kept and has not changed yet is the ordinary state after a restart and is an info line. Source:
     * owner decision D28.
     *
     * @param unknown the participants whose history was unreadable this cycle, and which condition each is in
     */
    private void report(Map<String, ProtectionHistory> unknown) {
        conditions.keySet().retainAll(unknown.keySet());
        conditions.putAll(unknown);
        if (protectionUnknown.equals(unknown.keySet())) {
            return;
        }
        Set<String> appeared = new TreeSet<>(unknown.keySet());
        appeared.removeAll(protectionUnknown);
        Set<String> cleared = new TreeSet<>(protectionUnknown);
        cleared.removeAll(unknown.keySet());
        protectionUnknown.retainAll(unknown.keySet());
        protectionUnknown.addAll(unknown.keySet());
        if (!appeared.isEmpty()) {
            Set<String> fixable = new TreeSet<>();
            Set<String> harmless = new TreeSet<>();
            appeared.forEach(
                    id -> (conditions.getOrDefault(id, ProtectionHistory.UNDETERMINED).isFixable() ? fixable : harmless)
                            .add(id));
            if (!fixable.isEmpty()) {
                logger.warn("Protections declared for {} but nothing is keeping the state history of those Items; "
                        + "their elapsed time is measured from the first cycle that observed them and starts over on "
                        + "every restart. Persist those Items with restoreOnStartup to close it.", fixable);
            }
            if (!harmless.isEmpty()) {
                logger.info("Protections declared for {} and no state change has been observed for those Items yet; "
                        + "their elapsed time is measured from the first cycle that observed them until the devices "
                        + "next change state. Nothing is misconfigured.", harmless);
            }
        }
        if (!cleared.isEmpty()) {
            logger.info("The state history of {} is readable again; their protections are back on device history",
                    cleared);
        }
    }

    /**
     * Applies the four protection rules to one consumer.
     * <p>
     * The rules themselves live in {@link DeclaredProtections#due}, because {@link EvaluationPass} has to ask the
     * same question when it corroborates a contributed claim to this rung (owner decision D25). Two copies would let
     * a contributor's claim be judged against a subtly different rule from the one the engine enforces.
     *
     * @param consumer the consumer
     * @param profile its Simple profile
     * @param running whether it reports itself as on
     * @param elapsed how long it has been in that state
     * @return the protection decision, or empty when no declared parameter applies
     */
    private Optional<Decision> protect(EnergyConsumer consumer, SimpleProfile profile, boolean running,
            Duration elapsed) {
        return DeclaredProtections.due(profile, running, elapsed)
                .map(protection -> decision(consumer, protection.action(), protection.reason()));
    }

    private Decision decision(EnergyConsumer consumer, ControlAction action, @Nullable String reason) {
        return new Decision(consumer.id(), action, ID, PRIORITY, DecisionKind.DEVICE_PROTECTION,
                reason == null ? "device protection" : reason);
    }
}
