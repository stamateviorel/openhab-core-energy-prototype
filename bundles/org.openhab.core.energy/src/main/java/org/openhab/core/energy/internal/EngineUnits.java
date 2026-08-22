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

import java.util.OptionalDouble;

import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * Unit conversions the engine needs, kept in one place so the conversion assumptions are visible.
 * <p>
 * Package-private: this is an implementation detail of the engine, not part of the published API.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
final class EngineUnits {

    /**
     * The slack an "exact" comparison still has to allow, so that a value which survived a unit conversion on the way
     * out and another on the way back is not judged different from itself. It is a representation tolerance, not a
     * device tolerance.
     */
    static final double EXACT_EPSILON = 1e-9;

    private EngineUnits() {
    }

    /**
     * Converts a quantity to watts.
     *
     * @param quantity the quantity to convert
     * @return the value in watts, or empty if the quantity is not a power
     */
    static OptionalDouble watts(QuantityType<?> quantity) {
        return convert(quantity, Units.WATT);
    }

    /**
     * Converts a quantity to amperes.
     *
     * @param quantity the quantity to convert
     * @return the value in amperes, or empty if the quantity is not an electric current
     */
    static OptionalDouble amperes(QuantityType<?> quantity) {
        return convert(quantity, Units.AMPERE);
    }

    /**
     * Converts a quantity to the given unit.
     *
     * @param quantity the quantity to convert
     * @param targetUnit the unit to convert to
     * @return the converted value, or empty if the quantity is incompatible with the unit
     */
    static OptionalDouble convert(QuantityType<?> quantity, Unit<?> targetUnit) {
        QuantityType<?> converted = quantity.toUnit(targetUnit);
        return converted == null ? OptionalDouble.empty() : OptionalDouble.of(converted.doubleValue());
    }

    /**
     * Reads a numeric value out of an Item state that was rendered as text.
     * <p>
     * A state carrying a unit ("3000 W", "16 A") is converted; a bare number ("3000") is taken to already be in the
     * target unit, which is the same assumption openHAB itself makes for a plain {@code DecimalType}.
     *
     * @param text the rendered state
     * @param targetUnit the unit the caller wants the value in
     * @return the value in the target unit, or empty if the text is not numeric or carries an incompatible unit
     */
    static OptionalDouble parse(String text, Unit<?> targetUnit) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return OptionalDouble.empty();
        }
        try {
            QuantityType<?> quantity = new QuantityType<>(trimmed);
            if (quantity.getUnit().isCompatible(targetUnit)) {
                return convert(quantity, targetUnit);
            }
            if (Units.ONE.equals(quantity.getUnit())) {
                return OptionalDouble.of(quantity.doubleValue());
            }
            return OptionalDouble.empty();
        } catch (IllegalArgumentException e) {
            return OptionalDouble.empty();
        }
    }

    /**
     * Compares an observed figure against a commanded one, either exactly or within a declared tolerance band.
     * <p>
     * <strong>The band is absolute and in the control Item's own dimension - never a fraction of the commanded
     * value.</strong> A proportional band, which is what the prototype used, silently means something different at
     * 16 A than at 1.6 A, and nothing in the corpus asks for that; a site that wants to accept 15.999 A for a
     * commanded 16 A says so by declaring 0.01 A. With no band declared the comparison is exact, up to the
     * representation error of converting between units - which is why {@link #EXACT_EPSILON} exists and why it is
     * far smaller than any figure a device reports.
     *
     * @param actual the value observed
     * @param expected the value commanded
     * @param band the declared tolerance band in the same unit, or {@code null} when none is declared
     * @return {@code true} if the observed value acknowledges the commanded one
     */
    static boolean acknowledges(double actual, double expected, @Nullable Double band) {
        double allowed = band == null ? EXACT_EPSILON : Math.max(EXACT_EPSILON, band);
        return Math.abs(actual - expected) <= allowed;
    }
}
