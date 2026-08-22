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

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The control surface of an {@link EnergyConsumer}: how the engine is allowed to steer it.
 * <p>
 * The type is sealed to exactly four variants, which is the whole point of the requirement: every consumer observed
 * across the reference production systems falls into one of them, and a fifth would be a spec change rather than an
 * implementation detail.
 * <ul>
 * <li>{@link SimpleProfile} - ON/OFF only, with the full protection set.</li>
 * <li>{@link ControllableProfile} - a continuous setpoint between a minimum and a maximum.</li>
 * <li>{@link ModeControllableProfile} - an ordered set of discrete modes and no power setpoint.</li>
 * <li>{@link BatchProfile} - a fixed program whose only degree of freedom is the start moment.</li>
 * </ul>
 * Every command derived from a profile is an <em>envelope</em>, never an order: the device keeps its own control
 * intelligence and may draw less than allowed.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public sealed interface PowerProfile permits SimpleProfile, ControllableProfile, ModeControllableProfile, BatchProfile {

    /**
     * Returns the profile class of this profile.
     *
     * @return the profile kind
     */
    Kind kind();

    /**
     * The four profile classes, as a plain enum for declaration mechanisms and logging.
     *
     * @author Stamate Viorel - Initial contribution
     */
    enum Kind {

        /** ON/OFF control only - see {@link SimpleProfile}. */
        SIMPLE("simple"),

        /** Continuous setpoint control - see {@link ControllableProfile}. */
        CONTROLLABLE("controllable"),

        /** Discrete ordered modes - see {@link ModeControllableProfile}. */
        MODE_CONTROLLABLE("mode", "modecontrollable"),

        /** Fixed uninterruptible program - see {@link BatchProfile}. */
        BATCH("batch");

        private final List<String> names;

        Kind(String... names) {
            this.names = List.of(names);
        }

        /**
         * Returns the canonical lower-case name of this profile class, plus any accepted aliases, first entry first.
         *
         * @return the accepted names of this profile class, canonical name first
         */
        public List<String> names() {
            return names;
        }

        /**
         * Resolves a user-supplied profile class name (canonical name or alias, case-insensitive).
         *
         * @param name the profile class name to resolve
         * @return the matching profile class, or {@link Optional#empty()} if the name is unknown
         */
        public static Optional<Kind> parse(String name) {
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.names.contains(normalized)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }
}
