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
package org.openhab.core.energy.level;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.LevelGate;
import org.openhab.core.energy.SimpleProfile;
import org.openhab.core.energy.level.SeasonalParameters.Season;
import org.openhab.core.energy.window.CostWeights;
import org.openhab.core.energy.window.SelectionStrategy;
import org.openhab.core.energy.window.SlotSelection;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.energy.window.WindowCost;
import org.openhab.core.energy.window.WindowRequest;
import org.openhab.core.energy.window.WindowSelection;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * One test per {@code #### Scenario:} of the {@code energy-levels} capability, plus the behaviours those scenarios
 * imply but do not spell out.
 * <p>
 * The scenarios covered here are <em>Numeric exchange</em>, <em>Mode round trip</em>, <em>Overcapacity drives mode
 * 4</em>, <em>Binary collapse</em>, <em>Repeated prices</em>, <em>First graded step</em>, <em>Second graded step</em>,
 * <em>Battery charging is part of the surplus</em>, <em>A site that has set no threshold does not escalate</em>,
 * <em>Plan says normal, sun says overcapacity</em>, <em>Consecutive window at 15-minute resolution</em>, <em>Not
 * enough eligible slots</em> and <em>Winter widens cheap windows</em>. <em>Cheapest-hours classification</em> is
 * pinned against the fixture in {@link LevelFixtureConformanceTest}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EnergyLevelScenarioTest {

    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");
    private static final Instant MIDNIGHT = Instant.parse("2026-01-15T00:00:00Z");

    /**
     * Scenario "Numeric exchange": the four codes are fixed centrally, so a level written as a number by one
     * component and read back by another returns the level it started from.
     */
    @Test
    public void aLevelWrittenAsANumberReadsBackAsTheSameLevel() {
        assertThat(EnergyLevel.BLOCKED.code(), is(0));
        assertThat(EnergyLevel.NORMAL.code(), is(1));
        assertThat(EnergyLevel.ENCOURAGED.code(), is(2));
        assertThat(EnergyLevel.OVERCAPACITY.code(), is(3));

        for (EnergyLevel level : EnergyLevel.values()) {
            assertThat("round trip of " + level, EnergyLevel.fromCode(level.code()), is(Optional.of(level)));
        }
        assertThat(EnergyLevel.fromCode(-1).isEmpty(), is(true));
        assertThat(EnergyLevel.fromCode(4).isEmpty(), is(true));
    }

    /**
     * Scenario "Overcapacity drives mode 4": the site level maps onto a heat pump's mode with no translation logic,
     * in both directions and for all four levels.
     */
    @Test
    public void sgReadyModeMapsOneToOneOntoTheScale() {
        assertThat(SgReadyMode.of(EnergyLevel.BLOCKED), is(SgReadyMode.BLOCKED_OPERATION));
        assertThat(SgReadyMode.of(EnergyLevel.NORMAL), is(SgReadyMode.NORMAL_OPERATION));
        assertThat(SgReadyMode.of(EnergyLevel.ENCOURAGED), is(SgReadyMode.RECOMMENDED_ON));
        assertThat("overcapacity drives mode 4, never mode 3", SgReadyMode.of(EnergyLevel.OVERCAPACITY),
                is(SgReadyMode.FORCED_ON));
        assertThat(SgReadyMode.of(EnergyLevel.OVERCAPACITY).mode(), is(4));

        assertThat(SgReadyMode.fromMode(0).isEmpty(), is(true));
        assertThat(SgReadyMode.fromMode(5).isEmpty(), is(true));
    }

    /**
     * Scenario "Mode round trip": the correspondence is named and total, and the two scales differ by exactly one
     * throughout - so no level's code is ever the mode it maps to, and nobody may reach a mode by arithmetic on a
     * code that happens to look right.
     */
    @Test
    public void anSgReadyModeRoundTripsAndNeverEqualsTheLevelCode() {
        for (EnergyLevel level : EnergyLevel.values()) {
            SgReadyMode mode = SgReadyMode.of(level);
            assertThat("round trip of " + level, mode.level(), is(level));
            assertThat(SgReadyMode.fromMode(mode.mode()), is(Optional.of(mode)));
            assertThat("mode " + mode.mode() + " must not be reachable as the code of " + level, mode.mode(),
                    is(not(level.code())));
            assertThat("the two scales differ by exactly one", mode.mode(), is(level.code() + 1));
        }
    }

    /**
     * Scenario "Binary collapse": a Simple ON/OFF consumer subscribing to levels sees allow/deny, through the level
     * gate of the participant model.
     */
    @Test
    public void simpleConsumerCollapsesTheScaleToAllowOrDeny() {
        EnergyConsumer boiler = EnergyConsumer.of("boiler", "Boiler_Switch",
                SimpleProfile.withGate(LevelGate.atLeast(EnergyLevel.ENCOURAGED)), 2);
        LevelGate gate = boiler.levelGate().orElseThrow();

        assertThat(gate.permits(EnergyLevel.BLOCKED), is(false));
        assertThat(gate.permits(EnergyLevel.NORMAL), is(false));
        assertThat(gate.permits(EnergyLevel.ENCOURAGED), is(true));
        assertThat(gate.permits(EnergyLevel.OVERCAPACITY), is(true));
    }

    /**
     * Scenario "Repeated prices": more slots tie on a price than the band has room for, and the same slots land in
     * the band both times - decided by the stated tie-break, the earlier slot, and not by which slot was visited
     * first.
     * <p>
     * The shipped fixture has no repeated price, so this needs a series of its own. It tests the rule, not the data.
     */
    @Test
    public void repeatedPricesAreSplitByTheEarlierSlotAndSplitTheSameWayTwice() {
        SlotSeries tied = SlotSeries.hourly(MIDNIGHT, 5, 3, 3, 4, 3);
        LevelDerivation twoCheapest = LevelDerivation.fixedCounts(LevelCounts.of(2, 0, 0));

        PlannedLevelSchedule first = twoCheapest.derive(tied);
        PlannedLevelSchedule second = twoCheapest.derive(tied);

        assertThat("three slots tie at 3 and only two fit; the two earliest take the band",
                first.slotsAt(EnergyLevel.OVERCAPACITY).indices(), is(List.of(1, 2)));
        assertThat("and deriving again from the same series gives the same answer", second.codes(), is(first.codes()));
        assertThat("the tie-break lives in one place and orders the whole series", tied.rankedIndices(),
                is(List.of(1, 2, 4, 3, 0)));
    }

    /**
     * Scenario "PV escalation" and "Plan says normal, sun says overcapacity": live surplus raises the current level
     * while the stored plan stays untouched.
     */
    @Test
    public void pvSurplusEscalatesTheCurrentLevelWithoutChangingThePlan() {
        SlotSeries series = FixtureCsv.prices();
        PlannedLevelSchedule plan = LevelDerivation.fixedCounts(LevelCounts.of(4, 4, 4)).derive(series);
        Instant normalHour = Instant.parse("2023-03-24T03:30:00Z");
        assertThat(plan.levelAt(normalHour), is(Optional.of(EnergyLevel.NORMAL)));

        CurrentLevelResolver resolver = new CurrentLevelResolver(plan,
                SurplusEscalationPolicy.graded(watts(500), watts(3000)));

        assertThat(resolver.currentAt(normalHour, watts(4200)), is(Optional.of(EnergyLevel.OVERCAPACITY)));
        assertThat(resolver.currentAt(normalHour, watts(1200)), is(Optional.of(EnergyLevel.ENCOURAGED)));
        assertThat(resolver.currentAt(normalHour, watts(100)), is(Optional.of(EnergyLevel.NORMAL)));
        assertThat(resolver.currentAt(normalHour, null), is(Optional.of(EnergyLevel.NORMAL)));

        assertThat("the stored plan must stay intact", resolver.plannedAt(normalHour),
                is(Optional.of(EnergyLevel.NORMAL)));
        assertThat(plan.levelAt(normalHour), is(Optional.of(EnergyLevel.NORMAL)));
    }

    /**
     * Scenario "First graded step": an hour planned normal reads encouraged once surplus reaches the site's declared
     * threshold, and the plan is untouched.
     */
    @Test
    public void theFirstGradedStepReachesEncouragedAtTheDeclaredThreshold() {
        SurplusEscalationPolicy graded = SurplusEscalationPolicy.graded(watts(1500));

        assertThat(graded.escalate(EnergyLevel.NORMAL, watts(1499)), is(EnergyLevel.NORMAL));
        assertThat(graded.escalate(EnergyLevel.NORMAL, watts(1500)), is(EnergyLevel.ENCOURAGED));
        assertThat(graded.isConfigured(), is(true));
    }

    /**
     * Scenario "Second graded step": a site that declares only {@code encouragedFrom} gets {@code overcapacityFrom}
     * at twice that, which is the one relation the corpus fixes.
     */
    @Test
    public void theSecondGradedStepDefaultsToTwiceTheFirst() {
        SurplusEscalationPolicy graded = SurplusEscalationPolicy.graded(watts(1500));

        assertThat(graded.escalate(EnergyLevel.NORMAL, watts(2999)), is(EnergyLevel.ENCOURAGED));
        assertThat(graded.escalate(EnergyLevel.NORMAL, watts(3000)), is(EnergyLevel.OVERCAPACITY));
        assertThat("declaring the second threshold explicitly still works",
                SurplusEscalationPolicy.graded(watts(1500), watts(5000)).escalate(EnergyLevel.NORMAL, watts(3000)),
                is(EnergyLevel.ENCOURAGED));
    }

    /**
     * Scenario "Battery charging is part of the surplus": 3 kW absorbed by a battery the engine may reclaim counts
     * towards the thresholds exactly as exported power would.
     * <p>
     * Composing that figure - grid export plus the battery charging that is reclaimable for a better-priority
     * consumer - is the engine's job under the site's single sign convention; a policy is handed the number and never
     * derives it. What the level plane owes the requirement is that it cannot tell the two apart, which is what this
     * pins.
     */
    @Test
    public void batteryChargingCountsTowardsTheThresholdsExactlyAsExportWould() {
        SurplusEscalationPolicy graded = SurplusEscalationPolicy.graded(watts(1500));

        QuantityType<Power> exportOnly = watts(3000);
        QuantityType<Power> nothingExportedButThreeKilowattsReclaimable = watts(0 + 3000);

        assertThat(graded.escalate(EnergyLevel.NORMAL, nothingExportedButThreeKilowattsReclaimable),
                is(graded.escalate(EnergyLevel.NORMAL, exportOnly)));
        assertThat(graded.escalate(EnergyLevel.NORMAL, nothingExportedButThreeKilowattsReclaimable),
                is(EnergyLevel.OVERCAPACITY));
    }

    /**
     * Scenario "A site that has set no threshold does not escalate": the current level stays at its planned value at
     * any surplus, and the absence of a threshold is what says so.
     */
    @Test
    public void aSiteThatHasSetNoThresholdDoesNotEscalateAndSaysWhy() {
        SurplusEscalationPolicy unconfigured = SurplusEscalationPolicy.unconfigured();

        assertThat(unconfigured.escalate(EnergyLevel.NORMAL, watts(50_000)), is(EnergyLevel.NORMAL));
        assertThat(unconfigured.escalate(EnergyLevel.BLOCKED, watts(50_000)), is(EnergyLevel.BLOCKED));
        assertThat("not escalating and being unable to escalate are different things", unconfigured.isConfigured(),
                is(false));
    }

    /**
     * Escalation raises and never lowers. The requirement's verb is "escalate", and a policy that could demote would
     * turn the live plane into a second, competing derivation.
     */
    @Test
    public void gradedEscalationRaisesButNeverLowers() {
        SurplusEscalationPolicy graded = SurplusEscalationPolicy.graded(watts(500), watts(3000));

        assertThat("a moderate surplus must not demote a cheap hour",
                graded.escalate(EnergyLevel.OVERCAPACITY, watts(600)), is(EnergyLevel.OVERCAPACITY));
        assertThat("nor may no surplus at all", graded.escalate(EnergyLevel.ENCOURAGED, watts(0)),
                is(EnergyLevel.ENCOURAGED));
        assertThat("an unknown surplus never escalates", graded.escalate(EnergyLevel.NORMAL, null),
                is(EnergyLevel.NORMAL));
    }

    /**
     * The resolver answers "no plan covers this moment" rather than inventing a level. Turning that into the decided
     * answer - normal, with the absence reported - belongs to the component that owns the report, and is pinned in
     * {@code LevelPlaneWiringTest}.
     */
    @Test
    public void theResolverReportsThatNoPlanCoversAMoment() {
        SlotSeries series = FixtureCsv.prices();
        PlannedLevelSchedule plan = LevelDerivation.fixedCounts(LevelCounts.of(4, 4, 4)).derive(series);
        CurrentLevelResolver resolver = new CurrentLevelResolver(plan, SurplusEscalationPolicy.unconfigured());

        assertThat(resolver.currentAt(Instant.parse("2023-03-26T12:00:00Z"), watts(5000)), is(Optional.empty()));
        assertThat(resolver.plannedAt(Instant.parse("2023-03-20T12:00:00Z")), is(Optional.empty()));
    }

    /**
     * Scenario "Consecutive window at 15-minute resolution": a load needing two uninterrupted hours gets the cheapest
     * contiguous run of eight quarter-hour slots - which here is deliberately not the same set as the eight
     * individually cheapest slots.
     */
    @Test
    public void consecutiveWindowFindsTheCheapestEightQuarterHourRun() {
        SlotSeries series = SlotSeries.uniform(Instant.parse("2023-03-24T00:00:00Z"), Duration.ofMinutes(15), 8, 8, 8,
                8, 2, 9, 2, 9, 3, 3, 3, 3, 3, 3, 3, 9);

        WindowSelection byDuration = SelectionStrategy.consecutiveWindow().select(series,
                WindowRequest.ofDuration(Duration.ofHours(2)));
        SlotSelection byCount = SelectionStrategy.consecutiveWindow().select(series, 8);

        assertThat(byDuration.indices(), is(List.of(6, 7, 8, 9, 10, 11, 12, 13)));
        assertThat("a request met in full says so", byDuration.isComplete(), is(true));
        assertThat(byDuration.granted(), is(Duration.ofHours(2)));
        assertThat("asking in hours and asking in slots must agree on a uniform series", byCount,
                is(byDuration.slots()));

        SlotSelection interruptible = SelectionStrategy.cheapestSlots().select(series, 8);
        assertThat("the fixture is only interesting if the two strategies disagree", interruptible, is(not(byCount)));
        assertThat(interruptible.indices(), is(List.of(4, 6, 8, 9, 10, 11, 12, 13)));
    }

    /**
     * Scenario "Not enough eligible slots": a 3-hour request against 2 hours of eligible slots is answered with the
     * best 2 hours, carrying requested = 3 h and granted = 2 h - <em>from either strategy</em>, because shortfall has
     * to behave identically everywhere.
     */
    @Test
    public void aRequestThatCannotBeMetIsAnsweredWithTheBestPartialByBothStrategies() {
        SlotSeries twoHours = SlotSeries.hourly(MIDNIGHT, 5, 2);
        WindowRequest threeHours = WindowRequest.ofDuration(Duration.ofHours(3));

        WindowSelection consecutive = SelectionStrategy.consecutiveWindow().select(twoHours, threeHours);
        WindowSelection interruptible = SelectionStrategy.cheapestSlots().select(twoHours, threeHours);

        for (WindowSelection answer : List.of(consecutive, interruptible)) {
            assertThat("nothing is not an answer", answer.isEmpty(), is(false));
            assertThat(answer.indices(), is(List.of(0, 1)));
            assertThat("what was asked travels with the answer", answer.request(), is(threeHours));
            assertThat("and so does what could be granted", answer.granted(), is(Duration.ofHours(2)));
            assertThat(answer.isComplete(), is(false));
            assertThat(answer.isPartial(), is(true));
        }
    }

    /**
     * The shared calculation, under the flat weights that are its default: a window costs each slot's own price for
     * exactly the time the load spends in it, including a final slot it only partly uses.
     */
    @Test
    public void theSharedCalculationCostsAWindowUnderFlatWeightsByDefault() {
        SlotSeries series = SlotSeries.hourly(MIDNIGHT, 9, 2, 3, 9);
        WindowCost cost = WindowCost.shared();

        WindowSelection ninetyMinutes = SelectionStrategy.consecutiveWindow().select(series,
                WindowRequest.ofDuration(Duration.ofMinutes(90)));

        assertThat(ninetyMinutes.indices(), is(List.of(1, 2)));
        // one whole hour at 2 plus half an hour at 3, in units of price times seconds
        assertThat(cost.cost(series, ninetyMinutes), is(closeTo(2 * 3600 + 3 * 1800, 1e-9)));
        assertThat("flat weights are what the two-argument form means",
                cost.cost(series, ninetyMinutes, CostWeights.flat()), is(cost.cost(series, ninetyMinutes)));
    }

    /**
     * Scenario "Winter widens cheap windows": the same prices classified on a winter and on a summer date give
     * different bands, with no user action between the two.
     */
    @Test
    public void seasonalParametersSwitchTheHourCountsByDate() {
        LevelDerivation winter = LevelDerivation.fixedCounts(LevelCounts.of(6, 6, 2));
        LevelDerivation summer = LevelDerivation.fixedCounts(LevelCounts.of(2, 2, 6));
        SeasonalParameters seasons = new SeasonalParameters(BRUSSELS,
                List.of(new Season("winter", MonthDay.of(11, 1), MonthDay.of(3, 31), winter),
                        new Season("summer", MonthDay.of(4, 1), MonthDay.of(10, 31), summer)),
                summer);
        LevelDerivation seasonal = LevelDerivation.seasonal(seasons);

        PlannedLevelSchedule inJanuary = seasonal.derive(dayOfFlatPrices(Instant.parse("2026-01-15T00:00:00Z")));
        PlannedLevelSchedule inJuly = seasonal.derive(dayOfFlatPrices(Instant.parse("2026-07-15T00:00:00Z")));

        assertThat(inJanuary.slotsAt(EnergyLevel.OVERCAPACITY).size(), is(6));
        assertThat(inJanuary.slotsAt(EnergyLevel.ENCOURAGED).size(), is(6));
        assertThat(inJanuary.slotsAt(EnergyLevel.BLOCKED).size(), is(2));
        assertThat(inJuly.slotsAt(EnergyLevel.OVERCAPACITY).size(), is(2));
        assertThat(inJuly.slotsAt(EnergyLevel.ENCOURAGED).size(), is(2));
        assertThat(inJuly.slotsAt(EnergyLevel.BLOCKED).size(), is(6));
    }

    /**
     * A season may wrap around the turn of the year, and a date no season covers falls back.
     */
    @Test
    public void seasonsWrapAroundNewYearAndFallBack() {
        LevelDerivation heating = LevelDerivation.fixedCounts(LevelCounts.of(8, 4, 0));
        LevelDerivation rest = LevelDerivation.fixedCounts(LevelCounts.of(1, 1, 1));
        SeasonalParameters seasons = new SeasonalParameters(BRUSSELS,
                List.of(new Season("heating season", MonthDay.of(11, 1), MonthDay.of(2, 28), heating)), rest);

        assertThat(seasons.derivationFor(LocalDate.of(2026, 12, 24)), is(heating));
        assertThat(seasons.derivationFor(LocalDate.of(2026, 1, 5)), is(heating));
        assertThat(seasons.derivationFor(LocalDate.of(2026, 11, 1)), is(heating));
        assertThat(seasons.derivationFor(LocalDate.of(2026, 2, 28)), is(heating));
        assertThat(seasons.derivationFor(LocalDate.of(2026, 6, 1)), is(rest));
        assertThat(seasons.seasonFor(LocalDate.of(2026, 6, 1)), is(Optional.empty()));
    }

    private static SlotSeries dayOfFlatPrices(Instant start) {
        double[] prices = new double[24];
        for (int hour = 0; hour < prices.length; hour++) {
            prices[hour] = hour + 1.0;
        }
        return SlotSeries.uniform(start, Duration.ofHours(1), prices);
    }

    private static QuantityType<Power> watts(double amount) {
        return new QuantityType<>(amount, Units.WATT);
    }
}
