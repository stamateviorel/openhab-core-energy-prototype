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
package org.openhab.core.energy.level.internal;

import java.util.OptionalDouble;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * Unit handling shared by the surplus escalation policies.
 * <p>
 * Configuration is validated eagerly and loudly - a threshold in the wrong dimension is a user mistake worth an
 * exception at construction time. A live reading is treated leniently: a surplus that cannot be read as a power is
 * "unknown", and an unknown surplus never escalates. A classifier that threw on a bad reading would take the site
 * down for a sensor glitch.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
final class SurplusChecks {

    private SurplusChecks() {
    }

    /**
     * Validates a configured threshold and returns it in watts.
     *
     * @param value the threshold to check
     * @param name the argument name, used in the exception message
     * @return the threshold in watts
     * @throws IllegalArgumentException if the threshold is not a power or is negative
     */
    static double requireNotNegativeWatts(QuantityType<Power> value, String name) {
        QuantityType<?> watts = value.toUnit(Units.WATT);
        if (watts == null) {
            throw new IllegalArgumentException(name + " (" + value + ") must be expressed in a unit of power");
        }
        double amount = watts.doubleValue();
        if (amount < 0) {
            throw new IllegalArgumentException(name + " must not be negative but was " + value);
        }
        return amount;
    }

    /**
     * Reads a live surplus as watts.
     *
     * @param surplus the live surplus, may be {@code null}
     * @return the surplus in watts, or empty if it is unknown or not a power
     */
    static OptionalDouble toWatts(@Nullable QuantityType<Power> surplus) {
        if (surplus == null) {
            return OptionalDouble.empty();
        }
        QuantityType<?> watts = surplus.toUnit(Units.WATT);
        return watts == null ? OptionalDouble.empty() : OptionalDouble.of(watts.doubleValue());
    }
}
