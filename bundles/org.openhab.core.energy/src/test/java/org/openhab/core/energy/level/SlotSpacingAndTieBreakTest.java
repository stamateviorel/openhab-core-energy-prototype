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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.window.SelectionStrategy;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSelection;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.energy.window.WindowRequest;
import org.openhab.core.energy.window.WindowSelection;

/**
 * The behaviours the requirements demand but no scenario spells out: non-uniform and gapped series, the tie-break,
 * and what happens when a request does not fit the data.
 * <p>
 * "Independent of the market time resolution" is the requirement's wording, and these tests are what makes it true
 * rather than merely claimed - every one of them uses a series no fixed slot width could describe.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class SlotSpacingAndTieBreakTest {

    private static final Instant MIDNIGHT = Instant.parse("2026-01-15T00:00:00Z");

    /**
     * Equal prices are ranked by the earlier slot - the decided tie-break, and the reason a repeated price never
     * makes a result depend on iteration order. The shipped dataset has no repeated price, so pinning the rule needs
     * a series of its own; that tests the rule rather than fabricating data.
     */
    @Test
    public void tiesGoToTheEarlierSlot() {
        SlotSeries series = SlotSeries.hourly(MIDNIGHT, 5, 3, 3, 4, 3);

        assertThat(series.rankedIndices(), is(List.of(1, 2, 4, 3, 0)));
        assertThat(SelectionStrategy.cheapestSlots().select(series, 1).indices(), is(List.of(1)));
        assertThat(SelectionStrategy.cheapestSlots().select(series, 2).indices(), is(List.of(1, 2)));
    }

    /**
     * A percentile threshold is a price, so it cannot split a tie; a fixed count can. This is the whole behavioural
     * difference between the two derivations, and the reason the corpus' open question is not purely cosmetic.
     */
    @Test
    public void tiedPricesSplitTheTwoDerivations() {
        SlotSeries series = SlotSeries.hourly(MIDNIGHT, 1, 1, 1, 1, 9, 9, 9, 9);

        PlannedLevelSchedule byCount = LevelDerivation.fixedCounts(LevelCounts.of(2, 0, 0)).derive(series);
        PlannedLevelSchedule byPercentile = LevelDerivation.percentiles(LevelPercentiles.of(0.25, 0, 0)).derive(series);

        assertThat("a count cuts the ranking wherever it lands", byCount.slotsAt(EnergyLevel.OVERCAPACITY).indices(),
                is(List.of(0, 1)));
        assertThat("a price threshold takes every slot at that price",
                byPercentile.slotsAt(EnergyLevel.OVERCAPACITY).indices(), is(List.of(0, 1, 2, 3)));
    }

    /**
     * Counts that do not fit the series are clamped, cheap bands first, instead of overwriting each other or
     * throwing.
     */
    @Test
    public void countsLargerThanTheSeriesAreClampedCheapBandsFirst() {
        SlotSeries series = SlotSeries.hourly(MIDNIGHT, 1, 2, 3, 4);

        PlannedLevelSchedule schedule = LevelDerivation.fixedCounts(LevelCounts.of(3, 3, 3)).derive(series);

        assertThat(schedule.codes(), is(List.of(3, 3, 3, 2)));
    }

    /**
     * A series may mix slot widths. The cheapest hour of running time is then whatever combination of slots covers an
     * hour most cheaply - two half-hour slots here, not the single cheapest-looking slot.
     */
    @Test
    public void consecutiveWindowHandlesMixedSlotWidths() {
        SlotSeries series = new SlotSeries(
                List.of(slot(0, 60, 5), slot(60, 30, 1), slot(90, 30, 1), slot(120, 60, 4), slot(240, 60, 6)));

        SlotSelection anHour = SelectionStrategy.consecutiveWindow().selectForDuration(series, Duration.ofHours(1));

        assertThat(anHour.indices(), is(List.of(1, 2)));
        assertThat(anHour.coveredDuration(series), is(Duration.ofHours(1)));
    }

    /**
     * A gap in the series breaks contiguity: a window may not jump across missing time. When no run is long enough,
     * the answer is the longest run that fits - still uninterrupted - carrying what it could not grant, rather than
     * nothing at all.
     */
    @Test
    public void aGapBreaksAConsecutiveWindow() {
        SlotSeries series = new SlotSeries(
                List.of(slot(0, 60, 5), slot(60, 30, 1), slot(90, 30, 1), slot(120, 60, 4), slot(240, 60, 1)));

        assertThat(series.isContiguous(0, 3), is(true));
        assertThat(series.isContiguous(3, 4), is(false));

        SlotSelection twoHours = SelectionStrategy.consecutiveWindow().selectForDuration(series, Duration.ofHours(2));
        assertThat("the cheapest gap-free two hours", twoHours.indices(), is(List.of(1, 2, 3)));

        SlotSelection threeHours = SelectionStrategy.consecutiveWindow().selectForDuration(series, Duration.ofHours(3));
        assertThat("the whole gap-free stretch is exactly three hours", threeHours.indices(), is(List.of(0, 1, 2, 3)));

        WindowSelection tooLong = SelectionStrategy.consecutiveWindow().select(series,
                WindowRequest.ofDuration(Duration.ofMinutes(210)));
        assertThat("no gap-free run covers three and a half hours, so the longest one that fits is the answer",
                tooLong.indices(), is(List.of(0, 1, 2, 3)));
        assertThat("and it never jumps the gap to reach the requested length", tooLong.contains(4), is(false));
        assertThat(tooLong.granted(), is(Duration.ofHours(3)));
        assertThat(tooLong.isPartial(), is(true));
    }

    /**
     * A load that needs less than a whole slot still occupies the slot it starts in, and one that needs more than a
     * slot spills into the next.
     */
    @Test
    public void aPartiallyUsedFinalSlotStillCounts() {
        SlotSeries series = SlotSeries.hourly(MIDNIGHT, 9, 2, 3, 9);

        SlotSelection ninetyMinutes = SelectionStrategy.consecutiveWindow().selectForDuration(series,
                Duration.ofMinutes(90));
        SlotSelection halfAnHour = SelectionStrategy.cheapestSlots().selectForDuration(series, Duration.ofMinutes(30));

        assertThat(ninetyMinutes.indices(), is(List.of(1, 2)));
        assertThat(halfAnHour.indices(), is(List.of(1)));
    }

    /**
     * An impossible request degrades the same way whichever strategy answers it: every slot the series can offer,
     * with the shortfall carried on the answer. The two used to diverge here - the interruptible one returned a short
     * set while the uninterruptible one returned nothing - and a caller could therefore not compare their answers.
     */
    @Test
    public void impossibleRequestsDegradeIdenticallyPerStrategy() {
        SlotSeries series = SlotSeries.hourly(MIDNIGHT, 3, 1, 2);
        WindowRequest tenSlots = WindowRequest.ofSlots(10);

        WindowSelection interruptible = SelectionStrategy.cheapestSlots().select(series, tenSlots);
        WindowSelection uninterruptible = SelectionStrategy.consecutiveWindow().select(series, tenSlots);

        assertThat(interruptible.indices(), is(List.of(0, 1, 2)));
        assertThat(uninterruptible.indices(), is(List.of(0, 1, 2)));
        assertThat(interruptible.isPartial(), is(true));
        assertThat(uninterruptible.isPartial(), is(true));
        assertThat(uninterruptible.granted(), is(Duration.ofHours(3)));

        assertThat(SelectionStrategy.cheapestSlots().select(series, 0).isEmpty(), is(true));
        assertThat(SelectionStrategy.consecutiveWindow().select(series, 0).isEmpty(), is(true));
        assertThat("asking for nothing is met in full",
                SelectionStrategy.consecutiveWindow().select(series, WindowRequest.ofSlots(0)).isComplete(), is(true));
    }

    /**
     * A negative request is a caller mistake and is refused at the request, which is the one place it can be caught
     * for every strategy at once.
     */
    @Test
    public void negativeRequestsAreRefused() {
        SlotSeries series = SlotSeries.hourly(MIDNIGHT, 3, 1, 2);

        assertThrows(IllegalArgumentException.class, () -> WindowRequest.ofSlots(-1));
        assertThrows(IllegalArgumentException.class, () -> WindowRequest.ofDuration(Duration.ofHours(-1)));
        assertThrows(IllegalArgumentException.class, () -> SelectionStrategy.cheapestSlots().select(series, -1));
        assertThrows(IllegalArgumentException.class,
                () -> SelectionStrategy.consecutiveWindow().selectForDuration(series, Duration.ofMinutes(-30)));
    }

    /**
     * Excluding slots is how a second load is scheduled around a first one, for both strategies.
     */
    @Test
    public void exclusionsAreHonouredByBothStrategies() {
        SlotSeries series = SlotSeries.hourly(MIDNIGHT, 1, 1, 5, 2, 2);
        SlotSelection taken = SlotSelection.of(0, 1);

        assertThat(SelectionStrategy.cheapestSlots().select(series, 2, taken).indices(), is(List.of(3, 4)));
        assertThat(SelectionStrategy.consecutiveWindow().select(series, 2, taken).indices(), is(List.of(3, 4)));
    }

    /**
     * The hours-to-slots conversion is offered but never guessed: it needs a single slot width and a whole multiple
     * of it.
     */
    @Test
    public void durationToSlotCountConversionRefusesToGuess() {
        SlotSeries hourly = SlotSeries.hourly(MIDNIGHT, 1, 2, 3, 4, 5, 6);
        SlotSeries quarterly = SlotSeries.uniform(MIDNIGHT, Duration.ofMinutes(15), 1, 2, 3, 4, 5, 6, 7, 8);
        SlotSeries mixed = new SlotSeries(List.of(slot(0, 60, 5), slot(60, 30, 1)));

        assertThat(LevelCounts.ofDurations(hourly, Duration.ofHours(2), Duration.ofHours(1), Duration.ofHours(1)),
                is(LevelCounts.of(2, 1, 1)));
        assertThat(LevelCounts.ofDurations(quarterly, Duration.ofHours(1), Duration.ZERO, Duration.ZERO),
                is(LevelCounts.of(4, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> LevelCounts.ofDurations(mixed, Duration.ofHours(1), Duration.ZERO, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> LevelCounts.ofDurations(hourly, Duration.ofMinutes(90), Duration.ZERO, Duration.ZERO));
    }

    /**
     * The input types refuse malformed series rather than classifying them wrongly.
     */
    @Test
    public void malformedSeriesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SlotSeries(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SlotSeries(List.of(slot(0, 60, 1), slot(30, 60, 2))));
        assertThrows(IllegalArgumentException.class, () -> new Slot(MIDNIGHT, MIDNIGHT, 1));
        assertThrows(IllegalArgumentException.class, () -> LevelCounts.of(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> LevelPercentiles.of(0.5, 0.5, 0.5));
    }

    private static Slot slot(int startMinute, int widthMinutes, double price) {
        Instant start = MIDNIGHT.plus(Duration.ofMinutes(startMinute));
        return new Slot(start, start.plus(Duration.ofMinutes(widthMinutes)), price);
    }
}
