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

import java.time.Duration;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;

/**
 * The scenarios of _Derived-demand forecasts_, plus the three places the site has to supply a number and the plane
 * reports it when the site has not.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class HeatingDemandDerivationTest {

    private static final HeatingDemandParameters BUILDING = HeatingDemandParameters.of(0.5, 17);

    /**
     * The linear relation itself, in the unit the requirement demands: kilowatt-hours per period, not a count of
     * hours.
     */
    @Test
    public void aColderHourNeedsMoreEnergyThanAWarmerOne() {
        ForecastSeries demand = HeatingDemandDerivation.derive(ForecastFixtures.coldSnapDay(), null, BUILDING, "test")
                .series().orElseThrow();

        assertThat(demand.unit(), is(Units.KILOWATT_HOUR));
        assertThat("0 °C against a base of 17 at 0.5 kW/K over an hour", demand.slotAt(0).value(),
                is(closeTo(8.5, 1e-9)));
        assertThat("-20 °C, same building, same hour", demand.slotAt(12).value(), is(closeTo(18.5, 1e-9)));
        assertThat("a building above its base temperature needs nothing",
                HeatingDemandDerivation
                        .derive(ForecastFixtures.hourly(ForecastRole.TEMPERATURE, "warm", SIUnits.CELSIUS,
                                ForecastFixtures.GENERATED_AT, 21, 24), null, BUILDING, "test")
                        .series().orElseThrow().slotAt(0).value(),
                is(0.0));
    }

    /**
     * _Cold snap pre-heat_: the temperature forecast shows 0 to -20 °C within twelve hours, and the heating-need
     * series rises ahead of the drop so the engine can pre-heat in the warmer hours.
     */
    @Test
    public void demandRisesAheadOfASteepDrop() {
        HeatingDemandParameters preheating = BUILDING.withPreheat(Duration.ofHours(12), 10, 0.5, PreheatModel.ADDITIVE);

        ForecastSeries without = HeatingDemandDerivation.derive(ForecastFixtures.coldSnapDay(), null, BUILDING, "test")
                .series().orElseThrow();
        ForecastSeries with = HeatingDemandDerivation.derive(ForecastFixtures.coldSnapDay(), null, preheating, "test")
                .series().orElseThrow();

        assertThat("the mild hours before the drop ask for more than they need for themselves", with.slotAt(0).value(),
                greaterThan(without.slotAt(0).value()));
        assertThat(with.slotAt(1).value(), greaterThan(without.slotAt(1).value()));
        assertThat("and the cold hours themselves are untouched under the additive model", with.slotAt(12).value(),
                is(without.slotAt(12).value()));
    }

    /**
     * A flat day has no drop to pre-heat for, which is what keeps the rule from firing on every winter morning.
     */
    @Test
    public void aFlatDayIsNotPreHeated() {
        HeatingDemandParameters preheating = BUILDING.withPreheat(Duration.ofHours(12), 10, 0.5, PreheatModel.ADDITIVE);

        ForecastSeries demand = HeatingDemandDerivation.derive(ForecastFixtures.flatColdDay(), null, preheating, "test")
                .series().orElseThrow();

        for (int slot = 0; slot < demand.size(); slot++) {
            assertThat(demand.slotAt(slot).value(), is(closeTo(8.5, 1e-9)));
        }
    }

    /**
     * The seam the corpus leaves open: pre-heated energy is either added to the day or moved within it, and nothing in
     * the corpus says which. Both are implemented, the site chooses, and the difference is exactly the day's total.
     */
    @Test
    public void preHeatingEitherAddsToTheDayOrMovesEnergyWithinIt() {
        ForecastSeries temperature = ForecastFixtures.hourly(ForecastRole.TEMPERATURE, "cliff", SIUnits.CELSIUS,
                ForecastFixtures.GENERATED_AT, 0, 0, -20, -20);
        HeatingDemandParameters additive = BUILDING.withPreheat(Duration.ofHours(3), 10, 0.5, PreheatModel.ADDITIVE);
        HeatingDemandParameters redistributed = BUILDING.withPreheat(Duration.ofHours(3), 10, 0.5,
                PreheatModel.REDISTRIBUTED);

        ForecastSeries added = HeatingDemandDerivation.derive(temperature, null, additive, "test").series()
                .orElseThrow();
        ForecastSeries moved = HeatingDemandDerivation.derive(temperature, null, redistributed, "test").series()
                .orElseThrow();

        assertThat("both pull the same energy forward", added.slotAt(0).value(), is(moved.slotAt(0).value()));
        assertThat(added.slotAt(0).value(), is(closeTo(13.5, 1e-9)));
        assertThat("the additive model leaves the cold hours asking for what they asked for", added.slotAt(2).value(),
                is(closeTo(18.5, 1e-9)));
        assertThat("the redistributing model takes it out of the hour it was pre-heating for", moved.slotAt(2).value(),
                is(closeTo(8.5, 1e-9)));
        assertThat(total(added), is(closeTo(64.0, 1e-9)));
        assertThat("and the day's total is unchanged under redistribution", total(moved), is(closeTo(54.0, 1e-9)));
    }

    /**
     * _Heating demand re-derived with a morning solar check_: tomorrow's demand computed the day before, then the
     * morning's solar forecast arrives and the daytime part comes down. The site has no photovoltaics; the solar
     * forecast is a proxy for passive gain.
     */
    @Test
    public void aMorningSolarCheckOverwritesTheDaytimePartDownwards() {
        ForecastSeries dayBefore = HeatingDemandDerivation
                .derive(ForecastFixtures.coldSnapDay(), null, BUILDING, "test").series().orElseThrow();

        ForecastSeries reDerived = HeatingDemandDerivation.derive(ForecastFixtures.coldSnapDay(),
                ForecastFixtures.solarDay(), BUILDING.withSolarGain(0.6), "test").series().orElseThrow();

        assertThat("the sunny midday hours need less heating", reDerived.slotAt(12).value(),
                lessThan(dayBefore.slotAt(12).value()));
        assertThat(reDerived.slotAt(12).value(), is(closeTo(18.5 - 0.6 * 4, 1e-9)));
        assertThat("the night is untouched, because there is no sun in it", reDerived.slotAt(3).value(),
                is(dayBefore.slotAt(3).value()));
        assertThat(reDerived.slotAt(22).value(), is(dayBefore.slotAt(22).value()));
        assertThat("the re-derivation is as fresh as its newest input", reDerived.generatedAt(),
                is(ForecastFixtures.GENERATED_AT));
    }

    /**
     * The proxy in its literal form: a site with no array at all, using an irradiance series.
     */
    @Test
    public void anIrradianceSeriesStandsInForProductionOnASiteWithNoArray() {
        ForecastSeries irradiance = ForecastFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, "weather", Units.IRRADIANCE,
                ForecastFixtures.GENERATED_AT, 0, 500, 800, 0);
        ForecastSeries temperature = ForecastFixtures.hourly(ForecastRole.TEMPERATURE, "weather", SIUnits.CELSIUS,
                ForecastFixtures.GENERATED_AT, 0, 0, 0, 0);

        ForecastSeries demand = HeatingDemandDerivation
                .derive(temperature, irradiance, BUILDING.withSolarGain(2), "test").series().orElseThrow();

        assertThat(demand.slotAt(0).value(), is(closeTo(8.5, 1e-9)));
        assertThat("500 W/m² for an hour, at the site's own declared factor", demand.slotAt(1).value(),
                is(closeTo(8.5 - 2 * 0.5, 1e-9)));
        assertThat(demand.slotAt(2).value(), is(closeTo(8.5 - 2 * 0.8, 1e-9)));
    }

    /**
     * Gain never turns into a negative need: a building does not export heat because the sun shone.
     */
    @Test
    public void solarGainCannotDriveDemandBelowZero() {
        ForecastSeries temperature = ForecastFixtures.hourly(ForecastRole.TEMPERATURE, "mild", SIUnits.CELSIUS,
                ForecastFixtures.GENERATED_AT, 16, 16);
        ForecastSeries solar = ForecastFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, "bright", Units.WATT,
                ForecastFixtures.GENERATED_AT, 10000, 10000);

        ForecastSeries demand = HeatingDemandDerivation.derive(temperature, solar, BUILDING.withSolarGain(1), "test")
                .series().orElseThrow();

        assertThat(demand.slotAt(0).value(), is(0.0));
    }

    /**
     * The building constants have no shipped value, so an unconfigured site derives nothing and is told exactly that -
     * never an empty series that reads as a building needing no heat. D22's pattern, applied to this plane.
     */
    @Test
    public void anUnconfiguredBuildingDerivesNothingAndSaysWhy() {
        DerivedDemand answer = HeatingDemandDerivation.derive(ForecastFixtures.coldSnapDay(), null,
                HeatingDemandParameters.unconfigured(), "test");

        assertThat(answer.isPresent(), is(false));
        assertThat(answer.conditions(), contains(ForecastPlaneCondition.DEMAND_DERIVATION_UNCONFIGURED));
    }

    /**
     * A solar forecast that arrives without a declared gain factor changes nothing and is reported, rather than being
     * applied at a factor nobody chose.
     */
    @Test
    public void aSolarForecastWithNoDeclaredFactorChangesNothing() {
        DerivedDemand answer = HeatingDemandDerivation.derive(ForecastFixtures.coldSnapDay(),
                ForecastFixtures.solarDay(), BUILDING, "test");

        assertThat(answer.conditions(), hasItem(ForecastPlaneCondition.SOLAR_GAIN_UNCONFIGURED));
        assertThat(answer.series().orElseThrow().slotAt(12).value(), is(closeTo(18.5, 1e-9)));
    }

    /**
     * The same for pre-heating: undeclared means it does not happen, and that is readable rather than silent.
     */
    @Test
    public void undeclaredPreHeatingIsReportedRatherThanSilent() {
        DerivedDemand answer = HeatingDemandDerivation.derive(ForecastFixtures.coldSnapDay(), null, BUILDING, "test");

        assertThat(answer.conditions(), contains(ForecastPlaneCondition.PREHEAT_UNCONFIGURED));
    }

    /**
     * A temperature series that is not one is refused rather than read as degrees.
     */
    @Test
    public void aSeriesThatIsNotATemperatureIsRefused() {
        DerivedDemand answer = HeatingDemandDerivation.derive(ForecastFixtures.solarDay(), null, BUILDING, "test");

        assertThat(answer.isPresent(), is(false));
        assertThat(answer.conditions(), contains(ForecastPlaneCondition.UNIT_MISMATCH));
    }

    private static double total(ForecastSeries series) {
        double sum = 0;
        for (int slot = 0; slot < series.size(); slot++) {
            sum += series.slotAt(slot).value();
        }
        return sum;
    }
}
