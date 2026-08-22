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
import java.util.Collections;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.level.LevelCounts;
import org.openhab.core.energy.level.LevelDerivation;
import org.openhab.core.energy.level.PlannedLevelSchedule;
import org.openhab.core.energy.window.SlotSeries;

/**
 * The fixed-count derivation: rank the slots by price, hand the cheapest ones to {@code OVERCAPACITY}, the next ones
 * to {@code ENCOURAGED}, the most expensive ones to {@code BLOCKED} and leave the rest {@code NORMAL}.
 * <p>
 * This is the algorithm the acceptance fixtures pin: {@code fixtures/dayahead-prices.csv} with counts 4/4/4 must
 * reproduce {@code fixtures/expected-planned-levels.csv} slot for slot.
 * <p>
 * Two behaviours the corpus does not specify and this class had to settle to be deterministic:
 * <ul>
 * <li><strong>Ties.</strong> Equal prices are ordered by the earlier slot, through
 * {@link SlotSeries#rankedIndices()}. The fixture data has no ties, but a real market day can.</li>
 * <li><strong>Counts that do not fit.</strong> When the three counts add up to more than the series has slots, the
 * cheap levels are filled first and {@code BLOCKED} takes only what is left, rather than overwriting a cheap slot or
 * throwing. A shorter-than-configured blocked band is the failure mode that keeps a demand schedulable.</li>
 * </ul>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class FixedCountLevelDerivation implements LevelDerivation {

    private final LevelCounts counts;

    /**
     * Creates the derivation.
     *
     * @param counts how many slots each non-normal level gets
     */
    public FixedCountLevelDerivation(LevelCounts counts) {
        this.counts = counts;
    }

    @Override
    public PlannedLevelSchedule derive(SlotSeries series) {
        int slotCount = series.size();
        List<Integer> ranked = series.rankedIndices();
        List<EnergyLevel> levels = new ArrayList<>(Collections.nCopies(slotCount, EnergyLevel.NORMAL));

        int overcapacity = Math.min(counts.overcapacitySlots(), slotCount);
        int encouraged = Math.min(counts.encouragedSlots(), slotCount - overcapacity);
        int blocked = Math.min(counts.blockedSlots(), slotCount - overcapacity - encouraged);

        for (int rank = 0; rank < overcapacity; rank++) {
            levels.set(ranked.get(rank), EnergyLevel.OVERCAPACITY);
        }
        for (int rank = overcapacity; rank < overcapacity + encouraged; rank++) {
            levels.set(ranked.get(rank), EnergyLevel.ENCOURAGED);
        }
        for (int rank = slotCount - blocked; rank < slotCount; rank++) {
            levels.set(ranked.get(rank), EnergyLevel.BLOCKED);
        }
        return LevelSchedules.build(series, levels);
    }

    /**
     * Returns the configured counts.
     *
     * @return the slot counts this derivation applies
     */
    public LevelCounts counts() {
        return counts;
    }

    @Override
    public String toString() {
        return "FixedCountLevelDerivation[" + counts + "]";
    }
}
