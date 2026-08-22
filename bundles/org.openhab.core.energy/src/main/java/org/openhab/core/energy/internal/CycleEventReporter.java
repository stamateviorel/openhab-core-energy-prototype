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

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.events.EnergyCycleDTO;
import org.openhab.core.energy.events.EnergyDecisionDTO;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The engine's outward voice: it turns a finished cycle into events and posts them.
 * <p>
 * <strong>This is the only class in this bundle that holds an {@link EventPublisher}, and it can post exactly two
 * things</strong> - an {@link org.openhab.core.energy.events.EnergyDecisionEvent} and an
 * {@link org.openhab.core.energy.events.EnergyCycleEvent}, both of them this bundle's own.
 * Neither is an Item event: nothing here commands a device, updates a state or touches the {@code ItemRegistry}, and
 * the bundle cannot even construct an Item event, because the only class that builds one lives in
 * {@code org.openhab.core.items.events} and a second structural test forbids that package everywhere but the three
 * read-only files that were always allowed it. Posting an {@code Event} is not a write, and the no-write invariant
 * is unchanged by this class existing.
 * <p>
 * <strong>FOLLOW-UP - D23</strong> (owner decision, 2026-08-03,
 * {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}). A8 asked for decisions published as deduplicated events and for
 * a summarising status Item. The wave-1 slice reported that those two are blocked for different reasons - one by
 * this bundle's own over-reaching test, the other by the invariant itself - and the owner split them: events here,
 * the status Item and the current level in the separate {@code org.openhab.core.energy.publish} bundle, which
 * subscribes to what this class posts. Alternatives preserved in {@code define-engine-contract} design.md §23.
 * <p>
 * <strong>Deduplication</strong> is the point of the class rather than an optimisation. The engine is a control
 * loop: for as long as the world does not change it reaches the same decision about the same device every cycle, so
 * an undeduplicated stream would be fourteen hundred identical events a day per device in the mode a fresh
 * installation starts in - which is precisely the log line the requirement exists to replace. A decision is
 * therefore re-emitted only when what the engine concluded about that participant, from that algorithm, changed. The
 * cycle summary follows the same rule, with one addition: it is also emitted whenever a decision event was, so the
 * two surfaces can never describe different cycles.
 * <p>
 * The consequence is worth stating because somebody will meet it: a subscriber that starts <em>after</em> the engine
 * sees nothing until something changes. That is a deliberate trade - a heartbeat re-emit would restore the identical
 * flood the deduplication exists to prevent - and the publishing component handles it by declaring its Items
 * "awaiting the first cycle" rather than by guessing.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class CycleEventReporter {

    private final Logger logger = LoggerFactory.getLogger(CycleEventReporter.class);

    private final Map<String, String> lastDecisionLines = new ConcurrentHashMap<>();

    private volatile String lastCycleLine = "";
    private volatile @Nullable EventPublisher publisher;

    /**
     * Installs the event publisher, or removes it. With none bound the engine simply reports nothing outward, which
     * is how every unit test in this bundle runs.
     *
     * @param eventPublisher the publisher, or {@code null} to stop publishing
     */
    void setPublisher(@Nullable EventPublisher eventPublisher) {
        publisher = eventPublisher;
    }

    /**
     * Tells whether anything is listening at all.
     *
     * @return {@code true} if a publisher is bound
     */
    boolean isPublishing() {
        return publisher != null;
    }

    /**
     * Publishes what a finished cycle decided: one event per decision whose outcome changed, and one summarising the
     * cycle whenever the summary changed or a decision was published.
     *
     * @param outcome the finished cycle
     * @param gaps the participants being steered with a gap in their declaration, as this cycle saw them
     */
    void report(CycleOutcome outcome, Map<String, String> gaps) {
        EventPublisher target = publisher;
        if (target == null) {
            return;
        }
        boolean anyDecision = false;
        for (DecisionOutcome decisionOutcome : outcome.outcomes()) {
            EnergyDecisionDTO decision = decisionOf(decisionOutcome, outcome);
            if (isNew(decisionOutcome.decision(), render(decision))) {
                post(target, EnergyEventFactory.createDecisionEvent(decision));
                anyDecision = true;
            }
        }
        EnergyCycleDTO cycle = cycleOf(outcome, gaps);
        String line = render(cycle);
        boolean cycleChanged = !line.equals(lastCycleLine);
        lastCycleLine = line;
        if (cycleChanged || anyDecision) {
            post(target, EnergyEventFactory.createCycleEvent(cycle));
        }
    }

    /**
     * Forgets every participant outside the given set, so that a device which leaves the site and comes back is
     * reported again rather than silently suppressed, and the memory cannot grow without bound.
     *
     * @param participantIds the participants still worth remembering
     */
    void retainParticipants(Set<String> participantIds) {
        lastDecisionLines.keySet().removeIf(key -> !participantIds.contains(key.substring(0, key.indexOf('\n'))));
    }

    /**
     * Records what the engine concluded about one participant from one algorithm, and says whether that differs from
     * what it last concluded.
     * <p>
     * The key is the pair rather than the participant alone: one cycle can reach two outcomes for one device - a
     * winner and the proposal it superseded - and collapsing them onto one key would make both look new on every
     * cycle, which is the flood this exists to prevent.
     *
     * @param decision the decision
     * @param line the rendered outcome
     * @return {@code true} if this is the first such conclusion or it differs from the previous one
     */
    private boolean isNew(Decision decision, String line) {
        return !Objects.equals(lastDecisionLines.put(decision.participantId() + "\n" + decision.algorithmId(), line),
                line);
    }

    /**
     * Posts an event, and treats a failure to do so as a reporting problem rather than a cycle problem.
     * <p>
     * The event bus can be unavailable during shutdown, and a subscriber can throw. Neither may take the control
     * loop down with it: this bundle's whole reason for existing is upstream of its reporting.
     *
     * @param target the publisher
     * @param event the event
     */
    private void post(EventPublisher target, Event event) {
        try {
            target.post(event);
        } catch (RuntimeException e) {
            logger.debug("Could not publish {}: {}", event.getType(), e.getMessage());
        }
    }

    /**
     * Builds the payload describing one decision and its outcome.
     *
     * @param decisionOutcome the decision and what became of it
     * @param outcome the cycle it belongs to
     * @return the payload
     */
    private static EnergyDecisionDTO decisionOf(DecisionOutcome decisionOutcome, CycleOutcome outcome) {
        Decision decision = decisionOutcome.decision();
        Decision proposed = decisionOutcome.original();
        EnergyDecisionDTO dto = new EnergyDecisionDTO();
        dto.participantId = decision.participantId();
        dto.algorithmId = decision.algorithmId();
        dto.priority = decision.priority();
        dto.kind = decision.kind().name();
        dto.status = decisionOutcome.status().name();
        dto.action = decision.action().describe();
        dto.trimmedFrom = proposed == null ? null : proposed.action().describe();
        dto.reason = decisionOutcome.detail();
        dto.cycle = outcome.context().timestamp().toString();
        dto.shadow = outcome.shadow();
        return dto;
    }

    /**
     * Builds the payload summarising one cycle.
     *
     * @param outcome the finished cycle
     * @param gaps the declaration gaps this cycle saw
     * @return the payload
     */
    private static EnergyCycleDTO cycleOf(CycleOutcome outcome, Map<String, String> gaps) {
        EnergyCycleDTO dto = new EnergyCycleDTO();
        dto.timestamp = outcome.context().timestamp().toString();
        dto.level = outcome.context().level().name();
        dto.shadow = outcome.shadow();
        dto.stopped = outcome.stopped();
        dto.measurementsStale = outcome.context().measurementsStale();
        dto.participants = outcome.context().participants().size();
        Map<String, Integer> counts = new TreeMap<>();
        for (DecisionOutcome decisionOutcome : outcome.outcomes()) {
            counts.merge(decisionOutcome.status().name(), 1, Integer::sum);
        }
        dto.outcomes = counts;
        dto.participantGaps = new TreeMap<>(gaps);
        return dto;
    }

    /**
     * Renders a decision to the string the deduplication compares. The cycle timestamp is deliberately left out: it
     * changes every cycle and would make every decision look new.
     *
     * @param decision the payload
     * @return the comparison key
     */
    private static String render(EnergyDecisionDTO decision) {
        return decision.status + "|" + decision.action + "|" + decision.trimmedFrom + "|" + decision.kind + "|"
                + decision.priority + "|" + decision.shadow + "|" + decision.reason;
    }

    /**
     * Renders a cycle summary to the string the deduplication compares, timestamp excluded for the same reason.
     *
     * @param cycle the payload
     * @return the comparison key
     */
    private static String render(EnergyCycleDTO cycle) {
        return cycle.level + "|" + cycle.shadow + "|" + cycle.stopped + "|" + cycle.measurementsStale + "|"
                + cycle.participants + "|" + cycle.outcomes + "|" + cycle.participantGaps;
    }
}
