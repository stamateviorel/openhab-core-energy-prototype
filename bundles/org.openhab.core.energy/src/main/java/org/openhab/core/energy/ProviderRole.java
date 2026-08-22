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
 * The role an {@link EnergyProvider} plays on the site.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum ProviderRole {

    /**
     * The grid connection. Its power reading carries the canonical sign convention
     * <strong>positive = export, negative = import</strong>, and site surplus is derived from it.
     */
    GRID("grid"),

    /**
     * Photovoltaic production.
     */
    PV("pv", "solar"),

    /**
     * An electrical storage system. This is the primary controllable-provider case: it acts as a negative load when
     * grid cost is high.
     */
    BATTERY("battery", "storage");

    private final List<String> names;

    ProviderRole(String... names) {
        this.names = List.of(names);
    }

    /**
     * Returns the canonical lower-case name of this role, plus any accepted aliases, first entry first.
     *
     * @return the accepted names of this role, canonical name first
     */
    public List<String> names() {
        return names;
    }

    /**
     * Resolves a user-supplied role name (canonical name or alias, case-insensitive) to a role.
     * <p>
     * This is a convenience for declaration mechanisms so that they do not each invent their own alias handling; it
     * carries no policy of its own.
     *
     * @param name the role name to resolve
     * @return the matching role, or {@link Optional#empty()} if the name is unknown
     */
    public static Optional<ProviderRole> parse(String name) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        for (ProviderRole role : values()) {
            if (role.names.contains(normalized)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
