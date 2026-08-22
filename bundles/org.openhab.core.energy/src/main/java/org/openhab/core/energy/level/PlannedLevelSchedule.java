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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.window.SlotSelection;

/**
 * The planned level schedule: which {@link EnergyLevel} each future slot is expected to carry.
 * <p>
 * This is the "planned" half of the <em>Planned schedule vs. current level</em> requirement, and it is deliberately
 * immutable and free of live inputs. The current level is computed on top of it by {@link CurrentLevelResolver},
 * which may escalate the plan without changing it - the requirement's "the stored plan stays intact".
 * <p>
 * Publishing this as a future-timestamped {@code TimeSeries} is a separate, outward step and belongs to whatever
 * component owns the site level Item; a pure classifier does not know Items exist, and this bundle cannot write to an
 * Item at all. How a re-plan relates to the schedule it replaces is answered on the publication side - re-derive over
 * the whole series and republish {@code [now, end)} with {@code Policy.REPLACE}, leaving elapsed entries alone - so
 * this type offers no merge operation: a re-plan is simply a new instance.
 *
 * @param entries the planned levels, ordered by start and non-overlapping; may be empty
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record PlannedLevelSchedule(List<PlannedLevel> entries) {

    /**
     * One planned slot: a half-open time interval and the level planned for it.
     *
     * @param start the inclusive start of the slot
     * @param end the exclusive end of the slot
     * @param level the level planned for that slot
     *
     * @author Stamate Viorel - Initial contribution
     */
    public record PlannedLevel(Instant start, Instant end, EnergyLevel level) {

        /**
         * Validates the planned slot.
         *
         * @throws IllegalArgumentException if the slot does not end after it starts
         */
        public PlannedLevel {
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("end (" + end + ") must be after start (" + start + ")");
            }
        }

        /**
         * Tests whether the given instant falls inside this slot, start inclusive and end exclusive.
         *
         * @param instant the instant to test
         * @return {@code true} if the slot covers the instant
         */
        public boolean covers(Instant instant) {
            return !instant.isBefore(start) && instant.isBefore(end);
        }
    }

    /**
     * Validates the schedule and takes a defensive immutable copy.
     *
     * @throws IllegalArgumentException if two entries overlap or are out of order
     */
    public PlannedLevelSchedule {
        for (int i = 1; i < entries.size(); i++) {
            PlannedLevel previous = entries.get(i - 1);
            PlannedLevel current = entries.get(i);
            if (current.start().isBefore(previous.end())) {
                throw new IllegalArgumentException("entry " + i + " (" + current.start()
                        + ") must start at or after the end of entry " + (i - 1) + " (" + previous.end() + ")");
            }
        }
        entries = List.copyOf(entries);
    }

    /**
     * Returns the empty schedule, which is what "no plan yet" looks like.
     *
     * @return a schedule holding no entry
     */
    public static PlannedLevelSchedule empty() {
        return new PlannedLevelSchedule(List.of());
    }

    /**
     * Returns the number of planned slots.
     *
     * @return the entry count
     */
    public int size() {
        return entries.size();
    }

    /**
     * Returns the level planned for the given instant.
     *
     * @param instant the instant to look up
     * @return the planned level, or {@link Optional#empty()} if the instant falls outside the plan or into a gap
     */
    public Optional<EnergyLevel> levelAt(Instant instant) {
        for (PlannedLevel entry : entries) {
            if (entry.covers(instant)) {
                return Optional.of(entry.level());
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the numeric level codes of every entry, in chronological order - the shape
     * {@code fixtures/expected-planned-levels.csv} encodes.
     *
     * @return one code per entry, 0 (blocked) through 3 (overcapacity)
     */
    public List<Integer> codes() {
        List<Integer> codes = new ArrayList<>(entries.size());
        for (PlannedLevel entry : entries) {
            codes.add(entry.level().code());
        }
        return List.copyOf(codes);
    }

    /**
     * Returns the slot indices carrying the given level, which is how a schedule feeds back into a
     * {@link org.openhab.core.energy.window.SelectionStrategy} result comparison.
     *
     * @param level the level to look for
     * @return the indices of the entries carrying that level
     */
    public SlotSelection slotsAt(EnergyLevel level) {
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).level() == level) {
                found.add(i);
            }
        }
        return new SlotSelection(found);
    }
}
