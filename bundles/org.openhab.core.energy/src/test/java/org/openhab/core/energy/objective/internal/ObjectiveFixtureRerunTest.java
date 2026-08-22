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
package org.openhab.core.energy.objective.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.level.FixtureCsv;
import org.openhab.core.energy.objective.CarbonSeries;
import org.openhab.core.energy.objective.ExportShare;
import org.openhab.core.energy.objective.ObjectiveInputs;
import org.openhab.core.energy.window.SlotSelection;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.energy.window.internal.RankedSlotsSelection;

/**
 * The acceptance day, rerun under a carbon objective - task 3.2 of the objectives change: "rerun
 * {@code fixtures/dayahead-prices.csv} under a synthetic green-share series and document the expected placement
 * differences".
 * <p>
 * <strong>Only half of this is a conformance vector, and the halves are kept apart on purpose.</strong> The price
 * series is @masipila's real day, and the cost objective's placement on it is pinned against the real acceptance
 * fixture - if wave 2 had changed what "cheapest eight hours" means, this file would fail. The green-share series is
 * <em>synthetic</em>: a plausible March day, a wind-fed night and a solar bell peaking at local midday, written here
 * and sourced from nobody. It is marked derived-not-sourced wherever it appears and it must never be promoted into
 * {@code fixtures/} as though it came from the thread.
 * <p>
 * What the rerun demonstrates is exactly what the objective change exists for: on one real day, the metric decides
 * the schedule. The cheapest eight hours and the greenest eight hours of 2023-03-24 are almost disjoint, so a site
 * that switches objective moves nearly its whole heating schedule.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ObjectiveFixtureRerunTest {

    /**
     * A synthetic renewable share for the acceptance day, in percent, one value per hourly slot of
     * {@code dayahead-prices.csv}. <strong>Derived, not sourced.</strong> Slot {@code i} starts at
     * {@code 2023-03-23T23:00Z + i hours}, which is local hour {@code i} of the CET delivery day, so the shape is a
     * wind-fed night around thirty percent and a solar bell peaking just after local midday.
     */
    private static final double[] SYNTHETIC_GREEN_SHARE = { 32, 31, 30, 29, 28, 28, 30, 36, 45, 56, 66, 74, 79, 81, 78,
            71, 60, 48, 38, 33, 31, 30, 31, 33 };

    private final RankedSlotsSelection best = new RankedSlotsSelection(false);

    /**
     * Returns the acceptance day's prices as the cost objective sees them.
     *
     * @return the price series
     */
    private static SlotSeries prices() {
        return FixtureCsv.prices();
    }

    /**
     * Returns the synthetic green share on the acceptance day's own slot geometry.
     *
     * @return the carbon series, higher being better
     */
    private static CarbonSeries greenShare() {
        SlotSeries prices = prices();
        List<org.openhab.core.energy.window.Slot> slots = new java.util.ArrayList<>(prices.size());
        for (int index = 0; index < prices.size(); index++) {
            org.openhab.core.energy.window.Slot slot = prices.slotAt(index);
            slots.add(new org.openhab.core.energy.window.Slot(slot.start(), slot.end(), SYNTHETIC_GREEN_SHARE[index]));
        }
        return CarbonSeries.renewableShare("synthetic-derived-not-sourced", prices.start(), new SlotSeries(slots));
    }

    /**
     * The conformance half: the cost objective changes nothing about the acceptance day. Its ranking is the price
     * series itself, and the eight hours it picks are the eight the shipped fixture pins.
     */
    @Test
    public void theCostObjectiveReproducesTheAcceptanceFixtureExactly() {
        SlotSeries ranking = new CostObjective().rank(ObjectiveInputs.ofPrices(prices())).orElseThrow();

        assertThat("the cost objective adds nothing to a price series", ranking, is(prices()));

        List<Integer> chosen = best.select(ranking, 8, SlotSelection.empty()).indices();
        List<Boolean> onOff = new SlotSelection(chosen).onOffFlags(ranking.size());
        List<Boolean> expected = FixtureCsv.read("/fixtures/expected-heating-control.csv").stream()
                .map(row -> row.value() != 0).toList();

        assertThat("the eight cheapest hours are still the fixture's eight", onOff, is(expected));
    }

    /**
     * The rerun: the same day, the same load, the same selection strategy, and a different objective moves the
     * schedule almost entirely.
     * <p>
     * The numbers this asserts are the documented outcome the task asks for. Cheapest eight: the small hours plus
     * the two cheap afternoon dips. Greenest eight: the solar bell, local 09:00 to 16:00, which the price series
     * prices in the middle of the day.
     */
    @Test
    public void theCarbonObjectiveMovesTheScheduleOnTheSameDay() {
        SlotSeries byCost = new CostObjective().rank(ObjectiveInputs.ofPrices(prices())).orElseThrow();
        SlotSeries byCarbon = new CarbonObjective().rank(ObjectiveInputs.empty().withCarbon(greenShare()))
                .orElseThrow();

        List<Integer> cheapest = best.select(byCost, 8, SlotSelection.empty()).indices();
        List<Integer> greenest = best.select(byCarbon, 8, SlotSelection.empty()).indices();

        assertThat("the greenest eight hours are the solar bell, local 09:00 to 16:00", greenest,
                containsInAnyOrder(9, 10, 11, 12, 13, 14, 15, 16));
        assertThat("the cheapest eight are the small hours plus the midday dip and the last hour of the day", cheapest,
                containsInAnyOrder(0, 1, 2, 3, 13, 14, 15, 23));
        List<Integer> shared = cheapest.stream().filter(greenest::contains).sorted().toList();
        assertThat("the two schedules share three hours of the twenty-four - local 13:00, 14:00 and 15:00, where "
                + "the cheap midday dip and the solar bell happen to coincide", shared, contains(13, 14, 15));
        assertThat("so switching objective moves five of the eight hours a heating schedule runs in",
                cheapest.stream().filter(hour -> !greenest.contains(hour)).count(), is(5L));
    }

    /**
     * The export-credit rule on a real day: with the acceptance day's own prices standing in for a feed-in series
     * and a synthetic afternoon surplus, the rule bites in exactly the hours whose feed-in price is negative - and
     * on this day there are none, so it bites nowhere.
     * <p>
     * That is worth asserting rather than assuming. <strong>The corpus has no fixture with a negative price at
     * all</strong>, so the one requirement in it that turns on negative prices cannot be demonstrated on any real
     * data the corpus ships. The synthetic day in {@link ObjectiveFixtures} is what demonstrates it; this test is
     * what records that the real one cannot.
     */
    @Test
    public void theAcceptanceDayHasNoNegativeHourSoTheExportCreditNeverBites() {
        SlotSeries prices = prices();
        for (int index = 0; index < prices.size(); index++) {
            assertThat("no hour of the acceptance day is negative", prices.valueAt(index), greaterThan(0.0));
        }

        CarbonSeries intensity = CarbonSeries.intensity("synthetic-derived-not-sourced", prices.start(), prices);
        ObjectiveInputs inputs = new ObjectiveInputs(prices, prices, intensity, prices, ExportShare.unknown())
                .withExportShareFor(4000);

        SlotSeries scored = new CarbonObjective().rank(inputs).orElseThrow();

        assertThat("so the ranking is the carbon series untouched", scored, is(intensity.values()));
    }

    /**
     * The level plane's input is a seam, not an answer: on the acceptance day the shipped setting cuts the bands out
     * of the price series - which is what produced {@code expected-planned-levels.csv} - and the other setting cuts
     * them out of the active objective's ranking instead.
     * <p>
     * Whether levels should follow the objective is open in the corpus, so both are implemented and the shipped one
     * is the behaviour that already exists.
     */
    @Test
    public void theLevelInputIsASeamAndItsShippedValueIsThePriceSeries() {
        ObjectiveInputs inputs = ObjectiveInputs.ofPrices(prices()).withCarbon(greenShare());

        ObjectivePlane onPrice = ObjectiveFixtures.planeWithBuiltIns(Map.of("objective", CarbonObjective.ID));
        ObjectivePlane onObjective = ObjectiveFixtures
                .planeWithBuiltIns(Map.of("objective", CarbonObjective.ID, "levelInput", "objective"));

        assertThat("the shipped setting keeps the level bands on the price series, as they have always been",
                onPrice.levelDerivationInput(inputs, onPrice.resolve(inputs)).orElseThrow(), is(prices()));
        assertThat("and the other setting cuts them out of the carbon ranking instead",
                onObjective.levelDerivationInput(inputs, onObjective.resolve(inputs)).orElseThrow(),
                is(greenShare().values()));
    }
}
