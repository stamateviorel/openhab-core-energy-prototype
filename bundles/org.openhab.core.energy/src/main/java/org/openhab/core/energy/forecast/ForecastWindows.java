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
package org.openhab.core.energy.forecast;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.window.SelectionStrategy;
import org.openhab.core.energy.window.SeriesSense;
import org.openhab.core.energy.window.SlotSelection;
import org.openhab.core.energy.window.WindowRequest;
import org.openhab.core.energy.window.WindowSelection;

/**
 * Finding the best stretch of a forecast - "tomorrow's best three-hour photovoltaic window" - through the same
 * request and the same answer a price window uses.
 * <p>
 * <strong>There is no second window search here.</strong> _Solar production forecast_ requires a photovoltaic window
 * request to take "exactly" the shape of a price one: a {@code Duration}, answered on slot boundaries, with the best
 * partial answer and its requested-versus-granted duration when the horizon is shorter than the request. That shape
 * already exists, as {@link WindowRequest}, {@link WindowSelection} and {@link SelectionStrategy}, and a second
 * implementation of it would be a second place for the slot-boundary rule, the tie-break and the partial-answer rule
 * to live. So every method here is a direct delegation, and this class exists for the forecast-shaped names and for
 * {@link #energyKilowattHours}, which is the one thing a price window has no equivalent of.
 * <p>
 * <strong>There is no re-signing here either, and that is the point.</strong> An earlier form of this class negated a
 * {@link SeriesSense#HIGHER_IS_BETTER} series before handing it over, on the assumption that the shared strategies
 * only ever minimised. They do not: both of them read {@link SeriesSense} off the series itself, so the negation was
 * a second mechanism for a concept that already had one. It was provably redundant rather than merely untidy -
 * {@code rankedIndices()} on a higher-is-better series and on its negated lower-is-better twin produce the same order
 * including the decided tie-break, because negation preserves equality and the tie-break is on the slot start rather
 * than on the value. Removing it leaves one place where "which direction is better" is decided, and stops callers
 * being handed a series whose values are the negatives of the quantity it claims to carry.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ForecastWindows {

    private ForecastWindows() {
    }

    /**
     * Returns the best uninterrupted window of a forecast - the sunniest three hours of a photovoltaic day, the
     * coldest six hours of a temperature series.
     *
     * @param series the forecast to search
     * @param request how much running time, or how many slots, the caller needs
     * @param excluded slots that are not available
     * @return the chosen slots, with the request and how much of it was granted; the best partial answer when the
     *         horizon is shorter than the request
     */
    public static WindowSelection bestWindow(ForecastSeries series, WindowRequest request, SlotSelection excluded) {
        return SelectionStrategy.consecutiveWindow().select(series.values(), request, excluded);
    }

    /**
     * Returns the best uninterrupted window of a forecast, with nothing excluded.
     *
     * @param series the forecast to search
     * @param request how much running time, or how many slots, the caller needs
     * @return the chosen slots, with the request and how much of it was granted
     */
    public static WindowSelection bestWindow(ForecastSeries series, WindowRequest request) {
        return bestWindow(series, request, SlotSelection.empty());
    }

    /**
     * Returns the best slots of a forecast wherever they are, for a load that can be interrupted.
     *
     * @param series the forecast to search
     * @param request how much running time, or how many slots, the caller needs
     * @param excluded slots that are not available
     * @return the chosen slots, with the request and how much of it was granted
     */
    public static WindowSelection bestSlots(ForecastSeries series, WindowRequest request, SlotSelection excluded) {
        return SelectionStrategy.cheapestSlots().select(series.values(), request, excluded);
    }

    /**
     * Returns the best slots of a forecast wherever they are, with nothing excluded.
     *
     * @param series the forecast to search
     * @param request how much running time, or how many slots, the caller needs
     * @return the chosen slots, with the request and how much of it was granted
     */
    public static WindowSelection bestSlots(ForecastSeries series, WindowRequest request) {
        return bestSlots(series, request, SlotSelection.empty());
    }

    /**
     * Returns how much of the forecast quantity a window covers - the kilowatt-hours of production under a
     * photovoltaic window, which is what makes two windows comparable to a caller that has to decide whether either
     * is worth using at all.
     *
     * @param series the forecast the window's slot indices refer to
     * @param window the chosen window
     * @return the summed energy of the chosen slots, in kilowatt-hours
     * @throws IllegalStateException if the series is in neither a power nor an energy unit
     */
    public static double energyKilowattHours(ForecastSeries series, WindowSelection window) {
        double total = 0;
        for (Integer index : window.indices()) {
            total += series.energyKilowattHoursAt(index);
        }
        return total;
    }
}
