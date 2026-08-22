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
package org.openhab.core.energy.forecast.store.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
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
import org.openhab.core.energy.forecast.LayeredWritePolicy;
import org.openhab.core.energy.forecast.SeriesLayer;

/**
 * What a site says about where its prediction series live and how their writers get on.
 * <p>
 * <strong>{@code writePolicy} has no default on purpose.</strong> It is the corpus's open question - what happens when
 * a refresh lands on a capped entry - and the four values are the three options its design section frames plus the
 * requirement's own literal words. A site that chooses nothing gets the literal words and a reported condition, which
 * is the one behaviour that neither invents an answer nor hides the collision.
 *
 * @param persistenceServiceId which persistence service holds the prediction series, or {@code null} for the default
 * @param seriesItems which Item carries which role's prediction series
 * @param capItems which Item carries the constraint series of a prediction Item
 * @param writePolicy how a collision between two layers resolves, or {@code null} where the site chose nothing
 * @param layerPrecedence the rank of each layer, used only by the writer-precedence policy
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
record StoreConfiguration(@Nullable String persistenceServiceId, Map<ForecastRole, String> seriesItems,
        Map<String, String> capItems, @Nullable LayeredWritePolicy writePolicy,
        Map<SeriesLayer, Integer> layerPrecedence) {

    static final String CONFIG_PERSISTENCE_SERVICE = "persistenceService";
    static final String CONFIG_SERIES = "series";
    static final String CONFIG_CAP_SERIES = "capSeries";
    static final String CONFIG_WRITE_POLICY = "writePolicy";
    static final String CONFIG_LAYER_PRECEDENCE = "layerPrecedence";

    /**
     * Every parameter this record reads, so the configuration description and the code can be held to each other.
     */
    static final Set<String> CONFIG_KEYS = Set.of(CONFIG_PERSISTENCE_SERVICE, CONFIG_SERIES, CONFIG_CAP_SERIES,
            CONFIG_WRITE_POLICY, CONFIG_LAYER_PRECEDENCE);

    /**
     * Takes defensive immutable copies.
     */
    StoreConfiguration {
        seriesItems = Map.copyOf(seriesItems);
        capItems = Map.copyOf(capItems);
        layerPrecedence = Map.copyOf(layerPrecedence);
    }

    /**
     * Returns the configuration of a site that has declared nothing.
     *
     * @return the default configuration
     */
    static StoreConfiguration defaults() {
        return new StoreConfiguration(null, Map.of(), Map.of(), null, Map.of());
    }

    /**
     * Reads the configuration out of OSGi component properties.
     *
     * @param properties the component properties
     * @param rejected receives one description per value that could not be used
     * @return the configuration
     */
    static StoreConfiguration fromProperties(Map<String, Object> properties, Consumer<String> rejected) {
        @Nullable
        String serviceId = text(properties.get(CONFIG_PERSISTENCE_SERVICE));

        Map<ForecastRole, String> series = new LinkedHashMap<>();
        for (String entry : pairs(properties.get(CONFIG_SERIES), rejected)) {
            String[] parts = split(entry);
            Optional<ForecastRole> role = ForecastRole.fromId(parts[0]);
            if (role.isEmpty()) {
                rejected.accept("'" + parts[0] + "' is not a forecast role; ignoring it");
                continue;
            }
            series.put(role.get(), parts[1]);
        }

        Map<String, String> caps = new LinkedHashMap<>();
        for (String entry : pairs(properties.get(CONFIG_CAP_SERIES), rejected)) {
            String[] parts = split(entry);
            caps.put(parts[0], parts[1]);
        }

        @Nullable
        LayeredWritePolicy policy = null;
        @Nullable
        String declaredPolicy = text(properties.get(CONFIG_WRITE_POLICY));
        if (declaredPolicy != null) {
            policy = LayeredWritePolicy.fromId(declaredPolicy).orElse(null);
            if (policy == null) {
                rejected.accept("unknown write policy '" + declaredPolicy
                        + "'; treating the site as having chosen none, which follows the requirement literally");
            }
        }

        Map<SeriesLayer, Integer> precedence = new EnumMap<>(SeriesLayer.class);
        for (String entry : pairs(properties.get(CONFIG_LAYER_PRECEDENCE), rejected)) {
            String[] parts = split(entry);
            Optional<SeriesLayer> layer = SeriesLayer.fromId(parts[0]);
            @Nullable
            Integer rank = ConfigParser.valueAs(parts[1], Integer.class);
            if (layer.isEmpty() || rank == null) {
                rejected.accept("'" + entry + "' is not a 'layer=rank' pair; ignoring it");
                continue;
            }
            precedence.put(layer.get(), rank);
        }

        return new StoreConfiguration(serviceId, series, caps, policy, precedence);
    }

    /**
     * Returns the Item carrying the constraint series of a prediction Item.
     *
     * @param predictionItem the prediction Item
     * @return the constraint Item, or empty where the site named none
     */
    Optional<String> capItemOf(String predictionItem) {
        return Optional.ofNullable(capItems.get(predictionItem));
    }

    private static List<String> pairs(@Nullable Object value, Consumer<String> rejected) {
        List<String> valid = new ArrayList<>();
        for (String entry : toList(value)) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                rejected.accept("'" + entry + "' is not a 'key=value' pair; ignoring it");
                continue;
            }
            valid.add(entry);
        }
        return valid;
    }

    private static String[] split(String entry) {
        int separator = entry.indexOf('=');
        return new String[] { entry.substring(0, separator).trim(), entry.substring(separator + 1).trim() };
    }

    private static @Nullable String text(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String trimmed = String.valueOf(value).trim();
        return trimmed.isEmpty() ? null : trimmed;
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
