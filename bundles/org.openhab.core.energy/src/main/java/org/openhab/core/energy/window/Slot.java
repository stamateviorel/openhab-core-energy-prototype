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
import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * One slot of a series: a half-open time interval {@code [start, end)} and the one number that applies inside it.
 * <p>
 * The slot carries its own {@code end} rather than a shared slot width, because the requirement demands every
 * calculation work "independent of the market time resolution (60- or 15-minute slots)" - and a real series can mix
 * widths across a daylight-saving change, when a firm 15-minute near term is stitched to a coarse hourly far term, or
 * when two providers are joined. No code in this package ever assumes a fixed slot width.
 * <p>
 * <strong>The value is a plain {@code double} and deliberately carries no unit or meaning.</strong> The calculations
 * over it need only a total order and a way to integrate it over time, and they are used by more than prices: a
 * carbon-intensity series, a photovoltaic forecast and a day-ahead price all rank and integrate identically. What the
 * number means, in what unit, and whether more of it is better, belongs to the typed series that wraps this one -
 * {@code org.openhab.core.energy.price.EnergyPriceSeries} for a price - together with
 * {@link SlotSeries#sense()}.
 * <p>
 * This type was called {@code PriceSlot} while the level plane was the only caller. The rename is deliberate: a class
 * named for prices carrying carbon intensity is exactly the user-facing modelling inconsistency openHAB's reviewers
 * weight first.
 *
 * @param start the inclusive start of the slot
 * @param end the exclusive end of the slot
 * @param value the value that applies inside the slot, in whatever unit the whole series shares
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record Slot(Instant start, Instant end, double value) {

    /**
     * Validates the slot.
     *
     * @throws IllegalArgumentException if the slot does not end after it starts, or the value is not a finite number
     */
    public Slot {
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end (" + end + ") must be after start (" + start + ")");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be a finite number but was " + value);
        }
    }

    /**
     * Creates a slot from its start and width.
     *
     * @param start the inclusive start of the slot
     * @param width the slot width
     * @param value the value that applies inside the slot
     * @return the slot
     */
    public static Slot of(Instant start, Duration width, double value) {
        return new Slot(start, start.plus(width), value);
    }

    /**
     * Returns the width of this slot.
     *
     * @return the slot width, always positive
     */
    public Duration duration() {
        return Duration.between(start, end);
    }

    /**
     * Tests whether this slot ends exactly where the given slot starts, which is what makes two slots eligible to sit
     * in the same uninterrupted window.
     *
     * @param next the slot that would follow this one
     * @return {@code true} if the two slots touch without a gap
     */
    public boolean abuts(Slot next) {
        return end.equals(next.start);
    }

    /**
     * Tests whether the given instant falls inside this slot, start inclusive and end exclusive.
     *
     * @param instant the instant to test
     * @return {@code true} if the slot covers the instant
     */
    public boolean covers(Instant instant) {
        return !instant.isBefore(start) && instant.isBefore(end);
    }
}
