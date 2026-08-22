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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.window.SlotSeries;

/**
 * The synthetic days the forecast-plane tests are written against, and the fake source they arrive through.
 * <p>
 * Everything here is a whole day of hourly slots starting at midnight UTC, because every scenario in the change is
 * stated in whole hours of a day and a fixture that is harder to read than the scenario it stands for helps nobody.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ForecastFixtures {

    /**
     * Midnight of the synthetic day.
     */
    public static final Instant DAY_START = Instant.parse("2026-01-15T00:00:00Z");

    /**
     * The moment a run is said to have been generated at, an hour before the day it covers.
     */
    public static final Instant GENERATED_AT = Instant.parse("2026-01-14T23:00:00Z");

    private ForecastFixtures() {
    }

    /**
     * Returns a photovoltaic day with a broad midday plateau: nothing until 08:00, a rise, a 11:00-14:00 plateau at
     * 4 kW, a fall, nothing after 18:00.
     *
     * @return the production forecast, in watts
     */
    public static ForecastSeries solarDay() {
        return ForecastSeries.of(ForecastRole.SOLAR_PRODUCTION, "fixture-solar",
                org.openhab.core.library.unit.Units.WATT, GENERATED_AT, SlotSeries.hourly(DAY_START, 0, 0, 0, 0, 0, 0,
                        0, 0, 200, 900, 2200, 4000, 4000, 4000, 3900, 2600, 1200, 300, 0, 0, 0, 0, 0, 0));
    }

    /**
     * Returns a temperature day that falls off a cliff: mild until 08:00, then dropping to -20 °C by 12:00 and
     * staying there.
     *
     * @return the temperature forecast, in degrees Celsius
     */
    public static ForecastSeries coldSnapDay() {
        return ForecastSeries.of(ForecastRole.TEMPERATURE, "fixture-weather",
                org.openhab.core.library.unit.SIUnits.CELSIUS, GENERATED_AT, SlotSeries.hourly(DAY_START, 0, 0, 0, 0, 0,
                        0, 0, 0, -2, -6, -11, -16, -20, -20, -20, -20, -20, -20, -20, -20, -20, -20, -20, -20));
    }

    /**
     * Returns a flat winter day at 0 °C, which is the control the cold snap is compared against.
     *
     * @return the temperature forecast, in degrees Celsius
     */
    public static ForecastSeries flatColdDay() {
        return ForecastSeries.of(ForecastRole.TEMPERATURE, "fixture-weather",
                org.openhab.core.library.unit.SIUnits.CELSIUS, GENERATED_AT, SlotSeries.uniform(DAY_START,
                        Duration.ofHours(1), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    /**
     * Returns a fake source, which is all the framework bundle ever sees of a forecast provider.
     *
     * @param sourceId the id the source answers under
     * @param role what it forecasts
     * @param series what it currently has, or {@code null} for a source that has nothing
     * @param ranking the {@code service.ranking} it registers at
     * @return the source
     */
    public static ForecastSeriesSource source(String sourceId, ForecastRole role, @Nullable ForecastSeries series,
            int ranking) {
        return new ForecastSeriesSource() {

            @Override
            public String getSourceId() {
                return sourceId;
            }

            @Override
            public ForecastRole getRole() {
                return role;
            }

            @Override
            public Optional<ForecastSeries> getSeries() {
                return Optional.ofNullable(series);
            }

            @Override
            public int getServiceRanking() {
                return ranking;
            }
        };
    }

    /**
     * Returns a forecast built from hourly values.
     *
     * @param role what the series is about
     * @param sourceId who produced it
     * @param unit what the values are in
     * @param generatedAt when the run was made
     * @param values the hourly values, from {@link #DAY_START}
     * @return the series
     */
    public static ForecastSeries hourly(ForecastRole role, String sourceId, Unit<?> unit, Instant generatedAt,
            double... values) {
        return ForecastSeries.of(role, sourceId, unit, generatedAt, SlotSeries.hourly(DAY_START, values));
    }
}
