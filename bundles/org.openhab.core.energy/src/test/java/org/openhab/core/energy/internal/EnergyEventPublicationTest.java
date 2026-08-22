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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.openhab.core.energy.internal.EngineTestFixtures.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.ControllableProfile;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.EnergyProvider;
import org.openhab.core.energy.LevelGate;
import org.openhab.core.energy.ProviderRole;
import org.openhab.core.energy.SimpleProfile;
import org.openhab.core.energy.events.EnergyCycleDTO;
import org.openhab.core.energy.events.EnergyCycleEvent;
import org.openhab.core.energy.events.EnergyDecisionDTO;
import org.openhab.core.energy.events.EnergyDecisionEvent;
import org.openhab.core.events.Event;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * The engine's outward voice, which D23 put in this bundle rather than in the publishing one.
 * <p>
 * A8 asks for every decision to carry an outcome and a reason and for both to be <em>published</em>, deduplicated so
 * an unchanged decision is not re-emitted. The owner's follow-up answer (D23, 2026-08-03,
 * {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}) settled that this half ships here, because posting an event is
 * not an Item write, while the status Item and the REST view move to {@code org.openhab.core.energy.publish}. What
 * is asserted here is therefore the framework's side of the split in full: that the events go out, that they carry
 * what a subscriber needs, that an unchanged decision is silent, that a site with no publishing component still
 * reports, and that an event surviving a round trip through the bus is still readable.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EnergyEventPublicationTest {

    private final MutableClock clock = new MutableClock(T0);
    private final MapItemStateReader reader = new MapItemStateReader();
    private final RecordingSink sink = new RecordingSink();
    private final RecordingEventPublisher published = new RecordingEventPublisher();
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

    private final EnergyProvider grid = EnergyProvider.of("grid", "Grid_Power", ProviderRole.GRID);
    private final EnergyConsumer boiler = EnergyConsumer
            .of("boiler", "Boiler_Switch", new SimpleProfile(new QuantityType<Power>(2000, Units.WATT), null, null,
                    null, null, null, LevelGate.always()), 2)
            .withMeasurement("Boiler_Power");
    private final EnergyConsumer wallbox = EnergyConsumer
            .of("wallbox", "Wallbox_Current", ControllableProfile.amperes(6, 32), 1).withMeasurement("Wallbox_Power");

    /**
     * The current the test's own wallbox algorithm asks for. Changing it is how a test says "the engine now reaches a
     * different conclusion about the same device", without having to find a grid reading that happens to produce one.
     */
    private volatile double wallboxAmperes = 16;

    /**
     * Builds the engine over a two-consumer site, with the recording publisher bound.
     *
     * @param configuration the engine configuration
     * @return the engine
     */
    private EnergyEngine site(Map<String, Object> configuration) {
        EnergyEngine engine = new EnergyEngine(scheduler, clock, reader, withRecordingSink(configuration));
        engine.setParticipantSnapshotSource(new FixedParticipants(grid, boiler, wallbox));
        engine.addActuationSink(sink);
        engine.addAlgorithm(new DefaultSurplusAlgorithm());
        engine.registerAlgorithm("test-wallbox", 1, context -> List.of(
                Decision.of("wallbox", ControlAction.amperes(wallboxAmperes), "test-wallbox", 1, "the test says so")));
        engine.setEventPublisher(published);
        return engine;
    }

    /**
     * Moves the synthetic day on one step. Every declared reading is written, because a reading the engine cannot
     * read at all freezes the site and would be demonstrating the safe state instead.
     *
     * @param gridWatts the grid reading, positive = export
     */
    private void nextHour(double gridWatts) {
        reader.putWatts("Grid_Power", gridWatts);
        reader.putWatts("Boiler_Power", 0);
        reader.putWatts("Wallbox_Power", 0);
        clock.advance(Duration.ofHours(1));
    }

    /**
     * Returns the decision events published so far.
     *
     * @return the decision events
     */
    private List<EnergyDecisionEvent> decisions() {
        return published.events(EnergyDecisionEvent.TYPE).stream().map(EnergyDecisionEvent.class::cast).toList();
    }

    /**
     * Returns the cycle events published so far.
     *
     * @return the cycle events
     */
    private List<EnergyCycleEvent> cycles() {
        return published.events(EnergyCycleEvent.TYPE).stream().map(EnergyCycleEvent.class::cast).toList();
    }

    @Test
    public void everyDecisionIsPublishedWithItsOutcomeAndItsReason() {
        EnergyEngine engine = site(Map.of());
        nextHour(4800);

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.outcomes(), is(not(empty())));
        assertThat(decisions(), hasSize(outcome.outcomes().size()));
        for (EnergyDecisionEvent event : decisions()) {
            EnergyDecisionDTO decision = event.getDecision();
            assertThat(decision.status, is(not(emptyString())));
            assertThat(decision.reason, is(not(emptyString())));
            assertThat(decision.action, is(not(emptyString())));
            assertThat(decision.algorithmId, is(not(emptyString())));
            assertThat(decision.shadow, is(true));
            assertThat(decision.cycle, is(outcome.context().timestamp().toString()));
            assertThat(event.getTopic(), is("openhab/energy/" + decision.participantId + "/decision"));
        }
        // every outcome the cycle produced reached the bus, and no other did
        assertThat(decisions().stream().map(EnergyDecisionEvent::getParticipantId).sorted().toList(),
                is(outcome.outcomes().stream().map(each -> each.decision().participantId()).sorted().toList()));
        assertThat(decisions().stream().map(EnergyDecisionEvent::getParticipantId).toList(),
                hasItems("boiler", "wallbox"));
    }

    /**
     * The requirement's own wording: "re-published only when it changes". The engine is a control loop, so without
     * this an unchanged site would emit one event per decision per tick - fourteen hundred a day per device in the
     * mode a fresh installation starts in, which is the log line the requirement exists to replace.
     */
    @Test
    public void anUnchangedDecisionIsNotRepublished() {
        EnergyEngine engine = site(Map.of());
        nextHour(4800);
        engine.runCycleNow();
        int first = decisions().size();
        assertThat(first, is(greaterThan(0)));

        published.clear();
        CycleOutcome second = engine.runCycleNow();

        // the cycle still decided the same things ...
        assertThat(second.outcomes(), hasSize(first));
        // ... and said nothing new about any of them
        assertThat(published.events(), is(empty()));
    }

    @Test
    public void aChangedDecisionIsRepublished() {
        EnergyEngine engine = site(Map.of());
        nextHour(4800);
        engine.runCycleNow();
        published.clear();

        // the engine now wants a different current for the same device
        wallboxAmperes = 20;
        engine.runCycleNow();

        assertThat(decisions().stream().map(EnergyDecisionEvent::getParticipantId).toList(), contains("wallbox"));
        assertThat(decisions().get(0).getDecision().action, containsString("20"));
    }

    /**
     * Two proposals about one participant in one cycle - a winner and the one it superseded - must not collapse onto
     * a single deduplication key, or both would look new on every tick.
     */
    @Test
    public void twoAlgorithmsAboutOneParticipantAreDeduplicatedSeparately() {
        EnergyEngine engine = site(Map.of());
        engine.registerAlgorithm("rival", 9,
                context -> List.of(Decision.of("boiler", ControlAction.off(), "rival", 9, "the rival wants it off")));
        nextHour(4800);

        engine.runCycleNow();
        assertThat(decisions().stream().map(event -> event.getDecision().algorithmId).toList(),
                hasItems(DefaultSurplusAlgorithm.ID, "rival"));

        published.clear();
        engine.runCycleNow();

        assertThat("neither of the two conclusions about the boiler changed", published.events(), is(empty()));
    }

    @Test
    public void theCycleEventCarriesTheLevelAndTheSummary() {
        EnergyEngine engine = site(Map.of());
        nextHour(4800);

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(cycles(), hasSize(1));
        EnergyCycleEvent event = cycles().get(0);
        EnergyCycleDTO cycle = event.getCycle();
        assertThat(event.getTopic(), is("openhab/energy/engine/cycle"));
        assertThat(cycle.timestamp, is(outcome.context().timestamp().toString()));
        assertThat(event.getLevel(), is(EnergyLevel.NORMAL.name()));
        assertThat(event.isShadow(), is(true));
        assertThat(event.isStopped(), is(false));
        assertThat(cycle.participants, is(3));
        assertThat(cycle.outcomes.get(DecisionStatus.SHADOWED.name()), is(outcome.shadowed().size()));
    }

    /**
     * The published level is the whole reason the cycle event exists as well as the decision event: D6 asks for a
     * current-level Item, D23 says the framework may not write one, so the level has to leave here.
     */
    @Test
    public void aChangedLevelIsPublishedWithoutTheEngineWritingAnItem() {
        EnergyEngine engine = site(Map.of());
        engine.setCurrentLevelFunction(new FixedLevel(EnergyLevel.OVERCAPACITY));
        nextHour(4800);

        engine.runCycleNow();

        assertThat(cycles().stream().map(EnergyCycleEvent::getLevel).toList(),
                hasItem(EnergyLevel.OVERCAPACITY.name()));
        assertThat(sink.dispatched(), is(empty()));
    }

    /**
     * The summary of the participant conditions rides with the cycle, in the same vocabulary as the pull surface, so
     * the status Item the publishing component maintains and the configuration-status page an operator opens can
     * never say different things.
     */
    @Test
    public void aParticipantConditionRidesOnTheCycleEvent() {
        // an on-threshold and no rated power: the floor books the threshold as the rating, which is a declared gap
        EnergyConsumer unrated = EnergyConsumer.of("unrated", "Unrated_Switch", new SimpleProfile(
                new QuantityType<Power>(500, Units.WATT), null, null, null, null, null, LevelGate.always()), 3);
        EnergyEngine engine = new EnergyEngine(scheduler, clock, reader, withRecordingSink(Map.of()));
        engine.setParticipantSnapshotSource(new FixedParticipants(grid, unrated));
        engine.addActuationSink(sink);
        engine.setEventPublisher(published);
        nextHour(4800);
        reader.put("Unrated_Switch", OnOffType.OFF);

        engine.runCycleNow();

        assertThat(cycles(), hasSize(1));
        assertThat(cycles().get(0).getCycle().participantGaps,
                hasEntry(is("unrated"), containsString("declares no rated power")));
    }

    /**
     * A stopped engine reads nothing, evaluates nothing and enforces nothing - but it is not allowed to go quiet
     * about being stopped, because that is the one thing an operator needs to be able to see.
     */
    @Test
    public void aStoppedEngineStillPublishesThatItIsStopped() {
        EnergyEngine engine = site(Map.of());
        nextHour(4800);
        engine.runCycleNow();
        published.clear();

        engine.setStopped(true);
        engine.runCycleNow();

        assertThat(cycles(), hasSize(1));
        assertThat(cycles().get(0).isStopped(), is(true));
        assertThat(decisions(), is(empty()));
    }

    /**
     * The scenario the owner's answer added: a site running the framework alone still reports. Nothing here is
     * conditional on a publishing component existing.
     */
    @Test
    public void theEngineDecidesJustTheSameWithNoPublisherBound() {
        EnergyEngine engine = new EnergyEngine(scheduler, clock, reader, withRecordingSink(Map.of()));
        engine.setParticipantSnapshotSource(new FixedParticipants(grid, boiler, wallbox));
        engine.addActuationSink(sink);
        engine.addAlgorithm(new DefaultSurplusAlgorithm());
        nextHour(4800);

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.outcomes(), is(not(empty())));
        assertThat(published.events(), is(empty()));
    }

    @Test
    public void unbindingThePublisherStopsThePublishingAndNothingElse() {
        EnergyEngine engine = site(Map.of());
        nextHour(4800);
        engine.runCycleNow();
        assertThat(published.events(), is(not(empty())));

        engine.unsetEventPublisher(published);
        published.clear();
        wallboxAmperes = 20;
        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.outcomes(), is(not(empty())));
        assertThat(published.events(), is(empty()));
    }

    /**
     * A publisher that throws is a reporting problem, never a control-loop problem: the cycle that produced the
     * decisions has already finished by the time anything is posted, and the site must not stop being steered
     * because a subscriber blew up.
     */
    @Test
    public void aFailingPublisherDoesNotBreakTheCycle() {
        EnergyEngine engine = site(Map.of());
        engine.setEventPublisher(event -> {
            throw new IllegalStateException("the event bus is not available");
        });
        nextHour(4800);

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.outcomes(), is(not(empty())));
    }

    /**
     * An event that has crossed the bus arrives as a topic and a JSON payload; without the factory a subscriber
     * could not read an outcome or a level back out of it. Both types therefore have to survive the round trip.
     *
     * @throws Exception if the factory refuses the event
     */
    @Test
    public void bothEventTypesSurviveTheRoundTripThroughTheBus() throws Exception {
        EnergyEngine engine = site(Map.of());
        nextHour(4800);
        engine.runCycleNow();
        EnergyEventFactory factory = new EnergyEventFactory();
        assertThat(factory.getSupportedEventTypes(),
                containsInAnyOrder(EnergyDecisionEvent.TYPE, EnergyCycleEvent.TYPE));

        EnergyDecisionEvent decision = decisions().get(0);
        Event restoredDecision = factory.createEvent(EnergyDecisionEvent.TYPE, decision.getTopic(),
                decision.getPayload(), null);
        EnergyCycleEvent cycle = cycles().get(0);
        Event restoredCycle = factory.createEvent(EnergyCycleEvent.TYPE, cycle.getTopic(), cycle.getPayload(), null);

        assertThat(restoredDecision, is(instanceOf(EnergyDecisionEvent.class)));
        EnergyDecisionEvent readBack = (EnergyDecisionEvent) restoredDecision;
        assertThat(readBack.getParticipantId(), is(decision.getParticipantId()));
        assertThat(readBack.getStatus(), is(decision.getStatus()));
        assertThat(readBack.getReason(), is(decision.getReason()));
        assertThat(readBack.toString(), is(decision.toString()));

        assertThat(restoredCycle, is(instanceOf(EnergyCycleEvent.class)));
        EnergyCycleEvent cycleReadBack = (EnergyCycleEvent) restoredCycle;
        assertThat(cycleReadBack.getLevel(), is(cycle.getLevel()));
        assertThat(cycleReadBack.getCycle().participants, is(cycle.getCycle().participants));
        assertThat(cycleReadBack.getCycle().outcomes, is(cycle.getCycle().outcomes));
        assertThat(cycleReadBack.toString(), is(cycle.toString()));
    }

    @Test
    public void theFactoryRefusesAnEventTypeItDoesNotOwn() {
        EnergyEventFactory factory = new EnergyEventFactory();

        assertThrows(IllegalArgumentException.class,
                () -> factory.createEvent("ItemCommandEvent", "openhab/items/Boiler/command", "{}", null));
    }
}
