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
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.DecisionKind;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.ParticipantState;
import org.openhab.core.energy.SimpleProfile;

/**
 * The four declared Simple-consumer protections, written out once as the single question "what does this
 * participant's own declaration require of it, right now".
 * <p>
 * It exists because that question now has <em>two</em> readers that have to agree. {@link DeviceProtectionAlgorithm}
 * asks it in order to propose the protection; {@link EvaluationPass} asks it in order to decide whether a contributed
 * algorithm's claim to the device-protection rung is corroborated. Two copies of the four rules would let a
 * contributor's claim be judged against a slightly different rule from the one the engine enforces, which is the one
 * way this check could be wrong in the direction that matters.
 *
 * <h2>Corroboration (owner decision D25)</h2>
 * Source: owner decision <strong>D25</strong> (2026-08-03, {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}),
 * answering what the wave-1 slice reported as an open question in its {@code STAGE1_REPORT.md} §3.4. D13 says a
 * contributor may not <em>invent</em> a prohibition and says nothing about a contributor whose claim is simply true -
 * a binding that knows its own compressor's duty cycle better than the metadata does. The slice shipped the strict
 * reading, capping every contributed proposal at the level gate.
 * <p>
 * D25 replaces the cap with a corroboration test: a contributed decision is honoured at the device-protection rung
 * when, and only when, the engine can see the same thing independently, from the same cycle's snapshot. Three
 * conjuncts, each of which closes one way the claim could otherwise certify itself:
 * <ol>
 * <li>the participant's own <strong>effective declaration</strong> carries the protection being claimed - the
 * declaration that survived precedence, never one the contributor supplied alongside the decision;</li>
 * <li>that protection is <strong>due at this cycle's evaluation</strong>, measured through
 * {@link ParticipantState#protectionElapsed(java.time.Instant)}, which is the way every other enforcement point
 * measures it;</li>
 * <li>the <strong>rendered action is the action that protection requires</strong> - an elapsed {@code maxOff}
 * requires ON, a running {@code minOff} requires staying off, and a claim pointing the other way is not corroborated
 * by it.</li>
 * </ol>
 * <strong>Protection-unknown does not corroborate.</strong> An unreadable state history is the absence of evidence,
 * and reading it as corroboration would put the strongest reachable rung within reach exactly on the sites least able
 * to check it. The engine's own protections still run on the first-observation clock there, because a degraded
 * guarantee is better than none; a contributor's claim gets no such benefit of the doubt, because nothing about it
 * has been checked.
 * <p>
 * <strong>What D25 does not open.</strong> The electrical-limit rung has no participant declaration to corroborate a
 * claim against - a site's limits are the engine's own inputs - so a contributed decision still cannot reach it, and
 * {@link #corroborate(Decision, EnergyContext)} refuses it in one line. That half of the question the slice raised is
 * still open, and is not read into an answer that did not mention it.
 * <p>
 * <em>Alternatives preserved:</em> the strict cap the slice shipped - one rule, unforgeable, nothing to get subtly
 * wrong, at the cost of making a duty-cycle-aware binding unshippable as a contribution; and trusting the claim as
 * made, which re-opens exactly the escalation D13 closed, since {@link Decision#kind()} is a field anybody can set.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
final class DeclaredProtections {

    private DeclaredProtections() {
    }

    /**
     * A declared protection that is due, and what it requires be done.
     *
     * @param action the action the protection requires
     * @param reason the protection, in the words that go into a decision and a log line
     *
     * @author Stamate Viorel - Initial contribution
     */
    record Due(ControlAction action, String reason) {
    }

    /**
     * Returns the declared protection that is due for a consumer in a given state, if one is.
     * <p>
     * The four rules are the literal requirement wording, in the order the ladder needs them: a device past its
     * {@code maxOn} goes off, one inside its {@code minOn} is held on, one past its {@code maxOff} goes on, and one
     * inside its {@code minOff} is held off. The last two of those four produce a requirement even though the device
     * is already in the state they ask for: that is what lets a protection outrank an optimizer's proposal for the
     * same participant without this class having to know what the optimizer wants.
     *
     * @param profile the consumer's Simple profile, which is the only class carrying protection parameters
     * @param running whether the steered Item reports itself on
     * @param elapsed how long it has been in that state, from {@link ParticipantState#protectionElapsed}
     * @return the protection that is due, or empty when none of the declared parameters applies
     */
    static Optional<Due> due(SimpleProfile profile, boolean running, Duration elapsed) {
        if (running) {
            Duration maxOn = profile.maxOn();
            if (maxOn != null && elapsed.compareTo(maxOn) >= 0) {
                return Optional.of(new Due(ControlAction.off(),
                        "it has run for " + elapsed + ", reaching its declared maxOn of " + maxOn));
            }
            Duration minOn = profile.minOn();
            if (minOn != null && elapsed.compareTo(minOn) < 0) {
                return Optional.of(new Due(ControlAction.on(),
                        "it has run for only " + elapsed + " of its declared minOn of " + minOn));
            }
            return Optional.empty();
        }
        Duration maxOff = profile.maxOff();
        if (maxOff != null && elapsed.compareTo(maxOff) >= 0) {
            return Optional.of(new Due(ControlAction.on(),
                    "it has been off for " + elapsed + ", reaching its declared maxOff of " + maxOff));
        }
        Duration minOff = profile.minOff();
        if (minOff != null && elapsed.compareTo(minOff) < 0) {
            return Optional.of(new Due(ControlAction.off(),
                    "it has been off for only " + elapsed + " of its declared minOff of " + minOff));
        }
        return Optional.empty();
    }

    /**
     * Tests a contributed decision's claim to the device-protection rung against what the engine can see for itself.
     * <p>
     * It is a pure function of the proposal and the snapshot, which is what makes the outcome a property of the
     * cycle rather than of which contributor asked, or of the order they asked in: the same snapshot corroborates the
     * same claims however many times it is evaluated.
     *
     * @param proposal the decision as the contributor claimed it
     * @param context the one snapshot of the cycle
     * @return an empty string when the claim is corroborated, otherwise the reason it is not, phrased to be read
     *         after "because"
     */
    static String corroborate(Decision proposal, EnergyContext context) {
        if (proposal.kind() != DecisionKind.DEVICE_PROTECTION) {
            return "the " + proposal.kind()
                    + " rung is the engine's own and carries no participant declaration to corroborate a claim "
                    + "against - a site's electrical limits are the engine's inputs, not a device's declaration";
        }
        Optional<ParticipantState> maybeState = context.participant(proposal.participantId());
        if (maybeState.isEmpty()) {
            return "'" + proposal.participantId() + "' is not a participant of this cycle";
        }
        ParticipantState state = maybeState.get();
        if (EngineProhibitions.isHandsOff(state)) {
            return "its owner marked it hands-off, so the engine enforces no protection on it either";
        }
        Optional<SimpleProfile> maybeProfile = state.consumer().map(EnergyConsumer::profile)
                .filter(SimpleProfile.class::isInstance).map(SimpleProfile.class::cast);
        if (maybeProfile.isEmpty()) {
            return "it does not declare a Simple profile, which is the only class that carries protection parameters";
        }
        SimpleProfile profile = maybeProfile.get();
        if (!profile.declaresProtections()) {
            return "its effective declaration carries no protection parameters at all";
        }
        if (state.protectionHistoryUnknown()) {
            return "its state history cannot be read, and an absence of evidence does not stand in for evidence";
        }
        Optional<Duration> elapsed = state.protectionElapsed(context.timestamp());
        if (elapsed.isEmpty()) {
            return "how long it has been in its current state is unknown";
        }
        Optional<Due> due = due(profile, state.isReportedOn(), elapsed.get());
        if (due.isEmpty()) {
            return "none of its declared protections is due at this cycle";
        }
        ControlAction required = due.get().action();
        if (!required.equals(proposal.action())) {
            return "the protection that is due requires " + required.describe() + " (" + due.get().reason()
                    + "), which is not " + proposal.action().describe();
        }
        return "";
    }
}
