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
 * What a kilowatt-hour is worth, in one expression that serves both directions - {@code price-data} <em>Feed-in
 * pricing</em>, scenario "One arithmetic, both directions".
 * <p>
 * The whole of it is {@code cost = direction.costSign() * price * energy}. An imported kilowatt-hour costs its
 * consumption price; an exported one costs the negative of its feed-in price, which is to say it earns it. A
 * <em>negative</em> feed-in price therefore comes out as a positive cost, which is the Danish net-tariff case jlaur
 * raised and the reason the requirement exists at all.
 * <p>
 * <strong>Why that makes both objectives agree without a special case.</strong> Self-consuming a kilowatt-hour
 * replaces one import and one export with neither, so it is worth
 * {@code consumptionPrice - feedInPrice} - {@link #selfConsumptionBenefit}. Under a positive consumption price and a
 * negative feed-in price that difference is larger than either term, so the cost objective prefers local consumption
 * for the same reason the self-consumption objective does. Neither objective needs to know that export is special:
 * the sign already says it.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class EnergyValuation {

    private EnergyValuation() {
    }

    /**
     * Returns the cost of moving energy in a direction at a price.
     *
     * @param direction whether the energy is imported or exported
     * @param price the price per unit of energy
     * @param energy the amount of energy, in the same unit the price is per
     * @return the cost: positive is money leaving, negative is money arriving
     */
    public static double cost(PriceDirection direction, double price, double energy) {
        return direction.costSign() * price * energy;
    }

    /**
     * Returns the cost of moving energy through one slot of a series.
     *
     * @param series the price series, whose own direction decides the sign
     * @param slotIndex the slot
     * @param energy the amount of energy, in the series' own unit of energy
     * @return the cost
     * @throws IndexOutOfBoundsException if the slot index is out of range
     */
    public static double cost(EnergyPriceSeries series, int slotIndex, double energy) {
        return cost(series.direction(), series.values().valueAt(slotIndex), energy);
    }

    /**
     * Returns what consuming a kilowatt-hour locally is worth compared with importing it and exporting an equal one.
     * <p>
     * Derived from {@link #cost} rather than written out, so the two can never disagree: it is the cost of the import
     * plus the cost of the export that self-consumption avoids.
     *
     * @param consumptionPrice the consumption price for the slot
     * @param feedInPrice the feed-in price for the same slot
     * @param energy the amount of energy
     * @return the benefit: positive when local consumption is worth more than exporting
     */
    public static double selfConsumptionBenefit(double consumptionPrice, double feedInPrice, double energy) {
        return cost(PriceDirection.CONSUMPTION, consumptionPrice, energy)
                + cost(PriceDirection.FEED_IN, feedInPrice, energy);
    }

    /**
     * Returns what consuming energy locally is worth in one slot of a matched pair of series.
     *
     * @param consumption the consumption price series
     * @param feedIn the feed-in price series, sharing the consumption series' geometry
     * @param slotIndex the slot
     * @param energy the amount of energy
     * @return the benefit
     * @throws IllegalArgumentException if the two series are not the same direction pair, or do not share the slot
     * @throws IndexOutOfBoundsException if the slot index is out of range
     */
    public static double selfConsumptionBenefit(EnergyPriceSeries consumption, EnergyPriceSeries feedIn, int slotIndex,
            double energy) {
        if (consumption.direction() != PriceDirection.CONSUMPTION || feedIn.direction() != PriceDirection.FEED_IN) {
            throw new IllegalArgumentException("self-consumption is valued against a consumption series and a feed-in "
                    + "series, but was given " + consumption.direction() + " and " + feedIn.direction());
        }
        if (!consumption.slotAt(slotIndex).start().equals(feedIn.slotAt(slotIndex).start())
                || !consumption.slotAt(slotIndex).end().equals(feedIn.slotAt(slotIndex).end())) {
            throw new IllegalArgumentException("the consumption and feed-in series must share slot " + slotIndex
                    + ", but one runs " + consumption.slotAt(slotIndex).start() + " to "
                    + consumption.slotAt(slotIndex).end() + " and the other " + feedIn.slotAt(slotIndex).start()
                    + " to " + feedIn.slotAt(slotIndex).end());
        }
        double feedInPrice = feedIn.toEnergyUnit(consumption.energyUnit()).values().valueAt(slotIndex);
        return selfConsumptionBenefit(consumption.values().valueAt(slotIndex), feedInPrice, energy);
    }
}
