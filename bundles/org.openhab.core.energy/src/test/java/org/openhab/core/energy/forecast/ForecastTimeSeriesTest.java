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
package org.openhab.core.energy.forecast;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.window.SeriesSense;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.MetricPrefix;
import org.openhab.core.library.unit.Units;
import org.openhab.core.types.TimeSeries;

/**
 * The bridge between core's own future-timestamped transport and the slot geometry this framework calculates on -
 * including the two places where the transport carries less than the calculation needs.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ForecastTimeSeriesTest {

    private static final Instant DAY = ForecastFixtures.DAY_START;

    /**
     * The rule: a value is held from its own timestamp until the next one, so a series that arrives with uneven gaps
     * stays uneven instead of being interpolated into something nobody published.
     */
    @Test
    public void eachEntryIsHeldUntilTheNextOne() {
        TimeSeries series = new TimeSeries(TimeSeries.Policy.ADD);
        series.add(DAY, new QuantityType<Power>(1000, Units.WATT));
        series.add(DAY.plus(Duration.ofMinutes(15)), new QuantityType<Power>(2000, Units.WATT));
        series.add(DAY.plus(Duration.ofHours(1)), new QuantityType<Power>(3000, Units.WATT));

        SlotSeries slots = ForecastTimeSeries.toSlots(series, Units.WATT, SeriesSense.HIGHER_IS_BETTER,
                Duration.ofHours(1));

        assertThat(slots.size(), is(3));
        assertThat(slots.slotAt(0).duration(), is(Duration.ofMinutes(15)));
        assertThat(slots.slotAt(1).duration(), is(Duration.ofMinutes(45)));
        assertThat("the last entry's width is the one the caller stated", slots.slotAt(2).duration(),
                is(Duration.ofHours(1)));
        assertThat(slots.valueAt(2), is(3000.0));
    }

    /**
     * A typed entry is converted into the unit the caller reads in, so a source publishing kilowatts and one
     * publishing watts do not silently differ by a factor of a thousand.
     */
    @Test
    public void aTypedEntryIsConvertedIntoTheUnitItIsReadIn() {
        TimeSeries series = new TimeSeries(TimeSeries.Policy.ADD);
        series.add(DAY, new QuantityType<Power>(4, MetricPrefix.KILO(Units.WATT)));
        series.add(DAY.plus(Duration.ofHours(1)), new QuantityType<Power>(2, MetricPrefix.KILO(Units.WATT)));

        SlotSeries slots = ForecastTimeSeries.toSlots(series, Units.WATT, SeriesSense.HIGHER_IS_BETTER);

        assertThat(slots.valueAt(0), is(4000.0));
        assertThat(slots.valueAt(1), is(2000.0));
    }

    /**
     * An untyped entry is taken as already being in the unit asked for, which is what a source publishing a plain
     * number means and all a receiver can do with it.
     */
    @Test
    public void anUntypedEntryIsTakenAsBeingInTheUnitAskedFor() {
        TimeSeries series = new TimeSeries(TimeSeries.Policy.ADD);
        series.add(DAY, new DecimalType(1500));
        series.add(DAY.plus(Duration.ofHours(1)), new DecimalType(2500));

        SlotSeries slots = ForecastTimeSeries.toSlots(series, Units.WATT, SeriesSense.HIGHER_IS_BETTER);

        assertThat(slots.valueAt(0), is(1500.0));
    }

    /**
     * <strong>A corpus gap, made explicit rather than papered over.</strong> Nothing in a transported series says how
     * long its final value applies for. A single-entry series therefore cannot be converted without being told, and
     * the framework says so instead of assuming an hour.
     */
    @Test
    public void aSingleEntrySeriesCannotHaveItsWidthAssumed() {
        TimeSeries series = new TimeSeries(TimeSeries.Policy.ADD);
        series.add(DAY, new QuantityType<Power>(1000, Units.WATT));

        assertThrows(IllegalArgumentException.class,
                () -> ForecastTimeSeries.toSlots(series, Units.WATT, SeriesSense.HIGHER_IS_BETTER));
        assertThat("stating the width is all it takes", ForecastTimeSeries
                .toSlots(series, Units.WATT, SeriesSense.HIGHER_IS_BETTER, Duration.ofHours(1)).size(), is(1));
    }

    /**
     * The round trip a source and a consumer make: values out, values back, unchanged.
     */
    @Test
    public void aForecastSurvivesTheRoundTripThroughTheTransport() {
        ForecastSeries original = ForecastFixtures.solarDay();

        TimeSeries transported = ForecastTimeSeries.toTimeSeries(original, TimeSeries.Policy.ADD);
        SlotSeries returned = ForecastTimeSeries.toSlots(transported, Units.WATT, SeriesSense.HIGHER_IS_BETTER,
                Duration.ofHours(1));

        assertThat(returned.size(), is(original.size()));
        for (int slot = 0; slot < returned.size(); slot++) {
            assertThat(returned.valueAt(slot), is(original.slotAt(slot).value()));
            assertThat(returned.slotAt(slot).start(), is(original.slotAt(slot).start()));
        }
    }

    /**
     * <strong>The publication hazard worth a corpus fix.</strong> "Fresh overwrites old" is a per-entry statement, and
     * core's {@code REPLACE} policy is a per-<em>range</em> one: a sparse refresh published with it deletes everything
     * stored between its own first and last timestamps - which is exactly the baseline the layered-prediction
     * requirement exists to protect. This test does not fix that; it pins the shape of the hazard so that a
     * publication path choosing a policy has to choose it on purpose.
     */
    @Test
    public void aSparseRefreshCarriesTheRangeItWouldReplace() {
        ForecastSeries sparse = ForecastSeries.of(ForecastRole.SOLAR_PRODUCTION, "sparse", Units.WATT,
                ForecastFixtures.GENERATED_AT,
                new SlotSeries(java.util.List.of(
                        new org.openhab.core.energy.window.Slot(DAY, DAY.plus(Duration.ofHours(1)), 1000),
                        new org.openhab.core.energy.window.Slot(DAY.plus(Duration.ofHours(6)),
                                DAY.plus(Duration.ofHours(7)), 3000))));

        TimeSeries replacing = ForecastTimeSeries.toTimeSeries(sparse, TimeSeries.Policy.REPLACE);

        assertThat(replacing.getPolicy(), is(TimeSeries.Policy.REPLACE));
        assertThat(replacing.getBegin(), is(DAY));
        assertThat("everything stored in these six hours goes, including the four hours this series never mentions",
                replacing.getEnd(), is(DAY.plus(Duration.ofHours(6))));
        assertThat(replacing.size(), is(2));

        TimeSeries adding = ForecastTimeSeries.toTimeSeries(sparse, TimeSeries.Policy.ADD);
        assertThat("the additive policy touches only the two entries it carries", adding.getPolicy(),
                is(TimeSeries.Policy.ADD));
    }
}
