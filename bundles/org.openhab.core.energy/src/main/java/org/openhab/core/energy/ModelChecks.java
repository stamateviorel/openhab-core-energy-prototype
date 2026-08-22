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

import java.time.Duration;
import java.util.Set;

import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.QuantityType;

/**
 * Argument checks shared by the participant model records.
 * <p>
 * Package-private on purpose: it is an implementation detail of this package's records and is not part of the
 * published API.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
final class ModelChecks {

    /**
     * The lowest phase index a declaration may name.
     */
    static final int MIN_PHASE = 1;

    /**
     * The highest phase index a declaration may name.
     */
    static final int MAX_PHASE = 3;

    private ModelChecks() {
    }

    /**
     * Requires a string to be non-blank and returns it trimmed.
     *
     * @param value the value to check
     * @param name the argument name, used in the exception message
     * @return the trimmed value
     * @throws IllegalArgumentException if the value is blank
     */
    static String requireText(String value, String name) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }

    /**
     * Requires every phase index of a declaration to be one of the integer indices 1, 2 and 3.
     *
     * @param phases the declared phase indices
     * @return the same collection, for chaining
     * @throws IllegalArgumentException if an index is outside 1..3
     */
    static Set<Integer> requirePhases(Set<Integer> phases) {
        for (Integer phase : phases) {
            if (phase < MIN_PHASE || phase > MAX_PHASE) {
                throw new IllegalArgumentException(
                        "phases are the integer indices " + MIN_PHASE + ".." + MAX_PHASE + " but was " + phase);
            }
        }
        return phases;
    }

    /**
     * Requires a phase index to be one of the integer indices 1, 2 and 3.
     *
     * @param phase the declared phase index
     * @return the same index, for chaining
     * @throws IllegalArgumentException if the index is outside 1..3
     */
    static int requirePhase(int phase) {
        requirePhases(Set.of(phase));
        return phase;
    }

    /**
     * Requires a quantity to be expressed in a unit compatible with the given reference unit.
     *
     * @param value the quantity to check
     * @param referenceUnit the unit the quantity must be convertible to
     * @param name the argument name, used in the exception message
     * @throws IllegalArgumentException if the quantity cannot be converted to the reference unit
     */
    static void requireCompatible(QuantityType<?> value, Unit<?> referenceUnit, String name) {
        if (!value.getUnit().isCompatible(referenceUnit)) {
            throw new IllegalArgumentException(
                    name + " must be expressed in a unit compatible with " + referenceUnit + " but was " + value);
        }
    }

    /**
     * Requires a quantity to be zero or greater.
     *
     * @param value the quantity to check
     * @param name the argument name, used in the exception message
     * @throws IllegalArgumentException if the quantity is negative
     */
    static void requireNotNegative(QuantityType<?> value, String name) {
        if (value.doubleValue() < 0) {
            throw new IllegalArgumentException(name + " must not be negative but was " + value);
        }
    }

    /**
     * Requires a quantity to be greater than zero.
     *
     * @param value the quantity to check
     * @param name the argument name, used in the exception message
     * @throws IllegalArgumentException if the quantity is zero or negative
     */
    static void requirePositive(QuantityType<?> value, String name) {
        if (value.doubleValue() <= 0) {
            throw new IllegalArgumentException(name + " must be positive but was " + value);
        }
    }

    /**
     * Requires an optional duration to be zero or greater.
     *
     * @param value the duration to check, may be {@code null}
     * @param name the argument name, used in the exception message
     * @throws IllegalArgumentException if the duration is negative
     */
    static void requireNotNegative(@Nullable Duration value, String name) {
        if (value != null && value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative but was " + value);
        }
    }

    /**
     * Requires an optional duration to be greater than zero, and returns it unchanged.
     * <p>
     * A declared window or age of zero is rejected rather than silently meaning "no declaration": a site that means
     * "use the default" leaves the key out, and one that types zero has said something it cannot have meant.
     *
     * @param value the duration to check, may be {@code null}
     * @param name the argument name, used in the exception message
     * @return the duration, unchanged
     * @throws IllegalArgumentException if the duration is present and not positive
     */
    static @Nullable Duration requirePositiveOrNull(@Nullable Duration value, String name) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException(name + " must be positive but was " + value);
        }
        return value;
    }

    /**
     * Requires an optional quantity to be zero or greater, and returns it unchanged.
     *
     * @param value the quantity to check, may be {@code null}
     * @param name the argument name, used in the exception message
     * @return the quantity, unchanged
     * @throws IllegalArgumentException if the quantity is present and negative
     */
    static @Nullable QuantityType<?> requireNonNegativeOrNull(@Nullable QuantityType<?> value, String name) {
        if (value != null) {
            requireNotNegative(value, name);
        }
        return value;
    }

    /**
     * Requires two optional durations to be ordered, when both are present.
     *
     * @param lower the duration that must not exceed {@code upper}, may be {@code null}
     * @param upper the duration that must not fall below {@code lower}, may be {@code null}
     * @param lowerName the name of the lower argument, used in the exception message
     * @param upperName the name of the upper argument, used in the exception message
     * @throws IllegalArgumentException if both are present and out of order
     */
    static void requireOrdered(@Nullable Duration lower, @Nullable Duration upper, String lowerName, String upperName) {
        if (lower != null && upper != null && lower.compareTo(upper) > 0) {
            throw new IllegalArgumentException(
                    lowerName + " (" + lower + ") must not exceed " + upperName + " (" + upper + ")");
        }
    }

    /**
     * Requires two quantities of the same dimension to be ordered.
     *
     * @param lower the quantity that must not exceed {@code upper}
     * @param upper the quantity that must not fall below {@code lower}
     * @param lowerName the name of the lower argument, used in the exception message
     * @param upperName the name of the upper argument, used in the exception message
     * @throws IllegalArgumentException if the quantities are incompatible or out of order
     */
    static void requireOrdered(QuantityType<?> lower, QuantityType<?> upper, String lowerName, String upperName) {
        if (toDouble(lower, upper.getUnit(), lowerName) > upper.doubleValue()) {
            throw new IllegalArgumentException(
                    lowerName + " (" + lower + ") must not exceed " + upperName + " (" + upper + ")");
        }
    }

    /**
     * Converts a quantity to the given unit and returns its numeric value.
     *
     * @param value the quantity to convert
     * @param targetUnit the unit to convert to
     * @param name the argument name, used in the exception message
     * @return the numeric value in the target unit
     * @throws IllegalArgumentException if the quantity cannot be converted to the target unit
     */
    static double toDouble(QuantityType<?> value, Unit<?> targetUnit, String name) {
        QuantityType<?> converted = value.toUnit(targetUnit);
        if (converted == null) {
            throw new IllegalArgumentException(name + " (" + value + ") cannot be converted to " + targetUnit);
        }
        return converted.doubleValue();
    }
}
