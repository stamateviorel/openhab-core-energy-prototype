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
package org.openhab.core.energy.price;

import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.spi.EnergySeriesSource;

/**
 * Where a price series comes from - the "many sources behind one interface" of openhab-core issue #3478.
 * <p>
 * <strong>Sources push values in; this bundle never fetches.</strong> An implementation is whatever already knows how
 * to get prices - the EnergiDataService binding, a Tibber or ENTSO-E add-on, a script, or the Item-backed reader in
 * the companion {@code org.openhab.core.energy.series} bundle. None of them has to change shape to become one: a
 * binding that already publishes prices to an Item keeps doing exactly that, and the reader turns that Item into a
 * source. That is what "existing price bindings become sources without breaking changes" means in the proposal's own
 * impact note.
 * <p>
 * It also happens to be the only route left open. This bundle cannot query persistence, cannot subscribe to an Item
 * time-series event and cannot read a future out of an Item's state, and all three of those are pinned by tests. An
 * in-process push is what remains, and it is the right answer rather than a workaround: the same split decision D23
 * drew for writing, applied to reading.
 * <p>
 * <strong>No HTTP client appears anywhere in this plane</strong>, for the same reason. Core fetches nothing.
 * <p>
 * Implementations are registered as OSGi services and may come and go while the framework runs.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface EnergyPriceSource extends EnergySeriesSource<EnergyPriceSeries> {

    /**
     * Returns what this source publishes.
     *
     * @return the price role
     */
    PriceRole getRole();

    /**
     * Returns the prices this source currently has.
     * <p>
     * <strong>Empty is a normal answer, not a fault.</strong> A day-ahead source has nothing before the market
     * publishes, and a source whose fetch failed has nothing until it succeeds. The registry reports that as a
     * condition; nothing invents a price to fill it.
     *
     * @return the series, or empty when the source has nothing to offer
     */
    @Override
    Optional<EnergyPriceSeries> getSeries();
}
