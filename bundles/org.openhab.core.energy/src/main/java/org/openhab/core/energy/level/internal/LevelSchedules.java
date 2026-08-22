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
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.level.PlannedLevelSchedule;
import org.openhab.core.energy.level.PlannedLevelSchedule.PlannedLevel;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;

/**
 * Turns a per-slot level assignment back into a {@link PlannedLevelSchedule}, keeping the series' own slot
 * boundaries.
 * <p>
 * Package-private on purpose: both derivations share it and nothing outside needs it.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
final class LevelSchedules {

    private LevelSchedules() {
    }

    /**
     * Builds a schedule from a series and one level per slot.
     *
     * @param series the classified series
     * @param levels the level of each slot, in the series' order
     * @return the planned schedule
     * @throws IllegalArgumentException if the level list does not match the series length
     */
    static PlannedLevelSchedule build(SlotSeries series, List<EnergyLevel> levels) {
        if (levels.size() != series.size()) {
            throw new IllegalArgumentException(
                    "expected " + series.size() + " levels for the series but got " + levels.size());
        }
        List<PlannedLevel> entries = new ArrayList<>(levels.size());
        for (int i = 0; i < levels.size(); i++) {
            Slot slot = series.slotAt(i);
            entries.add(new PlannedLevel(slot.start(), slot.end(), levels.get(i)));
        }
        return new PlannedLevelSchedule(entries);
    }
}
