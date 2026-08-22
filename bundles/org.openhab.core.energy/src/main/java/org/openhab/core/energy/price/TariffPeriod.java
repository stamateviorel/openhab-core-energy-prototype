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

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * One condition of a conditional grid tariff: "winter, Monday to Saturday, 07:00 to 22:00, this much per kilowatt
 * hour".
 * <p>
 * That sentence is the requirement's own example, and masipila's Caruna seasonal tariff is the production case behind
 * it. The three conditions are independent and any of them may be left open - an empty month set means every month,
 * an empty day set means every day, and a period may run from midnight to midnight.
 * <p>
 * <strong>A period that wraps midnight is a period, not two.</strong> {@code 22:00} to {@code 07:00} is a night
 * tariff, and writing it as two periods would make a user state their own tariff twice and keep the two in step by
 * hand.
 *
 * @param id the period's stable id, which is what a log line names
 * @param months the months the period applies in, empty meaning every month
 * @param days the days of the week the period applies on, empty meaning every day
 * @param from the local time the period starts at, inclusive
 * @param to the local time the period ends at, exclusive; equal to {@code from} meaning the whole day
 * @param amount what this period adds per unit of energy, in the series' own currency and unit
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record TariffPeriod(String id, Set<Month> months, Set<DayOfWeek> days, LocalTime from, LocalTime to,
        double amount) {

    /**
     * Validates the period and takes defensive immutable copies.
     *
     * @throws IllegalArgumentException if the id is blank or the amount is not a finite number
     */
    public TariffPeriod {
        if (id.isBlank()) {
            throw new IllegalArgumentException("a tariff period needs an id");
        }
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("the amount of tariff period '" + id + "' must be a finite number");
        }
        months = months.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(months));
        days = days.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(days));
    }

    /**
     * Creates a period that applies at every hour of every day it is in season.
     *
     * @param id the period's stable id
     * @param months the months, empty meaning every month
     * @param amount what the period adds per unit of energy
     * @return the period
     */
    public static TariffPeriod allDay(String id, Set<Month> months, double amount) {
        return new TariffPeriod(id, months, Set.of(), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, amount);
    }

    /**
     * Tells whether this period is in force at a moment, read in the calendar's own zone.
     *
     * @param moment the moment, already in the tariff calendar's zone
     * @return {@code true} if all three conditions hold
     */
    public boolean applies(ZonedDateTime moment) {
        if (!months.isEmpty() && !months.contains(moment.getMonth())) {
            return false;
        }
        if (!days.isEmpty() && !days.contains(moment.getDayOfWeek())) {
            return false;
        }
        return coversTime(moment.toLocalTime());
    }

    /**
     * Tells whether the time of day falls inside this period, midnight wrap included.
     *
     * @param time the local time
     * @return {@code true} if the period covers it
     */
    public boolean coversTime(LocalTime time) {
        if (from.equals(to)) {
            return true;
        }
        if (from.isBefore(to)) {
            return !time.isBefore(from) && time.isBefore(to);
        }
        return !time.isBefore(from) || time.isBefore(to);
    }
}
