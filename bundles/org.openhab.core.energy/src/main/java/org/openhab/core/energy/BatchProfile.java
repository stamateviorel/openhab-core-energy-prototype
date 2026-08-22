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

import javax.measure.quantity.Energy;
import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * A consumer running a fixed program that must not be interrupted once started - a dishwasher or a washing machine.
 * <p>
 * The engine's only degree of freedom is the start moment. The optional {@link LoadCurve} lets a scheduler cost a
 * candidate start against the actual shape of the draw instead of an assumed flat rectangle; without it the program
 * is treated as flat at {@code ratedPower} for the whole {@code runtime}.
 *
 * @param ratedPower the power the program draws at full load
 * @param runtime the wall-clock length of the program
 * @param loadCurve the normalized draw over the program, or {@code null} for a flat program
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record BatchProfile(QuantityType<Power> ratedPower, Duration runtime,
        @Nullable LoadCurve loadCurve) implements PowerProfile {

    /**
     * Validates the profile.
     *
     * @throws IllegalArgumentException if the rated power is not a positive power or the runtime is not positive
     */
    public BatchProfile {
        ModelChecks.requireCompatible(ratedPower, Units.WATT, "ratedPower");
        ModelChecks.requirePositive(ratedPower, "ratedPower");
        if (runtime.isNegative() || runtime.isZero()) {
            throw new IllegalArgumentException("runtime must be positive but was " + runtime);
        }
    }

    /**
     * Creates a flat program.
     *
     * @param watts the power the program draws, in watts
     * @param runtime the wall-clock length of the program
     * @return the profile
     */
    public static BatchProfile flat(double watts, Duration runtime) {
        return new BatchProfile(new QuantityType<>(watts, Units.WATT), runtime, null);
    }

    /**
     * Returns the mean fraction of rated power over the program: the load curve's mean, or {@code 1.0} for a flat
     * program.
     *
     * @return the mean fraction of rated power
     */
    public double meanFraction() {
        LoadCurve curve = loadCurve;
        return curve == null ? 1.0 : curve.meanFraction();
    }

    /**
     * Returns the highest fraction of rated power the program reaches: the load curve's peak, or {@code 1.0} for a
     * flat program.
     *
     * @return the peak fraction of rated power
     */
    public double peakFraction() {
        LoadCurve curve = loadCurve;
        return curve == null ? 1.0 : curve.peakFraction();
    }

    /**
     * Returns the power figure the electrical-limit floor books while <em>admitting</em> this program: rated power
     * scaled by the curve's peak, which is the worst moment the site has to carry.
     *
     * @return the admission figure, in watts
     */
    public QuantityType<Power> admissionPower() {
        return new QuantityType<>(ModelChecks.toDouble(ratedPower, Units.WATT, "ratedPower") * peakFraction(),
                Units.WATT);
    }

    /**
     * Returns the power figure this program draws on average over its run: rated power scaled by the curve's mean.
     *
     * @return the mean draw, in watts
     */
    public QuantityType<Power> meanPower() {
        return new QuantityType<>(ModelChecks.toDouble(ratedPower, Units.WATT, "ratedPower") * meanFraction(),
                Units.WATT);
    }

    /**
     * Returns the total energy the program consumes, derived from rated power, runtime and the load curve.
     *
     * @return the program energy, in watt-hours
     */
    public QuantityType<Energy> energy() {
        double watts = ModelChecks.toDouble(ratedPower, Units.WATT, "ratedPower");
        double hours = runtime.toMillis() / 3_600_000.0;
        return new QuantityType<>(watts * hours * meanFraction(), Units.WATT_HOUR);
    }

    @Override
    public Kind kind() {
        return Kind.BATCH;
    }
}
