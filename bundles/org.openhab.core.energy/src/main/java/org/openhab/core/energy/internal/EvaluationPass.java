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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.DecisionKind;
import org.openhab.core.energy.EnergyAlgorithm;
import org.openhab.core.energy.EnergyContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs every algorithm against the one snapshot of a cycle and resolves the conflicts between what they propose.
 * <p>
 * Determinism is the point of this class, so nothing in it depends on the order in which algorithms happened to
 * register or on the iteration order of a hash-based collection:
 * <ul>
 * <li>algorithms are evaluated in {@code (priority, id)} order;</li>
 * <li>proposals are grouped per participant in a key-ordered map;</li>
 * <li>each group is sorted with {@link ConstraintLadder#STRENGTH_ORDER} - the one fixed ladder, which no
 * configuration and no contributed service can reorder. It is a <em>total</em> order that falls back to
 * {@link Decision#PRIORITY_ORDER} and finally to the rendered action, so two decisions can only tie if they are
 * indistinguishable;</li>
 * <li>the winners come back in that same total order, which is also the order in which the electrical-limit floor
 * hands out headroom.</li>
 * </ul>
 * An algorithm that throws loses its proposals for that cycle and nothing else: the remaining algorithms are still
 * evaluated, which is the "graceful degradation on contributor loss" behaviour applied to the compute plane.
 * <p>
 * A proposal naming a participant this cycle does not know is <strong>rejected with a reason</strong> rather than
 * silently dropped, and the engine counts it: an algorithm addressing a device that was never declared, or was
 * withdrawn, is a configuration fault somebody has to be able to see.
 * <p>
 * <strong>A contributed algorithm cannot buy itself a stronger rung by labelling its own decision.</strong> The
 * prohibitions are engine-owned and closed, and a {@link DecisionKind} is a self-declared field on a public record:
 * an algorithm claiming {@code DEVICE_PROTECTION} or {@code ELECTRICAL_LIMIT} would otherwise be exempt from the
 * user's level gate, exempt from the stale-measurement freeze, and stronger than the engine's own protection for the
 * same participant.
 * <p>
 * <strong>A claim the engine can check for itself is honoured; anything else is demoted.</strong> Source: owner
 * decision <strong>D25</strong> (2026-08-03, {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}). An algorithm carrying
 * {@link EngineOwnedAlgorithm} keeps whatever rung it claims, because the engine wrote it. A contributed claim to
 * {@code DEVICE_PROTECTION} is put to {@link DeclaredProtections#corroborate(Decision, EnergyContext)}, which
 * honours it exactly when the participant's own effective declaration carries a protection that is due at this
 * cycle and requires the very action the decision renders. A contributed claim to {@code ELECTRICAL_LIMIT} is never
 * corroborable, because a site's limits are the engine's own inputs rather than a participant's declaration.
 * Everything that fails is read at {@code LEVEL_GATE} at the strongest, and <strong>carries the reason it was
 * demoted in its own {@link Decision#reason()}</strong>, so the demotion reaches every surface a decision reaches -
 * the cycle event, the outcome, the "would have done" line - rather than only a debug log a contributor would have
 * to be tailing.
 * <p>
 * The earlier reading, which the wave-1 slice shipped, was the strict cap: no contributed decision above the level
 * gate, full stop. It is preserved as the alternative to return to if corroboration proves fragile; its cost was
 * that a binding which genuinely knows its device's duty cycle could not be written as a contribution at all.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EvaluationPass {

    private final Logger logger = LoggerFactory.getLogger(EvaluationPass.class);

    /**
     * Returns the deterministic evaluation order of a set of algorithms: better priority first, ties on the id.
     *
     * @param algorithms the algorithms to order
     * @return a new, ordered list
     */
    public static List<EnergyAlgorithm> ordered(List<EnergyAlgorithm> algorithms) {
        return algorithms.stream()
                .sorted(Comparator.comparingInt(EnergyAlgorithm::getPriority).thenComparing(EnergyAlgorithm::getId))
                .toList();
    }

    /**
     * Evaluates all algorithms against one snapshot and resolves the conflicts.
     *
     * @param context the one snapshot of the cycle
     * @param algorithms the algorithms to evaluate
     * @return the winning decisions and an outcome for every proposal that did not survive
     */
    public Result run(EnergyContext context, List<EnergyAlgorithm> algorithms) {
        List<DecisionOutcome> discarded = new ArrayList<>();
        Map<String, List<Decision>> byParticipant = new TreeMap<>();

        for (EnergyAlgorithm algorithm : ordered(algorithms)) {
            List<Decision> proposals;
            try {
                // the copy also rejects a null list or null elements from a loosely typed script implementation
                proposals = List.copyOf(algorithm.evaluate(context));
            } catch (RuntimeException e) {
                // the trace goes to debug: a permanently broken algorithm would otherwise write one per cycle
                logger.warn("Algorithm '{}' failed and is skipped for this cycle: {}", algorithm.getId(),
                        e.getMessage());
                logger.debug("Algorithm '{}' threw", algorithm.getId(), e);
                continue;
            }
            for (Decision claimed : proposals) {
                Decision proposal = admissibleKind(algorithm, claimed, context);
                if (!context.participants().containsKey(proposal.participantId())) {
                    logger.debug("Algorithm '{}' addressed '{}', which is not a participant of this cycle",
                            algorithm.getId(), proposal.participantId());
                    discarded.add(DecisionOutcome.of(proposal, DecisionStatus.REJECTED,
                            "participant '" + proposal.participantId() + "' is not part of this cycle"));
                    continue;
                }
                List<Decision> group = byParticipant.get(proposal.participantId());
                if (group == null) {
                    group = new ArrayList<>();
                    byParticipant.put(proposal.participantId(), group);
                }
                group.add(proposal);
            }
        }

        Comparator<Decision> strength = ConstraintLadder.STRENGTH_ORDER;
        List<Decision> winners = new ArrayList<>();
        for (Map.Entry<String, List<Decision>> entry : byParticipant.entrySet()) {
            List<Decision> group = new ArrayList<>(entry.getValue());
            group.sort(strength);
            Decision winner = group.get(0);
            winners.add(winner);
            for (Decision loser : group.subList(1, group.size())) {
                discarded.add(
                        DecisionOutcome.of(loser, DecisionStatus.SUPERSEDED, "superseded by " + winner.describe()));
                logger.trace("Decision {} superseded by {}", loser.describe(), winner.describe());
            }
        }
        winners.sort(strength);
        return new Result(winners, discarded);
    }

    /**
     * Returns the proposal at the strongest rung its author is allowed to claim.
     * <p>
     * An engine-owned algorithm keeps whatever it says, because the engine wrote it. A contributed claim above
     * {@link DecisionKind#LEVEL_GATE} has to be corroborated from the same cycle's snapshot - owner decision D25,
     * implemented in {@link DeclaredProtections#corroborate(Decision, EnergyContext)} - and is otherwise demoted to
     * the level-gate rung, where it is judged on its merits like any other contributed proposal.
     *
     * @param algorithm the algorithm that proposed it
     * @param proposal the proposal as it was claimed
     * @param context the one snapshot of the cycle, which is what a claim is corroborated against
     * @return the same proposal, or a copy at the strongest admissible rung carrying the reason it was demoted
     */
    private Decision admissibleKind(EnergyAlgorithm algorithm, Decision proposal, EnergyContext context) {
        if (algorithm instanceof EngineOwnedAlgorithm
                || ConstraintLadder.rank(proposal.kind()) >= ConstraintLadder.rank(DecisionKind.LEVEL_GATE)) {
            return proposal;
        }
        String refusal = DeclaredProtections.corroborate(proposal, context);
        if (refusal.isEmpty()) {
            logger.debug(
                    "Algorithm '{}' claimed {} for '{}' and the participant's own declaration corroborates it, "
                            + "so it is honoured at that rung",
                    algorithm.getId(), proposal.kind(), proposal.participantId());
            return proposal;
        }
        String note = "demoted from " + proposal.kind() + " to " + DecisionKind.LEVEL_GATE + " because " + refusal;
        logger.debug("Algorithm '{}' claimed {} for '{}': {}", algorithm.getId(), proposal.kind(),
                proposal.participantId(), note);
        String claimedReason = proposal.reason();
        return proposal.withKind(DecisionKind.LEVEL_GATE)
                .withReason(claimedReason.isBlank() ? note : claimedReason + "; " + note);
    }

    /**
     * What the evaluation pass made of one cycle.
     *
     * @param winners one decision per addressed participant, strongest first
     * @param discarded an outcome for every proposal that was superseded or rejected
     *
     * @author Stamate Viorel - Initial contribution
     */
    public record Result(List<Decision> winners, List<DecisionOutcome> discarded) {

        /**
         * Takes immutable copies of both lists.
         */
        public Result {
            winners = List.copyOf(winners);
            discarded = List.copyOf(discarded);
        }
    }
}
