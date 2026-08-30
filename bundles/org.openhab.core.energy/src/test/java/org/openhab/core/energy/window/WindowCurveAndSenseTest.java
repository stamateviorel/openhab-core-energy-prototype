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
package org.openhab.core.energy.window;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.LoadCurve;

/**
 * The two things wave 2 added to the shared window calculation: a declared load curve as the costing weights, and a
 * direction.
 * <p>
 * Both close scenarios of {@code price-data} <em>Shared window calculations</em> that wave 1 could state but not
 * exercise - it had no curve to cost and only ever minimised.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class WindowCurveAndSenseTest {

    private static final Instant MIDNIGHT = Instant.parse("2023-01-11T00:00:00Z");

    /**
     * "Energy under a curve": each interval's energy is integrated LEFT-Riemann and priced at that interval's own
     * price, so two conforming implementations cost the same dishwasher identically.
     * <p>
     * The curve draws a tenth of its rating for the first hour and its full rating for the second, over a two-hour
     * window whose hours cost 1 and 10. Flat weights would cost 11; the curve costs {@code 0.1 x 1 + 1.0 x 10}.
     */
    @Test
    public void aCurveIsCostedIntervalByIntervalAgainstEachSlotsOwnPrice() {
        SlotSeries prices = SlotSeries.hourly(MIDNIGHT, 1, 10);
        WindowSelection window = SelectionStrategy.consecutiveWindow().select(prices,
                WindowRequest.ofDuration(Duration.ofHours(2)));

        double flat = WindowCost.shared().cost(prices, window);
        double shaped = WindowCost.shared().cost(prices, window, CostWeights.ofCurve(LoadCurve.of(0.1, 1.0)));

        assertThat(flat, is(closeTo(11 * 3600, 1e-6)));
        assertThat(shaped, is(closeTo((0.1 * 1 + 1.0 * 10) * 3600, 1e-6)));
    }

    /**
     * A single-sample curve is the flat case, so a rectangular program costs exactly what it did before curves
     * existed - the guarantee that adding the parameter changed no existing answer.
     */
    @Test
    public void aSingleSampleCurveIsExactlyTheFlatCase() {
        SlotSeries prices = SlotSeries.hourly(MIDNIGHT, 3, 1, 4, 1, 5);
        WindowSelection window = SelectionStrategy.consecutiveWindow().select(prices,
                WindowRequest.ofDuration(Duration.ofHours(3)));

        assertThat(WindowCost.shared().cost(prices, window, CostWeights.ofCurve(LoadCurve.of(1.0))),
                is(closeTo(WindowCost.shared().cost(prices, window), 1e-9)));
    }

    /**
     * The curve's samples are read at the resolution the curve has, not at the resolution the slots impose: a
     * three-sample curve over a two-hour window has a sample boundary in the middle of the second hour, and the mean
     * draw over that hour is the average of the two samples covering it.
     */
    @Test
    public void aCurveWhoseSamplesDoNotLineUpWithTheSlotsIsAveragedOverTheOverlap() {
        SlotSeries prices = SlotSeries.hourly(MIDNIGHT, 1, 1);
        WindowSelection window = SelectionStrategy.consecutiveWindow().select(prices,
                WindowRequest.ofDuration(Duration.ofHours(2)));

        // samples 0, 3 and 6 over two hours: hour one is sample 0 plus half of sample 1, hour two is the rest
        double cost = WindowCost.shared().cost(prices, window, CostWeights.ofCurve(LoadCurve.of(0, 3, 6)));

        // the total energy is the curve's own mean over the whole run, which is (0 + 3 + 6) / 3
        assertThat(cost, is(closeTo(3.0 * 2 * 3600, 1e-6)));
    }

    /**
     * "Cheapest consecutive window for a curve": the curve moves the answer. A load that draws almost everything in
     * its last hour prefers a window whose <em>last</em> hour is cheap, which is not the window a flat load prefers.
     */
    @Test
    public void aCurveMovesTheCheapestConsecutiveWindow() {
        // two candidate two-hour windows cost the same flat (2 + 8 = 8 + 2), and differently under a late-drawing load
        SlotSeries prices = SlotSeries.hourly(MIDNIGHT, 2, 8, 2);
        CostWeights lateDrawer = CostWeights.ofCurve(LoadCurve.of(0.1, 1.0));

        WindowSelection first = WindowSelection.fillingInTimeOrder(WindowRequest.ofDuration(Duration.ofHours(2)),
                SlotSelection.of(0, 1), Duration.ofHours(2), prices);
        WindowSelection second = WindowSelection.fillingInTimeOrder(WindowRequest.ofDuration(Duration.ofHours(2)),
                SlotSelection.of(1, 2), Duration.ofHours(2), prices);

        assertThat(WindowCost.shared().cost(prices, first),
                is(closeTo(WindowCost.shared().cost(prices, second), 1e-9)));
        assertThat("a load that draws late prefers the window ending cheap",
                WindowCost.shared().cost(prices, second, lateDrawer),
                lessThan(WindowCost.shared().cost(prices, first, lateDrawer)));

        // and the search itself is run under those weights, which is what the scenario asks for: a rule asks for the
        // cheapest window "costing the curve as weights", not for the flat answer with a shaped price put on it
        WindowRequest request = WindowRequest.ofDuration(Duration.ofHours(2));
        assertThat("flat, the two windows tie and the earlier one keeps it",
                SelectionStrategy.consecutiveWindow().select(prices, request).indices(), is(List.of(0, 1)));
        assertThat("shaped, the later window wins outright",
                SelectionStrategy.consecutiveWindow().select(prices, request, lateDrawer).indices(), is(List.of(1, 2)));
    }

    /**
     * "A window starts on a slot boundary": the restriction binds rather than coinciding with the optimum.
     * <p>
     * The scenario's whole content is that a cheaper mid-slot start is deliberately <em>not</em> returned, so
     * asserting only that the answer starts on a boundary proves nothing - every answer does. What is asserted here
     * is that the boundary answer is strictly worse than a placement twenty minutes later, which is what makes this a
     * restriction of the search space rather than a property of the optimum. An implementation that quietly gained
     * continuous placement would pass every other assertion in this class and fail this one.
     */
    @Test
    public void aWindowStartsOnASlotBoundaryEvenWhenACheaperOffsetStartExists() {
        // a cheap hour between two dear ones, and a load that draws almost everything in its middle third
        SlotSeries prices = SlotSeries.hourly(MIDNIGHT, 5, 1, 6);
        CostWeights middleDrawer = CostWeights.ofCurve(LoadCurve.of(0.1, 1.0, 0.1));
        WindowRequest request = WindowRequest.ofDuration(Duration.ofHours(2));

        WindowSelection chosen = SelectionStrategy.consecutiveWindow().select(prices, request, middleDrawer);

        assertThat("the returned start is a slot boundary", chosen.indices(), is(List.of(0, 1)));
        assertThat(prices.slotAt(chosen.indices().getFirst()).start(), is(MIDNIGHT));

        // the same load started half an hour in puts its whole heavy third inside the cheap hour, and costs half
        double onTheBoundary = WindowCost.shared().cost(prices, chosen, middleDrawer);
        double halfAnHourIn = offsetCost(prices, Duration.ofMinutes(30), middleDrawer);
        assertThat("the boundary restriction has to bind, or this scenario proves nothing", halfAnHourIn,
                lessThan(onTheBoundary));
    }

    /**
     * Costs a two-hour run starting {@code offset} into the first slot, which is the placement the boundary
     * restriction forbids. Built by hand precisely because no shipped strategy can express it.
     *
     * @param prices three hourly slots
     * @param offset how far into the first slot the run starts
     * @param weights the load's shape
     * @return what that placement would cost
     */
    private static double offsetCost(SlotSeries prices, Duration offset, CostWeights weights) {
        Instant start = MIDNIGHT.plus(offset);
        SlotSeries shifted = new SlotSeries(List.of(new Slot(start, prices.slotAt(1).start(), prices.slotAt(0).value()),
                new Slot(prices.slotAt(1).start(), prices.slotAt(2).start(), prices.slotAt(1).value()),
                new Slot(prices.slotAt(2).start(), prices.slotAt(2).start().plus(offset), prices.slotAt(2).value())),
                prices.sense());
        WindowRequest request = WindowRequest.ofDuration(Duration.ofHours(2));
        return WindowCost.shared().cost(shifted,
                WindowSelection.fillingInTimeOrder(request, SlotSelection.of(0, 1, 2), Duration.ofHours(2), shifted),
                weights);
    }

    /**
     * The one shared costing function costs a non-consecutive selection the way the strategy that made it allocated
     * it, which is not the way filling slots in time order would.
     * <p>
     * <strong>This is the failure the allocation exists to prevent.</strong> On hourly prices {@code [1, 100, 2]} a
     * two-and-a-half-hour interruptible request takes slots 0 and 2 whole and only half of slot 1, because slot 1 is
     * the worst-ranked of the three. Costing it by filling in time order charges a whole hour to slot 1 and half an
     * hour to slot 2 - 367 200 against the 190 800 the plan actually costs, out by a factor of nearly two. The
     * requirement asks for one function under which two conforming implementations cost the same load identically;
     * the two shipped strategies did not agree with each other through it.
     */
    @Test
    public void aNonConsecutiveSelectionIsCostedAsTheStrategyAllocatedIt() {
        SlotSeries prices = SlotSeries.hourly(MIDNIGHT, 1, 100, 2);

        WindowSelection chosen = SelectionStrategy.cheapestSlots().select(prices,
                WindowRequest.ofDuration(Duration.ofMinutes(150)));

        assertThat("all three slots are taken, because 2.5 hours does not fit in two", chosen.indices(),
                is(List.of(0, 1, 2)));
        assertThat(chosen.granted(), is(Duration.ofMinutes(150)));
        assertThat("the part-used slot is the dearest one, not the last one in time", chosen.allocation(),
                is(List.of(Duration.ofHours(1), Duration.ofMinutes(30), Duration.ofHours(1))));
        // 1 x 3600 + 100 x 1800 + 2 x 3600 - and emphatically not 1 x 3600 + 100 x 3600 + 2 x 1800
        assertThat(WindowCost.shared().cost(prices, chosen), is(closeTo(1 * 3600 + 100 * 1800 + 2 * 3600.0, 1e-9)));
    }

    /**
     * The most expensive selection, which {@code Shared window calculations} requires and no scenario in the corpus
     * exercises.
     */
    @Test
    public void theWorstSelectionIsTheMostExpensiveSlots() {
        SlotSeries prices = SlotSeries.hourly(MIDNIGHT, 3, 1, 4, 1, 5);

        assertThat(SelectionStrategy.worstSlots().select(prices, 2).indices(), is(List.of(2, 4)));
        assertThat(SelectionStrategy.worstConsecutiveWindow().selectForDuration(prices, Duration.ofHours(2)).indices(),
                is(List.of(3, 4)));
    }

    /**
     * A series whose higher values are the better ones - a photovoltaic forecast, a green share - ranks the other way
     * round from the same call, which is what makes one calculation serve every plane.
     */
    @Test
    public void aHigherIsBetterSeriesRanksTheOtherWayRoundFromTheSameCall() {
        SlotSeries production = SlotSeries.hourly(MIDNIGHT, 0, 2, 9, 4, 1).withSense(SeriesSense.HIGHER_IS_BETTER);

        assertThat(SelectionStrategy.bestSlots().select(production, 2).indices(), is(List.of(2, 3)));
        assertThat(SelectionStrategy.worstSlots().select(production, 2).indices(), is(List.of(0, 4)));
        assertThat("the sunniest three-hour run",
                SelectionStrategy.bestConsecutiveWindow().selectForDuration(production, Duration.ofHours(3)).indices(),
                is(List.of(1, 2, 3)));
    }

    /**
     * The tie-break does not flip with the direction: it is a statement about when to act, not about the value, so
     * the earlier slot wins under every combination of sense and direction.
     */
    @Test
    public void theEarlierSlotWinsATieUnderEverySenseAndDirection() {
        SlotSeries lowerIsBetter = SlotSeries.hourly(MIDNIGHT, 5, 1, 5, 1);
        SlotSeries higherIsBetter = lowerIsBetter.withSense(SeriesSense.HIGHER_IS_BETTER);

        assertThat(SelectionStrategy.bestSlots().select(lowerIsBetter, 1).indices(), is(List.of(1)));
        assertThat(SelectionStrategy.worstSlots().select(lowerIsBetter, 1).indices(), is(List.of(0)));
        assertThat(SelectionStrategy.bestSlots().select(higherIsBetter, 1).indices(), is(List.of(0)));
        assertThat(SelectionStrategy.worstSlots().select(higherIsBetter, 1).indices(), is(List.of(1)));
    }

    /**
     * The price-flavoured aliases are the same objects as the neutral names, so no caller has two behaviours to keep
     * in step.
     */
    @Test
    public void thePriceFlavouredNamesAreTheNeutralOnes() {
        SlotSeries prices = SlotSeries.hourly(MIDNIGHT, 3, 1, 4);

        assertThat(SelectionStrategy.cheapestSlots().select(prices, 2),
                is(SelectionStrategy.bestSlots().select(prices, 2)));
        assertThat(SelectionStrategy.consecutiveWindow().selectForDuration(prices, Duration.ofHours(2)),
                is(SelectionStrategy.bestConsecutiveWindow().selectForDuration(prices, Duration.ofHours(2))));
    }

    /**
     * A series' sense is a property of the numbers rather than of the slots, so re-reading the same slots under the
     * other sense changes the ranking and nothing else.
     */
    @Test
    public void changingTheSenseChangesTheRankingAndNothingElse() {
        SlotSeries prices = SlotSeries.hourly(MIDNIGHT, 3, 1, 4);

        SlotSeries flipped = prices.withSense(SeriesSense.HIGHER_IS_BETTER);

        assertThat(flipped.slots(), is(prices.slots()));
        assertThat(flipped.rankedIndices(), is(prices.worstRankedIndices()));
        assertThat(flipped.worstRankedIndices(), is(prices.rankedIndices()));
    }

    /**
     * Owner decision D38: two windows whose costs differ by less than any tariff expresses are a tie, and the earlier
     * one takes it.
     * <p>
     * An exact tie was always safe - the incumbent keeps it - so the defect only shows when the *later* window comes
     * out marginally cheaper through arithmetic rather than through price. Here the last hour is cheaper by a
     * millionth of a millionth of a cent, which is noise, not money. Compared exactly that noise moves the load three
     * hours; compared within the tolerance it does not.
     */
    @Test
    public void aLaterWindowCheaperOnlyByNoiseDoesNotTakeTheTie() {
        SlotSeries prices = SlotSeries.hourly(MIDNIGHT, 1.0, 1.0, 1.0, 1.0, 1.0 - 1e-12);

        WindowSelection window = SelectionStrategy.consecutiveWindow().select(prices,
                WindowRequest.ofDuration(Duration.ofHours(3)));

        assertThat("noise in the last bits must not decide which hour a load runs in",
                prices.slotAt(window.indices().get(0)).start(), is(MIDNIGHT));
    }
}
