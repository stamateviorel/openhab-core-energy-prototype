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
package org.openhab.core.energy.window.internal;

import java.time.Duration;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.window.CostWeights;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.energy.window.WindowCost;
import org.openhab.core.energy.window.WindowSelection;

/**
 * The shared window calculation: walk the window's slots in time order, charge each one for the time the selection
 * says the load spends in it, and weight that time by the load's own shape over the slice of its run the slot covers.
 * <p>
 * The integration is LEFT-Riemann over the slices the slot boundaries cut the run into, which is the corpus' stated
 * rule and the reason two implementations cost the same load identically. Under flat weights every factor is one and
 * the result collapses to price times time - the arithmetic wave 1 has always used, unchanged bit for bit.
 * <p>
 * <strong>The per-slot time comes from {@link WindowSelection#allocation()} and is never re-derived here.</strong>
 * This class used to fill slots in time order until the granted time ran out, which silently assumed that the slot a
 * load leaves part way through is the last one in time. That holds for a consecutive run and is false for a ranked,
 * non-consecutive one, whose part-used slot is its worst-ranked slot and can sit anywhere: on hourly prices
 * {@code [1, 100, 2]} a two-and-a-half hour interruptible request was costed at 367 200 against a plan that actually
 * costs 190 800. Which slot is short-changed is the strategy's decision and only the strategy knows it.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class LeftRiemannWindowCost implements WindowCost {

    @Override
    public double cost(SlotSeries series, WindowSelection window, CostWeights weights) {
        double totalSeconds = toSeconds(window.granted());
        if (totalSeconds <= 0) {
            return 0;
        }
        double cost = 0;
        double elapsed = 0;
        for (int position = 0; position < window.indices().size(); position++) {
            double used = toSeconds(window.allocation().get(position));
            if (used <= 0) {
                continue;
            }
            Slot slot = series.slotAt(window.indices().get(position));
            double draw = weights.meanDrawOver(elapsed / totalSeconds, (elapsed + used) / totalSeconds);
            cost += slot.value() * used * draw;
            elapsed += used;
        }
        return cost;
    }

    /**
     * Returns the duration-weighted mean value of a window, which is how candidates of a slot-count request are
     * compared: a window is judged on what it costs per unit of time rather than on how much time it happens to
     * span.
     *
     * @param series the series the window's slot indices refer to
     * @param window the chosen window
     * @return the duration-weighted mean value, or {@link Double#NaN} for a window covering no time - which no caller
     *         ever passes, because a candidate covering no time is never enumerated
     */
    public double meanValue(SlotSeries series, WindowSelection window) {
        return meanValue(series, window, CostWeights.flat());
    }

    /**
     * Returns the duration-weighted mean value of a window under the load's own shape.
     *
     * @param series the series the window's slot indices refer to
     * @param window the chosen window
     * @param weights the load's shape over its own running time
     * @return the duration-weighted mean value, or {@link Double#NaN} for a window covering no time
     */
    public double meanValue(SlotSeries series, WindowSelection window, CostWeights weights) {
        double seconds = toSeconds(window.granted());
        return seconds <= 0 ? Double.NaN : cost(series, window, weights) / seconds;
    }

    private static double toSeconds(Duration duration) {
        return duration.toNanos() / 1_000_000_000d;
    }

    @Override
    public String toString() {
        return "LeftRiemannWindowCost";
    }
}
