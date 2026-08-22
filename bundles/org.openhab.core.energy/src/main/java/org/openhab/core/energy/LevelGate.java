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
package org.openhab.core.energy;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The "run at level &ge; N" coupling between a Simple consumer and the shared site {@link EnergyLevel} signal.
 * <p>
 * A gate on {@link EnergyLevel#BLOCKED} permits every level and is therefore the "always" gate, which is also the
 * default when a consumer declares no gate at all.
 * <p>
 * The gate is an <strong>engine-owned prohibition</strong>: the engine enforces it for every algorithm rather than
 * offering it as advice a contributed one may set aside. It is <em>not</em> the place where "leave this device
 * alone" is expressed - that is {@link EnergyConsumer#handsOff()}, which every profile class carries. A gate always
 * names a level; there is no "never" gate any more.
 *
 * @param minimumLevel the lowest site level at which the engine may run the consumer
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record LevelGate(EnergyLevel minimumLevel) {

    private static final LevelGate ALWAYS = new LevelGate(EnergyLevel.BLOCKED);

    /**
     * Returns the gate that permits engine-initiated operation at any site level.
     *
     * @return the "always" gate
     */
    public static LevelGate always() {
        return ALWAYS;
    }

    /**
     * Returns the gate that permits engine-initiated operation from the given level upwards.
     *
     * @param minimumLevel the lowest site level at which the consumer may run
     * @return the matching gate
     */
    public static LevelGate atLeast(EnergyLevel minimumLevel) {
        return new LevelGate(minimumLevel);
    }

    /**
     * Tests whether this gate permits engine-initiated operation at the given site level.
     * <p>
     * This answers the gate question only. Hands-off, device protections, readiness and electrical limits are
     * evaluated separately by the engine.
     *
     * @param currentLevel the current site energy level
     * @return {@code true} if the gate is open at that level
     */
    public boolean permits(EnergyLevel currentLevel) {
        return currentLevel.atLeast(minimumLevel);
    }
}
