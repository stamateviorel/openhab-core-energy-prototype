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
package org.openhab.core.energy.forecast.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.ConfigParser;
import org.openhab.core.energy.forecast.ForecastRole;

/**
 * Everything about the forecast plane that a site chooses rather than the framework fixing.
 * <p>
 * Two parameters, and neither has a shipped value:
 * <ul>
 * <li><strong>{@code sources}</strong> - which source answers for which role, written {@code role=sourceId}. Nothing
 * configured means {@code service.ranking} decides, which is `extension-surface` _Multiple contributors, user
 * selection_ read literally;</li>
 * <li><strong>{@code staleAfter}</strong> - how old a forecast run may be before the site is told it is planning on
 * something old. _Forecast source fails_ speaks of a provider dark "for any duration" and gives no age, and no
 * decided parameter in the corpus supplies one, so the shape ships and the number does not.</li>
 * </ul>
 * Nothing here rejects a configuration outright: an unreadable entry is reported and skipped, so one typo cannot stop
 * the plane from coming up.
 *
 * @param preferredSources the source a site prefers per role, empty where it named none
 * @param staleAfter how old a run may be before it counts as stale, or {@code null} where the site declared no age
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
record ForecastConfiguration(Map<ForecastRole, String> preferredSources, @Nullable Duration staleAfter) {

    static final String CONFIG_SOURCES = "sources";
    static final String CONFIG_STALE_AFTER = "staleAfter";

    /**
     * Every parameter this record reads, so that the configuration description and the code can be held to each other
     * instead of drifting apart.
     */
    static final Set<String> CONFIG_KEYS = Set.of(CONFIG_SOURCES, CONFIG_STALE_AFTER);

    /**
     * Takes a defensive immutable copy.
     */
    ForecastConfiguration {
        preferredSources = Map.copyOf(preferredSources);
    }

    /**
     * Returns the configuration of a plane nobody has configured.
     *
     * @return the default configuration
     */
    static ForecastConfiguration defaults() {
        return new ForecastConfiguration(Map.of(), null);
    }

    /**
     * Reads a configuration out of OSGi component properties.
     *
     * @param properties the component properties
     * @param rejected receives one description per value that could not be used
     * @return the configuration
     */
    static ForecastConfiguration fromProperties(Map<String, Object> properties, Consumer<String> rejected) {
        Map<ForecastRole, String> preferred = new LinkedHashMap<>();
        for (String entry : toList(properties.get(CONFIG_SOURCES))) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                rejected.accept("'" + entry + "' is not a 'role=sourceId' pair; ignoring it");
                continue;
            }
            String roleId = entry.substring(0, separator).trim();
            String sourceId = entry.substring(separator + 1).trim();
            Optional<ForecastRole> role = ForecastRole.fromId(roleId);
            if (role.isEmpty()) {
                rejected.accept("'" + roleId + "' is not a forecast role; ignoring it");
                continue;
            }
            preferred.put(role.get(), sourceId);
        }
        @Nullable
        Duration staleAfter = null;
        @Nullable
        Object age = properties.get(CONFIG_STALE_AFTER);
        if (age != null && !String.valueOf(age).isBlank()) {
            @Nullable
            Integer hours = ConfigParser.valueAs(age, Integer.class);
            if (hours == null || hours <= 0) {
                rejected.accept("'" + CONFIG_STALE_AFTER + "' (" + age
                        + ") must be a positive number of hours; leaving it undeclared");
            } else {
                staleAfter = Duration.ofHours(hours);
            }
        }
        return new ForecastConfiguration(preferred, staleAfter);
    }

    private static List<String> toList(@Nullable Object value) {
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

    private static void add(List<String> entries, String value) {
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) {
            entries.add(trimmed);
        }
    }
}
