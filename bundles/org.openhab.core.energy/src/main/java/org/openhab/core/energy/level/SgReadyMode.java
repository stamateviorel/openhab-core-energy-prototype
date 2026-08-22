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

import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.EnergyLevel;

/**
 * The Smart-Grid-ready operating modes of a heat pump, and their one-to-one correspondence with
 * {@link EnergyLevel}.
 * <p>
 * The four-level scale exists precisely so that this mapping needs no translation logic in user rules - that is the
 * <em>SG-ready mode mapping</em> requirement of the {@code energy-levels} capability.
 * <p>
 * <strong>The correspondence is named, one constant at a time, and never computed.</strong> Each mode carries the
 * level it answers to as its own field: blocked to mode 1, normal to mode 2, encouraged to mode 3, overcapacity to
 * mode 4. Levels are encoded 0-3 and SG-ready numbers its modes 1-4, so the two differ by exactly one everywhere -
 * and that is precisely why arithmetic on a level code is forbidden here. A reader that published a level code
 * straight onto an SG-ready channel would drive a heat pump to mode 3 when the site is at overcapacity, which is the
 * one failure the naming makes impossible.
 * <p>
 * What this type deliberately does <em>not</em> encode is SG-ready's own operational constraint that mode 1 may only
 * be commanded for a limited time per day. That is a device-side obligation and belongs to the actuation side, not to
 * a level scale.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum SgReadyMode {

    /**
     * Mode 1, the utility block signal, corresponding to {@link EnergyLevel#BLOCKED}.
     */
    BLOCKED_OPERATION(1, EnergyLevel.BLOCKED),

    /**
     * Mode 2, normal operation, corresponding to {@link EnergyLevel#NORMAL}.
     */
    NORMAL_OPERATION(2, EnergyLevel.NORMAL),

    /**
     * Mode 3, the switch-on recommendation, corresponding to {@link EnergyLevel#ENCOURAGED}.
     */
    RECOMMENDED_ON(3, EnergyLevel.ENCOURAGED),

    /**
     * Mode 4, the forced start command, corresponding to {@link EnergyLevel#OVERCAPACITY}.
     */
    FORCED_ON(4, EnergyLevel.OVERCAPACITY);

    private final int mode;
    private final EnergyLevel level;

    SgReadyMode(int mode, EnergyLevel level) {
        this.mode = mode;
        this.level = level;
    }

    /**
     * Returns the SG-ready mode number, 1 through 4.
     *
     * @return the mode number as the industry model names it
     */
    public int mode() {
        return mode;
    }

    /**
     * Returns the energy level this mode corresponds to.
     *
     * @return the matching level
     */
    public EnergyLevel level() {
        return level;
    }

    /**
     * Maps an energy level onto the SG-ready mode a heat pump should be driven to.
     *
     * @param level the current or planned site level
     * @return the matching mode
     */
    public static SgReadyMode of(EnergyLevel level) {
        for (SgReadyMode candidate : values()) {
            if (candidate.level == level) {
                return candidate;
            }
        }
        throw new IllegalStateException("no SG-ready mode for level " + level);
    }

    /**
     * Resolves an SG-ready mode number back to its constant.
     *
     * @param mode the mode number, 1 through 4
     * @return the matching mode, or {@link Optional#empty()} if the number is out of range
     */
    public static Optional<SgReadyMode> fromMode(int mode) {
        for (SgReadyMode candidate : values()) {
            if (candidate.mode == mode) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
