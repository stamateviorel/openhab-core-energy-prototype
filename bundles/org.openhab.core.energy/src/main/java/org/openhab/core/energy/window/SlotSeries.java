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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * An ordered, non-overlapping series of {@link Slot}s - the input to every derivation and selection in this
 * package, and the numeric core of every typed series in the energy framework.
 * <p>
 * Gaps between slots are allowed on purpose. A series is not required to be contiguous, uniform, aligned to the hour
 * or a whole day long; the only invariants are that slots are ordered and do not overlap. Anything that needs
 * contiguity (an uninterrupted window) asks for it explicitly through {@link #isContiguous(int, int)}.
 * <p>
 * <strong>The series carries its own {@link SeriesSense}</strong>, which is what makes one ranking serve a price
 * (cheaper is better) and a photovoltaic forecast or a green-share series (more is better) without a caller having to
 * remember which is which. {@link #rankedIndices()} always answers best first, whatever the sense, and
 * {@link #worstRankedIndices()} always answers worst first. A series built without stating a sense is
 * {@link SeriesSense#LOWER_IS_BETTER}, which is what a price is.
 *
 * @param slots the slots, ordered by start and non-overlapping
 * @param sense whether a lower or a higher value is the better one
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record SlotSeries(List<Slot> slots, SeriesSense sense) {

    /**
     * Validates the series and takes a defensive immutable copy.
     *
     * @throws IllegalArgumentException if the series is empty, or two slots overlap or are out of order
     */
    public SlotSeries {
        if (slots.isEmpty()) {
            throw new IllegalArgumentException("slots must not be empty");
        }
        for (int i = 1; i < slots.size(); i++) {
            Slot previous = slots.get(i - 1);
            Slot current = slots.get(i);
            if (current.start().isBefore(previous.end())) {
                throw new IllegalArgumentException("slot " + i + " (" + current.start()
                        + ") must start at or after the end of slot " + (i - 1) + " (" + previous.end() + ")");
            }
        }
        slots = List.copyOf(slots);
    }

    /**
     * Creates a series whose lower values are the better ones, which is what a price series is.
     *
     * @param slots the slots, ordered by start and non-overlapping
     */
    public SlotSeries(List<Slot> slots) {
        this(slots, SeriesSense.LOWER_IS_BETTER);
    }

    /**
     * Returns the same slots read under a different sense.
     *
     * @param newSense whether a lower or a higher value is the better one
     * @return a series holding the same slots with the given sense
     */
    public SlotSeries withSense(SeriesSense newSense) {
        return new SlotSeries(slots, newSense);
    }

    /**
     * Creates a series of equally wide slots starting back to back at the given instant.
     *
     * @param firstStart the start of the first slot
     * @param width the width of every slot
     * @param values the value of each slot, in chronological order
     * @return the series
     * @throws IllegalArgumentException if no value is given or the width is not positive
     */
    public static SlotSeries uniform(Instant firstStart, Duration width, double... values) {
        if (values.length == 0) {
            throw new IllegalArgumentException("values must not be empty");
        }
        if (width.isZero() || width.isNegative()) {
            throw new IllegalArgumentException("width must be positive but was " + width);
        }
        List<Slot> built = new ArrayList<>(values.length);
        Instant cursor = firstStart;
        for (double value : values) {
            built.add(Slot.of(cursor, width, value));
            cursor = cursor.plus(width);
        }
        return new SlotSeries(built);
    }

    /**
     * Creates a series of hourly slots starting back to back at the given instant.
     *
     * @param firstStart the start of the first slot
     * @param values the value of each hour, in chronological order
     * @return the series
     */
    public static SlotSeries hourly(Instant firstStart, double... values) {
        return uniform(firstStart, Duration.ofHours(1), values);
    }

    /**
     * Returns the number of slots.
     *
     * @return the slot count, always at least one
     */
    public int size() {
        return slots.size();
    }

    /**
     * Returns one slot.
     *
     * @param index the zero-based slot index
     * @return the slot at that position
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public Slot slotAt(int index) {
        return slots.get(index);
    }

    /**
     * Returns the start of the first slot.
     *
     * @return the first instant the series covers
     */
    public Instant start() {
        return slots.getFirst().start();
    }

    /**
     * Returns the end of the last slot.
     *
     * @return the first instant after the series
     */
    public Instant end() {
        return slots.getLast().end();
    }

    /**
     * Returns the slot indices ordered best first, "best" being read through this series' {@link #sense()}.
     * <p>
     * This is the single place where the tie-break lives: <strong>equal values are ordered by the earlier slot
     * start</strong>. That is the decided rule (owner decision D21), and it is the one every derivation and selection
     * in this package uses, so a repeated value never makes a result depend on iteration order. Ties are common -
     * flat and day/night tariffs produce them constantly - and the earlier slot wins because it biases towards acting
     * sooner, which leaves a deadline-driven load its margin.
     * <p>
     * The tie-break points the same way under both senses and under {@link #worstRankedIndices()}: it is a statement
     * about <em>when</em> to act rather than about the value, so reversing the value order must not reverse it.
     *
     * @return every slot index exactly once, best first
     */
    public List<Integer> rankedIndices() {
        return ranked(sense == SeriesSense.LOWER_IS_BETTER);
    }

    /**
     * Returns the slot indices ordered worst first - the most expensive slots of a price series, and the dirtiest
     * hours of a carbon-intensity one.
     * <p>
     * This is deliberately not {@link #rankedIndices()} reversed: reversing that list would resolve a tie to the
     * later slot, which is a different rule from the decided one.
     *
     * @return every slot index exactly once, worst first
     */
    public List<Integer> worstRankedIndices() {
        return ranked(sense != SeriesSense.LOWER_IS_BETTER);
    }

    private List<Integer> ranked(boolean ascending) {
        List<Integer> order = new ArrayList<>(slots.size());
        for (int i = 0; i < slots.size(); i++) {
            order.add(i);
        }
        Comparator<Integer> byValue = Comparator.comparingDouble(index -> slots.get(index).value());
        Comparator<Integer> byValueThenStart = (ascending ? byValue : byValue.reversed())
                .thenComparing(index -> slots.get(index).start());
        order.sort(byValueThenStart);
        return List.copyOf(order);
    }

    /**
     * Tests whether the slots in the given inclusive index range touch each other without a gap, which is the
     * condition for them to form an uninterrupted window.
     *
     * @param fromIndex the first slot index of the range
     * @param toIndexInclusive the last slot index of the range
     * @return {@code true} if every neighbouring pair in the range abuts
     * @throws IndexOutOfBoundsException if either index is out of range
     */
    public boolean isContiguous(int fromIndex, int toIndexInclusive) {
        if (fromIndex < 0 || toIndexInclusive >= slots.size() || fromIndex > toIndexInclusive) {
            throw new IndexOutOfBoundsException(
                    "range " + fromIndex + ".." + toIndexInclusive + " is not inside 0.." + (slots.size() - 1));
        }
        for (int i = fromIndex; i < toIndexInclusive; i++) {
            if (!slots.get(i).abuts(slots.get(i + 1))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the slot width if every slot in the series is equally wide.
     *
     * @return the shared slot width, or {@link Optional#empty()} if the series mixes widths
     */
    public Optional<Duration> uniformSlotDuration() {
        Duration first = slots.getFirst().duration();
        for (Slot slot : slots) {
            if (!slot.duration().equals(first)) {
                return Optional.empty();
            }
        }
        return Optional.of(first);
    }

    /**
     * Returns the index of the slot covering the given instant.
     *
     * @param instant the instant to look up
     * @return the covering slot index, or {@link OptionalInt#empty()} if the instant falls outside the series or into
     *         a gap
     */
    public OptionalInt indexAt(Instant instant) {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).covers(instant)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Returns the value of one slot.
     *
     * @param index the zero-based slot index
     * @return the value at that position
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public double valueAt(int index) {
        return slots.get(index).value();
    }

    /**
     * Returns the total time the slots of this series cover, gaps excluded.
     *
     * @return the summed slot durations
     */
    public Duration coveredDuration() {
        Duration total = Duration.ZERO;
        for (Slot slot : slots) {
            total = total.plus(slot.duration());
        }
        return total;
    }
}
