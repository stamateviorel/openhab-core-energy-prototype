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
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * What a {@link SelectionStrategy} answered: the slots it chose, together with what it was asked for, how much of
 * that it could grant, and how that granted time is spread across the chosen slots.
 * <p>
 * <strong>A request that cannot be met in full is answered with the best partial selection, never with
 * silence.</strong>
 * A boiler that needs three hours and can get two gets two, and the answer says so - {@code request} carries the
 * three hours, {@code granted} the two. That rule is the same for both strategies: an uninterruptible load used to
 * get nothing where an interruptible one got a partial set, and that divergence is exactly what the corpus now
 * forbids.
 * <p>
 * <strong>Why the allocation is stated rather than derived.</strong> A selection can cover more time than it grants -
 * take three hourly slots for a two-and-a-half hour load and one of them is only half used - and <em>which</em> slot
 * is the part-used one is the strategy's decision, not a property of the slots. A consecutive run always trails off
 * in its last slot; a ranked, non-consecutive selection trails off in its <em>worst-ranked</em> slot, which may sit
 * anywhere in time. {@link WindowCost} used to re-derive the allocation by filling slots in time order, which costed
 * the ranked strategy's answers as if they had been chosen consecutively - by a factor of nearly two on a day with
 * one expensive hour. The corpus asks for one costing function under which "two conforming implementations cost the
 * same dishwasher identically"; that is only true if the answer says how the load is spread, so it does.
 *
 * @param request what the caller asked for
 * @param slots the slots the strategy chose, ascending and distinct
 * @param granted how much of the requested running time those slots actually cover - for a slot-count request, the
 *            total duration of the chosen slots
 * @param allocation how much of the granted time the load spends in each chosen slot, positionally aligned with
 *            {@link SlotSelection#indices()} and summing to {@code granted}
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record WindowSelection(WindowRequest request, SlotSelection slots, Duration granted, List<Duration> allocation) {

    /**
     * Validates the answer and makes the allocation immutable.
     *
     * @throws IllegalArgumentException if the granted time is negative, or the allocation does not describe exactly
     *             the chosen slots and exactly the granted time
     */
    public WindowSelection {
        if (granted.isNegative()) {
            throw new IllegalArgumentException("granted must not be negative but was " + granted);
        }
        if (allocation.size() != slots.size()) {
            throw new IllegalArgumentException(
                    "the allocation holds " + allocation.size() + " entries for " + slots.size() + " chosen slots");
        }
        Duration total = Duration.ZERO;
        for (Duration spent : allocation) {
            if (spent.isNegative()) {
                throw new IllegalArgumentException("a slot cannot be allocated negative time but one was " + spent);
            }
            total = total.plus(spent);
        }
        if (!total.equals(granted)) {
            throw new IllegalArgumentException("the allocation sums to " + total + " but " + granted + " was granted");
        }
        allocation = List.copyOf(allocation);
    }

    /**
     * Returns the answer to a request nothing could be found for.
     *
     * @param request what the caller asked for
     * @return an answer holding no slot and granting no time
     */
    public static WindowSelection none(WindowRequest request) {
        return new WindowSelection(request, SlotSelection.empty(), Duration.ZERO, List.of());
    }

    /**
     * Returns an answer whose granted time is spread over the chosen slots in time order, filling each one before
     * moving to the next.
     * <p>
     * This is what a <em>consecutive</em> run does, and only what a consecutive run does: the load starts at the
     * first slot's boundary and runs without interruption, so the slot it leaves part way through is necessarily the
     * last one in time. A strategy that chooses slots by rank rather than by adjacency has to state its own
     * allocation instead of reaching for this.
     *
     * @param request what the caller asked for
     * @param slots the chosen slots
     * @param granted how much running time those slots grant
     * @param series the series the indices refer to, which is where the slot widths come from
     * @return the answer
     */
    public static WindowSelection fillingInTimeOrder(WindowRequest request, SlotSelection slots, Duration granted,
            SlotSeries series) {
        List<Duration> allocation = new ArrayList<>(slots.size());
        Duration remaining = granted;
        for (Integer index : slots.indices()) {
            Duration width = series.slotAt(index).duration();
            Duration used = remaining.compareTo(width) <= 0 ? remaining : width;
            if (used.isNegative()) {
                used = Duration.ZERO;
            }
            allocation.add(used);
            remaining = remaining.minus(used);
        }
        return new WindowSelection(request, slots, granted.minus(remaining), allocation);
    }

    /**
     * Returns how much of the granted running time the load spends in a chosen slot.
     *
     * @param index the slot index
     * @return the allocated time, or {@link Duration#ZERO} when the slot is not part of this answer
     */
    public Duration allocatedTo(int index) {
        int position = slots.indices().indexOf(index);
        return position < 0 ? Duration.ZERO : allocation.get(position);
    }

    /**
     * Returns the chosen slot indices.
     *
     * @return the indices, ascending and distinct
     */
    public List<Integer> indices() {
        return slots.indices();
    }

    /**
     * Returns the number of chosen slots.
     *
     * @return the selection size
     */
    public int size() {
        return slots.size();
    }

    /**
     * Tests whether nothing was chosen.
     *
     * @return {@code true} if no slot is chosen
     */
    public boolean isEmpty() {
        return slots.isEmpty();
    }

    /**
     * Tests whether a slot is part of this answer.
     *
     * @param index the slot index to test
     * @return {@code true} if the slot is chosen
     */
    public boolean contains(int index) {
        return slots.contains(index);
    }

    /**
     * Tells whether the request was met in full.
     *
     * @return {@code true} if the whole requested running time, or the whole requested number of slots, was granted
     */
    public boolean isComplete() {
        return switch (request) {
            case WindowRequest.ForDuration forDuration -> granted.compareTo(forDuration.runningTime()) >= 0;
            case WindowRequest.ForSlots forSlots -> slots.size() >= forSlots.slotCount();
        };
    }

    /**
     * Tells whether this is a best-effort answer to a request that could not be met in full.
     *
     * @return {@code true} if less was granted than was requested
     */
    public boolean isPartial() {
        return !isComplete();
    }
}
