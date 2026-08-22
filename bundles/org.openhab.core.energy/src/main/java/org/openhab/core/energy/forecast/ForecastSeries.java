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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.DoubleUnaryOperator;

import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.SignConvention;
import org.openhab.core.energy.window.SeriesSense;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * One forecast: what it is about, who produced it, when it was produced, what unit it is in, and the future-
 * timestamped numbers themselves.
 * <p>
 * <strong>It wraps {@link SlotSeries} rather than re-implementing it.</strong> The slot geometry the level plane
 * already has - ordered, non-overlapping, gaps and mixed widths permitted, contiguity defined as abutting, ties
 * broken by the earlier slot - is exactly the geometry a photovoltaic forecast needs, and a second implementation
 * would be a second place for the tie-break to live. So the numbers stay in {@code SlotSeries} and this record adds
 * the four facts a forecast consumer needs on top of them. That is the corpus's own "same data shape as prices,
 * reusing the same storage capability" read literally.
 * <p>
 * <strong>{@code generatedAt} is not decoration.</strong> _Forecast source fails_ requires a site to keep planning on
 * a baseline when a provider has been dark "for any duration", and _Graceful degradation on contributor loss_
 * requires the degraded source to be reported. Neither is decidable from the values: a year-long baseline and a
 * forecast run refreshed a minute ago look identical once they are numbers. The run time is what tells them apart,
 * and it is carried here rather than inferred from the persistence layer, which stores when a value <em>applies</em>
 * and not when it was <em>computed</em>.
 * <p>
 * <strong>Signs are normalised at the edge.</strong> _Forecasts as future-timestamped series_ requires the same
 * single convention as the reading a forecast predicts, "with a source that disagrees normalised at the edge rather
 * than interpreted downstream". {@link #normalised(boolean)} is that edge, and it delegates to the framework's one
 * {@link SignConvention} rather than carrying a second copy of the rule.
 *
 * @param role what the series is about
 * @param sourceId the id of the source that produced it, as a site names it to prefer it
 * @param unit the unit every value in the series is in
 * @param generatedAt when the run that produced these values was made
 * @param values the numbers and their slot geometry
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record ForecastSeries(ForecastRole role, String sourceId, Unit<?> unit, Instant generatedAt, SlotSeries values) {

    /**
     * Validates the series.
     *
     * @throws IllegalArgumentException if the source id is blank
     */
    public ForecastSeries {
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
    }

    /**
     * Creates a series, taking the sense the role falls back to.
     *
     * @param role what the series is about
     * @param sourceId the id of the source that produced it
     * @param unit the unit every value is in
     * @param generatedAt when the run was made
     * @param values the numbers and their slot geometry
     * @return the series, carrying the role's default sense
     */
    public static ForecastSeries of(ForecastRole role, String sourceId, Unit<?> unit, Instant generatedAt,
            SlotSeries values) {
        return new ForecastSeries(role, sourceId, unit, generatedAt, values.withSense(role.defaultSense()));
    }

    /**
     * Returns the number of slots.
     *
     * @return the slot count, always at least one
     */
    public int size() {
        return values.size();
    }

    /**
     * Returns the start of the first slot.
     *
     * @return the first instant the forecast covers
     */
    public Instant start() {
        return values.start();
    }

    /**
     * Returns the end of the last slot.
     *
     * @return the first instant after the forecast
     */
    public Instant end() {
        return values.end();
    }

    /**
     * Returns whether more of this quantity is the better outcome.
     *
     * @return the sense the series carries
     */
    public SeriesSense sense() {
        return values.sense();
    }

    /**
     * Returns one slot.
     *
     * @param index the zero-based slot index
     * @return the slot
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public Slot slotAt(int index) {
        return values.slotAt(index);
    }

    /**
     * Returns the value covering an instant.
     *
     * @param instant the instant to look up
     * @return the value, or empty when the instant falls outside the forecast or into a gap
     */
    public OptionalDouble valueAt(Instant instant) {
        OptionalInt index = values.indexAt(instant);
        return index.isPresent() ? OptionalDouble.of(values.valueAt(index.getAsInt())) : OptionalDouble.empty();
    }

    /**
     * Returns one value as a typed quantity, which is what a consumer that has to display or convert it wants.
     *
     * @param index the zero-based slot index
     * @return the value with its unit
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public QuantityType<?> quantityAt(int index) {
        return new QuantityType<>(values.valueAt(index), unit);
    }

    /**
     * Returns how much energy this series accounts for in one slot, in kilowatt-hours.
     * <p>
     * A series in a power unit is integrated over the slot's own width - the same LEFT-Riemann rule the shared window
     * cost uses, applied to a value that is constant across its slot by construction. A series already in an energy
     * unit is returned as it stands. Any other unit has no energy reading and is refused rather than guessed at.
     *
     * @param index the zero-based slot index
     * @return the energy in the slot, in kilowatt-hours
     * @throws IllegalStateException if the series is in neither a power nor an energy unit
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public double energyKilowattHoursAt(int index) {
        Slot slot = values.slotAt(index);
        if (unit.isCompatible(Units.KILOWATT_HOUR)) {
            return converted(slot.value(), Units.KILOWATT_HOUR);
        }
        if (unit.isCompatible(Units.WATT)) {
            return converted(slot.value(), Units.WATT) * hours(slot.duration()) / 1000;
        }
        throw new IllegalStateException(
                "a series in " + unit + " carries no energy; only power and energy series can be integrated");
    }

    private double converted(double value, Unit<?> target) {
        @Nullable
        QuantityType<?> converted = new QuantityType<>(value, unit).toUnit(target);
        if (converted == null) {
            throw new IllegalStateException("a value in " + unit + " cannot be converted to " + target);
        }
        return converted.doubleValue();
    }

    /**
     * Returns this series with its values normalised onto the site's sign convention.
     *
     * @param invert whether the source counts the opposite way round
     * @return the normalised series, or this one when nothing has to change
     */
    public ForecastSeries normalised(boolean invert) {
        return invert ? mapValues(value -> SignConvention.normalise(value, true)) : this;
    }

    /**
     * Returns this series with every value passed through a function, keeping everything else.
     *
     * @param function what to do to each value
     * @return the mapped series
     */
    public ForecastSeries mapValues(DoubleUnaryOperator function) {
        List<Slot> mapped = new ArrayList<>(values.size());
        for (Slot slot : values.slots()) {
            mapped.add(new Slot(slot.start(), slot.end(), function.applyAsDouble(slot.value())));
        }
        return new ForecastSeries(role, sourceId, unit, generatedAt, new SlotSeries(mapped, values.sense()));
    }

    /**
     * Returns this series with different numbers under the same identity.
     *
     * @param newValues the numbers to carry instead
     * @return the series carrying the given numbers
     */
    public ForecastSeries withValues(SlotSeries newValues) {
        return new ForecastSeries(role, sourceId, unit, generatedAt, newValues);
    }

    /**
     * Tells whether this series is older than a given age.
     *
     * @param now the moment to measure from
     * @param maximumAge how old a run may be before it counts as stale
     * @return {@code true} if the run that produced this series is older than the given age
     */
    public boolean isStale(Instant now, Duration maximumAge) {
        return generatedAt.plus(maximumAge).isBefore(now);
    }

    private static double hours(Duration duration) {
        return duration.toNanos() / (double) Duration.ofHours(1).toNanos();
    }
}
