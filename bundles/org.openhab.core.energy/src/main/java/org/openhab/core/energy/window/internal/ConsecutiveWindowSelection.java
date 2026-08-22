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
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.window.CostWeights;
import org.openhab.core.energy.window.SelectionStrategy;
import org.openhab.core.energy.window.SeriesSense;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSelection;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.energy.window.WindowRequest;
import org.openhab.core.energy.window.WindowSelection;

/**
 * The consecutive window strategy: the best uninterrupted run, for loads that must not be interrupted.
 * <p>
 * <strong>"Best" is a function of two things</strong>: the series' own
 * {@link org.openhab.core.energy.window.SeriesSense}, and whether the caller asked for the best window or the worst
 * one. Combined they say whether a higher integral or a lower one wins, and that single derived flag is the only
 * difference between finding the cheapest three hours of a price day, the most expensive three hours of the same day,
 * and the sunniest three hours of a photovoltaic forecast.
 * <p>
 * Everything here is derived from the slot boundaries in the data, never from an assumed slot width. "Uninterrupted"
 * means each slot in the run ends exactly where the next begins, so a gap in the series or an excluded slot simply
 * ends a run - which is also what makes the 15-minute scenario work without a single special case: two uninterrupted
 * hours are whatever run of slots covers two hours.
 * <p>
 * <strong>A window starts on a slot boundary.</strong> Candidates are enumerated one per slot start; a load never
 * begins part way through a slot. That is a stated restriction of the corpus, not an approximation.
 * <p>
 * Candidates are ranked on three keys, in this order:
 * <ol>
 * <li><strong>How much of the request they grant.</strong> A run covering the whole request always beats a shorter
 * one, which is what makes the full-request case behave exactly as it always has.</li>
 * <li><strong>What they cost</strong>, through {@link LeftRiemannWindowCost} - the one shared calculation. By running
 * time every surviving candidate covers the same time, so total cost is the comparison; by slot count runs can differ
 * in total length on a series that mixes widths, so they are compared on duration-weighted mean price, which is the
 * only comparison that does not reward a window for being shorter.</li>
 * <li><strong>The earlier start</strong>, matching the tie-break used everywhere else in this package.</li>
 * </ol>
 * <p>
 * <strong>Shortfall is answered, never withheld.</strong> Where no run covers the whole request, the longest run that
 * does fit is returned, carrying what was asked beside what was granted. This class used to return nothing there,
 * which made an uninterruptible load degrade differently from an interruptible one; the corpus now requires the two
 * to behave identically. What is never traded away is contiguity: a partial answer is still one uninterrupted
 * stretch, so a caller that cannot use a short run reads {@link WindowSelection#isComplete()} and declines rather
 * than being handed a scattered set it must not run in.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ConsecutiveWindowSelection implements SelectionStrategy {

    private final LeftRiemannWindowCost cost = new LeftRiemannWindowCost();
    private final boolean worst;

    /**
     * Creates the strategy.
     *
     * @param worst {@code true} to look for the worst uninterrupted run rather than the best one
     */
    public ConsecutiveWindowSelection(boolean worst) {
        this.worst = worst;
    }

    @Override
    public WindowSelection select(SlotSeries series, WindowRequest request, SlotSelection excluded,
            CostWeights weights) {
        return switch (request) {
            case WindowRequest.ForSlots forSlots -> bySlotCount(series, forSlots, excluded, weights);
            case WindowRequest.ForDuration forDuration -> byRunningTime(series, forDuration, excluded, weights);
        };
    }

    /**
     * Tells whether a larger integral is the better one for this series and this direction.
     *
     * @param series the series being searched
     * @return {@code true} when a window with the higher cost wins
     */
    private boolean prefersHigher(SlotSeries series) {
        return (series.sense() == SeriesSense.HIGHER_IS_BETTER) != worst;
    }

    private WindowSelection bySlotCount(SlotSeries series, WindowRequest.ForSlots request, SlotSelection excluded,
            CostWeights weights) {
        WindowSelection best = WindowSelection.none(request);
        if (request.slotCount() == 0) {
            return best;
        }
        for (int start = 0; start < series.size(); start++) {
            List<Integer> run = runFrom(series, excluded, start, request.slotCount());
            if (run.isEmpty()) {
                continue;
            }
            SlotSelection slots = new SlotSelection(run);
            WindowSelection candidate = WindowSelection.fillingInTimeOrder(request, slots,
                    slots.coveredDuration(series), series);
            if (isBetterBySlotCount(series, candidate, best, weights)) {
                best = candidate;
            }
        }
        return best;
    }

    private WindowSelection byRunningTime(SlotSeries series, WindowRequest.ForDuration request, SlotSelection excluded,
            CostWeights weights) {
        WindowSelection best = WindowSelection.none(request);
        if (!request.runningTime().isPositive()) {
            return best;
        }
        for (int start = 0; start < series.size(); start++) {
            List<Integer> run = new ArrayList<>();
            Duration remaining = request.runningTime();
            int index = start;
            while (remaining.isPositive() && index < series.size() && isUsable(series, excluded, start, index)) {
                Slot slot = series.slotAt(index);
                remaining = remaining.minus(min(slot.duration(), remaining));
                run.add(index);
                index++;
            }
            if (run.isEmpty()) {
                continue;
            }
            // a run is contiguous and starts on a boundary, so the slot the load leaves part way through is the last
            // one in time - which is exactly what filling in time order means
            WindowSelection candidate = WindowSelection.fillingInTimeOrder(request, new SlotSelection(run),
                    request.runningTime().minus(remaining), series);
            if (isBetterByRunningTime(series, candidate, best, weights)) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Returns the longest uninterrupted, unexcluded run starting at {@code start}, capped at {@code maximumSlots}.
     *
     * @param series the series to walk
     * @param excluded slots that are not available
     * @param start the slot the run starts at
     * @param maximumSlots the most slots the run may hold
     * @return the slot indices of the run, empty if the starting slot itself is unavailable
     */
    private static List<Integer> runFrom(SlotSeries series, SlotSelection excluded, int start, int maximumSlots) {
        List<Integer> run = new ArrayList<>(maximumSlots);
        for (int index = start; index < series.size() && run.size() < maximumSlots; index++) {
            if (!isUsable(series, excluded, start, index)) {
                break;
            }
            run.add(index);
        }
        return run;
    }

    private static boolean isUsable(SlotSeries series, SlotSelection excluded, int start, int index) {
        if (excluded.contains(index)) {
            return false;
        }
        return index == start || series.slotAt(index - 1).abuts(series.slotAt(index));
    }

    private boolean isBetterBySlotCount(SlotSeries series, WindowSelection candidate, WindowSelection best,
            CostWeights weights) {
        if (candidate.size() != best.size()) {
            return candidate.size() > best.size();
        }
        return wins(cost.meanValue(series, candidate, weights), cost.meanValue(series, best, weights), series);
    }

    private boolean isBetterByRunningTime(SlotSeries series, WindowSelection candidate, WindowSelection best,
            CostWeights weights) {
        int byGranted = candidate.granted().compareTo(best.granted());
        if (byGranted != 0) {
            return byGranted > 0;
        }
        return wins(cost.cost(series, candidate, weights), cost.cost(series, best, weights), series);
    }

    /**
     * Compares two figures under the direction this search is running in. Candidates are enumerated from the earliest
     * slot start onwards and a dead heat is never taken, so the earlier window keeps a tie - the same tie-break the
     * ranking uses.
     *
     * @param candidate the candidate's figure
     * @param incumbent the incumbent's figure
     * @param series the series being searched
     * @return {@code true} if the candidate beats the incumbent outright
     */
    private boolean wins(double candidate, double incumbent, SlotSeries series) {
        return prefersHigher(series) ? candidate > incumbent : candidate < incumbent;
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    @Override
    public String toString() {
        return worst ? "ConsecutiveWindowSelection[worst]" : "ConsecutiveWindowSelection[best]";
    }
}
