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
import org.openhab.core.energy.window.SeriesSense;

/**
 * Which way the kilowatt-hour a price values is flowing.
 * <p>
 * <strong>Feed-in is modelled as a separate series, not as a sign or a discount on the consumption price.</strong>
 * That is what {@code price-data} <em>Feed-in pricing</em> requires, and it is the only model that survives contact
 * with the case that motivated it: jlaur's Danish net tariff, where delivering to the grid can cost more in tariffs
 * than the spot revenue, so the effective feed-in price is <em>negative while the consumption price is
 * positive</em>. One number with a sign cannot express two independently signed prices for the same hour.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum PriceDirection {

    /**
     * The price of a kilowatt-hour taken from the grid. Paying it is a cost, so it enters the valuation with a
     * positive sign.
     */
    CONSUMPTION(1, SeriesSense.LOWER_IS_BETTER),

    /**
     * The price of a kilowatt-hour delivered to the grid. Receiving it is a revenue, so it enters the valuation with a
     * negative sign - and a <em>negative</em> feed-in price therefore lands as a positive cost, which is exactly the
     * case the requirement names.
     */
    FEED_IN(-1, SeriesSense.HIGHER_IS_BETTER);

    private final int costSign;
    private final SeriesSense sense;

    PriceDirection(int costSign, SeriesSense sense) {
        this.costSign = costSign;
        this.sense = sense;
    }

    /**
     * Returns the sign this direction contributes to a cost.
     * <p>
     * This one number is the whole of "one arithmetic, both directions": {@code cost = sign * price * energy} values
     * an imported kilowatt-hour and an exported one with the same expression, so no objective needs a special case
     * for export.
     *
     * @return {@code +1} for consumption, {@code -1} for feed-in
     */
    public int costSign() {
        return costSign;
    }

    /**
     * Returns which end of a series of this direction a window search should prefer.
     * <p>
     * A consumption price is {@link SeriesSense#LOWER_IS_BETTER} - the cheapest hours are the ones to run in. A
     * feed-in price is {@link SeriesSense#HIGHER_IS_BETTER}, because the best hour to <em>export</em> in is the one
     * paying most. Both fall straight out of {@link #costSign()}: the better slot is always the one with the lower
     * cost, and reading the sense off the direction saves every caller from remembering which is which.
     * <p>
     * <em>Alternative preserved:</em> giving both directions {@link SeriesSense#LOWER_IS_BETTER} and leaving a caller
     * that wants the most lucrative export hour to ask for the <em>worst</em> slots of the feed-in series. That is one
     * fewer concept and it reads backwards at every call site. Nothing in the corpus decides this; a series built
     * directly rather than through {@link EnergyPriceSeries#of} can still carry whichever sense its author means.
     *
     * @return the sense a series of this direction carries unless its author says otherwise
     */
    public SeriesSense sense() {
        return sense;
    }
}
