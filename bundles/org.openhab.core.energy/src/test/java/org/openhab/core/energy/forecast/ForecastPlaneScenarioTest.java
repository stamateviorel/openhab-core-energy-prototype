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
import java.time.Instant;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.window.SelectionStrategy;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.energy.window.WindowRequest;
import org.openhab.core.energy.window.WindowSelection;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;

/**
 * The scenarios of `define-forecast-providers` _Forecasts as future-timestamped series_ and _Solar production
 * forecast_, one test each.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ForecastPlaneScenarioTest {

    /**
     * _A demand forecast is not sign-ambiguous_: a derived heating demand and a photovoltaic forecast for the same
     * hours are both positive, meaning consumption and production respectively.
     */
    @Test
    public void aDemandForecastAndAProductionForecastAreBothPositive() {
        ForecastSeries solar = ForecastFixtures.solarDay();
        ForecastSeries demand = HeatingDemandDerivation
                .derive(ForecastFixtures.coldSnapDay(), null, HeatingDemandParameters.of(0.5, 17), "test").series()
                .orElseThrow();

        for (int slot = 0; slot < solar.size(); slot++) {
            assertThat("photovoltaic production is never negative", solar.slotAt(slot).value(),
                    greaterThanOrEqualTo(0.0));
            assertThat("a demand is never negative", demand.slotAt(slot).value(), greaterThanOrEqualTo(0.0));
        }
        assertThat("the demand is in energy per period, not a count of hours", demand.unit(), is(Units.KILOWATT_HOUR));
        assertThat(demand.role(), is(ForecastRole.HEATING_DEMAND));
        assertThat("both series cover the same hours", demand.start(), is(solar.start()));
    }

    /**
     * The other half of the same requirement: a source that counts the opposite way round is normalised at the edge,
     * so nothing downstream ever sees the disagreement.
     */
    @Test
    public void aSourceThatDisagreesAboutTheSignIsNormalisedAtTheEdge() {
        ForecastSeries asPublished = ForecastFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, "inverted", Units.WATT,
                ForecastFixtures.GENERATED_AT, -100, -2000, -3000);

        ForecastSeries normalised = asPublished.normalised(true);

        assertThat(normalised.slotAt(0).value(), is(100.0));
        assertThat(normalised.slotAt(1).value(), is(2000.0));
        assertThat("nothing but the numbers changes", normalised.role(), is(asPublished.role()));
        assertThat(normalised.generatedAt(), is(asPublished.generatedAt()));
    }

    /**
     * _PV-first boiler window_: a strong midday plateau is where a solar-first load goes, and it is emphatically not
     * where the cheapest grid hours are.
     */
    @Test
    public void aSolarFirstLoadIsPlacedInTheMiddayPlateauRatherThanTheCheapestHours() {
        ForecastSeries solar = ForecastFixtures.solarDay();
        // the night is always the cheap end of a day-ahead curve
        SlotSeries prices = SlotSeries.hourly(ForecastFixtures.DAY_START, 4, 3, 2, 2, 3, 5, 9, 14, 18, 20, 19, 17, 16,
                15, 15, 16, 19, 24, 28, 26, 20, 14, 9, 6);

        WindowSelection sunniest = ForecastWindows.bestWindow(solar, WindowRequest.ofDuration(Duration.ofHours(3)));
        WindowSelection cheapest = SelectionStrategy.consecutiveWindow().select(prices,
                WindowRequest.ofDuration(Duration.ofHours(3)));

        assertThat(sunniest.indices(), is(List.of(11, 12, 13)));
        assertThat("the cheapest grid hours are at night", cheapest.indices(), is(List.of(1, 2, 3)));
        assertThat("the two answers are genuinely different", sunniest.indices(), is(not(cheapest.indices())));
        assertThat(ForecastWindows.energyKilowattHours(solar, sunniest), is(closeTo(12.0, 1e-9)));
    }

    /**
     * _A PV window request takes the same shape as a price one_: the same request type, answered on slot boundaries,
     * with requested-versus-granted when the horizon is shorter than the request.
     */
    @Test
    public void aPhotovoltaicWindowRequestTakesTheSameShapeAsAPriceOne() {
        ForecastSeries shortHorizon = ForecastFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, "short", Units.WATT,
                ForecastFixtures.GENERATED_AT, 1000, 3000, 2000);

        WindowSelection answer = ForecastWindows.bestWindow(shortHorizon,
                WindowRequest.ofDuration(Duration.ofHours(5)));

        assertThat("the request travels with the answer", answer.request(),
                is(WindowRequest.ofDuration(Duration.ofHours(5))));
        assertThat("what could be granted is said, rather than nothing being answered", answer.granted(),
                is(Duration.ofHours(3)));
        assertThat(answer.isPartial(), is(true));
        assertThat(answer.indices(), is(List.of(0, 1, 2)));
        // and a window starts on a slot boundary, never part way through one
        assertThat(shortHorizon.slotAt(answer.indices().getFirst()).start(), is(ForecastFixtures.DAY_START));
    }

    /**
     * The maximisation is not a second search: it is the shared one, reading the series' own sense.
     * <p>
     * The assertion compares the forecast plane's answer on a higher-is-better series against the shared strategy's
     * answer on the <em>negated</em> series, and requires them to be identical, tie-break included. That equality is
     * the reason the plane can express "more is better" by stating a sense rather than by re-signing the values: the
     * two formulations pick the same window, so only one of them needs to exist. An earlier form of the plane did
     * negate, and this test is what makes removing that safe rather than hopeful - it still compares the two, and
     * would fail if they ever came apart.
     */
    @Test
    public void theBestWindowOfAForecastIsTheSharedSearchReadThroughTheSeriesSense() {
        ForecastSeries solar = ForecastFixtures.solarDay();
        SlotSeries negated = SlotSeries.hourly(ForecastFixtures.DAY_START, negate(solar));

        WindowSelection viaForecastPlane = ForecastWindows.bestWindow(solar,
                WindowRequest.ofDuration(Duration.ofHours(4)));
        WindowSelection viaSharedCalculation = SelectionStrategy.consecutiveWindow().select(negated,
                WindowRequest.ofDuration(Duration.ofHours(4)));

        assertThat(viaForecastPlane.indices(), is(viaSharedCalculation.indices()));
    }

    /**
     * A forecast in a unit its role does not carry is refused rather than reinterpreted - the check that keeps a
     * watt from being read as a degree.
     */
    @Test
    public void aRoleRefusesAUnitItDoesNotCarry() {
        assertThat(ForecastRole.TEMPERATURE.accepts(SIUnits.CELSIUS), is(true));
        assertThat(ForecastRole.TEMPERATURE.accepts(Units.WATT), is(false));
        assertThat(ForecastRole.SOLAR_PRODUCTION.accepts(Units.WATT), is(true));
        assertThat("an irradiance series stands in for production where there is no array",
                ForecastRole.SOLAR_PRODUCTION.accepts(Units.IRRADIANCE), is(true));
        assertThat(ForecastRole.HEATING_DEMAND.accepts(Units.KILOWATT_HOUR), is(true));
    }

    /**
     * Slot geometry is inherited whole from the shared calculation, so a fifteen-minute forecast and a mixed-width one
     * need no special case anywhere in this plane.
     */
    @Test
    public void aForecastIsIndependentOfTheResolutionItArrivesIn() {
        Instant start = ForecastFixtures.DAY_START;
        ForecastSeries quarters = ForecastSeries.of(ForecastRole.SOLAR_PRODUCTION, "quarters", Units.WATT,
                ForecastFixtures.GENERATED_AT,
                SlotSeries.uniform(start, Duration.ofMinutes(15), 0, 400, 1200, 2000, 2400, 2000, 800, 0));

        WindowSelection best = ForecastWindows.bestWindow(quarters, WindowRequest.ofDuration(Duration.ofMinutes(45)));

        assertThat(best.indices(), is(List.of(3, 4, 5)));
        assertThat(best.granted(), is(Duration.ofMinutes(45)));
        assertThat(quarters.values().uniformSlotDuration().orElseThrow(), is(Duration.ofMinutes(15)));
    }

    private static double[] negate(ForecastSeries series) {
        double[] values = new double[series.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = -series.slotAt(i).value();
        }
        return values;
    }
}
