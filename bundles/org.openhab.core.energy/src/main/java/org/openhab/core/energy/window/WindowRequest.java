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

import java.time.Duration;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * What a caller asked a {@link SelectionStrategy} for.
 * <p>
 * <strong>A window request carries a {@link Duration}</strong>. That is the primary form, and it is what makes two
 * candidate windows comparable at all: every candidate covers the same running time, so the "total cost versus cost
 * per hour" question never arises, whatever slot widths the series happens to mix.
 * <p>
 * The slot-count form is retained for the "N cheapest slots" case, where the caller genuinely means slots rather
 * than time. Its candidates are compared on duration-weighted mean price, and its shortfall is counted in slots.
 * <p>
 * The request travels with the answer inside {@link WindowSelection}, so a partial answer can always say what it was
 * asked for as well as what it could grant.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public sealed interface WindowRequest {

    /**
     * A request for a running time - the primary form.
     *
     * @param runningTime how long the load needs to run
     *
     * @author Stamate Viorel - Initial contribution
     */
    record ForDuration(Duration runningTime) implements WindowRequest {

        /**
         * Validates the request.
         *
         * @throws IllegalArgumentException if the running time is negative
         */
        public ForDuration {
            if (runningTime.isNegative()) {
                throw new IllegalArgumentException("runningTime must not be negative but was " + runningTime);
            }
        }
    }

    /**
     * A request for a number of slots - the retained "N cheapest slots" form.
     *
     * @param slotCount how many slots the load needs
     *
     * @author Stamate Viorel - Initial contribution
     */
    record ForSlots(int slotCount) implements WindowRequest {

        /**
         * Validates the request.
         *
         * @throws IllegalArgumentException if the slot count is negative
         */
        public ForSlots {
            if (slotCount < 0) {
                throw new IllegalArgumentException("slotCount must not be negative but was " + slotCount);
            }
        }
    }

    /**
     * Creates a request for a running time.
     *
     * @param runningTime how long the load needs to run
     * @return the request
     */
    static WindowRequest ofDuration(Duration runningTime) {
        return new ForDuration(runningTime);
    }

    /**
     * Creates a request for a number of slots.
     *
     * @param slotCount how many slots the load needs
     * @return the request
     */
    static WindowRequest ofSlots(int slotCount) {
        return new ForSlots(slotCount);
    }
}
