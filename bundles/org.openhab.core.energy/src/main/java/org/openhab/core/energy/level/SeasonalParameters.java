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
package org.openhab.core.energy.level;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The seasonal parameter sets of the <em>Seasonal window defaults</em> requirement: which derivation applies in which
 * part of the year, selected automatically by date.
 * <p>
 * The season boundaries are entirely user-supplied. The requirement gives "more encouraged hours in winter, fewer in
 * summer" as an example but never defines when winter starts, and meteorological, astronomical and heating-season
 * boundaries all differ - so nothing is hardcoded here.
 * <p>
 * A season is a <em>local</em>-date concept while a price series is a list of instants, so the zone is part of the
 * configuration. On the merge track this would come from core's {@code TimeZoneProvider}; a pure function takes it as
 * data instead.
 *
 * @param zone the zone whose local date decides the season
 * @param seasons the configured seasons, tried in order
 * @param fallback the derivation to use for a date no season covers
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record SeasonalParameters(ZoneId zone, List<Season> seasons, LevelDerivation fallback) {

    /**
     * One season: a closed range of month-days and the derivation that applies inside it.
     * <p>
     * The range may wrap around the turn of the year, which is the normal case for winter: {@code from} November 1st
     * {@code toInclusive} February 28th covers December and January as well.
     *
     * @param name the season name, used in messages only
     * @param from the first month-day of the season, inclusive
     * @param toInclusive the last month-day of the season, inclusive
     * @param derivation the derivation that applies inside the season
     *
     * @author Stamate Viorel - Initial contribution
     */
    public record Season(String name, MonthDay from, MonthDay toInclusive, LevelDerivation derivation) {

        /**
         * Validates the season.
         *
         * @throws IllegalArgumentException if the name is blank
         */
        public Season {
            name = name.trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("name must not be blank");
            }
        }

        /**
         * Tests whether the given local date falls inside this season, wrap-around included.
         *
         * @param date the local date to test
         * @return {@code true} if the season covers the date
         */
        public boolean covers(LocalDate date) {
            MonthDay monthDay = MonthDay.from(date);
            if (from.compareTo(toInclusive) <= 0) {
                return monthDay.compareTo(from) >= 0 && monthDay.compareTo(toInclusive) <= 0;
            }
            return monthDay.compareTo(from) >= 0 || monthDay.compareTo(toInclusive) <= 0;
        }
    }

    /**
     * Validates the parameters and takes a defensive immutable copy of the season list.
     *
     * @throws IllegalArgumentException if no season is configured
     */
    public SeasonalParameters {
        if (seasons.isEmpty()) {
            throw new IllegalArgumentException("seasons must not be empty");
        }
        seasons = List.copyOf(seasons);
    }

    /**
     * Returns the first configured season covering the given local date.
     * <p>
     * Overlapping seasons are resolved by declaration order rather than rejected, because the corpus says nothing
     * about overlaps and refusing a configuration a user can express is the harsher of the two guesses.
     *
     * @param date the local date to look up
     * @return the covering season, or {@link Optional#empty()} if none covers it
     */
    public Optional<Season> seasonFor(LocalDate date) {
        for (Season season : seasons) {
            if (season.covers(date)) {
                return Optional.of(season);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the derivation that applies on the given local date, falling back when no season covers it.
     *
     * @param date the local date to look up
     * @return the derivation to use
     */
    public LevelDerivation derivationFor(LocalDate date) {
        Optional<Season> season = seasonFor(date);
        return season.isPresent() ? season.get().derivation() : fallback;
    }
}
