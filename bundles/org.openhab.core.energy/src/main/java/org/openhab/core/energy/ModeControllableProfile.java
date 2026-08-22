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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * A consumer that accepts a small ordered set of discrete modes and no power setpoint - an SG-ready heat pump being
 * the archetype, with the modes blocked / normal / encouraged / forced.
 * <p>
 * The list is <strong>ordered, most restricted first</strong>: index 0 is the mode that consumes least, the last
 * index the mode that consumes most. At least two modes are required, since a single mode carries no choice; a
 * two-mode device degrades naturally to allow/deny.
 * <p>
 * This class carries <strong>no power figure by default</strong>, and that is the rule rather than a gap: an
 * SG-ready mode 3 draws whatever the heat pump decides it needs, so a number declared for it would be fiction and a
 * planner booking that fiction would allocate against it. A mode change is therefore exempt from the planner's
 * budget, and the consequence is caught by the runtime floor through measurement on a later cycle. A site that
 * <em>does</em> know what a mode costs may say so per mode through {@link #modeDraws()}, which is optional and
 * partial - declaring a figure for one mode says nothing about the others.
 * <p>
 * How the four site {@link EnergyLevel}s map onto an arbitrary number of modes is stated by the requirement, not by
 * this record: a profile knows its own ordered modes, and the mapping from a site level onto one of them is applied
 * above the model where the level is known.
 *
 * @param modes the accepted modes, most restricted first
 * @param modeDraws the power a named mode is known to draw, empty where the site declares none
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record ModeControllableProfile(List<String> modes,
        Map<String, QuantityType<Power>> modeDraws) implements PowerProfile {

    /**
     * Validates the profile and takes defensive immutable copies of the mode list and the declared draws.
     *
     * @throws IllegalArgumentException if fewer than two modes are given, a mode is blank or duplicated, or a
     *             declared draw names a mode this profile does not carry or is not a non-negative power
     */
    public ModeControllableProfile {
        List<String> trimmed = modes.stream().map(mode -> ModelChecks.requireText(mode, "mode")).toList();
        if (trimmed.size() < 2) {
            throw new IllegalArgumentException("modes must hold at least two entries but held " + trimmed.size());
        }
        Set<String> distinct = new LinkedHashSet<>(trimmed);
        if (distinct.size() != trimmed.size()) {
            throw new IllegalArgumentException("modes must not repeat but were " + trimmed);
        }
        modes = List.copyOf(trimmed);
        Map<String, QuantityType<Power>> draws = new LinkedHashMap<>();
        for (Map.Entry<String, QuantityType<Power>> entry : modeDraws.entrySet()) {
            String mode = ModelChecks.requireText(entry.getKey(), "modeDraw mode");
            if (!distinct.contains(mode)) {
                throw new IllegalArgumentException(
                        "a declared draw names mode '" + mode + "' which is not one of " + trimmed);
            }
            QuantityType<Power> draw = entry.getValue();
            ModelChecks.requireCompatible(draw, Units.WATT, "modeDraw of '" + mode + "'");
            ModelChecks.requireNotNegative(draw, "modeDraw of '" + mode + "'");
            draws.put(mode, draw);
        }
        modeDraws = Map.copyOf(draws);
    }

    /**
     * Creates a profile from the given modes, most restricted first, declaring no per-mode draw.
     *
     * @param modes the accepted modes, most restricted first
     * @return the profile
     */
    public static ModeControllableProfile of(String... modes) {
        return new ModeControllableProfile(List.of(modes), Map.of());
    }

    /**
     * Returns a copy declaring what the named modes draw.
     *
     * @param declaredDraws the power per mode name
     * @return the copy
     * @throws IllegalArgumentException if a declared draw names a mode this profile does not carry
     */
    public ModeControllableProfile withModeDraws(Map<String, QuantityType<Power>> declaredDraws) {
        return new ModeControllableProfile(modes, declaredDraws);
    }

    /**
     * Returns what a mode is declared to draw.
     *
     * @param mode the mode name
     * @return the declared draw, or {@link Optional#empty()} when the site declares none for that mode - in which
     *         case a change into it is exempt from the planner's budget
     */
    public Optional<QuantityType<Power>> drawOf(String mode) {
        return Optional.ofNullable(modeDraws.get(mode));
    }

    /**
     * Tests whether this profile declares what any of its modes draw.
     *
     * @return {@code true} if at least one per-mode draw is declared
     */
    public boolean declaresModeDraws() {
        return !modeDraws.isEmpty();
    }

    /**
     * Returns the number of modes.
     *
     * @return the mode count, always at least two
     */
    public int size() {
        return modes.size();
    }

    /**
     * Returns the most restricted mode, the one that consumes least.
     *
     * @return the first mode
     */
    public String mostRestricted() {
        return modes.getFirst();
    }

    /**
     * Returns the least restricted mode, the one that consumes most.
     *
     * @return the last mode
     */
    public String leastRestricted() {
        return modes.getLast();
    }

    /**
     * Returns the position of a mode in the ordered list.
     *
     * @param mode the mode to look up
     * @return the zero-based index, or {@link OptionalInt#empty()} if the mode is not declared
     */
    public OptionalInt indexOf(String mode) {
        int index = modes.indexOf(mode);
        return index < 0 ? OptionalInt.empty() : OptionalInt.of(index);
    }

    @Override
    public Kind kind() {
        return Kind.MODE_CONTROLLABLE;
    }
}
