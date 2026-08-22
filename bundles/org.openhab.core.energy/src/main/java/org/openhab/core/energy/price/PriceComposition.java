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

import java.util.ArrayList;
import java.util.List;

import javax.measure.Unit;
import javax.measure.quantity.Energy;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;

/**
 * The effective price as a sum of components - {@code price-data} <em>Price component composition</em>.
 * <p>
 * The real consumer price is not one number from one place: it is a spot price from a market, a transfer tariff from
 * a grid operator, an energy tax and whatever a supplier adds, each of which may come from a different binding and
 * any of which a given site may already have inside another. This class is the sum, the rules under which the sum is
 * defined, and the refusals where it is not.
 * <p>
 * <strong>Four rules, three of which are refusals.</strong>
 * <ol>
 * <li><strong>The first included component is the base.</strong> Its currency, its unit of energy and its market zone
 * are the composition's. This is what gives a composed series a delivery day at all: a sum has no zone of its own,
 * and the corpus fixes the zone on <em>a</em> series without saying what a sum of two carries.</li>
 * <li><strong>A different currency is refused.</strong> Core does ship an exchange service, and the change's own
 * non-goal defers currency exchange; a rate silently applied inside a figure a user is about to act on is worse than
 * a message telling them their components disagree.</li>
 * <li><strong>A different market zone is refused.</strong> Two zones give two candidate delivery days and there is no
 * rule to pick one.</li>
 * <li><strong>A different unit of energy is converted</strong>, not refused - that one is arithmetic on the
 * denominator and cannot fail.</li>
 * </ol>
 * <p>
 * <strong>The sum is signed and unclamped.</strong> A spot component more negative than the fixed components are
 * positive leaves a negative effective price, and it is carried through exactly as it is (owner decision D17, row
 * EL-13). Nothing here treats zero as a floor, because {@code Number:EnergyPrice} is a signed quantity and the
 * markets this plane serves produce negative prices routinely.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class PriceComposition {

    private PriceComposition() {
    }

    /**
     * Composes the effective price from the included components, under the default alignment.
     *
     * @param components the components, in the user's own order
     * @return the effective price series
     * @throws PriceCompositionException if nothing is included, or the components cannot be summed
     */
    public static EnergyPriceSeries compose(List<PriceComponent> components) throws PriceCompositionException {
        return compose(components, SeriesAlignment.unionOfBoundaries());
    }

    /**
     * Composes the effective price from the included components.
     *
     * @param components the components, in the user's own order
     * @param alignment how components of differing geometry are brought onto one set of slots
     * @return the effective price series
     * @throws PriceCompositionException if nothing is included, or the components cannot be summed
     */
    public static EnergyPriceSeries compose(List<PriceComponent> components, SeriesAlignment alignment)
            throws PriceCompositionException {
        List<PriceComponent> included = new ArrayList<>();
        for (PriceComponent component : components) {
            if (component.included()) {
                included.add(component);
            }
        }
        if (included.isEmpty()) {
            throw new PriceCompositionException(PricePlaneCondition.COMPOSITION_UNCONFIGURED,
                    "no price component is included, so there is no effective price to compose"
                            + (components.isEmpty() ? ""
                                    : " - " + components.size() + " are declared but switched off"));
        }
        EnergyPriceSeries base = included.getFirst().series();
        Unit<Energy> energyUnit = base.energyUnit();
        List<SlotSeries> terms = new ArrayList<>(included.size());
        for (PriceComponent component : included) {
            EnergyPriceSeries series = component.series();
            if (!EnergyPriceUnits.isSameCurrency(base.currency(), series.currency())) {
                throw new PriceCompositionException(PricePlaneCondition.CURRENCY_MISMATCH,
                        "price component '" + component.id() + "' is denominated in " + series.currency().getName()
                                + " but the composition is in " + base.currency().getName()
                                + "; converting between currencies is not part of this plane, so the components have to"
                                + " agree");
            }
            if (!base.marketZone().equals(series.marketZone())) {
                throw new PriceCompositionException(PricePlaneCondition.MARKET_ZONE_MISMATCH,
                        "price component '" + component.id() + "' carries market zone " + series.marketZone()
                                + " but the composition is anchored to " + base.marketZone()
                                + "; a sum of two markets has no delivery day");
            }
            if (base.direction() != series.direction()) {
                throw new PriceCompositionException(PricePlaneCondition.DIRECTION_MISMATCH,
                        "price component '" + component.id() + "' is a " + series.direction()
                                + " price but the composition is a " + base.direction()
                                + " one; the two value opposite kilowatt-hours and carry opposite senses, so summing"
                                + " them would rank the day backwards");
            }
            terms.add(series.toEnergyUnit(energyUnit).values());
        }
        List<SlotSeries> aligned = alignment.align(terms);
        return base.withValues(sum(aligned, base.direction().sense()));
    }

    /**
     * Sums aligned series slot by slot.
     *
     * @param aligned series that already share their slots
     * @param sense the sense the sum carries
     * @return the summed series
     */
    private static SlotSeries sum(List<SlotSeries> aligned, org.openhab.core.energy.window.SeriesSense sense) {
        SlotSeries reference = aligned.getFirst();
        List<Slot> summed = new ArrayList<>(reference.size());
        for (int slot = 0; slot < reference.size(); slot++) {
            double total = 0;
            for (SlotSeries term : aligned) {
                total += term.valueAt(slot);
            }
            summed.add(new Slot(reference.slotAt(slot).start(), reference.slotAt(slot).end(), total));
        }
        return new SlotSeries(summed, sense);
    }
}
