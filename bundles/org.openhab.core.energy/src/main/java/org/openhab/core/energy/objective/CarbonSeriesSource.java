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
package org.openhab.core.energy.objective;

import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.spi.EnergySeriesSource;

/**
 * A place carbon data comes from: a national grid-intensity API, a regional emissions service, a script that knows
 * its own tariff's generation mix.
 * <p>
 * <strong>Pluggable on exactly the terms a price feed is.</strong> Core ships no carbon source at all - the corpus
 * puts market- and region-specific data in add-ons - so this interface plus the objective that reads it is the whole
 * of the framework's carbon support, and a contributed source needs nothing that a price source does not.
 * <p>
 * <strong>Push, not pull, and in-process.</strong> The engine bundle cannot query persistence and cannot subscribe to
 * an Item time-series event; both are structurally forbidden here and proven so by test. A source that fetches over
 * the network or reads an Item's future series therefore lives in an edge bundle and hands the values in through this
 * interface. That is the same split the framework already applies to writing.
 * <p>
 * Two sources for the same site are ordered by {@link #getServiceRanking()}, higher winning, ties broken by source
 * id so that the answer never depends on start order; a site may name the one it prefers in the objective plane's
 * configuration.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface CarbonSeriesSource extends EnergySeriesSource<CarbonSeries> {

    /**
     * Returns the carbon series as it stands now.
     * <p>
     * Empty is a normal answer, not a fault: a source that has just started has nothing yet, and one whose data has
     * gone stale should answer empty rather than answer with yesterday's numbers - the plane can report an absent
     * series and cannot detect a plausible wrong one.
     *
     * @return the current series, or empty when the source has none
     */
    @Override
    Optional<CarbonSeries> getSeries();
}
