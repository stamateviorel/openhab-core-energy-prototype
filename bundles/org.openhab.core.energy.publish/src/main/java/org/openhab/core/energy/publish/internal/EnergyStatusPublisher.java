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
package org.openhab.core.energy.publish.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.events.EnergyCycleDTO;
import org.openhab.core.energy.events.EnergyCycleEvent;
import org.openhab.core.energy.events.EnergyDecisionEvent;
import org.openhab.core.energy.publish.EnergyItems;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventFilter;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.events.EventSubscriber;
import org.openhab.core.events.TopicPrefixEventFilter;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.StringType;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the energy framework's events into the two Items this bundle owns.
 * <p>
 * <strong>THIS CLASS WRITES ITEMS, ON PURPOSE.</strong> It is the half of A8 that the framework bundle is forbidden
 * to do: {@code org.openhab.core.energy} may never write an Item and proves it with four tests, so the summarising
 * status Item and the published current level live here instead. Do not copy this class back over there, and do not
 * read its existence as a precedent for relaxing the invariant next door - the whole point of D23 (owner decision,
 * 2026-08-03, {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}) is that the two rules are different because the two
 * bundles are.
 * <p>
 * What it writes is bounded, and the bound is worth stating, because "may write Items" is a licence that grows if
 * nobody writes it down:
 * <ul>
 * <li>it writes <strong>two</strong> Items, both of which {@link EnergyItemProvider} supplies, so it can never
 * scribble on something a user declared;</li>
 * <li>it writes <strong>state updates</strong> and never commands, so nothing it does moves a device;</li>
 * <li>it writes <strong>on change</strong>, mirroring the framework's own deduplication, so an unchanged site
 * produces no bus traffic.</li>
 * </ul>
 * <p>
 * The status line is assembled from the cycle event, which carries the level, the outcome counts and the
 * participant conditions, and from the most recent decision event, which is what tells an operator that the engine
 * is deciding rather than merely running. Both arrive on the bus: this component holds no reference to the engine at
 * all, which is what lets it be installed, removed and restarted independently of it.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(immediate = true, service = EventSubscriber.class)
public class EnergyStatusPublisher implements EventSubscriber {

    private static final Set<String> SUBSCRIBED = Set.of(EnergyCycleEvent.TYPE, EnergyDecisionEvent.TYPE);
    private static final EventFilter TOPIC_FILTER = new TopicPrefixEventFilter("openhab/energy/");
    private static final String SEPARATOR = " · ";
    private static final String SOURCE = EnergyStatusPublisher.class.getSimpleName();

    private final Logger logger = LoggerFactory.getLogger(EnergyStatusPublisher.class);

    private final EventPublisher eventPublisher;
    private final Object lock = new Object();

    private @Nullable EnergyCycleDTO lastCycle;
    private String lastDecision = "";
    private String publishedStatus = "";
    private String publishedLevel = "";

    /**
     * Creates the publisher.
     *
     * @param eventPublisher the bus this component listens on and writes its two Items through
     */
    @Activate
    public EnergyStatusPublisher(final @Reference EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Set<String> getSubscribedEventTypes() {
        return SUBSCRIBED;
    }

    @Override
    public @Nullable EventFilter getEventFilter() {
        return TOPIC_FILTER;
    }

    @Override
    public void receive(Event event) {
        synchronized (lock) {
            if (event instanceof EnergyCycleEvent cycleEvent) {
                lastCycle = cycleEvent.getCycle();
            } else if (event instanceof EnergyDecisionEvent decisionEvent) {
                lastDecision = decisionEvent.getStatus() + " " + decisionEvent.getParticipantId() + " "
                        + decisionEvent.getDecision().action;
            } else {
                return;
            }
            publish();
        }
    }

    /**
     * Writes whatever changed. The caller holds {@link #lock}, and nothing called from here calls back into this
     * component.
     */
    private void publish() {
        EnergyCycleDTO cycle = lastCycle;
        if (cycle == null) {
            // a decision arrived before any cycle summary did, so there is nothing to say about the site yet
            return;
        }
        String level = cycle.stopped ? EnergyItems.UNKNOWN_WHILE_STOPPED : cycle.level;
        if (!level.equals(publishedLevel)) {
            publishedLevel = level;
            post(EnergyItems.CURRENT_LEVEL, level);
        }
        String status = statusLine(cycle);
        if (!status.equals(publishedStatus)) {
            publishedStatus = status;
            post(EnergyItems.ENGINE_STATUS, status);
        }
    }

    /**
     * Posts a state update for one Item.
     * <p>
     * A failure here is a reporting problem and is logged as one: the site is being steered by the framework next
     * door, which does not know this component exists and carries on whatever happens to it.
     *
     * @param itemName the Item to update
     * @param value what it should say
     */
    private void post(String itemName, String value) {
        try {
            eventPublisher.post(ItemEventFactory.createStateEvent(itemName, new StringType(value), SOURCE));
        } catch (RuntimeException e) {
            logger.debug("Could not update {}: {}", itemName, e.getMessage());
        }
    }

    /**
     * Renders one cycle as the sentence the status Item carries.
     * <p>
     * The order of the clauses is fixed rather than incidental: an operator reading this at a glance wants the
     * verdict first, the caveats next and the evidence last, and a test that pins the string needs the line to be a
     * function of the cycle alone.
     *
     * @param cycle the cycle summary as it came off the bus
     * @return the status line
     */
    private String statusLine(EnergyCycleDTO cycle) {
        if (cycle.stopped) {
            return "Master stop engaged - no evaluation runs and no device protection is enforced";
        }
        List<String> parts = new ArrayList<>();
        parts.add(cycle.level);
        parts.add(cycle.shadow ? "shadow" : "live");
        parts.add(cycle.participants + (cycle.participants == 1 ? " participant" : " participants"));
        if (cycle.measurementsStale) {
            parts.add("measurements stale");
        }
        int conditions = cycle.participantGaps.size();
        if (conditions > 0) {
            parts.add(conditions + (conditions == 1 ? " participant condition" : " participant conditions"));
        }
        parts.add(cycle.outcomes.isEmpty() ? "nothing to decide" : outcomes(cycle.outcomes));
        if (!lastDecision.isEmpty()) {
            parts.add("last: " + lastDecision);
        }
        return String.join(SEPARATOR, parts);
    }

    /**
     * Renders the outcome counts of a cycle, in outcome-name order so the line is stable.
     *
     * @param counts how many decisions ended in each outcome
     * @return the rendering
     */
    private static String outcomes(Map<String, Integer> counts) {
        List<String> rendered = new ArrayList<>();
        counts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> rendered.add(entry.getValue() + " " + entry.getKey().toLowerCase(Locale.ROOT)));
        return String.join(", ", rendered);
    }
}
