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

import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.spi.EnergySeriesSource;

/**
 * A place a forecast comes from: a solar-forecast service, a weather binding, a script, a stored baseline.
 * <p>
 * <strong>This is the whole of what a consumer knows about a forecast's origin.</strong> _Source-agnostic
 * consumption_ requires that engines and rules read a series "without knowing which add-on produced them, selected by
 * user configuration", so a consumer asks {@link ForecastRegistry} for a role and never names a source. Replacing one
 * solar service with another is then a configuration edit, and no rule changes - which is the requirement's own
 * scenario, made structural rather than promised.
 * <p>
 * <strong>Push, not pull, and in process.</strong> The framework bundle can neither query persistence nor subscribe
 * to an Item's time-series events - both are structurally forbidden there and proved so by test - so a source that
 * reads an Item, a database or the network lives in an edge bundle and hands the values in through this interface.
 * That is the same split D23 drew for writing, applied to the input side: the value is computed and reasoned about
 * where nothing can be written, and the Item is touched where touching Items is the point.
 * <p>
 * <strong>Ranking, and a site's own preference.</strong> Two sources for one role are ordered by
 * {@link #getServiceRanking()}, higher winning, and a site may name a preferred source id per role. That is
 * `extension-surface` _Multiple contributors, user selection_ verbatim - "the highest {@code service.ranking} used
 * where the site names no preference" - and the ranking is answered here rather than read off the service registry
 * because the object reaches the registry without its service reference, exactly as it does for participant sources.
 * A source core itself ships registers at {@code service.ranking = -2}, so any contributed alternative outranks it
 * with no configuration at all.
 * <p>
 * <strong>Not a safety input.</strong> A contributed forecast is data-plane by construction: losing it degrades
 * planning to whatever baseline remains, never a device to a safe state. The engine owns the enumeration of what
 * counts as a safety input and a contributor may not claim that status for itself (D13), which is why this interface
 * has no way to say otherwise.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface ForecastSeriesSource extends EnergySeriesSource<ForecastSeries> {

    /**
     * Returns what this source forecasts.
     *
     * @return the role of its series
     */
    ForecastRole getRole();

    /**
     * Returns the forecast as it stands now.
     * <p>
     * Empty is a normal answer rather than a fault: a service that has just started has nothing yet, and a service
     * whose last run failed has nothing new. A source whose data has gone stale should still answer with it and let
     * the plane apply the site's declared maximum age - the requirement is explicit that a site keeps planning on
     * what it has rather than losing the series.
     *
     * @return the current forecast, or empty when this source has none
     */
    @Override
    Optional<ForecastSeries> getSeries();
}
