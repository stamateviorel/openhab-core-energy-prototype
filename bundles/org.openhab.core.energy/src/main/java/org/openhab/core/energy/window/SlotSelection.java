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
import java.util.TreeSet;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * A set of chosen slot indices into a {@link SlotSeries} - the result of a {@link SelectionStrategy}, and the
 * shape the acceptance fixtures for the heating and boiler schedules compare against.
 * <p>
 * The indices are always distinct and stored in ascending order, so two selections holding the same slots are equal
 * regardless of the order in which they were found.
 *
 * @param indices the chosen slot indices, ascending and distinct
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record SlotSelection(List<Integer> indices) {

    private static final SlotSelection EMPTY = new SlotSelection(List.of());

    /**
     * Normalizes the indices to an ascending, distinct, immutable list.
     *
     * @throws IllegalArgumentException if an index is negative
     */
    public SlotSelection {
        for (Integer index : indices) {
            if (index < 0) {
                throw new IllegalArgumentException("slot index must not be negative but was " + index);
            }
        }
        indices = List.copyOf(new TreeSet<>(indices));
    }

    /**
     * Returns the empty selection.
     *
     * @return a selection holding no slot
     */
    public static SlotSelection empty() {
        return EMPTY;
    }

    /**
     * Creates a selection from the given slot indices.
     *
     * @param indices the chosen slot indices, in any order
     * @return the selection
     */
    public static SlotSelection of(int... indices) {
        List<Integer> boxed = new ArrayList<>(indices.length);
        for (int index : indices) {
            boxed.add(index);
        }
        return new SlotSelection(boxed);
    }

    /**
     * Returns the number of chosen slots.
     *
     * @return the selection size
     */
    public int size() {
        return indices.size();
    }

    /**
     * Tests whether this selection is empty.
     *
     * @return {@code true} if no slot is chosen
     */
    public boolean isEmpty() {
        return indices.isEmpty();
    }

    /**
     * Tests whether a slot is part of this selection.
     *
     * @param index the slot index to test
     * @return {@code true} if the slot is chosen
     */
    public boolean contains(int index) {
        return indices.contains(index);
    }

    /**
     * Returns the union of this selection and another one.
     *
     * @param other the selection to merge in
     * @return a selection holding every slot of either
     */
    public SlotSelection union(SlotSelection other) {
        List<Integer> merged = new ArrayList<>(indices);
        merged.addAll(other.indices);
        return new SlotSelection(merged);
    }

    /**
     * Renders this selection as one flag per slot of a series of the given length - the ON/OFF shape the control
     * fixtures use.
     *
     * @param slotCount the number of slots in the series the selection refers to
     * @return {@code slotCount} flags, {@code true} where the slot is chosen
     * @throws IllegalArgumentException if the selection holds an index outside the series
     */
    public List<Boolean> onOffFlags(int slotCount) {
        List<Boolean> flags = new ArrayList<>(slotCount);
        for (int i = 0; i < slotCount; i++) {
            flags.add(contains(i));
        }
        for (Integer index : indices) {
            if (index >= slotCount) {
                throw new IllegalArgumentException(
                        "selection holds slot " + index + " which is outside a series of " + slotCount + " slots");
            }
        }
        return List.copyOf(flags);
    }

    /**
     * Returns the total time the chosen slots cover.
     *
     * @param series the series the indices refer to
     * @return the summed duration of the chosen slots
     * @throws IndexOutOfBoundsException if the selection holds an index outside the series
     */
    public Duration coveredDuration(SlotSeries series) {
        Duration total = Duration.ZERO;
        for (Integer index : indices) {
            total = total.plus(series.slotAt(index).duration());
        }
        return total;
    }
}
