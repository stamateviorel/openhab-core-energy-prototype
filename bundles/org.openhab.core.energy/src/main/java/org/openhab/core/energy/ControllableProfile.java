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

import javax.measure.quantity.ElectricCurrent;
import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * A consumer that takes a continuous setpoint between a minimum and a maximum - an EV charger being the archetype.
 * <p>
 * The bounds are typed as {@code QuantityType<?>} rather than {@code QuantityType<Power>} on purpose: the
 * requirement's own scenario declares a wallbox with "min 6 A and max 32 A", so the control surface may be a current
 * as well as a power. Both bounds must share the same dimension; {@link #isPowerBased()} and
 * {@link #isCurrentBased()} tell an engine which one it is looking at.
 * <p>
 * The minimum is a hard floor while the device runs: the engine must either stay at or above it, or stop the device
 * altogether.
 *
 * @param min the lowest setpoint the device accepts while running, in watts or amperes
 * @param max the highest setpoint the device accepts, in the same dimension as {@code min}
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record ControllableProfile(QuantityType<?> min, QuantityType<?> max) implements PowerProfile {

    /**
     * Validates the profile.
     *
     * @throws IllegalArgumentException if the bounds are not both powers or both currents, or if {@code min} exceeds
     *             {@code max}
     */
    public ControllableProfile {
        boolean power = min.getUnit().isCompatible(Units.WATT) && max.getUnit().isCompatible(Units.WATT);
        boolean current = min.getUnit().isCompatible(Units.AMPERE) && max.getUnit().isCompatible(Units.AMPERE);
        if (!power && !current) {
            throw new IllegalArgumentException(
                    "min and max must both be powers or both be currents but were " + min + " and " + max);
        }
        ModelChecks.requireOrdered(min, max, "min", "max");
    }

    /**
     * Creates a power-bounded profile.
     *
     * @param minWatts the lowest setpoint while running, in watts
     * @param maxWatts the highest setpoint, in watts
     * @return the profile
     */
    public static ControllableProfile watts(double minWatts, double maxWatts) {
        return new ControllableProfile(new QuantityType<Power>(minWatts, Units.WATT),
                new QuantityType<Power>(maxWatts, Units.WATT));
    }

    /**
     * Creates a current-bounded profile.
     *
     * @param minAmperes the lowest setpoint while running, in amperes
     * @param maxAmperes the highest setpoint, in amperes
     * @return the profile
     */
    public static ControllableProfile amperes(double minAmperes, double maxAmperes) {
        return new ControllableProfile(new QuantityType<ElectricCurrent>(minAmperes, Units.AMPERE),
                new QuantityType<ElectricCurrent>(maxAmperes, Units.AMPERE));
    }

    /**
     * Tests whether the bounds are expressed as powers.
     *
     * @return {@code true} if the control surface is a power
     */
    public boolean isPowerBased() {
        return min.getUnit().isCompatible(Units.WATT);
    }

    /**
     * Tests whether the bounds are expressed as electric currents.
     *
     * @return {@code true} if the control surface is a current
     */
    public boolean isCurrentBased() {
        return min.getUnit().isCompatible(Units.AMPERE);
    }

    @Override
    public Kind kind() {
        return Kind.CONTROLLABLE;
    }
}
