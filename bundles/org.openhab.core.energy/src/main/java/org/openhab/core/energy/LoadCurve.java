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
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * A normalized load curve: the characteristic draw of a program as a fraction of rated power over relative time.
 * <p>
 * The samples are evenly spaced across the owning {@link BatchProfile#runtime()}, so the curve is resolution
 * independent - the same three samples describe a two-hour and a three-hour program. This is deliberately a
 * <em>relative-time</em> shape and not an openHAB {@code TimeSeries}: only once a program is scheduled does it
 * project onto absolute time.
 * <p>
 * A flat program is simply a curve of a single sample {@code 1.0}, which is exactly the rectangular special case the
 * requirement describes as {@code ratedW x runtimeHours}.
 *
 * @param samples the fractions of rated power, program start first
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record LoadCurve(List<Double> samples) {

    /**
     * Validates the curve and takes a defensive immutable copy of the sample list.
     *
     * @throws IllegalArgumentException if the curve is empty, a sample is negative or not finite, or every sample is
     *             zero
     */
    public LoadCurve {
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("samples must not be empty");
        }
        double total = 0;
        for (Double sample : samples) {
            double value = sample;
            if (!Double.isFinite(value) || value < 0) {
                throw new IllegalArgumentException("samples must be finite and not negative but held " + sample);
            }
            total += value;
        }
        if (total == 0) {
            throw new IllegalArgumentException("samples must not be all zero");
        }
        samples = List.copyOf(samples);
    }

    /**
     * Creates a curve from the given fractions of rated power, program start first.
     *
     * @param samples the fractions of rated power
     * @return the curve
     */
    public static LoadCurve of(double... samples) {
        List<Double> boxed = new ArrayList<>(samples.length);
        for (double sample : samples) {
            boxed.add(sample);
        }
        return new LoadCurve(boxed);
    }

    /**
     * Returns the number of samples.
     *
     * @return the sample count, always at least one
     */
    public int size() {
        return samples.size();
    }

    /**
     * Returns one sample.
     *
     * @param index the zero-based sample index
     * @return the fraction of rated power at that position
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public double sampleAt(int index) {
        return samples.get(index);
    }

    /**
     * Returns the mean fraction of rated power over the whole program. Multiplying rated power by this factor and by
     * the runtime yields the program's total energy.
     * <p>
     * The samples are evenly spaced and each one describes the interval that starts at it, so the arithmetic mean is
     * exactly the LEFT-Riemann integral over that spacing divided by the runtime. Stating it here keeps every energy
     * figure derived from a curve on one integration rule.
     *
     * @return the arithmetic mean of the samples
     */
    public double meanFraction() {
        return samples.stream().mapToDouble(Double::doubleValue).sum() / samples.size();
    }

    /**
     * Returns the highest fraction of rated power the program reaches.
     * <p>
     * This is the figure the electrical-limit floor books when it is deciding whether to <em>admit</em> a program:
     * the mean is what the program costs, but the peak is what the site has to survive, and admitting a dishwasher
     * on its mean would let its heating phase break the very limit the booking exists to protect.
     *
     * @return the maximum of the samples
     */
    public double peakFraction() {
        return samples.stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }

    /**
     * Returns the wall-clock duration each sample covers, given the total program runtime.
     *
     * @param runtime the total program runtime
     * @return the duration of one sample
     */
    public Duration sampleInterval(Duration runtime) {
        return runtime.dividedBy(samples.size());
    }
}
