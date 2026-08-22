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
import static org.mockito.Mockito.mock;
import static org.openhab.core.energy.internal.EngineTestFixtures.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.energy.EnergyProvider;
import org.openhab.core.energy.LevelGate;
import org.openhab.core.energy.ProviderRole;
import org.openhab.core.energy.level.FixtureCsv;
import org.openhab.core.energy.level.LevelCounts;
import org.openhab.core.energy.level.LevelDerivation;
import org.openhab.core.energy.level.LevelPlaneCondition;
import org.openhab.core.energy.level.PlannedLevelSchedule;
import org.openhab.core.energy.level.SurplusEscalationPolicy;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * The wiring between the level classifier and the engine: prices become a plan, the engine computes the level of its
 * own cycle from that plan, and the level gates a consumer.
 * <p>
 * The three wave-1 components were built independently against a shared contract, and this is where they meet. The
 * price series is the acceptance fixture itself, so what the engine acts on here is the same classification that
 * {@code LevelFixtureConformanceTest} pins against {@code expected-planned-levels.csv} - the level the engine
 * computes is demonstrably the level the corpus specified, not a re-derivation.
 * <p>
 * It also pins the direction of the dependency, which is the whole point of the seam: the engine <em>calls</em>
 * {@link org.openhab.core.energy.level.CurrentLevelFunction} with its own snapshot's instant and its own snapshot's
 * surplus. It never reads a level back, and the level plane never consults a clock - so a plan lookup and a live
 * escalation can never be answered for two different moments or from two different readings.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class LevelPlaneWiringTest {

    private final MutableClock clock = new MutableClock(T0);
    private final MapItemStateReader reader = new MapItemStateReader();
    private final RecordingSink sink = new RecordingSink();
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

    private final EnergyProvider grid = EnergyProvider.of("grid", "Grid_Power", ProviderRole.GRID);

    private EnergyEngine engine(EnergyLevelPlane levels, Map<String, Object> configuration,
            EnergyParticipant... participants) {
        EnergyEngine engine = new EnergyEngine(scheduler, clock, reader, configuration);
        engine.setParticipantSnapshotSource(new FixedParticipants(participants));
        engine.setCurrentLevelFunction(levels);
        engine.addActuationSink(sink);
        return engine;
    }

    private static EnergyLevelPlane escalatingFrom(double encouragedWatts, double overcapacityWatts) {
        EnergyLevelPlane plane = new EnergyLevelPlane(Map.of());
        plane.setEscalation(SurplusEscalationPolicy.graded(new QuantityType<>(encouragedWatts, Units.WATT),
                new QuantityType<>(overcapacityWatts, Units.WATT)));
        return plane;
    }

    @Test
    public void theFixturePlanIsWhatTheEngineActsOn() {
        SlotSeries prices = FixtureCsv.prices();
        EnergyLevelPlane levels = new EnergyLevelPlane(Map.of());
        PlannedLevelSchedule plan = levels.derivePlan(prices);

        // the plan the engine will compute from is the fixture classification, slot for slot
        assertThat(plan.codes(), is(FixtureCsv.read("/fixtures/expected-planned-levels.csv").stream()
                .map(row -> (int) row.value()).toList()));

        // walk the plan's own day and ask the function what the engine would compute at each slot
        List<EnergyLevel> asTheEngineComputesThem = new ArrayList<>();
        for (int slot = 0; slot < prices.size(); slot++) {
            Instant middle = prices.slotAt(slot).start().plus(Duration.ofMinutes(30));
            asTheEngineComputesThem.add(levels.levelAt(middle, OptionalDouble.empty()));
        }

        assertThat(asTheEngineComputesThem,
                is(plan.entries().stream().map(PlannedLevelSchedule.PlannedLevel::level).toList()));
    }

    @Test
    public void aBlockedSlotOfThePlanReachesTheEngineAndGatesAConsumer() {
        SlotSeries prices = FixtureCsv.prices();
        EnergyLevelPlane levels = new EnergyLevelPlane(Map.of());
        PlannedLevelSchedule plan = levels.derivePlan(prices);

        int blocked = plan.slotsAt(EnergyLevel.BLOCKED).indices().get(0);
        clock.advance(Duration.between(T0, prices.slotAt(blocked).start().plus(Duration.ofMinutes(30))));

        // a pool pump that may only run from ENCOURAGED upwards, with plenty of surplus to run on
        EnergyConsumer pump = gated("pump", 1, 1000, LevelGate.atLeast(EnergyLevel.ENCOURAGED));
        reader.putWatts("Grid_Power", 6000);
        EnergyEngine engine = engine(levels, Map.of(), grid, pump);
        engine.addAlgorithm(new DefaultSurplusAlgorithm());

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.context().level(), is(EnergyLevel.BLOCKED));
        // the surplus would have covered it; the level the classifier planned is what stopped it
        assertThat(outcome.outcomes(), is(empty()));
    }

    @Test
    public void anEncouragedSlotOfThePlanLetsTheSameConsumerRun() {
        SlotSeries prices = FixtureCsv.prices();
        EnergyLevelPlane levels = new EnergyLevelPlane(Map.of());
        PlannedLevelSchedule plan = levels.derivePlan(prices);

        int encouraged = plan.slotsAt(EnergyLevel.ENCOURAGED).indices().get(0);
        clock.advance(Duration.between(T0, prices.slotAt(encouraged).start().plus(Duration.ofMinutes(30))));

        EnergyConsumer pump = gated("pump", 1, 1000, LevelGate.atLeast(EnergyLevel.ENCOURAGED));
        reader.putWatts("Grid_Power", 6000);
        EnergyEngine engine = engine(levels, Map.of(), grid, pump);
        engine.addAlgorithm(new DefaultSurplusAlgorithm());

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.context().level(), is(EnergyLevel.ENCOURAGED));
        assertThat(outcome.shadowed(), hasSize(1));
        assertThat(outcome.shadowed().get(0).action(), is(ControlAction.on()));
    }

    @Test
    public void theLevelOfACycleIsEscalatedByThatCyclesOwnSurplus() {
        SlotSeries prices = FixtureCsv.prices();
        EnergyLevelPlane levels = escalatingFrom(1500, 3000);
        PlannedLevelSchedule plan = levels.derivePlan(prices);

        int blocked = plan.slotsAt(EnergyLevel.BLOCKED).indices().get(0);
        clock.advance(Duration.between(T0, prices.slotAt(blocked).start().plus(Duration.ofMinutes(30))));

        EnergyConsumer pump = gated("pump", 1, 1000, LevelGate.atLeast(EnergyLevel.ENCOURAGED));
        EnergyEngine engine = engine(levels, Map.of(), grid, pump);
        engine.addAlgorithm(new DefaultSurplusAlgorithm());

        // no surplus: the plan stands and the pump stays gated
        reader.putWatts("Grid_Power", 0);
        assertThat(engine.runCycleNow().context().level(), is(EnergyLevel.BLOCKED));

        // a real surplus in the very same blocked slot escalates the live level, exactly as the requirement wants
        reader.putWatts("Grid_Power", 6000);
        CycleOutcome escalated = engine.runCycleNow();
        assertThat(escalated.context().level(), is(EnergyLevel.OVERCAPACITY));
        assertThat(escalated.shadowed(), hasSize(1));

        // and the stored plan stayed intact
        assertThat(levels.getPlan().entries().get(blocked).level(), is(EnergyLevel.BLOCKED));
    }

    @Test
    public void theLevelAndTheSurplusOfOneCycleAlwaysComeFromTheSameReading() {
        EnergyLevelPlane levels = escalatingFrom(1500, 3000);
        levels.setPlan(new PlannedLevelSchedule(
                List.of(new PlannedLevelSchedule.PlannedLevel(T0, T0.plus(Duration.ofHours(6)), EnergyLevel.NORMAL))));

        EnergyConsumer pump = gated("pump", 1, 1000, LevelGate.always());
        reader.putWatts("Grid_Power", 6000);
        EnergyEngine engine = engine(levels, Map.of(), grid, pump);

        List<Double> surplusSeen = new ArrayList<>();
        List<EnergyLevel> levelSeen = new ArrayList<>();
        engine.registerAlgorithm("observer", 1, context -> {
            surplusSeen.add(context.surplusWatts().getAsDouble());
            levelSeen.add(context.level());
            // the world moves under the cycle; neither figure may follow it
            reader.putWatts("Grid_Power", 0);
            return List.of();
        });

        engine.runCycleNow();

        // 6000 W of surplus and the level that 6000 W escalates to, from one and the same reading
        assertThat(surplusSeen, contains(6000.0));
        assertThat(levelSeen, contains(EnergyLevel.OVERCAPACITY));

        // next cycle sees the new world, consistently on both figures
        engine.runCycleNow();
        assertThat(surplusSeen.get(1), is(0.0));
        assertThat(levelSeen.get(1), is(EnergyLevel.NORMAL));
    }

    /**
     * The other half of the same guarantee, and the one a clock inside the level plane used to break: the plan slot
     * is looked up at the snapshot's instant, and the only clock in the picture is the engine's. Here the two
     * neighbouring slots carry different levels, so a component answering from a clock of its own would sooner or
     * later answer for the wrong slot.
     */
    @Test
    public void thePlanIsLookedUpAtTheSnapshotsInstantAndNotAtAClockOfItsOwn() {
        EnergyLevelPlane levels = new EnergyLevelPlane(Map.of());
        Instant secondSlot = T0.plus(Duration.ofHours(1));
        levels.setPlan(new PlannedLevelSchedule(
                List.of(new PlannedLevelSchedule.PlannedLevel(T0, secondSlot, EnergyLevel.NORMAL),
                        new PlannedLevelSchedule.PlannedLevel(secondSlot, secondSlot.plus(Duration.ofHours(1)),
                                EnergyLevel.OVERCAPACITY))));

        EnergyConsumer pump = gated("pump", 1, 1000, LevelGate.always());
        reader.putWatts("Grid_Power", 0);
        EnergyEngine engine = engine(levels, Map.of(), grid, pump);

        assertThat("the first cycle stands in the first slot", engine.runCycleNow().context().level(),
                is(EnergyLevel.NORMAL));

        clock.advance(Duration.ofHours(1));

        assertThat("moving the engine's own clock moves the slot, and nothing else can",
                engine.runCycleNow().context().level(), is(EnergyLevel.OVERCAPACITY));
    }

    @Test
    public void withoutAPlanTheLevelIsNormalAndTheAbsenceIsReported() {
        EnergyLevelPlane levels = new EnergyLevelPlane(Map.of());
        EnergyConsumer pump = gated("pump", 1, 1000, LevelGate.always());
        reader.putWatts("Grid_Power", 6000);

        // an engine with no level plane bound at all treats the site as NORMAL...
        EnergyEngine bare = new EnergyEngine(scheduler, clock, reader, Map.of());
        bare.setParticipantSnapshotSource(new FixedParticipants(grid, pump));
        assertThat(bare.runCycleNow().context().level(), is(EnergyLevel.NORMAL));

        // ...and so does one that has the level plane bound but nothing has published a plan yet
        assertThat(engine(levels, Map.of(), grid, pump).runCycleNow().context().level(), is(EnergyLevel.NORMAL));
        assertThat(levels.getPlan().size(), is(0));

        // but "normal" and "no prices have ever arrived" are not the same thing, and the plane says which it is
        assertThat(levels.conditionsAt(T0), hasItem(LevelPlaneCondition.PLAN_ABSENT));
    }

    @Test
    public void theDerivationIsSelectableByConfigurationAndNeitherOptionIsHardWired() {
        SlotSeries prices = FixtureCsv.prices();

        EnergyLevelPlane counts = new EnergyLevelPlane(
                Map.of("derivation", "fixed-counts", "overcapacitySlots", 2, "encouragedSlots", 2, "blockedSlots", 2));
        EnergyLevelPlane fractions = new EnergyLevelPlane(Map.of("derivation", "percentiles", "overcapacityFraction",
                1.0 / 6, "encouragedFraction", 1.0 / 6, "blockedFraction", 1.0 / 6));

        assertThat(counts.derivePlan(prices).slotsAt(EnergyLevel.OVERCAPACITY).indices(), hasSize(2));
        assertThat(fractions.derivePlan(prices).slotsAt(EnergyLevel.OVERCAPACITY).indices(), hasSize(4));

        // the seasonal variant is reachable too, just not from configuration - the corpus defines no grammar for
        // season boundaries, so this component does not invent one
        EnergyLevelPlane seasonal = new EnergyLevelPlane(Map.of());
        seasonal.setDerivation(LevelDerivation.fixedCounts(LevelCounts.of(1, 1, 1)));
        assertThat(seasonal.derivePlan(prices).slotsAt(EnergyLevel.BLOCKED).indices(), hasSize(1));
    }

    /**
     * The level outside the plan is no longer a choice. It reads normal, always, and the absence of the plan is what
     * gets reported - a failed price fetch must not be configurable into a cold house.
     */
    @Test
    public void theLevelOutsideThePlanIsNormalAndFixed() {
        PlannedLevelSchedule plan = new PlannedLevelSchedule(List
                .of(new PlannedLevelSchedule.PlannedLevel(T0, T0.plus(Duration.ofHours(1)), EnergyLevel.ENCOURAGED)));
        EnergyLevelPlane levels = new EnergyLevelPlane(Map.of());
        levels.setPlan(plan);

        assertThat("inside the plan the plan decides", levels.levelAt(T0, OptionalDouble.empty()),
                is(EnergyLevel.ENCOURAGED));
        assertThat(levels.conditionsAt(T0), not(hasItem(LevelPlaneCondition.PLAN_ABSENT)));

        Instant afterwards = T0.plus(Duration.ofHours(2));
        assertThat("after it runs out, the answer is normal", levels.levelAt(afterwards, OptionalDouble.empty()),
                is(EnergyLevel.NORMAL));
        assertThat("and the absence is what says so", levels.conditionsAt(afterwards),
                hasItem(LevelPlaneCondition.PLAN_ABSENT));

        // there is no parameter left that could turn the gap into anything else
        assertThat(EnergyLevelConfiguration.CONFIG_KEYS, not(hasItem("levelOutsidePlan")));
        assertThat(EnergyLevelPlane.LEVEL_OUTSIDE_PLAN, is(EnergyLevel.NORMAL));
    }

    /**
     * A fresh site escalates on nothing and says so. Escalation is not off and it is not broken - it is waiting for
     * a threshold only the site can supply, and that has to be visible rather than merely inert.
     */
    @Test
    public void aFreshSiteReportsEscalationAsUnconfiguredRatherThanBeingSilentlyInert() {
        EnergyLevelPlane levels = new EnergyLevelPlane(Map.of());
        levels.setPlan(new PlannedLevelSchedule(
                List.of(new PlannedLevelSchedule.PlannedLevel(T0, T0.plus(Duration.ofHours(6)), EnergyLevel.NORMAL))));

        assertThat("any surplus at all", levels.levelAt(T0, OptionalDouble.of(50_000)), is(EnergyLevel.NORMAL));
        assertThat(levels.conditionsAt(T0), hasItem(LevelPlaneCondition.ESCALATION_UNCONFIGURED));

        EnergyLevelPlane configured = new EnergyLevelPlane(Map.of("encouragedFrom", 1500.0));
        configured.setPlan(levels.getPlan());
        assertThat(configured.levelAt(T0, OptionalDouble.of(1500)), is(EnergyLevel.ENCOURAGED));
        assertThat(configured.conditionsAt(T0), not(hasItem(LevelPlaneCondition.ESCALATION_UNCONFIGURED)));
    }
}
