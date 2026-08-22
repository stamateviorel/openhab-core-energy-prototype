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

import java.util.List;

import javax.measure.Unit;
import javax.measure.quantity.Energy;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.window.SlotSeries;

/**
 * One step of the generic grid-price provider's pipeline: a function from a price series to a price series.
 * <p>
 * <strong>The order of operations is the user's ordered list, and that is a deliberate answer to a hole in the
 * requirement.</strong> {@code price-data} <em>Generic grid-price provider</em> names four adjustments - "VAT
 * multiplier, unit conversion such as EUR/MWh to ct/kWh, fixed fees, simple conditional tariffs" - and never says in
 * which order they apply. It matters: VAT on a price that already includes a transfer fee is a different number from
 * a fee added after VAT, and masipila's own Caruna case has a transfer fee that carries its own VAT. Rather than
 * fixing an order here and being wrong for half the markets in Europe, the pipeline has <em>no</em> order of its own:
 * a site lists the steps it wants in the order its own bill applies them, and
 * {@link GridPriceProvider#apply(EnergyPriceSeries)} walks that list. The requirement should say so; the report says
 * it does not.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public sealed interface PriceAdjustment {

    /**
     * Applies this step.
     *
     * @param series the series as the previous step left it
     * @return the adjusted series
     * @throws PriceCompositionException if this step cannot be carried out on this series
     */
    EnergyPriceSeries apply(EnergyPriceSeries series) throws PriceCompositionException;

    /**
     * Returns a short description of this step, for explaining a composed price back to the user who configured it.
     *
     * @return the description
     */
    String describe();

    /**
     * Multiplies every price by a factor - the untyped half of "unit conversion", and the escape hatch for a source
     * that publishes a bare number in a unit nothing can name.
     * <p>
     * This is also the only way to render prices in cents, because cents are not a currency core can construct; see
     * {@link EnergyPriceUnits}. Doing so leaves the series' declared unit saying something the numbers no longer
     * mean, which is why it is a step a site chooses rather than something this plane does on its own.
     *
     * @param factor the multiplier
     *
     * @author Stamate Viorel - Initial contribution
     */
    record Scale(double factor) implements PriceAdjustment {

        /**
         * Validates the step.
         *
         * @throws IllegalArgumentException if the factor is not a finite number
         */
        public Scale {
            if (!Double.isFinite(factor)) {
                throw new IllegalArgumentException("a scale factor must be a finite number but was " + factor);
            }
        }

        @Override
        public EnergyPriceSeries apply(EnergyPriceSeries series) {
            return series.scaledBy(factor);
        }

        @Override
        public String describe() {
            return "x" + factor;
        }
    }

    /**
     * Adds value-added tax at a percentage - "the user configures x VAT" from the requirement's own scenario.
     * <p>
     * Expressed as the percentage a user reads off their bill rather than as the multiplier, because 24 is what a
     * Finnish bill says and 1.24 is what an implementation wants; asking the user for the second is how a site ends
     * up with a 24-fold price.
     *
     * @param percent the tax rate in percent
     *
     * @author Stamate Viorel - Initial contribution
     */
    record Vat(double percent) implements PriceAdjustment {

        /**
         * Validates the step.
         *
         * @throws IllegalArgumentException if the rate is not a finite number, or is below -100 percent
         */
        public Vat {
            if (!Double.isFinite(percent) || percent <= -100) {
                throw new IllegalArgumentException(
                        "a VAT rate must be a finite percentage above -100 but was " + percent);
            }
        }

        @Override
        public EnergyPriceSeries apply(EnergyPriceSeries series) {
            return series.scaledBy(1 + percent / 100);
        }

        @Override
        public String describe() {
            return "VAT " + percent + "%";
        }
    }

    /**
     * Adds a fixed amount to every slot - a per-kilowatt-hour levy, a supplier's margin, an energy tax.
     * <p>
     * The amount is stated per a unit of energy of its own and converted to the series', so a fee a user knows in
     * EUR/MWh does not have to be divided by hand before it is typed in.
     *
     * @param amount the amount to add
     * @param per the unit of energy the amount is per
     *
     * @author Stamate Viorel - Initial contribution
     */
    record FixedFee(double amount, Unit<Energy> per) implements PriceAdjustment {

        /**
         * Validates the step.
         *
         * @throws IllegalArgumentException if the amount is not a finite number
         */
        public FixedFee {
            if (!Double.isFinite(amount)) {
                throw new IllegalArgumentException("a fixed fee must be a finite number but was " + amount);
            }
        }

        @Override
        public EnergyPriceSeries apply(EnergyPriceSeries series) {
            return series.plus(amount * EnergyPriceUnits.energyDenominatorFactor(per, series.energyUnit()));
        }

        @Override
        public String describe() {
            return "+" + amount + "/" + per;
        }
    }

    /**
     * Restates the prices per a different unit of energy - EUR/MWh to EUR/kWh, the typed half of the requirement's
     * "unit conversion".
     *
     * @param target the unit of energy the prices should be per
     *
     * @author Stamate Viorel - Initial contribution
     */
    record Denomination(Unit<Energy> target) implements PriceAdjustment {

        @Override
        public EnergyPriceSeries apply(EnergyPriceSeries series) {
            return series.toEnergyUnit(target);
        }

        @Override
        public String describe() {
            return "per " + target;
        }
    }

    /**
     * Composes a conditional tariff onto the series - the seasonal and day/night half of the requirement.
     * <p>
     * The tariff is rendered as its own series over the price series' span and summed through the ordinary
     * composition, so a tariff step that falls inside a price slot cuts that slot in two rather than being rounded to
     * its start.
     *
     * @param calendar the tariff calendar
     * @param alignment how the tariff's boundaries and the prices' are brought together
     *
     * @author Stamate Viorel - Initial contribution
     */
    record ConditionalTariff(TariffCalendar calendar, SeriesAlignment alignment) implements PriceAdjustment {

        /**
         * Creates the step with the default alignment.
         *
         * @param calendar the tariff calendar
         */
        public ConditionalTariff(TariffCalendar calendar) {
            this(calendar, SeriesAlignment.unionOfBoundaries());
        }

        @Override
        public EnergyPriceSeries apply(EnergyPriceSeries series) throws PriceCompositionException {
            SlotSeries tariff = calendar.toSeries(series.start(), series.end());
            List<SlotSeries> aligned = alignment.align(List.of(series.values(), tariff));
            SlotSeries prices = aligned.get(0);
            SlotSeries amounts = aligned.get(1);
            List<org.openhab.core.energy.window.Slot> slots = new java.util.ArrayList<>(prices.size());
            for (int slot = 0; slot < prices.size(); slot++) {
                slots.add(new org.openhab.core.energy.window.Slot(prices.slotAt(slot).start(),
                        prices.slotAt(slot).end(), prices.valueAt(slot) + amounts.valueAt(slot)));
            }
            return series.withValues(new SlotSeries(slots, series.values().sense()));
        }

        @Override
        public String describe() {
            return "tariff calendar of " + calendar.periods().size() + " periods in " + calendar.zone();
        }
    }
}
