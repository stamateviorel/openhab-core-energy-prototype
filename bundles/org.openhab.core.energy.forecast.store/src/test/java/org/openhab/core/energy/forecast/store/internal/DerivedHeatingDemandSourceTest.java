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
package org.openhab.core.energy.forecast.store.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.forecast.ForecastPlaneCondition;
import org.openhab.core.energy.forecast.ForecastRole;
import org.openhab.core.energy.forecast.ForecastSeries;
import org.openhab.core.energy.forecast.ForecastSeriesSource;
import org.openhab.core.energy.forecast.internal.ForecastRegistryImpl;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.events.ItemTimeSeriesEvent;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.types.TimeSeries;

/**
 * _Derived-demand forecasts_ where it meets a running framework: the derived series is readable exactly as a fetched
 * one is, and it reaches an Item only when a site names one.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class DerivedHeatingDemandSourceTest {

    private static final String DEMAND_ITEM = "Heating_Demand";
    private static final Instant DAY = StoreFixtures.DAY_START;

    private final RecordingEventPublisher published = new RecordingEventPublisher();
    private final ForecastRegistryImpl registry = new ForecastRegistryImpl(
            Clock.fixed(DAY.plus(Duration.ofHours(6)), ZoneOffset.UTC), Map.of());

    private DerivedHeatingDemandSource source(Map<String, Object> configuration) {
        return new DerivedHeatingDemandSource(registry, published, configuration);
    }

    private void declareWeather() {
        registry.addForecastSeriesSource(source("weather", ForecastRole.TEMPERATURE,
                StoreFixtures.hourly(ForecastRole.TEMPERATURE, SIUnits.CELSIUS, 0, 0, -10, -10)));
        registry.addForecastSeriesSource(source("solarforecast", ForecastRole.SOLAR_PRODUCTION,
                StoreFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 0, 0, 2000, 2000)));
    }

    /**
     * The derived demand is a source like any other: a consumer asks the plane for a heating demand and never learns
     * that it was computed rather than fetched.
     */
    @Test
    public void aDerivedDemandIsReadThroughThePlaneLikeAnyOtherForecast() {
        declareWeather();
        registry.addForecastSeriesSource(source(Map.of("heatLoss", 0.5, "baseTemperature", 17)));

        ForecastSeries demand = registry.getSeries(ForecastRole.HEATING_DEMAND).orElseThrow();

        assertThat(demand.unit(), is(Units.KILOWATT_HOUR));
        assertThat(demand.slotAt(0).value(), is(closeTo(8.5, 1e-9)));
        assertThat(demand.slotAt(2).value(), is(closeTo(13.5, 1e-9)));
    }

    /**
     * A site that has not described its building gets no series and a named reason, rather than a series of zeroes
     * that reads as a building needing no heat.
     */
    @Test
    public void anUndescribedBuildingProducesNoSeriesAndSaysWhy() {
        declareWeather();
        DerivedHeatingDemandSource derived = source(Map.of());

        assertThat(derived.getSeries(), is(Optional.empty()));
        assertThat(derived.getConditions(), hasItem(ForecastPlaneCondition.DEMAND_DERIVATION_UNCONFIGURED));
        assertThat("and nothing is published either", published.events(), is(empty()));
    }

    /**
     * With an Item named, the derived series is published as a time series - one event, to that Item, with the
     * additive policy.
     */
    @Test
    public void namingAnItemPublishesTheSeriesAsATimeSeries() {
        declareWeather();
        DerivedHeatingDemandSource derived = source(
                Map.of("heatLoss", 0.5, "baseTemperature", 17, "demandItem", DEMAND_ITEM));

        assertThat(published.events(), hasSize(1));
        Event event = published.events().getFirst();
        assertThat(event, is(instanceOf(ItemTimeSeriesEvent.class)));
        ItemTimeSeriesEvent timeSeriesEvent = (ItemTimeSeriesEvent) event;
        assertThat(timeSeriesEvent.getItemName(), is(DEMAND_ITEM));
        assertThat("REPLACE would delete the baseline entries in this series' own gaps",
                timeSeriesEvent.getTimeSeries().getPolicy(), is(TimeSeries.Policy.ADD));
        assertThat(timeSeriesEvent.getTimeSeries().size(), is(derived.getSeries().orElseThrow().size()));
        assertThat(timeSeriesEvent.getTimeSeries().getBegin(), is(DAY));
    }

    /**
     * Publishing again after a configuration change is what keeps a changed building constant from leaving stale
     * numbers on the Item.
     */
    @Test
    public void changingTheBuildingRepublishes() {
        declareWeather();
        DerivedHeatingDemandSource derived = source(
                Map.of("heatLoss", 0.5, "baseTemperature", 17, "demandItem", DEMAND_ITEM));

        derived.modified(Map.of("heatLoss", 1.0, "baseTemperature", 17, "demandItem", DEMAND_ITEM));

        assertThat(published.events(), hasSize(2));
        assertThat(derived.getSeries().orElseThrow().slotAt(0).value(), is(closeTo(17.0, 1e-9)));
    }

    /**
     * Nothing is published while no Item is named, which is what makes the write opt-in rather than a side effect of
     * installing the bundle.
     */
    @Test
    public void nothingIsPublishedWhileNoItemIsNamed() {
        declareWeather();
        DerivedHeatingDemandSource derived = source(Map.of("heatLoss", 0.5, "baseTemperature", 17));

        derived.refresh();

        assertThat(published.events(), is(empty()));
        assertThat("the series is still there for anything that asks the plane for it", derived.getSeries().isPresent(),
                is(true));
    }

    /**
     * Without a temperature forecast there is nothing to derive from, and the plane says that rather than deriving
     * from nothing.
     */
    @Test
    public void withoutATemperatureForecastThereIsNothingToDerive() {
        DerivedHeatingDemandSource derived = source(Map.of("heatLoss", 0.5, "baseTemperature", 17));

        assertThat(derived.getSeries(), is(Optional.empty()));
        assertThat(derived.getConditions(), contains(ForecastPlaneCondition.SOURCE_UNAVAILABLE));
    }

    private static ForecastSeriesSource source(String sourceId, ForecastRole role, ForecastSeries series) {
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
                return Optional.of(series);
            }
        };
    }

    /**
     * An event publisher that keeps what it was handed, so a test can assert what a component wrote.
     *
     * @author Stamate Viorel - Initial contribution
     */
    private static final class RecordingEventPublisher implements EventPublisher {

        private final List<Event> events = new ArrayList<>();

        @Override
        public void post(@Nullable Event event) {
            if (event != null) {
                events.add(event);
            }
        }

        List<Event> events() {
            return List.copyOf(events);
        }
    }
}
