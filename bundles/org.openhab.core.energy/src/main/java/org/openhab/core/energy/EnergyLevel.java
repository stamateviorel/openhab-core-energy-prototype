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

import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The ordered four-level site energy availability scale.
 * <p>
 * The scale is deliberately four wide because that is the granularity the real control surfaces have: Smart-Grid
 * (SG-ready) heat pump modes map onto it one-to-one, EVCC-style charge modes map onto it one-to-one, and a plain
 * ON/OFF consumer collapses it to allow/deny through its {@link LevelGate}.
 * <p>
 * The declaration order is ascending availability: {@link #BLOCKED} is the most restrictive level,
 * {@link #OVERCAPACITY} the most permissive. {@link #code()} returns <strong>the</strong> numeric encoding -
 * {@code blocked = 0}, {@code normal = 1}, {@code encouraged = 2}, {@code overcapacity = 3} - fixed centrally so
 * that every component exchanging a level as a number uses the same one and no exchange has to agree an encoding of
 * its own. The acceptance fixtures carry these codes because they are the central ones, not the other way round.
 * <p>
 * SG-ready numbers its four modes 1-4, so the two scales differ by exactly one throughout. That offset is crossed by
 * the named correspondence in {@code SgReadyMode} and never by arithmetic on a code.
 * <p>
 * How the level is <em>derived</em> (price ranking, PV escalation, seasonal windows) belongs to the level classifier
 * and is not part of this enum.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum EnergyLevel {

    /**
     * The most restrictive level: the most expensive slots of the price schedule. The engine does not run
     * discretionary loads here.
     */
    BLOCKED(0),

    /**
     * The neutral level: neither expensive nor cheap, and no live surplus.
     */
    NORMAL(1),

    /**
     * The "low price" level: cheap slots of the price schedule, or a moderate live surplus.
     */
    ENCOURAGED(2),

    /**
     * The most permissive level: the cheapest slots of the price schedule, or a large live surplus.
     */
    OVERCAPACITY(3);

    private final int code;

    EnergyLevel(int code) {
        this.code = code;
    }

    /**
     * Returns the numeric encoding of this level, 0 (blocked) through 3 (overcapacity).
     *
     * @return the numeric level code
     */
    public int code() {
        return code;
    }

    /**
     * Tests whether this level is at least as permissive as the given one.
     *
     * @param other the level to compare against
     * @return {@code true} if this level is the same as or more permissive than {@code other}
     */
    public boolean atLeast(EnergyLevel other) {
        return code >= other.code;
    }

    /**
     * Resolves a numeric level code back to its constant.
     *
     * @param code the numeric level code, 0 through 3
     * @return the matching level, or {@link Optional#empty()} if the code is out of range
     */
    public static Optional<EnergyLevel> fromCode(int code) {
        for (EnergyLevel level : values()) {
            if (level.code == code) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }
}
