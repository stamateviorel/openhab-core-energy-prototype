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
package org.openhab.core.energy.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Reads a multi-valued configuration parameter.
 * <p>
 * The parameters concerned are declared {@code multiple="true"}, so the framework hands them over as a
 * {@link Collection}. A single string is still accepted and split on commas, because a {@code .cfg} file and the
 * REST API can both deliver one that way, and because a configuration written before the parameter became
 * multi-valued must keep working.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ConfigLists {

    private ConfigLists() {
    }

    /**
     * Reads a multi-valued parameter as a list, in the configured order.
     *
     * @param value the raw configuration value
     * @return the entries, trimmed, without blanks, empty when nothing is configured
     */
    public static List<String> toList(@Nullable Object value) {
        if (value == null) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            collection.forEach(entry -> add(entries, String.valueOf(entry)));
        } else {
            for (String entry : value.toString().split(",")) {
                add(entries, entry);
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Reads a multi-valued parameter as a set, in the configured order.
     *
     * @param value the raw configuration value
     * @return the entries, trimmed, without blanks or duplicates, empty when nothing is configured
     */
    public static Set<String> toSet(@Nullable Object value) {
        List<String> entries = toList(value);
        return entries.isEmpty() ? Set.of() : Set.copyOf(new LinkedHashSet<>(entries));
    }

    private static void add(List<String> entries, String value) {
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) {
            entries.add(trimmed);
        }
    }
}
