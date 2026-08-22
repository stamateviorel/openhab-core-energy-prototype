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

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.ConfigParser;
import org.openhab.core.energy.level.LevelCounts;
import org.openhab.core.energy.level.LevelDerivation;
import org.openhab.core.energy.level.LevelPercentiles;
import org.openhab.core.energy.level.SurplusEscalationPolicy;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * Everything about the level plane that is a choice rather than a rule, read out of the component properties once
 * and kept as an immutable snapshot.
 * <p>
 * It is the level-plane twin of {@link EnergyEngineConfiguration}, and it exists for the same two reasons: the
 * component should read its configuration once rather than on every use, and the defaults the code falls back to
 * should be one set of named values that a test can hold the configuration description to. The percentile fractions
 * are the case in point - {@link #DEFAULT_FRACTION} is exactly one sixth, which is what makes the percentile
 * derivation agree with the fixed-count default of four slots in twenty-four on the acceptance fixture.
 * <p>
 * <strong>The two surplus thresholds have no shipped default and none is invented here.</strong> A site that declares
 * {@code encouragedFrom} gets {@code overcapacityFrom} at twice that unless it says otherwise, which is the one
 * relation the corpus fixes; a site that declares neither gets {@link SurplusEscalationPolicy#unconfigured()}, which
 * never escalates and reports itself as unconfigured. Escalating at a number nobody chose would be worse than not
 * escalating at all.
 * <p>
 * Nothing here rejects a configuration. An unreadable or out-of-range value falls back to the default and is
 * logged, so that a typo in one parameter cannot stop the level plane from coming up at all.
 *
 * @param derivationId which derivation cuts the bands out of a price series
 * @param counts the band widths in slots, used by the fixed-count derivation
 * @param percentiles the band widths as fractions of the series, used by the percentile derivation
 * @param encouragedFrom the surplus from which the level is raised to encouraged, or {@code null} when the site has
 *            declared none
 * @param overcapacityFrom the surplus from which the level is raised to overcapacity, or {@code null} when the site
 *            has declared none, in which case it is twice {@code encouragedFrom}
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
record EnergyLevelConfiguration(String derivationId, LevelCounts counts, LevelPercentiles percentiles,
        @Nullable QuantityType<Power> encouragedFrom, @Nullable QuantityType<Power> overcapacityFrom) {

    /**
     * The number of slots each non-normal band gets when nothing is configured.
     */
    static final int DEFAULT_SLOTS = 4;

    /**
     * The share of the series each non-normal band gets when nothing is configured.
     * <p>
     * One sixth, so that the percentile derivation classifies the twenty-four slot acceptance fixture exactly as
     * the fixed-count default does. It has to be written as an exact double rather than as a rounded decimal: the
     * nearest-rank ceiling turns {@code 0.1667 * 24 = 4.0008} into a fifth slot.
     */
    static final double DEFAULT_FRACTION = 1.0 / 6;

    static final String CONFIG_DERIVATION = "derivation";
    static final String CONFIG_OVERCAPACITY_SLOTS = "overcapacitySlots";
    static final String CONFIG_ENCOURAGED_SLOTS = "encouragedSlots";
    static final String CONFIG_BLOCKED_SLOTS = "blockedSlots";
    static final String CONFIG_OVERCAPACITY_FRACTION = "overcapacityFraction";
    static final String CONFIG_ENCOURAGED_FRACTION = "encouragedFraction";
    static final String CONFIG_BLOCKED_FRACTION = "blockedFraction";
    static final String CONFIG_ENCOURAGED_FROM = "encouragedFrom";
    static final String CONFIG_OVERCAPACITY_FROM = "overcapacityFrom";

    /**
     * Every parameter this record reads, so that the configuration description and the code can be held to each
     * other instead of drifting apart.
     */
    static final Set<String> CONFIG_KEYS = Set.of(CONFIG_DERIVATION, CONFIG_OVERCAPACITY_SLOTS, CONFIG_ENCOURAGED_SLOTS,
            CONFIG_BLOCKED_SLOTS, CONFIG_OVERCAPACITY_FRACTION, CONFIG_ENCOURAGED_FRACTION, CONFIG_BLOCKED_FRACTION,
            CONFIG_ENCOURAGED_FROM, CONFIG_OVERCAPACITY_FROM);

    /**
     * Returns the configuration of a level plane nobody has configured.
     *
     * @return the default configuration
     */
    static EnergyLevelConfiguration defaults() {
        return fromProperties(Map.of());
    }

    /**
     * Reads a configuration out of OSGi component properties.
     *
     * @param properties the component properties
     * @return the configuration
     */
    static EnergyLevelConfiguration fromProperties(Map<String, Object> properties) {
        return fromProperties(properties, rejected -> {
        });
    }

    /**
     * Reads a configuration out of OSGi component properties, reporting anything it had to reject.
     * <p>
     * The rejections are handed to the caller rather than logged here, because a value type has no business owning
     * a logger.
     *
     * @param properties the component properties
     * @param rejected receives one description per value that could not be used
     * @return the configuration
     */
    static EnergyLevelConfiguration fromProperties(Map<String, Object> properties, Consumer<String> rejected) {
        EnergyLevelConfiguration config = new EnergyLevelConfiguration(
                text(properties, CONFIG_DERIVATION, EnergyLevelPlane.DERIVATION_FIXED_COUNTS),
                counts(properties, rejected), percentiles(properties, rejected),
                watts(properties, CONFIG_ENCOURAGED_FROM, rejected),
                watts(properties, CONFIG_OVERCAPACITY_FROM, rejected));
        config.reportUnusableSelections(rejected);
        return config;
    }

    /**
     * Reports every selection this configuration will silently fall back from, once, at the moment it is read -
     * rather than every time a plan is derived or a level resolved.
     * <p>
     * An absent {@code encouragedFrom} is deliberately <em>not</em> reported here. It is neither a mistake nor a
     * fallback: it is a site that has declared no threshold, which the level plane reports as
     * {@link org.openhab.core.energy.level.LevelPlaneCondition#ESCALATION_UNCONFIGURED} for as long as it holds.
     *
     * @param rejected receives one description per unusable selection
     */
    private void reportUnusableSelections(Consumer<String> rejected) {
        String derivation = derivationId.toLowerCase(Locale.ROOT);
        if (!EnergyLevelPlane.DERIVATION_PERCENTILES.equals(derivation)
                && !EnergyLevelPlane.DERIVATION_FIXED_COUNTS.equals(derivation)) {
            rejected.accept("unknown level derivation '" + derivationId + "'; falling back to '"
                    + EnergyLevelPlane.DERIVATION_FIXED_COUNTS + "'");
        }
        QuantityType<Power> encouraged = encouragedFrom;
        QuantityType<Power> overcapacity = overcapacityFrom;
        if (encouraged == null && overcapacity != null) {
            rejected.accept("'" + CONFIG_OVERCAPACITY_FROM + "' needs '" + CONFIG_ENCOURAGED_FROM
                    + "' to be declared as well; not escalating at all");
        } else if (encouraged != null && overcapacity != null
                && encouraged.doubleValue() > overcapacity.doubleValue()) {
            rejected.accept("'" + CONFIG_ENCOURAGED_FROM + "' (" + encouraged + ") must not exceed '"
                    + CONFIG_OVERCAPACITY_FROM + "' (" + overcapacity + "); not escalating at all");
        }
    }

    /**
     * Builds the derivation this configuration selects.
     *
     * @return the derivation future plans are derived with
     */
    LevelDerivation createDerivation() {
        if (EnergyLevelPlane.DERIVATION_PERCENTILES.equals(derivationId.toLowerCase(Locale.ROOT))) {
            return LevelDerivation.percentiles(percentiles);
        }
        return LevelDerivation.fixedCounts(counts);
    }

    /**
     * Builds the surplus escalation policy this configuration declares.
     * <p>
     * Graded is the only shape core ships. A site that has declared no {@code encouragedFrom} - or a pair of
     * thresholds that cannot be honoured - gets the unconfigured policy, which never escalates and says why.
     *
     * @return the policy applied to the planned level of the current slot
     */
    SurplusEscalationPolicy createEscalation() {
        QuantityType<Power> encouraged = encouragedFrom;
        if (encouraged == null) {
            return SurplusEscalationPolicy.unconfigured();
        }
        QuantityType<Power> overcapacity = overcapacityFrom;
        if (overcapacity == null) {
            return SurplusEscalationPolicy.graded(encouraged);
        }
        if (encouraged.doubleValue() > overcapacity.doubleValue()) {
            return SurplusEscalationPolicy.unconfigured();
        }
        return SurplusEscalationPolicy.graded(encouraged, overcapacity);
    }

    private static LevelCounts counts(Map<String, Object> properties, Consumer<String> rejected) {
        return LevelCounts.of(count(properties, CONFIG_OVERCAPACITY_SLOTS, rejected),
                count(properties, CONFIG_ENCOURAGED_SLOTS, rejected),
                count(properties, CONFIG_BLOCKED_SLOTS, rejected));
    }

    private static LevelPercentiles percentiles(Map<String, Object> properties, Consumer<String> rejected) {
        double overcapacity = fraction(properties, CONFIG_OVERCAPACITY_FRACTION, rejected);
        double encouraged = fraction(properties, CONFIG_ENCOURAGED_FRACTION, rejected);
        double blocked = fraction(properties, CONFIG_BLOCKED_FRACTION, rejected);
        if (overcapacity + encouraged + blocked > 1.0) {
            rejected.accept("the configured level fractions add up to more than the whole series (" + overcapacity
                    + " + " + encouraged + " + " + blocked + "); falling back to " + DEFAULT_FRACTION + " each");
            return LevelPercentiles.of(DEFAULT_FRACTION, DEFAULT_FRACTION, DEFAULT_FRACTION);
        }
        return LevelPercentiles.of(overcapacity, encouraged, blocked);
    }

    private static int count(Map<String, Object> properties, String key, Consumer<String> rejected) {
        Integer value = ConfigParser.valueAs(properties.get(key), Integer.class);
        if (value == null) {
            return DEFAULT_SLOTS;
        }
        if (value < 0) {
            rejected.accept(
                    "'" + key + "' must not be negative but was " + value + "; falling back to " + DEFAULT_SLOTS);
            return DEFAULT_SLOTS;
        }
        return value;
    }

    private static double fraction(Map<String, Object> properties, String key, Consumer<String> rejected) {
        Double value = ConfigParser.valueAs(properties.get(key), Double.class);
        if (value == null) {
            return DEFAULT_FRACTION;
        }
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            rejected.accept("'" + key + "' must be a fraction between 0 and 1 but was " + value + "; falling back to "
                    + DEFAULT_FRACTION);
            return DEFAULT_FRACTION;
        }
        return value;
    }

    private static @Nullable QuantityType<Power> watts(Map<String, Object> properties, String key,
            Consumer<String> rejected) {
        Double value = ConfigParser.valueAs(properties.get(key), Double.class);
        if (value == null) {
            return null;
        }
        if (!Double.isFinite(value) || value < 0) {
            rejected.accept("'" + key + "' must be a surplus of zero watts or more but was " + value + "; ignoring it");
            return null;
        }
        return new QuantityType<>(value, Units.WATT);
    }

    private static String text(Map<String, Object> properties, String key, String fallback) {
        String value = ConfigParser.valueAsOrElse(properties.get(key), String.class, fallback).trim();
        return value.isEmpty() ? fallback : value;
    }
}
