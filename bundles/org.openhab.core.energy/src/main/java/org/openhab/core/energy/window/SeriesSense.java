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

/**
 * Which direction is the good one on a series - the one property that lets a single ranking, a single window search
 * and a single costing serve every kind of series the energy framework carries.
 * <p>
 * <strong>Why this exists at all.</strong> Nothing in the corpus states the polarity of a series, and two
 * requirements need opposite ones in the same breath. {@code optimization-objectives} <em>Carbon data as a
 * first-class series</em> accepts "CO2 intensity <em>or</em> green share" - the first is lower-is-better, the second
 * higher-is-better - with no way to tell them apart. {@code forecast-data} <em>Solar production forecast</em> asks
 * for "the best 3-hour photovoltaic window", where best means <em>most</em>, through calculations that until now
 * only ever minimised. And {@code price-data} <em>Shared window calculations</em> requires the most expensive
 * selections as well as the cheapest ones. One concept discharges all three.
 * <p>
 * It is a property of the <em>series</em> rather than of the request, because it is a fact about what the numbers
 * mean, which the source knows and the caller should not have to. A caller asks for the <em>best</em> window and gets
 * the cheapest hours of a price series and the sunniest hours of a photovoltaic forecast, from the same call.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum SeriesSense {

    /**
     * A lower value is the better one: a price, a carbon intensity, a grid tariff.
     * <p>
     * This is the default a series takes when nothing states otherwise, because it is what every series wave 1 ever
     * saw was.
     */
    LOWER_IS_BETTER,

    /**
     * A higher value is the better one: a photovoltaic production forecast, a green-share series, an available
     * surplus.
     */
    HIGHER_IS_BETTER
}
