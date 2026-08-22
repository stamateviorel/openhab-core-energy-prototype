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

import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.window.SeriesSense;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.types.State;
import org.openhab.core.types.TimeSeries;

/**
 * The bridge between core's own future-timestamped transport - {@link TimeSeries} - and the slot geometry every
 * calculation in this framework works on.
 * <p>
 * <strong>Why a conversion is needed at all, when both are "a series over time".</strong> A {@code TimeSeries} entry
 * is an <em>instant</em> and a state; a {@link Slot} is a half-open <em>interval</em> and a number. Turning the first
 * into the second means deciding how long each entry applies for, and core does not say: it stores when a value
 * starts, never when it stops. The rule taken here is the one the rest of the framework already uses - a value is
 * held from its own timestamp until the next one, which is the LEFT-Riemann convention D16 · A13 fixed for costing -
 * so no entry is ever interpolated and a sparse series stays sparse.
 * <p>
 * <strong>The last entry has no successor, and that is a genuine gap in the model.</strong> Nothing in a
 * {@code TimeSeries} says how long its final value applies for: a day-ahead price series published at 13:00 for the
 * next day ends with an entry at 23:00 that plainly means "until midnight", but the transport does not carry the
 * "until". This class asks the caller for that width rather than inventing one, and the convenience overload assumes
 * the final entry is as wide as the one before it - stated here, tested, and reported as a corpus gap rather than
 * hidden. A single-entry series therefore <em>has</em> to be given a width.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ForecastTimeSeries {

    private ForecastTimeSeries() {
    }

    /**
     * Converts a core {@link TimeSeries} into slots, holding each value until the next entry.
     *
     * @param series the transported series
     * @param unit the unit the values are read in; a typed entry is converted into it, an untyped one is taken as
     *            already being in it
     * @param sense whether a lower or a higher value is the better one
     * @param lastEntryWidth how long the final entry applies for, which the transport itself does not carry
     * @return the slots
     * @throws IllegalArgumentException if the series is empty, an entry cannot be read as a number in the given unit,
     *             or the final width is not positive
     */
    public static SlotSeries toSlots(TimeSeries series, Unit<?> unit, SeriesSense sense, Duration lastEntryWidth) {
        if (lastEntryWidth.isZero() || lastEntryWidth.isNegative()) {
            throw new IllegalArgumentException("lastEntryWidth must be positive but was " + lastEntryWidth);
        }
        List<TimeSeries.Entry> entries = series.getStates().toList();
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("a time series with no entry carries no forecast");
        }
        List<Slot> slots = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            TimeSeries.Entry entry = entries.get(i);
            Instant end = i + 1 < entries.size() ? entries.get(i + 1).timestamp()
                    : entry.timestamp().plus(lastEntryWidth);
            slots.add(new Slot(entry.timestamp(), end, value(entry.state(), unit)));
        }
        return new SlotSeries(slots, sense);
    }

    /**
     * Converts a core {@link TimeSeries} into slots, assuming the final entry is as wide as the one before it.
     *
     * @param series the transported series
     * @param unit the unit the values are read in
     * @param sense whether a lower or a higher value is the better one
     * @return the slots
     * @throws IllegalArgumentException if the series holds fewer than two entries, in which case the final width
     *             cannot be assumed and has to be given
     */
    public static SlotSeries toSlots(TimeSeries series, Unit<?> unit, SeriesSense sense) {
        List<TimeSeries.Entry> entries = series.getStates().toList();
        if (entries.size() < 2) {
            throw new IllegalArgumentException("the width of the last entry cannot be assumed from a series of "
                    + entries.size() + " entries; state it explicitly");
        }
        Duration lastWidth = Duration.between(entries.get(entries.size() - 2).timestamp(),
                entries.getLast().timestamp());
        return toSlots(series, unit, sense, lastWidth);
    }

    /**
     * Converts a forecast into a core {@link TimeSeries} for transport or publication.
     * <p>
     * Every slot contributes one entry at its own start, carrying a typed quantity, so a consumer that never heard of
     * this framework still gets values it can render and convert. The end of a slot is <em>not</em> transported,
     * because the transport has nowhere to put it; a round trip through
     * {@link #toSlots(TimeSeries, Unit, SeriesSense, Duration)} therefore needs the final width again.
     * <p>
     * <strong>The policy is the caller's, and it matters more than it looks.</strong> {@link TimeSeries.Policy#ADD}
     * updates the entries it carries and leaves everything else alone; {@link TimeSeries.Policy#REPLACE} first
     * deletes everything stored between the series' own first and last timestamps. A sparse refresh published with
     * {@code REPLACE} therefore erases the baseline entries in its own gaps - which is precisely the year-long
     * baseline the layered-prediction requirement exists to protect.
     *
     * @param forecast the forecast to transport
     * @param policy what the receiving side should do with entries it already has
     * @return the transported series
     */
    public static TimeSeries toTimeSeries(ForecastSeries forecast, TimeSeries.Policy policy) {
        TimeSeries series = new TimeSeries(policy);
        for (Slot slot : forecast.values().slots()) {
            series.add(slot.start(), new QuantityType<>(slot.value(), forecast.unit()));
        }
        return series;
    }

    private static double value(State state, Unit<?> unit) {
        if (state instanceof QuantityType<?> quantity) {
            @Nullable
            QuantityType<?> converted = quantity.toUnit(unit);
            if (converted == null) {
                throw new IllegalArgumentException("a value in " + quantity.getUnit() + " is not a " + unit);
            }
            return converted.doubleValue();
        }
        if (state instanceof DecimalType decimal) {
            return decimal.doubleValue();
        }
        throw new IllegalArgumentException("a " + state.getClass().getSimpleName() + " carries no forecast value");
    }

    /**
     * Reads a state as a number in a unit, for callers that hold single states rather than a whole series.
     *
     * @param state the state to read
     * @param unit the unit to read it in
     * @return the value, or {@code null} when the state carries no number in that unit
     */
    public static @Nullable Double valueOf(State state, Unit<?> unit) {
        try {
            return value(state, unit);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
