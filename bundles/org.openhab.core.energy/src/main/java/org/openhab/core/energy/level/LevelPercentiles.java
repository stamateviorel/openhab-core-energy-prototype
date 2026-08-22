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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * What share of the price distribution each non-normal level gets, for the percentile derivation.
 * <p>
 * This is the second of the two derivation styles the corpus has not chosen between (change
 * {@code define-energy-levels}, task 2.1). It differs from {@link LevelCounts} only when prices repeat: a percentile
 * threshold is a price, so every slot at that price lands on the same level, whereas a fixed count cuts the ranking
 * at a slot boundary. On a tie-free series the two agree exactly.
 *
 * @param overcapacityFraction the share of the cheapest slots that becomes {@code OVERCAPACITY}, 0..1
 * @param encouragedFraction the share of the next-cheapest slots that becomes {@code ENCOURAGED}, 0..1
 * @param blockedFraction the share of the most expensive slots that becomes {@code BLOCKED}, 0..1
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record LevelPercentiles(double overcapacityFraction, double encouragedFraction, double blockedFraction) {

    /**
     * Validates the fractions.
     *
     * @throws IllegalArgumentException if a fraction is outside 0..1, not finite, or the three together exceed the
     *             whole distribution
     */
    public LevelPercentiles {
        requireFraction(overcapacityFraction, "overcapacityFraction");
        requireFraction(encouragedFraction, "encouragedFraction");
        requireFraction(blockedFraction, "blockedFraction");
        if (overcapacityFraction + encouragedFraction + blockedFraction > 1.0) {
            throw new IllegalArgumentException("the three fractions must not add up to more than 1 but were "
                    + overcapacityFraction + " + " + encouragedFraction + " + " + blockedFraction);
        }
    }

    private static void requireFraction(double fraction, String name) {
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) {
            throw new IllegalArgumentException(name + " must be a finite fraction between 0 and 1 but was " + fraction);
        }
    }

    /**
     * Creates a set of percentile fractions.
     *
     * @param overcapacityFraction the share of the cheapest slots that becomes {@code OVERCAPACITY}
     * @param encouragedFraction the share of the next-cheapest slots that becomes {@code ENCOURAGED}
     * @param blockedFraction the share of the most expensive slots that becomes {@code BLOCKED}
     * @return the fractions
     */
    public static LevelPercentiles of(double overcapacityFraction, double encouragedFraction, double blockedFraction) {
        return new LevelPercentiles(overcapacityFraction, encouragedFraction, blockedFraction);
    }
}
