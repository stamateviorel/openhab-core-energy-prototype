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
 * One term of the effective price: a named series, the role it plays, and whether the user has it switched on.
 * <p>
 * <strong>The {@code included} flag is a requirement, not a convenience.</strong> {@code price-data} <em>Price
 * component composition</em> says in terms that "the user selects which components/Items are included" - jlaur's
 * point, which Kai agreed. A site that pays a fixed grid tariff outside its supplier's bill wants the tariff in the
 * sum; a site whose supplier already includes it does not, and it must be able to say so without deleting the source
 * that publishes it.
 * <p>
 * The components of a composition are an <strong>ordered list</strong>. Addition does not care about order, but the
 * adjustments a component may have been through do, and stating the order makes the composed price a thing a user can
 * read back rather than a total whose provenance is lost.
 *
 * @param id the component's stable id, which is what a message names when this component is the problem
 * @param role what the component is, for choosing a source for it and for explaining the sum
 * @param included whether the user has this component switched on
 * @param series the component's own prices
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record PriceComponent(String id, PriceRole role, boolean included, EnergyPriceSeries series) {

    /**
     * Validates the component.
     *
     * @throws IllegalArgumentException if the id is blank
     */
    public PriceComponent {
        if (id.isBlank()) {
            throw new IllegalArgumentException("a price component needs an id");
        }
    }

    /**
     * Creates an included component.
     *
     * @param id the component's stable id
     * @param role what the component is
     * @param series the component's own prices
     * @return the component
     */
    public static PriceComponent of(String id, PriceRole role, EnergyPriceSeries series) {
        return new PriceComponent(id, role, true, series);
    }

    /**
     * Returns this component switched on or off.
     *
     * @param isIncluded whether the component takes part in the sum
     * @return the component
     */
    public PriceComponent withIncluded(boolean isIncluded) {
        return new PriceComponent(id, role, isIncluded, series);
    }
}
