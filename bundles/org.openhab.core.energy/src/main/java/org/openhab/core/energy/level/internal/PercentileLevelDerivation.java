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
package org.openhab.core.energy.level.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.level.LevelDerivation;
import org.openhab.core.energy.level.LevelPercentiles;
import org.openhab.core.energy.level.PlannedLevelSchedule;
import org.openhab.core.energy.window.SlotSeries;

/**
 * The percentile derivation: cut the price distribution at quantiles instead of at slot counts.
 * <p>
 * Thresholds use the nearest-rank method, and classification then compares <em>prices</em> against them. That is the
 * one behavioural difference from {@link FixedCountLevelDerivation}: slots sharing a price always share a level here,
 * so a band can come out wider than its nominal share. On a tie-free series - such as the acceptance fixture - the
 * two derivations produce identical schedules, which is worth knowing while the corpus decides whether it wants both
 * (change {@code define-energy-levels}, task 2.1).
 * <p>
 * As in the fixed-count derivation the cheap bands win over the blocked band where the two would overlap.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class PercentileLevelDerivation implements LevelDerivation {

    /**
     * Guards the nearest-rank ceiling against a fraction that lands a hair above a whole rank through binary
     * rounding, e.g. {@code 24 * (1.0 / 6)}.
     */
    private static final double RANK_EPSILON = 1e-9;

    private final LevelPercentiles percentiles;

    /**
     * Creates the derivation.
     *
     * @param percentiles what share of the distribution each non-normal level gets
     */
    public PercentileLevelDerivation(LevelPercentiles percentiles) {
        this.percentiles = percentiles;
    }

    @Override
    public PlannedLevelSchedule derive(SlotSeries series) {
        int slotCount = series.size();
        List<Integer> ranked = series.rankedIndices();
        List<Double> ascendingPrices = new ArrayList<>(slotCount);
        for (Integer index : ranked) {
            ascendingPrices.add(series.slotAt(index).value());
        }

        OptionalDouble overcapacityCeiling = lowerThreshold(ascendingPrices, percentiles.overcapacityFraction());
        OptionalDouble encouragedCeiling = lowerThreshold(ascendingPrices,
                percentiles.overcapacityFraction() + percentiles.encouragedFraction());
        OptionalDouble blockedFloor = upperThreshold(ascendingPrices, percentiles.blockedFraction());

        List<EnergyLevel> levels = new ArrayList<>(slotCount);
        for (int index = 0; index < slotCount; index++) {
            double price = series.slotAt(index).value();
            if (isAtMost(price, overcapacityCeiling)) {
                levels.add(EnergyLevel.OVERCAPACITY);
            } else if (isAtMost(price, encouragedCeiling)) {
                levels.add(EnergyLevel.ENCOURAGED);
            } else if (isAtLeast(price, blockedFloor)) {
                levels.add(EnergyLevel.BLOCKED);
            } else {
                levels.add(EnergyLevel.NORMAL);
            }
        }
        return LevelSchedules.build(series, levels);
    }

    /**
     * Returns the price at the given quantile counted from the cheap end.
     *
     * @param ascendingPrices every price of the series, cheapest first
     * @param fraction the share of the distribution below the threshold
     * @return the threshold price, or an empty result when the fraction is zero
     */
    private static OptionalDouble lowerThreshold(List<Double> ascendingPrices, double fraction) {
        if (fraction <= 0) {
            return OptionalDouble.empty();
        }
        int rank = nearestRank(ascendingPrices.size(), fraction);
        return OptionalDouble.of(ascendingPrices.get(rank - 1));
    }

    /**
     * Returns the price at the given quantile counted from the expensive end.
     *
     * @param ascendingPrices every price of the series, cheapest first
     * @param fraction the share of the distribution above the threshold
     * @return the threshold price, or an empty result when the fraction is zero
     */
    private static OptionalDouble upperThreshold(List<Double> ascendingPrices, double fraction) {
        if (fraction <= 0) {
            return OptionalDouble.empty();
        }
        int rank = nearestRank(ascendingPrices.size(), fraction);
        return OptionalDouble.of(ascendingPrices.get(ascendingPrices.size() - rank));
    }

    private static int nearestRank(int slotCount, double fraction) {
        int rank = (int) Math.ceil(fraction * slotCount - RANK_EPSILON);
        return Math.min(Math.max(rank, 1), slotCount);
    }

    private static boolean isAtMost(double price, OptionalDouble threshold) {
        return threshold.isPresent() && price <= threshold.getAsDouble();
    }

    private static boolean isAtLeast(double price, OptionalDouble threshold) {
        return threshold.isPresent() && price >= threshold.getAsDouble();
    }

    /**
     * Returns the configured fractions.
     *
     * @return the percentile fractions this derivation applies
     */
    public LevelPercentiles percentiles() {
        return percentiles;
    }

    @Override
    public String toString() {
        return "PercentileLevelDerivation[" + percentiles + "]";
    }
}
