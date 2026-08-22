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
package org.openhab.core.energy.objective;

import java.time.Instant;

import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.window.SeriesSense;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * The carbon content of grid electricity, per future slot: a CO<sub>2</sub> intensity, or the renewable share that
 * stands in for one.
 * <p>
 * <strong>It is the same shape a price and a forecast are, on purpose.</strong> The numbers and their slot geometry
 * live in {@link SlotSeries} - ordered, non-overlapping, gaps and mixed widths permitted, the tie-break in one place -
 * and this record adds the three facts that make a series identifiable: who published it, in what unit, and when. The
 * requirement that carbon data be "a first-class series through the same data plane as prices and forecasts" is
 * discharged by that reuse rather than by a parallel implementation.
 * <p>
 * <strong>The unit is load-bearing, not decoration.</strong> Core ships an {@code EmissionIntensity} dimension and
 * {@code g/kWh}, so "is this an intensity or a share?" is answerable from the unit rather than from a flag somebody
 * has to set correctly - {@link #isEmissionIntensity()}. That distinction decides one thing and one thing only: the
 * carbon objective's export-credit term scales a value by a fraction of the load's energy, which is meaningful on a
 * scale where zero means no emissions and meaningless on a share, and converting a share back to an intensity needs
 * an upper bound nothing in the corpus states. Ranking works on either.
 *
 * @param sourceId the source that published this series
 * @param unit the unit the values are in - {@code g/kWh} for an intensity, a percentage or a ratio for a share
 * @param generatedAt when the source produced these numbers, so staleness is answerable without a clock in here
 * @param values the numbers, their slot geometry and their sense
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record CarbonSeries(String sourceId, Unit<?> unit, Instant generatedAt, SlotSeries values) {

    /**
     * Validates the series.
     *
     * @throws IllegalArgumentException if the source id is blank
     */
    public CarbonSeries {
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
    }

    /**
     * Creates an emission-intensity series in grams of CO<sub>2</sub> per kilowatt-hour, where a lower value is the
     * better slot.
     *
     * @param sourceId the source that published it
     * @param generatedAt when it was produced
     * @param values the numbers and their slot geometry
     * @return the series, read as lower-is-better whatever sense the slot series carried
     */
    public static CarbonSeries intensity(String sourceId, Instant generatedAt, SlotSeries values) {
        return new CarbonSeries(sourceId, Units.GRAM_PER_KILOWATT_HOUR, generatedAt,
                values.withSense(SeriesSense.LOWER_IS_BETTER));
    }

    /**
     * Creates a renewable-share series as a percentage, where a higher value is the better slot.
     *
     * @param sourceId the source that published it
     * @param generatedAt when it was produced
     * @param values the numbers and their slot geometry
     * @return the series, read as higher-is-better whatever sense the slot series carried
     */
    public static CarbonSeries renewableShare(String sourceId, Instant generatedAt, SlotSeries values) {
        return new CarbonSeries(sourceId, Units.PERCENT, generatedAt, values.withSense(SeriesSense.HIGHER_IS_BETTER));
    }

    /**
     * Returns whether this series is an emission intensity rather than a share.
     * <p>
     * Asked of the unit rather than of a flag, so that a source publishing {@code g/kWh} gets the full behaviour
     * without having to know the question exists.
     *
     * @return {@code true} if the values are an emission intensity
     */
    public boolean isEmissionIntensity() {
        return unit.isCompatible(Units.GRAM_PER_KILOWATT_HOUR);
    }

    /**
     * Returns which end of this series is the good end.
     *
     * @return the sense
     */
    public SeriesSense sense() {
        return values.sense();
    }

    /**
     * Returns the number of slots.
     *
     * @return the slot count
     */
    public int size() {
        return values.size();
    }

    /**
     * Returns one slot.
     *
     * @param index the slot index
     * @return the slot
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public Slot slotAt(int index) {
        return values.slotAt(index);
    }

    /**
     * Returns one value, in this series' own unit.
     *
     * @param index the slot index
     * @return the value
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public double valueAt(int index) {
        return values.valueAt(index);
    }

    /**
     * Returns one value as a quantity, so a caller can convert or render it without knowing which unit the source
     * chose.
     *
     * @param index the slot index
     * @return the value with its unit
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public QuantityType<?> quantityAt(int index) {
        return new QuantityType<>(valueAt(index), unit);
    }

    /**
     * Returns the same series carrying different numbers - a ranking with the export credit applied, for instance -
     * keeping its identity, its unit and its sense.
     *
     * @param derived the derived numbers
     * @return the derived series
     * @throws IllegalArgumentException if the derived series has a different number of slots
     */
    public CarbonSeries withValues(SlotSeries derived) {
        if (derived.size() != values.size()) {
            throw new IllegalArgumentException("a derived carbon series must keep the slot geometry: expected "
                    + values.size() + " slots but got " + derived.size());
        }
        return new CarbonSeries(sourceId, unit, generatedAt, derived.withSense(values.sense()));
    }
}
