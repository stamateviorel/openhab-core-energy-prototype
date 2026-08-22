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
 * One of the series an objective may rank on, named so that "this objective needs data nobody publishes" is
 * answerable before a planning run rather than by watching one produce nothing.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum ObjectiveInput {

    /**
     * What a kilowatt-hour drawn from the grid costs, all components included.
     */
    CONSUMPTION_PRICE,

    /**
     * What a kilowatt-hour exported to the grid earns, all components included. Separate from the consumption price
     * because the two are separately published, separately signed and separately negative.
     */
    FEED_IN_PRICE,

    /**
     * The carbon content of grid electricity - an intensity, or the renewable share that stands in for one.
     */
    CARBON,

    /**
     * How much surplus the site expects to have per slot, in watts.
     * <p>
     * <strong>No requirement in the corpus defines this series.</strong> Surplus is defined as an instantaneous
     * figure taken from one cycle snapshot, while placing a deferrable load "into the surplus" is a statement about
     * future slots. Naming it as an input is how that gap is made resolvable instead of assumed: a site that has a
     * forecast gets the behaviour the scenario describes, and a site that has none gets an objective that reports its
     * data plane absent.
     */
    SURPLUS_FORECAST
}
