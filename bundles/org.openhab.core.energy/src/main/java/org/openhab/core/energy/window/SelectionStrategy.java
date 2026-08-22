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
import org.openhab.core.energy.window.internal.ConsecutiveWindowSelection;
import org.openhab.core.energy.window.internal.RankedSlotsSelection;

/**
 * Picks the slots a load should run in - the <em>Window strategies</em> requirement, which demands both
 * non-consecutive selection ("the cheapest slots totalling that duration") and consecutive windows ("one
 * uninterrupted stretch covering it"), "independent of the market time resolution".
 * <p>
 * <strong>A request carries a {@link Duration}.</strong> {@link #select(SlotSeries, WindowRequest, SlotSelection)}
 * is the one method an implementation writes, and {@link WindowRequest} is what it is asked in: a running time in the
 * primary form, a slot count in the retained "N cheapest slots" form. The slot-returning methods below are
 * conveniences over it and nothing more.
 * <p>
 * <strong>Every strategy answers a request it cannot meet with the best partial selection, never with
 * silence</strong>, and the answer carries what was asked for beside what was granted - that is
 * {@link WindowSelection}. The two strategies used to diverge here, the interruptible one returning a short set and
 * the uninterruptible one returning nothing, and that divergence is exactly what the corpus forbids: shortfall has to
 * behave identically everywhere so a caller can compare answers from either.
 * <p>
 * <strong>Windows start on slot boundaries.</strong> Both strategies begin a candidate at the start of a slot and
 * never part way through one. It is a stated restriction rather than an oversight: letting a load start mid-slot
 * would be a continuous optimization, and no requirement asks for one.
 * <p>
 * Where a strategy has whole candidate windows to compare, it compares them through the one shared calculation,
 * {@link WindowCost} - the same arithmetic wherever a window is costed, so two conforming callers cost the same
 * dishwasher identically. The non-consecutive strategy has no windows to compare: its answer is the price ranking
 * itself, and the ranking's tie-break lives in {@link SlotSeries#rankedIndices()}.
 * <p>
 * <strong>The search is run under the caller's weights, not only the costing.</strong>
 * {@code price-data} <em>Shared window calculations</em> asks for the selections to be made "over one costing
 * function {@code cost(window, weights)} ... that takes a declared load curve as its weights when one exists", and
 * its first scenario has a rule ask for the cheapest window <em>costing the curve as weights</em>. So the weights are
 * a parameter of {@link #select(SlotSeries, WindowRequest, SlotSelection, CostWeights)} rather than something applied
 * to an answer already chosen flat: a load that draws almost everything in its last hour prefers a different window
 * from a rectangular one, and choosing flat and costing shaped would return the rectangular load's answer with the
 * shaped load's price on it. The three-argument overloads are the flat case and stay the common one.
 * <p>
 * The non-consecutive strategy is the exception, and deliberately: its answer <em>is</em> the ranking of individual
 * slots, and a slot's position within the run is not known until the set is chosen, so weighting it would be
 * circular. It takes the parameter and does not use it, which the corpus supports - the ranking-based answer is
 * defined on the slots rather than on a placement.
 * <p>
 * Independence from the market resolution is taken literally: no implementation in this package converts between
 * hours and slots, assumes slots are the same width, or assumes they touch. A window is uninterrupted when the slots
 * inside it actually abut, which is a property of the data rather than of a configured slot length.
 * <p>
 * Every method takes an {@code excluded} selection. That is what lets a second load be scheduled around a first one -
 * the boiler fixture is exactly "the next three best slots that do not overlap the heating schedule". It is a
 * mechanism, not a policy: which load gets scheduled first is the engine's priority ordering, and the shared power
 * budget behind it belongs to the (wave-3) {@code grid-constraints} capability.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface SelectionStrategy {

    /**
     * Picks the slots answering one window request, ranking candidates under the load's own shape.
     *
     * @param series the series to pick from
     * @param request what the load needs - a running time, or a number of slots
     * @param excluded slots that are not available
     * @param weights the load's shape over its own running time, {@link CostWeights#flat()} for a rectangular load
     * @return the chosen slots, together with the request and how much of it they cover; the best partial answer when
     *         the request cannot be met in full
     */
    WindowSelection select(SlotSeries series, WindowRequest request, SlotSelection excluded, CostWeights weights);

    /**
     * Picks the slots answering one window request, for a load with no declared shape.
     *
     * @param series the series to pick from
     * @param request what the load needs - a running time, or a number of slots
     * @param excluded slots that are not available
     * @return the chosen slots, together with the request and how much of it they cover
     */
    default WindowSelection select(SlotSeries series, WindowRequest request, SlotSelection excluded) {
        return select(series, request, excluded, CostWeights.flat());
    }

    /**
     * Picks the slots answering one window request under the load's own shape, with nothing excluded.
     *
     * @param series the series to pick from
     * @param request what the load needs
     * @param weights the load's shape over its own running time
     * @return the chosen slots, together with the request and how much of it they cover
     */
    default WindowSelection select(SlotSeries series, WindowRequest request, CostWeights weights) {
        return select(series, request, SlotSelection.empty(), weights);
    }

    /**
     * Picks the slots answering one window request, with nothing excluded.
     *
     * @param series the series to pick from
     * @param request what the load needs
     * @return the chosen slots, together with the request and how much of it they cover
     */
    default WindowSelection select(SlotSeries series, WindowRequest request) {
        return select(series, request, SlotSelection.empty());
    }

    /**
     * Picks the given number of slots.
     *
     * @param series the series to pick from
     * @param slotCount how many slots the load needs
     * @param excluded slots that are not available
     * @return the chosen slots, or as many of them as the series offers
     * @throws IllegalArgumentException if {@code slotCount} is negative
     */
    default SlotSelection select(SlotSeries series, int slotCount, SlotSelection excluded) {
        return select(series, WindowRequest.ofSlots(slotCount), excluded).slots();
    }

    /**
     * Picks enough slots to cover the given running time.
     * <p>
     * The last slot may be used only partially: a load needing 90 minutes on an hourly series occupies two slots, the
     * second of them for half its width.
     *
     * @param series the series to pick from
     * @param runningTime how long the load needs to run
     * @param excluded slots that are not available
     * @return the chosen slots, or the best partial cover the series offers
     * @throws IllegalArgumentException if {@code runningTime} is negative
     */
    default SlotSelection selectForDuration(SlotSeries series, Duration runningTime, SlotSelection excluded) {
        return select(series, WindowRequest.ofDuration(runningTime), excluded).slots();
    }

    /**
     * Picks the given number of slots with nothing excluded.
     *
     * @param series the series to pick from
     * @param slotCount how many slots the load needs
     * @return the chosen slots
     */
    default SlotSelection select(SlotSeries series, int slotCount) {
        return select(series, slotCount, SlotSelection.empty());
    }

    /**
     * Picks enough slots to cover the given running time with nothing excluded.
     *
     * @param series the series to pick from
     * @param runningTime how long the load needs to run
     * @return the chosen slots
     */
    default SlotSelection selectForDuration(SlotSeries series, Duration runningTime) {
        return selectForDuration(series, runningTime, SlotSelection.empty());
    }

    /**
     * Returns the non-consecutive strategy taking the <strong>best</strong> available slots, wherever they are.
     * <p>
     * Best is read through the series' own {@link SeriesSense}: the cheapest hours of a price series, the sunniest
     * hours of a photovoltaic forecast. Ties fall back to the earlier slot, as everywhere in this package. A request
     * for more than the series offers yields every available slot, as the best partial answer.
     *
     * @return the strategy
     */
    static SelectionStrategy bestSlots() {
        return new RankedSlotsSelection(false);
    }

    /**
     * Returns the non-consecutive strategy taking the <strong>worst</strong> available slots - the most expensive
     * hours of a price series, the dirtiest hours of a carbon series.
     * <p>
     * {@code price-data} <em>Shared window calculations</em> requires this alongside the cheapest selection. The
     * tie-break does not flip with the direction: equal values still go to the earlier slot.
     *
     * @return the strategy
     */
    static SelectionStrategy worstSlots() {
        return new RankedSlotsSelection(true);
    }

    /**
     * Returns the consecutive-window strategy: the <strong>best</strong> uninterrupted run of slots.
     * <p>
     * A window must be gap-free and free of excluded slots, and it starts on a slot boundary. Where no window covers
     * the whole request, the longest gap-free run that does fit is returned as the best partial answer - the answer
     * says how much it granted, and a caller that genuinely cannot use a short run reads
     * {@link WindowSelection#isComplete()} and declines. Contiguity is never traded away for length: a partial answer
     * is still one uninterrupted stretch.
     *
     * @return the strategy
     */
    static SelectionStrategy bestConsecutiveWindow() {
        return new ConsecutiveWindowSelection(false);
    }

    /**
     * Returns the consecutive-window strategy looking for the <strong>worst</strong> uninterrupted run - the window a
     * load should be kept out of.
     *
     * @return the strategy
     */
    static SelectionStrategy worstConsecutiveWindow() {
        return new ConsecutiveWindowSelection(true);
    }

    /**
     * Returns {@link #bestSlots()} under its price-plane name.
     * <p>
     * The neutral names are the primary ones because the same calculation ranks carbon intensity and photovoltaic
     * output; these two aliases exist because the level plane, the price plane and every rule written against them
     * genuinely are talking about prices, and "cheapest" is the word a user of those planes reads. Keeping both is
     * the corpus' own instruction (the objective-neutral naming pass, {@code define-optimization-objectives}
     * design.md section 3): rename the calculation, keep the price plane's vocabulary where the subject really is a
     * price.
     *
     * @return the strategy
     */
    static SelectionStrategy cheapestSlots() {
        return bestSlots();
    }

    /**
     * Returns {@link #bestConsecutiveWindow()} under its price-plane name.
     *
     * @return the strategy
     */
    static SelectionStrategy consecutiveWindow() {
        return bestConsecutiveWindow();
    }
}
