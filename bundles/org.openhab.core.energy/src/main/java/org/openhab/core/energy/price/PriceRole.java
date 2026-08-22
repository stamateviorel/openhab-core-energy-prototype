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
 * What a price series is, which is how a site says "use the ENTSO-E binding for spot and my own calculator for the
 * grid tariff" without naming an implementation.
 * <p>
 * The roles are the vocabulary the thread already speaks: masipila composes a spot price from one binding with a
 * transfer tariff from another, jlaur's elements model separates spot, tariff and taxes, and Kai's
 * {@code GridEnergyProvider} is the generic thing that turns the first into the second. Feed-in is a role of its own
 * rather than a flag, because it is a separately signed series for the same hour.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum PriceRole {

    /**
     * The wholesale or day-ahead market price, before anything a supplier or a grid operator adds.
     */
    SPOT,

    /**
     * What the grid operator charges for transporting the energy, which is where seasonal and day/night conditions
     * usually live.
     */
    GRID_TARIFF,

    /**
     * Energy taxes, levies and fixed per-kilowatt-hour fees.
     */
    TAXES_AND_FEES,

    /**
     * The price paid for energy delivered to the grid.
     */
    FEED_IN,

    /**
     * A component a site has that none of the other roles describes.
     * <p>
     * Present so that an unusual national arrangement does not have to misuse a role that means something else. It is
     * deliberately not a per-role selection key that anything can be resolved by on its own - two components of this
     * role are told apart by their ids.
     */
    OTHER
}
