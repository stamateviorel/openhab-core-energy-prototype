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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Something the price plane cannot do and has to say so about, rather than doing nothing quietly.
 * <p>
 * The same shape as {@code LevelPlaneCondition} and for the same reason: a requirement's absence must be
 * <em>visible</em>. None of these stops the engine - the level plane keeps answering from whatever it last had - and
 * every one of them is a gap a site can close.
 * <p>
 * They are machine-readable on purpose. A UI needs something to render as "not configured" rather than as silence,
 * and {@code EnergyConfigStatus} needs a value to key a message on.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum PricePlaneCondition {

    /**
     * No source has published a series for a role the composition includes, so no effective price can be composed.
     * <p>
     * This is the ordinary state of a fresh installation, and it is also what a dead day-ahead feed looks like. It
     * is reported rather than met with a made-up price.
     */
    NO_SOURCE,

    /**
     * The composition includes no component at all, so there is nothing to sum.
     * <p>
     * Distinct from {@link #NO_SOURCE}: there the site said what it wanted and nothing supplied it; here the site has
     * not said what its price is made of. Core ships no default composition, following D22's precedent - the shape is
     * shipped, the numbers are not, and an unconfigured plane says so instead of inventing a spot-only price.
     */
    COMPOSITION_UNCONFIGURED,

    /**
     * Two components of one composition are denominated in different currencies.
     * <p>
     * Refused rather than converted. Core does ship {@code CurrencyService} and could convert, but the change's own
     * non-goal defers currency exchange ("explicitly deferred in 2023"), and a silently applied exchange rate is a
     * number the user never saw in a figure they are about to act on.
     */
    CURRENCY_MISMATCH,

    /**
     * Two components of one composition carry different market zones, so the composed series has no delivery day.
     * <p>
     * A delivery day is named in the market's zone and never inferred. A sum of a Finnish spot series and a French
     * grid tariff has two candidate zones and no rule to choose between them, so the composition refuses instead of
     * picking the first one silently.
     */
    MARKET_ZONE_MISMATCH,

    /**
     * A component values energy in the other direction from the composition it was put in.
     * <p>
     * A feed-in price and a consumption price are not summands of one another: one values a kilowatt-hour delivered
     * to the grid and the other one taken from it, and they carry opposite senses, so a sum of the two ranks its
     * slots backwards. <em>Feed-in pricing</em> requires the two to be modelled separately and the plane answers
     * feed-in through its own accessor; putting the role into a composition is a configuration mistake rather than a
     * modelling choice, and it is refused where the currency and market zone are already refused rather than
     * producing a plausible-looking series that blocks the cheapest hours.
     */
    DIRECTION_MISMATCH,

    /**
     * The components cannot be aligned onto one set of slot boundaries under the configured alignment.
     * <p>
     * Only the strict alignment can raise this: it is the reading in which composing series of differing geometry is
     * simply not defined. The other two alignments always produce an answer.
     */
    UNALIGNABLE,

    /**
     * The components do not overlap in time, so there is no slot any of them could be summed over.
     */
    NO_COMMON_TIME,

    /**
     * A tariff calendar was asked to cover a span it cannot reasonably enumerate.
     */
    TARIFF_SPAN_TOO_LONG
}
