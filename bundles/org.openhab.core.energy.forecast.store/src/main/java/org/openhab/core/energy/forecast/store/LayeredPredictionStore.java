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
package org.openhab.core.energy.forecast.store;

import java.time.Instant;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.forecast.ForecastRole;
import org.openhab.core.energy.forecast.ForecastSeries;
import org.openhab.core.energy.forecast.SeriesLayer;

/**
 * A read-write prediction series that several writers share - the baseline generator, the live forecast refresh, the
 * cap writer, and later the learning layer.
 * <p>
 * <strong>THIS IS THE WRITING HALF OF THE FORECAST PLANE, AND IT IS WHY THIS BUNDLE EXISTS.</strong> _Layered
 * prediction series_ requires a series "read-write and updatable at any time over any of its entries - past, present
 * or future", which means modifying persisted history. Its neighbour {@code org.openhab.core.energy} may never write
 * an Item and proves it with five tests, one of which forbids the very tokens this bundle is built on
 * ({@code .store(}, {@code .query(}, {@code FilterCriteria}). So the arithmetic of a layered write is decided there,
 * as a {@link org.openhab.core.energy.forecast.LayeredWritePlan}, and carried out here.
 * <p>
 * <strong>What it needs from persistence, and what it refuses without.</strong> Overwriting an entry that is already
 * in the past is {@code ModifiablePersistenceService.store(item, timestamp, state)}, and core itself persists no time
 * series at all to a service that does not implement that interface. A site whose chosen service cannot modify what
 * it stored is therefore <em>refused</em> and told so, rather than being given a layered series that silently only
 * ever appends. The services that qualify today are influxdb, inmemory, jdbc and mongodb.
 * <p>
 * <strong>Layer identity is this framework's own bookkeeping.</strong> What persistence holds is a number at a
 * timestamp: it does not record who wrote it, and neither does a published time series. So the collision rules that
 * need to know which layer holds an entry can only see writes that went through this surface while the framework was
 * running, and a restart forgets them. That limitation is the sharpest thing the prototype has to say about the
 * corpus's open writer-precedence question, and it is stated on
 * {@link org.openhab.core.energy.forecast.LayeredWritePolicy} rather than hidden here.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface LayeredPredictionStore {

    /**
     * Applies one writer's values to a prediction series.
     * <p>
     * What happens where an entry is already held by another layer is the site's configured
     * {@link org.openhab.core.energy.forecast.LayeredWritePolicy}, and where the site has configured nothing it is the
     * requirement's own words - the newest write wins - with the collision reported rather than silent.
     *
     * @param itemName the Item carrying the prediction series
     * @param layer which writer this is
     * @param series the values to apply
     * @return what was written, what was refused, and what has to be reported
     */
    LayeredWriteReport apply(String itemName, SeriesLayer layer, ForecastSeries series);

    /**
     * Reads a prediction series back, composed with its constraint series where the site's policy keeps caps apart.
     *
     * @param itemName the Item carrying the prediction series
     * @param role what the series is about, which decides the unit it is read in
     * @param from the first instant of interest, inclusive
     * @param to the last instant of interest, inclusive, or {@code null} to read everything stored ahead of
     *            {@code from} - which is what a caller that has declared no horizon means, and the reason a horizon is
     *            not invented here
     * @return the series, or empty when the range holds no entry that can be read
     */
    Optional<ForecastSeries> read(String itemName, ForecastRole role, Instant from, @Nullable Instant to);
}
