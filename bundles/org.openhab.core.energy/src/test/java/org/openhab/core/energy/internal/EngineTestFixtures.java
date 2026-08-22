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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.ActuationSink;
import org.openhab.core.energy.ControllableProfile;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.energy.LevelGate;
import org.openhab.core.energy.SimpleProfile;
import org.openhab.core.energy.level.CurrentLevelFunction;
import org.openhab.core.energy.spi.ParticipantSnapshotSource;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.types.State;

/**
 * Shared scaffolding for the engine tests: a clock that can be moved, a map-backed Item reader, a sink that records
 * instead of writing, and the two archetype consumers the corpus keeps using.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class EngineTestFixtures {

    /**
     * The moment every test starts at.
     */
    public static final Instant T0 = Instant.parse("2026-07-01T10:00:00Z");

    private EngineTestFixtures() {
    }

    /**
     * Creates an ON/OFF consumer with a declared on-threshold and no protections.
     *
     * @param id the participant id
     * @param priority the allocation priority, lower is better
     * @param thresholdWatts the on-threshold in watts, which the engine also reads as its rating
     * @return the consumer
     */
    public static EnergyConsumer simple(String id, int priority, double thresholdWatts) {
        SimpleProfile profile = new SimpleProfile(new QuantityType<Power>(thresholdWatts, Units.WATT), null, null, null,
                null, null, LevelGate.always());
        return EnergyConsumer.of(id, id + "_Switch", profile, priority);
    }

    /**
     * Creates an ON/OFF consumer its owner marked hands-off - the engine reads it and never steers it.
     *
     * @param id the participant id
     * @param priority the allocation priority
     * @param thresholdWatts the on-threshold in watts
     * @return the consumer
     */
    public static EnergyConsumer handsOff(String id, int priority, double thresholdWatts) {
        return simple(id, priority, thresholdWatts).withHandsOff();
    }

    /**
     * Creates an ON/OFF consumer gated on a minimum site level.
     *
     * @param id the participant id
     * @param priority the allocation priority
     * @param thresholdWatts the on-threshold in watts
     * @param gate the level gate
     * @return the consumer
     */
    public static EnergyConsumer gated(String id, int priority, double thresholdWatts, LevelGate gate) {
        SimpleProfile profile = new SimpleProfile(new QuantityType<Power>(thresholdWatts, Units.WATT), null, null, null,
                null, null, gate);
        return EnergyConsumer.of(id, id + "_Switch", profile, priority);
    }

    /**
     * Creates a current-bounded consumer - the wallbox of the requirement's own scenario.
     *
     * @param id the participant id
     * @param priority the allocation priority
     * @param minAmperes the lowest current it accepts while charging
     * @param maxAmperes the highest current it accepts
     * @return the consumer
     */
    public static EnergyConsumer wallbox(String id, int priority, double minAmperes, double maxAmperes) {
        return EnergyConsumer.of(id, id + "_Current", ControllableProfile.amperes(minAmperes, maxAmperes), priority);
    }

    /**
     * Starts a snapshot at {@link #T0} with the given level.
     *
     * @param level the site level
     * @return the builder
     */
    public static EnergyContext.Builder context(EnergyLevel level) {
        return EnergyContext.builder(T0, level);
    }

    /**
     * A clock the test moves by hand, so an acknowledgement window can expire without anything sleeping.
     *
     * @author Stamate Viorel - Initial contribution
     */
    public static final class MutableClock extends Clock {

        private Instant now;

        /**
         * Creates the clock.
         *
         * @param now the moment it starts at
         */
        public MutableClock(Instant now) {
            this.now = now;
        }

        /**
         * Moves the clock forward.
         *
         * @param amount how far to move it
         */
        public void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(@Nullable ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * An Item reader backed by a map, so a test can change "the world" between cycles.
     *
     * @author Stamate Viorel - Initial contribution
     */
    public static final class MapItemStateReader implements ItemStateReader {

        private final Map<String, State> states = new HashMap<>();
        private final Map<String, Instant> updates = new HashMap<>();
        private final Map<String, Instant> changes = new HashMap<>();
        private final Map<String, HistoryRetention> retentions = new HashMap<>();

        /**
         * Sets the state of an Item.
         *
         * @param itemName the Item name
         * @param state the state
         * @return this reader
         */
        public MapItemStateReader put(String itemName, State state) {
            states.put(itemName, state);
            return this;
        }

        /**
         * Sets the state of an Item to a power reading.
         *
         * @param itemName the Item name
         * @param watts the reading in watts
         * @return this reader
         */
        public MapItemStateReader putWatts(String itemName, double watts) {
            return put(itemName, new QuantityType<Power>(watts, Units.WATT));
        }

        /**
         * Sets when an Item was last updated.
         *
         * @param itemName the Item name
         * @param at the moment of the last update
         * @return this reader
         */
        public MapItemStateReader putLastUpdate(String itemName, Instant at) {
            updates.put(itemName, at);
            return this;
        }

        @Override
        public @Nullable State readState(String itemName) {
            return states.get(itemName);
        }

        @Override
        public @Nullable Instant lastUpdate(String itemName) {
            return updates.get(itemName);
        }

        @Override
        public @Nullable Instant lastChange(String itemName) {
            return changes.get(itemName);
        }

        /**
         * Records when an Item last changed state, which is what the protection durations are measured from.
         *
         * @param itemName the Item name
         * @param at the moment of the last state change
         */
        public void putLastChange(String itemName, Instant at) {
            changes.put(itemName, at);
        }

        @Override
        public HistoryRetention retentionOf(String itemName) {
            HistoryRetention declared = retentions.get(itemName);
            return declared == null ? HistoryRetention.UNKNOWN : declared;
        }

        /**
         * Records what the site's persistence configuration says about an Item, which is what tells "nothing is
         * keeping this history" from "the history is kept and holds no change yet" (owner decision D28). Unset
         * leaves the reader saying {@link HistoryRetention#UNKNOWN}, which is what a reader with no view of the
         * persistence configuration honestly answers.
         *
         * @param itemName the Item name
         * @param retention whether that Item's history is being kept
         * @return this reader
         */
        public MapItemStateReader putRetention(String itemName, HistoryRetention retention) {
            retentions.put(itemName, retention);
            return this;
        }
    }

    /**
     * Names the recording sink in a configuration map.
     * <p>
     * The engine deliberately dispatches only to a sink an operator has selected - binding one is not enough, so
     * that nothing can become a site's writer by winning a binding race. Without this every actuation assertion
     * below would pass vacuously.
     *
     * @param configuration the test's own configuration
     * @return the configuration with the recording sink selected
     */
    public static Map<String, Object> withRecordingSink(Map<String, Object> configuration) {
        Map<String, Object> merged = new LinkedHashMap<>(configuration);
        merged.put("actuationSink", "recording");
        return merged;
    }

    /**
     * An actuation sink that records what it was handed. Even this one writes to no Item.
     *
     * @author Stamate Viorel - Initial contribution
     */
    public static final class RecordingSink implements ActuationSink {

        private final List<Decision> dispatched = new ArrayList<>();
        private final String id;

        /**
         * Creates the sink under the id the fixtures name site-wide.
         */
        public RecordingSink() {
            this("recording");
        }

        /**
         * Creates the sink under a given id, which is how a participant naming its own sink is exercised.
         *
         * @param sinkId the id this sink registers under
         */
        public RecordingSink(String sinkId) {
            id = sinkId;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public void dispatch(Decision decision, EnergyContext context) {
            dispatched.add(decision);
        }

        /**
         * Returns what the engine dispatched, in order.
         *
         * @return the dispatched decisions
         */
        public List<Decision> dispatched() {
            return List.copyOf(dispatched);
        }
    }

    /**
     * An event bus that records what it was posted, so a test can assert on the engine's outward voice.
     * <p>
     * It is a recorder rather than a bus: nothing it receives goes anywhere, which is what makes "the engine posted
     * exactly these two event types and nothing else" a statement about the engine rather than about the runtime.
     *
     * @author Stamate Viorel - Initial contribution
     */
    public static final class RecordingEventPublisher implements EventPublisher {

        private final List<Event> posted = new ArrayList<>();

        @Override
        public void post(Event event) {
            posted.add(event);
        }

        /**
         * Returns everything posted so far, in order.
         *
         * @return the posted events
         */
        public List<Event> events() {
            return List.copyOf(posted);
        }

        /**
         * Returns the posted events of one type, in order.
         *
         * @param type the event type, as {@code Event.getType()} reports it
         * @return the matching events
         */
        public List<Event> events(String type) {
            return posted.stream().filter(event -> type.equals(event.getType())).toList();
        }

        /**
         * Forgets everything posted so far, so a test can assert about one cycle in isolation.
         */
        public void clear() {
            posted.clear();
        }
    }

    /**
     * A participant source with a fixed set of participants.
     *
     * @author Stamate Viorel - Initial contribution
     */
    public static final class FixedParticipants implements ParticipantSnapshotSource {

        private final List<EnergyParticipant> participants;

        /**
         * Creates the source.
         *
         * @param participants the participants of every cycle
         */
        public FixedParticipants(EnergyParticipant... participants) {
            this.participants = List.of(participants);
        }

        @Override
        public Collection<EnergyParticipant> getParticipants() {
            return participants;
        }
    }

    /**
     * A level function that answers the same level whatever it is asked.
     * <p>
     * It takes the moment and the surplus like any other implementation and ignores both, which is the point: a test
     * that wants a fixed level says so here rather than by arranging for a plan and a clock to agree.
     *
     * @author Stamate Viorel - Initial contribution
     */
    public static final class FixedLevel implements CurrentLevelFunction {

        private final EnergyLevel level;

        /**
         * Creates the function.
         *
         * @param level the level of every cycle
         */
        public FixedLevel(EnergyLevel level) {
            this.level = level;
        }

        @Override
        public EnergyLevel levelAt(Instant moment, OptionalDouble surplusWatts) {
            return level;
        }
    }
}
