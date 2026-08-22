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
package org.openhab.core.energy.price;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import javax.measure.Unit;
import javax.measure.quantity.Energy;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.window.SeriesSense;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.library.dimension.Currency;
import org.openhab.core.library.dimension.EnergyPrice;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.types.TimeSeries;

/**
 * A future-timestamped series of energy prices - the shape {@code price-data} <em>Prices as future-timestamped
 * series</em> requires, "not as per-slot channels".
 * <p>
 * <strong>What is typed here and what is not.</strong> The geometry and the numbers are a plain
 * {@link SlotSeries}, which is what the shared window calculations rank and integrate and what every other plane
 * uses too. What this record adds is everything that makes those numbers a <em>price</em>: the currency, the unit of
 * energy they are per, the time zone of the market that produced them, and whether they value an imported or an
 * exported kilowatt-hour. Keeping the two apart is what lets one calculation serve prices, carbon intensity and a
 * photovoltaic forecast without any of them borrowing another's vocabulary.
 * <p>
 * <strong>Currency and energy unit are carried separately rather than as one {@code Unit<EnergyPrice>}.</strong>
 * {@link #priceUnit()} composes them on demand for anyone who wants the typed unit, but the pair is the stored form,
 * because it is the only one this plane can do arithmetic on without an exchange-rate service -
 * see {@link EnergyPriceUnits} for the two findings behind that.
 * <p>
 * <strong>Resolution-agnostic, by inheritance.</strong> Nothing here or below assumes a slot width. A day-ahead
 * market that switches to fifteen minutes changes the number of slots and nothing else, and mstormi's mixed series -
 * firm quarter-hours for tomorrow, hourly estimates for the rest of the week, in <em>one</em> series - is the same
 * type with slots of two widths. That is {@code price-data} <em>Time resolution</em>, and it needed no code: it is a
 * property of {@link Slot} carrying its own end.
 *
 * @param values the prices and their slots, in {@code currency} per {@code energyUnit}
 * @param currency the currency the prices are denominated in
 * @param energyUnit the unit of energy the prices are per
 * @param marketZone the time zone of the market that produced the series, which is what names its delivery day
 * @param direction whether these prices value an imported or an exported kilowatt-hour
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record EnergyPriceSeries(SlotSeries values, Unit<Currency> currency, Unit<Energy> energyUnit, ZoneId marketZone,
        PriceDirection direction) {

    /**
     * Builds a series of equally wide slots.
     * <p>
     * The sense follows the direction ({@link PriceDirection#sense()}), so the best slots of a consumption series are
     * its cheapest and the best slots of a feed-in series are its most lucrative.
     *
     * @param firstStart the start of the first slot
     * @param width the width of every slot
     * @param currency the currency
     * @param energyUnit the unit of energy the prices are per
     * @param marketZone the market's time zone
     * @param direction the direction these prices value
     * @param prices the price of each slot, in chronological order
     * @return the series
     */
    public static EnergyPriceSeries of(Instant firstStart, Duration width, Unit<Currency> currency,
            Unit<Energy> energyUnit, ZoneId marketZone, PriceDirection direction, double... prices) {
        return new EnergyPriceSeries(SlotSeries.uniform(firstStart, width, prices).withSense(direction.sense()),
                currency, energyUnit, marketZone, direction);
    }

    /**
     * Returns the typed unit these prices are in.
     *
     * @return the price unit, for example {@code EUR/kWh}
     */
    public Unit<EnergyPrice> priceUnit() {
        return EnergyPriceUnits.priceUnit(currency, energyUnit);
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
     * Returns one slot's time interval.
     *
     * @param index the zero-based slot index
     * @return the slot
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public Slot slotAt(int index) {
        return values.slotAt(index);
    }

    /**
     * Returns one slot's price as a quantity.
     *
     * @param index the zero-based slot index
     * @return the price, in {@link #priceUnit()}
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public QuantityType<EnergyPrice> priceAt(int index) {
        return new QuantityType<>(values.valueAt(index), priceUnit());
    }

    /**
     * Returns the start of the first slot.
     *
     * @return the first instant the series covers
     */
    public Instant start() {
        return values.start();
    }

    /**
     * Returns the end of the last slot.
     *
     * @return the first instant after the series
     */
    public Instant end() {
        return values.end();
    }

    /**
     * Returns the delivery day this series belongs to, named in the market's own zone.
     * <p>
     * <strong>Never inferred from the site's zone or from UTC.</strong> The corpus' own fixture is the evidence:
     * {@code fixtures/dayahead-prices.csv} runs 2023-03-23T23:00Z to 2023-03-24T22:00Z, which is exactly the CET
     * calendar day 2023-03-24 - neither the UTC day, which it straddles, nor the publisher's own EET day. A delivery
     * day routinely spans two local dates and nothing but the market's zone says which one names it (owner decision
     * D17, row EL-12c).
     * <p>
     * A site whose own zone differs reads its own seasons and its own clock from {@code TimeZoneProvider}; the two
     * are deliberately not the same lookup.
     *
     * @return the market-zone calendar date the series starts on
     */
    public LocalDate deliveryDay() {
        return start().atZone(marketZone).toLocalDate();
    }

    /**
     * Tells whether this series covers exactly one whole delivery day in the market's zone.
     * <p>
     * Length is never assumed to be twenty-four hours. The day a market switches to summer time is twenty-three
     * hours long and the day it switches back is twenty-five, and both are whole delivery days.
     *
     * @return {@code true} if the series starts at the market-zone start of its delivery day and ends at the start of
     *         the next one
     */
    public boolean coversWholeDeliveryDay() {
        LocalDate day = deliveryDay();
        Instant dayStart = day.atStartOfDay(marketZone).toInstant();
        Instant nextDayStart = day.plusDays(1).atStartOfDay(marketZone).toInstant();
        return start().equals(dayStart) && end().equals(nextDayStart);
    }

    /**
     * Returns the same prices denominated per a different unit of energy.
     * <p>
     * This is the typed half of the requirement's "unit conversion such as EUR/MWh to ct/kWh": the currency is
     * untouched and only the denominator moves, which is arithmetic that needs no exchange rate.
     *
     * @param target the unit of energy the prices should be per
     * @return the converted series, or this one when the unit already matches
     */
    public EnergyPriceSeries toEnergyUnit(Unit<Energy> target) {
        if (target.equals(energyUnit)) {
            return this;
        }
        double factor = EnergyPriceUnits.energyDenominatorFactor(energyUnit, target);
        return withValues(scaled(values, factor)).withEnergyUnit(target);
    }

    /**
     * Returns this series with the prices multiplied by a factor, which is how a VAT multiplier, a scalar unit
     * conversion and a cents-for-display convention are all applied.
     *
     * @param factor the multiplier
     * @return the scaled series
     */
    public EnergyPriceSeries scaledBy(double factor) {
        return withValues(scaled(values, factor));
    }

    /**
     * Returns this series with an amount added to every slot, which is how a fixed fee is applied.
     * <p>
     * The result is not clamped. A fee is not required to be positive and a spot price more negative than the fee is
     * positive leaves an effective price below zero, which is carried through as it stands.
     *
     * @param amount the amount to add, in this series' own currency per its own unit of energy
     * @return the shifted series
     */
    public EnergyPriceSeries plus(double amount) {
        List<Slot> shifted = new ArrayList<>(values.size());
        for (Slot slot : values.slots()) {
            shifted.add(new Slot(slot.start(), slot.end(), slot.value() + amount));
        }
        return withValues(new SlotSeries(shifted, values.sense()));
    }

    /**
     * Returns this series with the numbers of another one, which is how an arithmetic step keeps every property that
     * is not arithmetic.
     *
     * @param newValues the new numbers
     * @return the series
     */
    public EnergyPriceSeries withValues(SlotSeries newValues) {
        return new EnergyPriceSeries(newValues, currency, energyUnit, marketZone, direction);
    }

    /**
     * Returns this series relabelled as being per another unit of energy, <strong>without touching the numbers</strong>
     * - which only a caller that has already scaled them should do.
     *
     * @param newEnergyUnit the unit of energy the prices are per
     * @return the relabelled series
     */
    public EnergyPriceSeries withEnergyUnit(Unit<Energy> newEnergyUnit) {
        return new EnergyPriceSeries(values, currency, newEnergyUnit, marketZone, direction);
    }

    /**
     * Returns this series read as valuing the other direction, keeping the numbers.
     *
     * @param newDirection the direction
     * @return the series
     */
    public EnergyPriceSeries withDirection(PriceDirection newDirection) {
        return new EnergyPriceSeries(values.withSense(newDirection.sense()), currency, energyUnit, marketZone,
                newDirection);
    }

    /**
     * Returns this series with a newer publication laid over it - the "a newly published value for a timestamp
     * overwrites the older value" half of <em>Prices as future-timestamped series</em>.
     * <p>
     * <strong>The rule is stated in covered time, not in timestamps</strong>, because two publications need not share
     * a geometry: tomorrow can arrive at fifteen minutes while the rest of the week is hourly. Every slot of
     * {@code newer} is kept, and a slot of this series survives only if no slot of {@code newer} overlaps it. Where
     * the two geometries agree - the ordinary case of a market republishing the same day - that is exactly
     * "same timestamp, newer wins".
     * <p>
     * Nothing older is deleted beyond what the newer publication actually covers. That matters, and it is where a
     * naive use of {@link TimeSeries.Policy#REPLACE} differs: {@code REPLACE} is scoped to the whole span between a
     * series' first and last entry, so a <em>sparse</em> refresh published that way deletes everything between its own
     * points, including days it says nothing about.
     *
     * @param newer the newer publication
     * @return the merged series
     * @throws IllegalArgumentException if the two series are not the same currency, unit of energy, market zone and
     *             direction
     */
    public EnergyPriceSeries overwriteWith(EnergyPriceSeries newer) {
        if (!EnergyPriceUnits.isSameCurrency(currency, newer.currency) || !energyUnit.equals(newer.energyUnit)
                || !marketZone.equals(newer.marketZone) || direction != newer.direction) {
            throw new IllegalArgumentException("a newer publication must describe the same prices: " + describe()
                    + " cannot be overwritten by " + newer.describe());
        }
        List<Slot> merged = new ArrayList<>(newer.values.slots());
        for (Slot existing : values.slots()) {
            boolean overlapped = false;
            for (Slot replacement : newer.values.slots()) {
                if (existing.start().isBefore(replacement.end()) && replacement.start().isBefore(existing.end())) {
                    overlapped = true;
                    break;
                }
            }
            if (!overlapped) {
                merged.add(existing);
            }
        }
        merged.sort((first, second) -> first.start().compareTo(second.start()));
        return withValues(new SlotSeries(merged, values.sense()));
    }

    /**
     * Renders this series as an openHAB {@link TimeSeries} of {@code Number:EnergyPrice} states, one entry per slot
     * start.
     * <p>
     * <strong>The policy is a required argument and has no default</strong>, deliberately.
     * {@link TimeSeries.Policy#REPLACE} clears the whole span between the series' first and last entry before storing
     * it, so it is right for republishing a contiguous day and wrong for a sparse refresh, which would silently take
     * the untouched entries between its own points with it. Whoever publishes knows which of the two they are doing;
     * a default here would guess.
     * <p>
     * This bundle cannot publish the result - an Item write is exactly what it may not do. The value is built here
     * and written by the companion {@code org.openhab.core.energy.series} bundle, which is the same split decision
     * D23 drew.
     *
     * @param policy how a persistence service should treat the entries
     * @return the time series
     */
    public TimeSeries toTimeSeries(TimeSeries.Policy policy) {
        TimeSeries series = new TimeSeries(policy);
        Unit<EnergyPrice> unit = priceUnit();
        for (Slot slot : values.slots()) {
            series.add(slot.start(), new QuantityType<>(slot.value(), unit));
        }
        return series;
    }

    /**
     * Returns a short description of what this series is denominated in, for a message a user has to act on.
     *
     * @return the description, for example {@code EUR/kWh CONSUMPTION in Europe/Paris}
     */
    public String describe() {
        return currency.getName() + "/" + energyUnit + " " + direction + " in " + marketZone;
    }

    private static SlotSeries scaled(SlotSeries series, double factor) {
        List<Slot> result = new ArrayList<>(series.size());
        for (Slot slot : series.slots()) {
            result.add(new Slot(slot.start(), slot.end(), slot.value() * factor));
        }
        SeriesSense sense = factor < 0 ? opposite(series.sense()) : series.sense();
        return new SlotSeries(result, sense);
    }

    private static SeriesSense opposite(SeriesSense sense) {
        return sense == SeriesSense.LOWER_IS_BETTER ? SeriesSense.HIGHER_IS_BETTER : SeriesSense.LOWER_IS_BETTER;
    }
}
