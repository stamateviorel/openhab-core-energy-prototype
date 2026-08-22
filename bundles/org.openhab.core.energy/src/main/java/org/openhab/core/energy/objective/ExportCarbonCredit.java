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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * What an exported kilowatt-hour is worth to the carbon objective, expressed as the fraction of the displacement
 * credit it earns.
 * <p>
 * <strong>This interface exists to make one decision reversible, and that is its whole job.</strong> The shipped rule
 * withdraws the credit while the feed-in price is negative; the alternative credits every export regardless of price,
 * which is what any implementation does if the rule is deleted. Both are shipped, the choice is one configuration
 * value, and the rule itself is one arithmetic expression in one class - see
 * {@code org.openhab.core.energy.objective.internal.NegativeFeedInCarbonCredit}, which carries the argument and the
 * warning that goes with it.
 * <p>
 * A third position - crediting the export at a curtailment-discounted rate - is named in the corpus and deliberately
 * not shipped: it needs a discount rate, and nobody has one. An implementation of this interface is all it would
 * take.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@FunctionalInterface
public interface ExportCarbonCredit {

    /**
     * Returns the fraction of the displacement credit an exported kilowatt-hour earns in a slot.
     *
     * @param effectiveFeedInPrice the effective feed-in price of the slot, in the feed-in series' own unit, negative
     *            meaning the site pays to export
     * @return the credit fraction, {@code 1} for the full credit and {@code 0} for none
     */
    double creditFactorAt(double effectiveFeedInPrice);

    /**
     * Returns the id this rule is selected by in the objective plane's configuration.
     *
     * @return the rule id, the implementation's simple class name unless it says otherwise
     */
    default String getId() {
        return getClass().getSimpleName();
    }
}
