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
package org.openhab.core.energy.window;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.LoadCurve;

/**
 * The weights a window is costed under - the {@code weights} argument of the corpus' single
 * {@code cost(window, weights)} calculation.
 * <p>
 * <strong>Flat weights are the default</strong>, and they are what wave 1 has: a load drawing the same power
 * throughout its run. A declared load curve becomes the weights when one exists, which is a profile-side capability
 * (the curve belongs to the profile, is stored in relative time, and is integrated LEFT-Riemann). This interface is
 * the shape that capability plugs into; core ships only {@link #flat()} today.
 * <p>
 * Time is relative to the load's own run: {@code 0} is the moment it starts, {@code 1} the moment it finishes. That
 * is what lets one curve be costed at any candidate start without being re-anchored.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@FunctionalInterface
public interface CostWeights {

    /**
     * Returns the load's mean relative draw over a slice of its own running time.
     * <p>
     * The slice is half-open, {@code [fromFraction, toFraction)}. A value of {@code 1} means "the load's nominal
     * draw", so flat weights answer {@code 1} everywhere and a curve answers the LEFT-Riemann mean of its samples
     * over that slice.
     *
     * @param fromFraction the inclusive start of the slice, 0..1 of the running time
     * @param toFraction the exclusive end of the slice, 0..1 of the running time
     * @return the mean relative draw over that slice, never negative
     */
    double meanDrawOver(double fromFraction, double toFraction);

    /**
     * Returns the flat weights: the load draws its nominal power throughout.
     *
     * @return the flat weights
     */
    static CostWeights flat() {
        return (fromFraction, toFraction) -> 1;
    }

    /**
     * Returns the weights of a declared {@link LoadCurve} - the bridge that turns the profile side's relative-time
     * shape into the {@code weights} argument of the one costing calculation.
     * <p>
     * A curve's samples are evenly spaced across the load's own runtime and each one is the mean draw from its own
     * timestamp to the next, so a slice of relative time falls across one or more whole samples and, at each end, a
     * fraction of one. The mean over the slice is therefore the sample values weighted by how much of the slice each
     * covers - which is the LEFT-Riemann rule the corpus names (owner decision D16, pack A13), applied at the
     * resolution the curve actually has rather than at the resolution the slot boundaries happen to impose.
     * <p>
     * A single-sample curve is the flat case and answers that sample everywhere, so a rectangular program costs
     * exactly {@code rated x runtime x price} as the requirement says it must.
     *
     * @param curve the load's declared shape over its own running time
     * @return weights reading that curve
     */
    static CostWeights ofCurve(LoadCurve curve) {
        return (fromFraction, toFraction) -> {
            double from = Math.max(0, Math.min(1, fromFraction));
            double to = Math.max(0, Math.min(1, toFraction));
            if (to <= from) {
                return curve.sampleAt(Math.min(curve.size() - 1, (int) (from * curve.size())));
            }
            double width = 1d / curve.size();
            double weighted = 0;
            for (int sample = 0; sample < curve.size(); sample++) {
                double overlap = Math.min(to, (sample + 1) * width) - Math.max(from, sample * width);
                if (overlap > 0) {
                    weighted += curve.sampleAt(sample) * overlap;
                }
            }
            return weighted / (to - from);
        };
    }
}
