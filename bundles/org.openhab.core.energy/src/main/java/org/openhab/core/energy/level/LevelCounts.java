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
package org.openhab.core.energy.level;

import java.time.Duration;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.window.SlotSeries;

/**
 * How many slots each non-normal level gets, for the fixed-count derivation.
 * <p>
 * The requirement phrases this as a number of <em>hours</em> ("a user configuration of 4 overcapacity, 4 low-price and
 * 4 blocked hours"), which is only the same thing as a number of slots on an hourly market. On a 15-minute market
 * "4 overcapacity hours" could equally mean 4 slots or 16, and the corpus never says which. This type therefore
 * counts <strong>slots</strong>, and {@link #ofDurations(SlotSeries, Duration, Duration, Duration)} converts from
 * wall-clock durations against a concrete series when the caller wants the other reading - refusing to guess on a
 * series that mixes slot widths.
 *
 * @param overcapacitySlots how many of the cheapest slots become {@code OVERCAPACITY}
 * @param encouragedSlots how many of the next-cheapest slots become {@code ENCOURAGED}
 * @param blockedSlots how many of the most expensive slots become {@code BLOCKED}
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record LevelCounts(int overcapacitySlots, int encouragedSlots, int blockedSlots) {

    /**
     * Validates the counts.
     *
     * @throws IllegalArgumentException if any count is negative
     */
    public LevelCounts {
        requireNotNegative(overcapacitySlots, "overcapacitySlots");
        requireNotNegative(encouragedSlots, "encouragedSlots");
        requireNotNegative(blockedSlots, "blockedSlots");
    }

    private static void requireNotNegative(int count, String name) {
        if (count < 0) {
            throw new IllegalArgumentException(name + " must not be negative but was " + count);
        }
    }

    /**
     * Creates a set of slot counts.
     *
     * @param overcapacitySlots how many of the cheapest slots become {@code OVERCAPACITY}
     * @param encouragedSlots how many of the next-cheapest slots become {@code ENCOURAGED}
     * @param blockedSlots how many of the most expensive slots become {@code BLOCKED}
     * @return the counts
     */
    public static LevelCounts of(int overcapacitySlots, int encouragedSlots, int blockedSlots) {
        return new LevelCounts(overcapacitySlots, encouragedSlots, blockedSlots);
    }

    /**
     * Converts wall-clock durations into slot counts against a concrete series.
     * <p>
     * This is the "4 overcapacity <em>hours</em>" reading of the requirement. It only works on a series with a single
     * slot width, and it refuses durations that are not a whole multiple of that width rather than inventing a
     * rounding rule the corpus never states.
     *
     * @param series the series the counts will be applied to
     * @param overcapacity how much of the cheapest time becomes {@code OVERCAPACITY}
     * @param encouraged how much of the next-cheapest time becomes {@code ENCOURAGED}
     * @param blocked how much of the most expensive time becomes {@code BLOCKED}
     * @return the equivalent slot counts
     * @throws IllegalArgumentException if the series mixes slot widths, or a duration is negative or not a whole
     *             multiple of the slot width
     */
    public static LevelCounts ofDurations(SlotSeries series, Duration overcapacity, Duration encouraged,
            Duration blocked) {
        Duration width = series.uniformSlotDuration().orElseThrow(() -> new IllegalArgumentException(
                "durations can only be converted to slot counts on a series with a single slot width"));
        return new LevelCounts(slotsFor(overcapacity, width, "overcapacity"), slotsFor(encouraged, width, "encouraged"),
                slotsFor(blocked, width, "blocked"));
    }

    private static int slotsFor(Duration target, Duration width, String name) {
        if (target.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative but was " + target);
        }
        long targetNanos = target.toNanos();
        long widthNanos = width.toNanos();
        if (targetNanos % widthNanos != 0) {
            throw new IllegalArgumentException(
                    name + " (" + target + ") is not a whole multiple of the slot width (" + width + ")");
        }
        return Math.toIntExact(targetNanos / widthNanos);
    }

    /**
     * Returns how many slots are assigned a non-normal level in total.
     *
     * @return the sum of the three counts
     */
    public int total() {
        return overcapacitySlots + encouragedSlots + blockedSlots;
    }
}
