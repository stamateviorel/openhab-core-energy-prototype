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

import java.time.Duration;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.objective.CarbonSeries;
import org.openhab.core.energy.objective.ObjectiveInputs;
import org.openhab.core.energy.window.SeriesSense;
import org.openhab.core.energy.window.SlotSelection;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.energy.window.internal.RankedSlotsSelection;
import org.openhab.core.library.unit.Units;

/**
 * The <em>Carbon data as a first-class series</em> requirement: contributed sources, the same storage shape prices
 * and forecasts use, and a ranking that reads the series exactly as the cost objective reads a price.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class CarbonSeriesPlaneTest {

    private final RankedSlotsSelection best = new RankedSlotsSelection(false);

    /**
     * Scenario "Green-share series drives ranking": a contributed source publishes tomorrow's renewable share per
     * hour, and the carbon objective ranks the hours on it.
     * <p>
     * The share is higher-is-better where a price is lower-is-better, and the assertion is that the same ranking
     * call answers correctly for both - which is the whole content of "exactly as the cost objective uses the price
     * series".
     */
    @Test
    public void aContributedRenewableShareSeriesDrivesTheRanking() {
        ObjectivePlane plane = ObjectiveFixtures.planeWithBuiltIns(Map.of("objective", CarbonObjective.ID));
        plane.addCarbonSource(new ObjectiveFixtures.TestCarbonSource("contributed", 0)
                .publishing(ObjectiveFixtures.renewableShare()));

        ObjectiveInputs inputs = plane
                .withResolvedCarbon(ObjectiveInputs.ofPrices(ObjectiveFixtures.consumptionPrices()));
        SlotSeries ranking = plane.resolve(inputs).ranking().orElseThrow();

        assertThat("the source's own numbers are what is ranked", ranking.valueAt(2), is(80.0));
        assertThat("and the greenest hour is the one with the most renewables, not the least",
                best.select(ranking, 1, SlotSelection.empty()).indices(), contains(2));
    }

    /**
     * The other form the requirement accepts - a CO2 intensity - ranks the other way round through the same call.
     */
    @Test
    public void anIntensitySeriesRanksTheOtherWayRoundThroughTheSameCall() {
        SlotSeries ranking = new CarbonObjective()
                .rank(ObjectiveInputs.empty().withCarbon(ObjectiveFixtures.carbonIntensity())).orElseThrow();

        assertThat(ranking.sense(), is(SeriesSense.LOWER_IS_BETTER));
        assertThat("the cleanest hour is the one with the fewest grams",
                best.select(ranking, 1, SlotSelection.empty()).indices(), contains(2));
        assertThat("and the dirtiest is answerable through the same series", ranking.worstRankedIndices().get(0),
                is(0));
    }

    /**
     * The storage shape really is shared: a carbon series is a slot series with a source, a unit and a generation
     * time, so gaps, mixed slot widths and the tie-break all behave as they do for a price.
     */
    @Test
    public void aCarbonSeriesIsTheSameShapeAPriceIs() {
        CarbonSeries carbon = CarbonSeries.intensity("mixed", ObjectiveFixtures.DAY,
                new SlotSeries(java.util.List.of(
                        org.openhab.core.energy.window.Slot.of(ObjectiveFixtures.DAY, Duration.ofMinutes(15), 200),
                        org.openhab.core.energy.window.Slot.of(ObjectiveFixtures.DAY.plus(Duration.ofHours(1)),
                                Duration.ofHours(1), 200))));

        assertThat("mixed widths are permitted", carbon.values().uniformSlotDuration().isPresent(), is(false));
        assertThat("a gap breaks contiguity", carbon.values().isContiguous(0, 1), is(false));
        assertThat("and equal values are still broken by the earlier slot", carbon.values().rankedIndices(),
                contains(0, 1));
    }

    /**
     * Two sources for one site are ordered by service ranking, and a site may name the one it wants regardless.
     */
    @Test
    public void theSourceInForceIsTheHighestRankedUnlessTheSiteNamesOne() {
        ObjectivePlane plane = ObjectiveFixtures.planeWithBuiltIns(Map.of());
        plane.addCarbonSource(
                new ObjectiveFixtures.TestCarbonSource("low", -2).publishing(ObjectiveFixtures.carbonIntensity()));
        plane.addCarbonSource(
                new ObjectiveFixtures.TestCarbonSource("high", 5).publishing(ObjectiveFixtures.renewableShare()));

        assertThat(plane.carbonSource().orElseThrow().getSourceId(), is("high"));

        ObjectivePlane preferring = ObjectiveFixtures.planeWithBuiltIns(Map.of("carbonSource", "low"));
        preferring.addCarbonSource(
                new ObjectiveFixtures.TestCarbonSource("low", -2).publishing(ObjectiveFixtures.carbonIntensity()));
        preferring.addCarbonSource(
                new ObjectiveFixtures.TestCarbonSource("high", 5).publishing(ObjectiveFixtures.renewableShare()));

        assertThat(preferring.carbonSource().orElseThrow().getSourceId(), is("low"));
    }

    /**
     * A source that has nothing yet is not a fault, and it is not a carbon series either: the plane answers empty
     * rather than handing an objective an empty ranking to think about.
     */
    @Test
    public void aSourceWithNothingToSayYetLeavesThePlaneWithNoSeries() {
        ObjectivePlane plane = ObjectiveFixtures.planeWithBuiltIns(Map.of());
        plane.addCarbonSource(new ObjectiveFixtures.TestCarbonSource("quiet", 0));

        assertThat(plane.carbonSource().isPresent(), is(true));
        assertThat(plane.carbonSeries().isPresent(), is(false));
    }

    /**
     * A source that goes away takes its series with it, which is what a dynamic whiteboard has to mean.
     */
    @Test
    public void aSourceThatGoesAwayTakesItsSeriesWithIt() {
        ObjectivePlane plane = ObjectiveFixtures.planeWithBuiltIns(Map.of());
        ObjectiveFixtures.TestCarbonSource source = new ObjectiveFixtures.TestCarbonSource("contributed", 0)
                .publishing(ObjectiveFixtures.carbonIntensity());
        plane.addCarbonSource(source);
        assertThat(plane.carbonSeries().isPresent(), is(true));

        plane.removeCarbonSource(source);

        assertThat(plane.carbonSeries().isPresent(), is(false));
    }

    /**
     * The intensity question is asked of the unit rather than of a flag, so a source that publishes core's own
     * {@code g/kWh} gets the full behaviour without knowing the question exists.
     */
    @Test
    public void whetherASeriesIsAnIntensityIsAskedOfItsUnit() {
        assertThat(ObjectiveFixtures.carbonIntensity().unit(), is(Units.GRAM_PER_KILOWATT_HOUR));
        assertThat(ObjectiveFixtures.carbonIntensity().isEmissionIntensity(), is(true));
        assertThat(ObjectiveFixtures.renewableShare().isEmissionIntensity(), is(false));
        assertThat(ObjectiveFixtures.carbonIntensity().quantityAt(2).getUnit(), is(Units.GRAM_PER_KILOWATT_HOUR));
    }
}
