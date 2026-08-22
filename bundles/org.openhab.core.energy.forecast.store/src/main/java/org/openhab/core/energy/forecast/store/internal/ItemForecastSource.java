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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.forecast.ForecastRole;
import org.openhab.core.energy.forecast.ForecastSeries;
import org.openhab.core.energy.forecast.ForecastSeriesSource;
import org.openhab.core.energy.forecast.store.LayeredPredictionStore;

/**
 * A forecast source backed by an Item's stored future series.
 * <p>
 * This is the one source core itself ships, and it is what makes the framework usable with no forecast add-on
 * installed at all: a rule, a script or a binding that already publishes a forecast onto an Item - which is what
 * openHAB 4.1's time-series support is for - becomes a source by being named in this component's configuration.
 * <p>
 * It is also what turns _Forecast source fails_ from a special case into ordinary resolution. A site's stored
 * baseline is simply a source, registered at the ranking core's own defaults use, so a contributed live service
 * outranks it with no configuration; when that service goes dark, the plane falls to this one and the site keeps
 * planning on the baseline. Nothing anywhere has a branch for "the forecast is missing".
 * <p>
 * <strong>Reading is all it does.</strong> Writing to the same series is {@link PersistenceLayeredStore}'s, through a
 * different call, so a source can never write to what it reads.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ItemForecastSource implements ForecastSeriesSource {

    /**
     * The ranking a source core itself ships registers at, so that any contributed alternative outranks it with no
     * configuration at all. The number is core's own precedent for a default provider.
     */
    public static final int CORE_DEFAULT_RANKING = -2;

    private final String itemName;
    private final ForecastRole role;
    private final LayeredPredictionStore store;
    private final Clock clock;
    private final @Nullable Duration horizon;

    /**
     * Creates a source over one Item.
     *
     * @param itemName the Item carrying the series
     * @param role what the series is about
     * @param store the store the series is read through
     * @param clock the clock the read window is anchored on
     * @param horizon how far ahead a read looks, or {@code null} to read everything stored ahead of now - which is
     *            what a site that has declared no horizon means, and why none is invented
     */
    public ItemForecastSource(String itemName, ForecastRole role, LayeredPredictionStore store, Clock clock,
            @Nullable Duration horizon) {
        this.itemName = itemName;
        this.role = role;
        this.store = store;
        this.clock = clock;
        this.horizon = horizon;
    }

    @Override
    public String getSourceId() {
        return "item:" + itemName;
    }

    @Override
    public ForecastRole getRole() {
        return role;
    }

    @Override
    public Optional<ForecastSeries> getSeries() {
        Instant now = clock.instant();
        // from the start of the current hour, so that the slot the site is in is part of the answer rather than
        // half-missing, and forward as far as the site asked for
        Instant from = now.minus(Duration.ofHours(1));
        @Nullable
        Duration declared = horizon;
        return store.read(itemName, role, from, declared == null ? null : now.plus(declared));
    }

    @Override
    public int getServiceRanking() {
        return CORE_DEFAULT_RANKING;
    }
}
