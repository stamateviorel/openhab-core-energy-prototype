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
import org.openhab.core.energy.level.internal.FixedCountLevelDerivation;
import org.openhab.core.energy.level.internal.PercentileLevelDerivation;
import org.openhab.core.energy.level.internal.SeasonalLevelDerivation;
import org.openhab.core.energy.window.SlotSeries;

/**
 * Turns a price series into a planned level schedule - the base level of the <em>Level derivation</em> requirement,
 * before any live escalation.
 * <p>
 * <strong>This interface is a seam, not a preference.</strong> The corpus asks whether percentile-based derivation
 * (the emsmanager reference) and fixed-hour-count derivation (storm.house, masipila) should both exist, or one be a
 * configuration of the other (change {@code define-energy-levels}, task 2.1 - undecided). Both are implemented here
 * and neither is a default, so a maintainer decision does not require rewriting anything: it either deletes one
 * factory method or keeps both.
 * <p>
 * Implementations are pure: same series in, same schedule out, no clock and no state. That is also what makes the
 * "user-configurable, including via rules" part of the requirement cheap - a rule swaps the whole immutable
 * derivation object rather than mutating counters underneath a running calculation.
 * <p>
 * Whether the resulting schedule is one site-global signal or one per domain is another open question (task 1.2);
 * nothing here assumes either, because a caller simply derives once per series it cares about.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface LevelDerivation {

    /**
     * Classifies every slot of the given series.
     *
     * @param series the price series to classify
     * @return one planned level per slot, in the series' own chronological order
     */
    PlannedLevelSchedule derive(SlotSeries series);

    /**
     * Returns the fixed-count derivation: the {@code n} cheapest slots become {@code OVERCAPACITY}, the next
     * {@code m} {@code ENCOURAGED}, the {@code k} most expensive {@code BLOCKED} and the rest {@code NORMAL}.
     * <p>
     * This is the variant the acceptance fixtures pin.
     *
     * @param counts how many slots each non-normal level gets
     * @return the derivation
     */
    static LevelDerivation fixedCounts(LevelCounts counts) {
        return new FixedCountLevelDerivation(counts);
    }

    /**
     * Returns the percentile derivation: level boundaries are price thresholds taken at the given quantiles of the
     * series, so slots at an identical price always share a level.
     *
     * @param percentiles what share of the distribution each non-normal level gets
     * @return the derivation
     */
    static LevelDerivation percentiles(LevelPercentiles percentiles) {
        return new PercentileLevelDerivation(percentiles);
    }

    /**
     * Returns a derivation that picks another derivation by the date of the series - the <em>Seasonal window
     * defaults</em> requirement.
     *
     * @param parameters the seasons, their derivations and the fallback outside them
     * @return the derivation
     */
    static LevelDerivation seasonal(SeasonalParameters parameters) {
        return new SeasonalLevelDerivation(parameters);
    }
}
