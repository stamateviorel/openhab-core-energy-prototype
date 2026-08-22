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
package org.openhab.core.energy.window;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.window.internal.LeftRiemannWindowCost;

/**
 * The one costing calculation every window selection goes through - {@code cost(window, weights)}.
 * <p>
 * There is deliberately a single function rather than one per strategy. Contiguity, start granularity and shortfall
 * are then the same wherever a window is chosen, whether by the classifier, by a rule or by a script, and two
 * conforming implementations cost the same dishwasher identically.
 * <p>
 * <strong>Scope.</strong> The corpus places the shared calculation in the price plane, which is a later wave: prices
 * there carry a currency, taxes and transfer fees, and a window's cost is answered together with its start time.
 * What lives here is the seam and the flat-weight arithmetic wave 1 needs, so that the later capability replaces an
 * implementation rather than a call shape. A price of a {@link Slot} is a plain {@code double} in one shared
 * unit, so the cost this returns is in that unit times energy and is only ever compared against another cost of the
 * same series.
 * <p>
 * <strong>A non-flat curve over a non-consecutive selection is not defined by the corpus.</strong>
 * {@link CostWeights} maps a relative run time of 0 to 1 across the window, which describes a load running once from
 * start to finish. Costing a scattered set of slots under a curve therefore assumes the load resumes where it left
 * off, in time order - a reading nothing in {@code price-data} <em>Shared window calculations</em> states, because
 * that requirement pairs curves with the <em>consecutive</em> search and says nothing about interrupting one. It is
 * the only reading available here and it is arithmetically consistent, but it is this implementation's reading; a
 * site that interrupts a shaped load should treat the number as indicative until the corpus says what a curve means
 * across a gap.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@FunctionalInterface
public interface WindowCost {

    /**
     * Returns the cost of running a load through the given window.
     * <p>
     * Only the granted running time is costed: a final slot the load leaves part way through is charged for the part
     * it uses, and a partial answer costs the part it could grant.
     *
     * @param series the series the window's slot indices refer to
     * @param window the chosen window
     * @param weights the load's shape over its own running time
     * @return the cost, in the series' own price unit times energy
     */
    double cost(SlotSeries series, WindowSelection window, CostWeights weights);

    /**
     * Returns the cost of a window under flat weights, which is what a caller with no declared curve wants.
     *
     * @param series the series the window's slot indices refer to
     * @param window the chosen window
     * @return the cost under flat weights
     */
    default double cost(SlotSeries series, WindowSelection window) {
        return cost(series, window, CostWeights.flat());
    }

    /**
     * Returns the shared calculation: energy under the weights, integrated LEFT-Riemann over the slots the window
     * occupies and priced at each slot's own price.
     *
     * @return the shared costing function
     */
    static WindowCost shared() {
        return new LeftRiemannWindowCost();
    }
}
