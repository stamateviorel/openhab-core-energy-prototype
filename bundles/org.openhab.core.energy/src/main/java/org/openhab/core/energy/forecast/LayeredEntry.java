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
package org.openhab.core.energy.forecast;

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * One entry of a layered prediction series: when it applies, what it says, and which layer put it there.
 * <p>
 * The layer travels with the entry only inside this framework. What reaches storage is the timestamp and the value;
 * see {@link SeriesLayer} for why that matters and what it costs.
 *
 * @param timestamp the instant the value applies from
 * @param value the predicted value, in the series' own unit
 * @param layer which writer produced it
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record LayeredEntry(Instant timestamp, double value, SeriesLayer layer) {

    /**
     * Validates the entry.
     *
     * @throws IllegalArgumentException if the value is not a finite number
     */
    public LayeredEntry {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be a finite number but was " + value);
        }
    }
}
