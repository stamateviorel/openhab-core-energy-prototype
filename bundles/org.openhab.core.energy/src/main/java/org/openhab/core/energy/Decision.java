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

import java.util.Comparator;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * A single proposal from an algorithm: do this to that participant, this cycle.
 * <p>
 * A decision is a <em>proposal</em>, never an order. Between the algorithm and the device sit conflict resolution,
 * the electrical-limit floor, the shadow gate, the master stop and the acknowledgement window - all engine-owned,
 * and all applied to every algorithm's output alike (the "same guardrails" half of the replaceable-algorithm
 * requirement).
 * <p>
 * <strong>Priority direction:</strong> a numerically <em>lower</em> value is the stronger decision, matching
 * {@link EnergyConsumer#priority()} and the acceptance fixtures (heating at priority 1 is served before the boiler
 * at priority 2). Lower is better in <em>both</em> senses - served first and winning a conflict - and equal
 * priorities are broken by participant id ascending, which is a decision rather than an inference from the
 * fixtures.
 *
 * @param participantId the id of the participant this decision addresses
 * @param action what to do
 * @param algorithmId the id of the proposing algorithm, carried so a log line and a conflict tie-break can name it
 * @param priority the strength of the proposal, lower is stronger
 * @param kind why the decision was proposed, ranked by the engine's one fixed constraint ladder
 * @param reason a free-text explanation for the "would have done" log line, may be empty
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record Decision(String participantId, ControlAction action, String algorithmId, int priority, DecisionKind kind,
        String reason) {

    /**
     * A total order over decisions that ignores their {@link DecisionKind}: better priority first, then
     * <em>participant id</em>, then algorithm id, then the rendered action. Because it is total and derived only from
     * the decision's own content, sorting with it makes a cycle's outcome independent of registration and iteration
     * order.
     * <p>
     * The participant id is the first tie-break because that is the stated rule: equal priorities are broken by
     * participant id ascending, statelessly. The algorithm id decides only what the participant id cannot - two
     * decisions about the <em>same</em> participant, which is the shape conflict resolution sees, so that use is
     * unaffected by the ordering of the two clauses. It matters where it is not: the electrical-limit floor hands out
     * headroom in this order, and two equal-priority consumers addressed by two different algorithms have to be
     * served by participant id rather than by whose algorithm happens to sort first.
     * <p>
     * Conflict resolution does not use this comparator directly - it ranks the {@link DecisionKind} first, on the
     * engine's one fixed ladder, and falls back to this order inside a rung.
     * <p>
     * <strong>The last two clauses are the tie-break conflict resolution actually reaches.</strong> Source: owner
     * decisions <strong>D4</strong> and <strong>D30</strong> (2026-08-03,
     * {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}). D4 names "participant id ascending", which orders
     * participants and <em>cannot</em> break this tie at all, because conflict resolution groups decisions by
     * participant and the id therefore always ties inside a group. The wave-1 slice filled the gap with a comparator
     * of its own and reported it as the code's choice rather than the decision's; D30 confirms it - <strong>algorithm
     * id, then the rendered action</strong> - because those keep the three properties D4 protects: deterministic,
     * stateless, and reproducible from the cycle's snapshot alone. One consequence is recorded rather than designed:
     * comparing action text means {@code "OFF" < "ON"}, so on a dead heat the safer action wins by alphabetical
     * accident. <em>Alternatives preserved:</em> making that safety bias explicit, and refusing the tie and
     * reporting it.
     */
    public static final Comparator<Decision> PRIORITY_ORDER = Comparator.comparingInt(Decision::priority)
            .thenComparing(Decision::participantId).thenComparing(Decision::algorithmId)
            .thenComparing(decision -> decision.action().describe());

    /**
     * Validates the decision.
     *
     * @throws IllegalArgumentException if the participant or algorithm id is blank
     */
    public Decision {
        participantId = ModelChecks.requireText(participantId, "participantId");
        algorithmId = ModelChecks.requireText(algorithmId, "algorithmId");
    }

    /**
     * Creates an optimization decision without an explanation.
     *
     * @param participantId the participant to steer
     * @param action what to do
     * @param algorithmId the proposing algorithm
     * @param priority the strength of the proposal, lower is stronger
     * @return the decision
     */
    public static Decision of(String participantId, ControlAction action, String algorithmId, int priority) {
        return new Decision(participantId, action, algorithmId, priority, DecisionKind.OPTIMIZATION, "");
    }

    /**
     * Creates an optimization decision with an explanation.
     *
     * @param participantId the participant to steer
     * @param action what to do
     * @param algorithmId the proposing algorithm
     * @param priority the strength of the proposal, lower is stronger
     * @param reason why, for the log line
     * @return the decision
     */
    public static Decision of(String participantId, ControlAction action, String algorithmId, int priority,
            String reason) {
        return new Decision(participantId, action, algorithmId, priority, DecisionKind.OPTIMIZATION, reason);
    }

    /**
     * Returns a copy of this decision with a different kind.
     *
     * @param newKind the kind to carry
     * @return the copy
     */
    public Decision withKind(DecisionKind newKind) {
        return new Decision(participantId, action, algorithmId, priority, newKind, reason);
    }

    /**
     * Returns a copy of this decision with a different action - how the electrical-limit floor records a trim.
     *
     * @param newAction the action to carry
     * @return the copy
     */
    public Decision withAction(ControlAction newAction) {
        return new Decision(participantId, newAction, algorithmId, priority, kind, reason);
    }

    /**
     * Returns a copy of this decision with a different explanation.
     *
     * @param newReason the explanation to carry
     * @return the copy
     */
    public Decision withReason(String newReason) {
        return new Decision(participantId, action, algorithmId, priority, kind, newReason);
    }

    /**
     * Renders the decision for a log line.
     *
     * @return a compact one-line rendering
     */
    public String describe() {
        return participantId + " " + action.describe() + " [" + algorithmId + ", priority " + priority + ", " + kind
                + (reason.isEmpty() ? "" : ", " + reason) + "]";
    }
}
