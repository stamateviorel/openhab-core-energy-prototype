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
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.forecast.ForecastRole;
import org.openhab.core.energy.forecast.ForecastSeries;
import org.openhab.core.energy.forecast.SeriesLayer;
import org.openhab.core.energy.forecast.internal.ForecastRegistryImpl;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.unit.Units;

/**
 * The Item-backed source: the one forecast source core itself ships, and the mechanism that turns "the provider went
 * dark" into ordinary source resolution rather than a special case.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ItemForecastSourceTest {

    private static final String ITEM = "PV_Forecast";
    private static final Instant NOW = StoreFixtures.DAY_START.plus(Duration.ofHours(6));

    private final StoreFixtures.InMemoryPersistence persistence = new StoreFixtures.InMemoryPersistence();
    private final ItemRegistry items = StoreFixtures.itemRegistry(ITEM);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final PersistenceLayeredStore store = new PersistenceLayeredStore(items,
            new StoreFixtures.FakeServiceRegistry(persistence), clock, Map.of());

    private ItemForecastSource source() {
        return new ItemForecastSource(ITEM, ForecastRole.SOLAR_PRODUCTION, store, clock, null);
    }

    /**
     * A stored series is a forecast source: nothing that reads it knows it came out of an Item rather than off the
     * network.
     */
    @Test
    public void aStoredSeriesIsReadAsAForecast() {
        store.apply(ITEM, SeriesLayer.BASELINE,
                StoreFixtures.hourlyFrom(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 5, 100, 900, 2000, 2500));

        ForecastSeries series = source().getSeries().orElseThrow();

        assertThat(series.role(), is(ForecastRole.SOLAR_PRODUCTION));
        assertThat(series.unit(), is(Units.WATT));
        assertThat(series.valueAt(StoreFixtures.DAY_START.plus(Duration.ofHours(7))).getAsDouble(), is(2000.0));
    }

    /**
     * An Item with nothing stored answers empty rather than throwing, because a site that has not filled its baseline
     * in yet is an ordinary state.
     */
    @Test
    public void anEmptyItemAnswersEmpty() {
        assertThat(source().getSeries(), is(Optional.empty()));
    }

    /**
     * It registers at the ranking core's own defaults use, so a contributed forecast service outranks it with no
     * configuration at all - and the stored baseline is what remains when that service goes away.
     */
    @Test
    public void theStoredSeriesIsOutrankedByAnyContributedSourceUntilItGoesAway() {
        store.apply(ITEM, SeriesLayer.BASELINE,
                StoreFixtures.hourlyFrom(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 5, 100, 900, 2000, 2500));
        ForecastRegistryImpl registry = new ForecastRegistryImpl(clock, Map.of());
        registry.addForecastSeriesSource(source());
        org.openhab.core.energy.forecast.ForecastSeriesSource live = liveSource();
        registry.addForecastSeriesSource(live);

        assertThat(registry.getAnsweringSourceId(ForecastRole.SOLAR_PRODUCTION), is(Optional.empty()));
        assertThat(registry.getSeries(ForecastRole.SOLAR_PRODUCTION).orElseThrow().slotAt(0).value(), is(4000.0));
        assertThat(registry.getAnsweringSourceId(ForecastRole.SOLAR_PRODUCTION), is(Optional.of("live")));

        registry.removeForecastSeriesSource(live);

        assertThat("the site plans on its stored baseline instead of losing the series",
                registry.getSeries(ForecastRole.SOLAR_PRODUCTION).orElseThrow().slotAt(0).value(), is(100.0));
        assertThat(registry.getAnsweringSourceId(ForecastRole.SOLAR_PRODUCTION), is(Optional.of("item:" + ITEM)));
        assertThat(source().getServiceRanking(), is(ItemForecastSource.CORE_DEFAULT_RANKING));
    }

    private static org.openhab.core.energy.forecast.ForecastSeriesSource liveSource() {
        ForecastSeries series = StoreFixtures.hourlyFrom(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 5, 4000, 4200);
        return new org.openhab.core.energy.forecast.ForecastSeriesSource() {

            @Override
            public String getSourceId() {
                return "live";
            }

            @Override
            public ForecastRole getRole() {
                return ForecastRole.SOLAR_PRODUCTION;
            }

            @Override
            public Optional<ForecastSeries> getSeries() {
                return Optional.of(series);
            }
        };
    }
}
