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
import org.openhab.core.energy.window.SlotSelection;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.energy.window.WindowRequest;
import org.openhab.core.energy.window.WindowSelection;

/**
 * The non-consecutive window strategy: take the best available slots, wherever in the series they sit.
 * <p>
 * <strong>"Best" is read off the series and off one flag.</strong> The order comes from
 * {@link SlotSeries#rankedIndices()} or {@link SlotSeries#worstRankedIndices()}, both of which already answer through
 * the series' own {@link org.openhab.core.energy.window.SeriesSense}. So the cheapest hours of a price series, the
 * most expensive hours of the same series, the sunniest hours of a photovoltaic forecast and the dirtiest hours of a
 * carbon series are all this one class - which is what {@code price-data} <em>Shared window calculations</em> means
 * by "at minimum the cheapest and most expensive ... implemented once".
 * <p>
 * This reproduces the two control fixtures. {@code fixtures/expected-heating-control.csv} is the eight cheapest slots
 * of the fixture day; {@code fixtures/expected-boiler-control.csv} is the next three cheapest with those eight passed
 * in as excluded.
 * <p>
 * Asking for more than the series can offer yields everything available - the best partial answer - and the answer
 * says so: for an interruptible load, running in every cheap slot there is remains the best outcome, and the
 * shortfall is readable as the request against {@code granted} rather than left for the caller to work out.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class RankedSlotsSelection implements SelectionStrategy {

    private final boolean worst;

    /**
     * Creates the strategy.
     *
     * @param worst {@code true} to take the worst slots rather than the best ones
     */
    public RankedSlotsSelection(boolean worst) {
        this.worst = worst;
    }

    /**
     * {@inheritDoc}
     * <p>
     * <strong>The weights are accepted and not used, on purpose.</strong> This strategy's answer is the ranking of
     * individual slots, and a slot's position within the load's run is not known until the whole set has been chosen,
     * so weighting the ranking by the run's shape would be circular. The corpus defines the non-consecutive answer on
     * the slots rather than on a placement; where the shape does matter - which slot is only partly used - it is
     * answered by the allocation this returns.
     */
    @Override
    public WindowSelection select(SlotSeries series, WindowRequest request, SlotSelection excluded,
            CostWeights weights) {
        return switch (request) {
            case WindowRequest.ForSlots forSlots -> bySlotCount(series, forSlots, excluded);
            case WindowRequest.ForDuration forDuration -> byRunningTime(series, forDuration, excluded);
        };
    }

    private List<Integer> order(SlotSeries series) {
        return worst ? series.worstRankedIndices() : series.rankedIndices();
    }

    private WindowSelection bySlotCount(SlotSeries series, WindowRequest.ForSlots request, SlotSelection excluded) {
        List<Integer> chosen = new ArrayList<>(request.slotCount());
        for (Integer index : order(series)) {
            if (chosen.size() >= request.slotCount()) {
                break;
            }
            if (!excluded.contains(index)) {
                chosen.add(index);
            }
        }
        SlotSelection slots = new SlotSelection(chosen);
        // every chosen slot is used whole, so the allocation is simply each slot's own width
        List<Duration> allocation = new ArrayList<>(slots.size());
        for (Integer index : slots.indices()) {
            allocation.add(series.slotAt(index).duration());
        }
        return new WindowSelection(request, slots, slots.coveredDuration(series), allocation);
    }

    /**
     * Takes slots in rank order until the requested running time is covered.
     * <p>
     * The slot the load leaves part way through is the <em>last one taken</em>, which is the worst-ranked of the
     * chosen set and may sit anywhere in time. That is stated in the answer rather than left to be guessed at: a
     * costing function that filled slots in time order would charge the shortfall to whichever slot happened to be
     * latest, which on a day with one expensive hour is a different number entirely.
     *
     * @param series the series to choose from
     * @param request the requested running time
     * @param excluded slots that are not available
     * @return the answer, carrying its own allocation
     */
    private WindowSelection byRunningTime(SlotSeries series, WindowRequest.ForDuration request,
            SlotSelection excluded) {
        List<Integer> chosen = new ArrayList<>();
        List<Duration> used = new ArrayList<>();
        Duration remaining = request.runningTime();
        for (Integer index : order(series)) {
            if (!remaining.isPositive()) {
                break;
            }
            if (excluded.contains(index)) {
                continue;
            }
            Duration width = series.slotAt(index).duration();
            chosen.add(index);
            used.add(remaining.compareTo(width) <= 0 ? remaining : width);
            remaining = remaining.minus(width);
        }
        Duration granted = remaining.isNegative() ? request.runningTime() : request.runningTime().minus(remaining);
        SlotSelection slots = new SlotSelection(chosen);
        // SlotSelection normalizes to ascending time, so the rank-ordered allocation has to be reordered with it
        List<Duration> allocation = new ArrayList<>(slots.size());
        for (Integer index : slots.indices()) {
            allocation.add(used.get(chosen.indexOf(index)));
        }
        return new WindowSelection(request, slots, granted, allocation);
    }

    @Override
    public String toString() {
        return worst ? "RankedSlotsSelection[worst]" : "RankedSlotsSelection[best]";
    }
}
