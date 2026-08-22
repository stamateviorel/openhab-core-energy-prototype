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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.events.EnergyCycleDTO;
import org.openhab.core.energy.events.EnergyCycleEvent;
import org.openhab.core.energy.events.EnergyDecisionDTO;
import org.openhab.core.energy.events.EnergyDecisionEvent;
import org.openhab.core.energy.publish.EnergyItems;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventFilter;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.events.ItemCommandEvent;
import org.openhab.core.items.events.ItemStateEvent;

/**
 * The half of A8 that only a bundle allowed to write Items can do.
 * <p>
 * The framework next door publishes a decision and a cycle summary as events and stops there, because writing an
 * Item is precisely what it may not do. This component listens and writes the two Items - and the assertions below
 * are as much about what it does <em>not</em> write (never a command, never an Item it does not own, never an
 * unchanged value) as about what it does. D23 (owner decision, 2026-08-03,
 * {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EnergyStatusPublisherTest {

    private final RecordingBus bus = new RecordingBus();
    private final EnergyStatusPublisher publisher = new EnergyStatusPublisher(bus);

    /**
     * Builds a cycle summary the way the framework would.
     *
     * @param level the level in force
     * @param participants how many participants the cycle knew about
     * @param outcomes how the cycle's decisions came out
     * @return the payload
     */
    private static EnergyCycleDTO cycle(String level, int participants, Map<String, Integer> outcomes) {
        EnergyCycleDTO dto = new EnergyCycleDTO();
        dto.timestamp = "2026-07-01T10:00:00Z";
        dto.level = level;
        dto.shadow = true;
        dto.participants = participants;
        dto.outcomes = new TreeMap<>(outcomes);
        return dto;
    }

    /**
     * Wraps a cycle summary in the event the framework posts it on.
     *
     * @param dto the payload
     * @return the event
     */
    private static EnergyCycleEvent cycleEvent(EnergyCycleDTO dto) {
        return new EnergyCycleEvent("openhab/energy/engine/cycle", "{}", null, dto);
    }

    /**
     * Builds the event the framework posts about one decision.
     *
     * @param participantId the participant
     * @param status what became of the decision
     * @param action the action, rendered
     * @return the event
     */
    private static EnergyDecisionEvent decisionEvent(String participantId, String status, String action) {
        EnergyDecisionDTO dto = new EnergyDecisionDTO();
        dto.participantId = participantId;
        dto.algorithmId = "default-surplus";
        dto.status = status;
        dto.action = action;
        dto.reason = "surplus covers its threshold";
        dto.kind = "OPTIMIZATION";
        return new EnergyDecisionEvent("openhab/energy/" + participantId + "/decision", "{}", null, dto);
    }

    /**
     * Returns what one Item was told, in order.
     *
     * @param itemName the Item
     * @return its published states
     */
    private List<String> statesOf(String itemName) {
        return bus.posted().stream().filter(ItemStateEvent.class::isInstance).map(ItemStateEvent.class::cast)
                .filter(event -> itemName.equals(event.getItemName())).map(event -> event.getItemState().toString())
                .toList();
    }

    @Test
    public void itSubscribesToExactlyTheFrameworksTwoEventTypes() {
        assertThat(publisher.getSubscribedEventTypes(),
                containsInAnyOrder(EnergyCycleEvent.TYPE, EnergyDecisionEvent.TYPE));
    }

    @Test
    public void theTopicFilterAcceptsEnergyEventsAndNothingElse() {
        EventFilter filter = Objects.requireNonNull(publisher.getEventFilter(),
                "the component has to filter, or it sees every event on the bus");

        assertThat(filter.apply(cycleEvent(cycle("NORMAL", 3, Map.of()))), is(true));
        assertThat(filter.apply(decisionEvent("boiler", "SHADOWED", "ON")), is(true));
        assertThat(filter.apply(new EnergyCycleEvent("openhab/items/Boiler/state", "{}", null, new EnergyCycleDTO())),
                is(false));
    }

    @Test
    public void aReportedCycleWritesTheLevelAndTheStatus() {
        publisher.receive(cycleEvent(cycle("OVERCAPACITY", 5, Map.of("SHADOWED", 2))));

        assertThat(statesOf(EnergyItems.CURRENT_LEVEL), contains("OVERCAPACITY"));
        assertThat(statesOf(EnergyItems.ENGINE_STATUS),
                contains("OVERCAPACITY · shadow · 5 participants · 2 shadowed"));
    }

    /**
     * The framework deduplicates its events and this component deduplicates its writes, for the same reason: an
     * unchanged site must not produce a state update per tick on a bus the whole runtime shares.
     */
    @Test
    public void anUnchangedCycleWritesNothingASecondTime() {
        publisher.receive(cycleEvent(cycle("NORMAL", 3, Map.of("SHADOWED", 1))));
        int first = bus.posted().size();
        assertThat(first, is(2));

        publisher.receive(cycleEvent(cycle("NORMAL", 3, Map.of("SHADOWED", 1))));

        assertThat(bus.posted(), hasSize(first));
    }

    @Test
    public void onlyTheItemThatChangedIsWritten() {
        publisher.receive(cycleEvent(cycle("NORMAL", 3, Map.of("SHADOWED", 1))));
        bus.clear();

        // the level is the same, the cycle is not
        publisher.receive(cycleEvent(cycle("NORMAL", 3, Map.of("SHADOWED", 1, "DEFERRED", 1))));

        assertThat(statesOf(EnergyItems.CURRENT_LEVEL), is(empty()));
        assertThat(statesOf(EnergyItems.ENGINE_STATUS),
                contains("NORMAL · shadow · 3 participants · 1 deferred, 1 shadowed"));
    }

    /**
     * A stopped engine takes no snapshot, so it has no level. Publishing the last one would be a lie that outlives
     * the fact, and a rule keyed on the level would go on acting on a stale verdict.
     */
    @Test
    public void aStoppedEngineIsReportedAsStoppedRatherThanAsItsLastLevel() {
        publisher.receive(cycleEvent(cycle("OVERCAPACITY", 5, Map.of("SHADOWED", 2))));
        bus.clear();

        EnergyCycleDTO stopped = cycle("NORMAL", 0, Map.of());
        stopped.stopped = true;
        publisher.receive(cycleEvent(stopped));

        assertThat(statesOf(EnergyItems.CURRENT_LEVEL), contains(EnergyItems.UNKNOWN_WHILE_STOPPED));
        assertThat(statesOf(EnergyItems.ENGINE_STATUS),
                contains("Master stop engaged - no evaluation runs and no device protection is enforced"));
    }

    @Test
    public void aDecisionArrivingBeforeAnyCycleIsNotEnoughToPublish() {
        publisher.receive(decisionEvent("boiler", "SHADOWED", "ON"));

        assertThat(bus.posted(), is(empty()));
    }

    @Test
    public void theMostRecentDecisionAppearsInTheStatusLine() {
        publisher.receive(cycleEvent(cycle("NORMAL", 3, Map.of("SHADOWED", 1))));
        bus.clear();

        publisher.receive(decisionEvent("boiler", "SHADOWED", "ON"));

        assertThat(statesOf(EnergyItems.ENGINE_STATUS),
                contains("NORMAL · shadow · 3 participants · 1 shadowed · last: SHADOWED boiler ON"));
    }

    /**
     * The participant conditions the framework reports on its pull surface are summarised here, which is the half of
     * "reported on one surface" that needed an Item.
     */
    @Test
    public void participantConditionsAreSummarisedOnTheStatusItem() {
        EnergyCycleDTO withGaps = cycle("NORMAL", 3, Map.of());
        withGaps.participantGaps = new TreeMap<>(Map.of("boiler", "it declares no rated power"));
        publisher.receive(cycleEvent(withGaps));

        assertThat(statesOf(EnergyItems.ENGINE_STATUS),
                contains("NORMAL · shadow · 3 participants · 1 participant condition · nothing to decide"));
    }

    @Test
    public void aFrozenSiteSaysSo() {
        EnergyCycleDTO frozen = cycle("NORMAL", 3, Map.of("DEFERRED", 2));
        frozen.measurementsStale = true;
        publisher.receive(cycleEvent(frozen));

        assertThat(statesOf(EnergyItems.ENGINE_STATUS),
                contains("NORMAL · shadow · 3 participants · measurements stale · 2 deferred"));
    }

    /**
     * The bound on the licence this bundle has: two Items, state updates only, nothing else. A command from here
     * would move a device that the framework deliberately refused to move.
     */
    @Test
    public void everythingItWritesIsAStateUpdateToOneOfItsOwnTwoItems() {
        publisher.receive(cycleEvent(cycle("NORMAL", 3, Map.of("SHADOWED", 1))));
        publisher.receive(decisionEvent("boiler", "SHADOWED", "ON"));
        publisher.receive(cycleEvent(cycle("OVERCAPACITY", 3, Map.of("APPLIED", 1))));

        assertThat(bus.posted(), is(not(empty())));
        for (Event event : bus.posted()) {
            assertThat(event, is(instanceOf(ItemStateEvent.class)));
            assertThat(event, is(not(instanceOf(ItemCommandEvent.class))));
            assertThat(((ItemStateEvent) event).getItemName(),
                    is(in(List.of(EnergyItems.ENGINE_STATUS, EnergyItems.CURRENT_LEVEL))));
        }
    }

    /**
     * The site is steered by the framework, which does not know this component exists. A bus that refuses a status
     * update must therefore not propagate out of here.
     */
    @Test
    public void aFailingBusIsAReportingProblemAndNotACrash() {
        EnergyStatusPublisher failing = new EnergyStatusPublisher(event -> {
            throw new IllegalStateException("the event bus is not available");
        });

        failing.receive(cycleEvent(cycle("NORMAL", 3, Map.of())));
    }

    @Test
    public void anEventOfNeitherTypeIsIgnored() {
        publisher.receive(new Event() {

            @Override
            public String getType() {
                return "SomethingElseEntirely";
            }

            @Override
            public String getTopic() {
                return "openhab/energy/whatever";
            }

            @Override
            public String getPayload() {
                return "{}";
            }

            @Override
            public @Nullable String getSource() {
                return null;
            }
        });

        assertThat(bus.posted(), is(empty()));
    }

    /**
     * An event bus that records what it was posted and delivers it nowhere.
     *
     * @author Stamate Viorel - Initial contribution
     */
    private static final class RecordingBus implements EventPublisher {

        private final List<Event> posted = new ArrayList<>();

        @Override
        public void post(Event event) {
            posted.add(event);
        }

        List<Event> posted() {
            return List.copyOf(posted);
        }

        void clear() {
            posted.clear();
        }
    }
}
