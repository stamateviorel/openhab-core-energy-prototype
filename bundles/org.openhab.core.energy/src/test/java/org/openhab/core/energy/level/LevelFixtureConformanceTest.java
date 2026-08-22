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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.Instant;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.level.PlannedLevelSchedule.PlannedLevel;
import org.openhab.core.energy.window.SelectionStrategy;
import org.openhab.core.energy.window.SlotSelection;
import org.openhab.core.energy.window.SlotSeries;

/**
 * The conformance gate: the three acceptance vectors of the {@code energy-levels} capability, reproduced slot by
 * slot.
 * <p>
 * The fixtures come from the spec corpus at {@code /home/openhab/work/openhab-ems-spec/fixtures} and are copied
 * verbatim into {@code src/test/resources/fixtures}; see {@link FixtureCsv} for their provenance. The corpus'
 * prototype rules state that a classifier which does not reproduce them exactly "is wrong, not close", so every
 * assertion here is element-by-element and none of them is a count or a spot check.
 * <p>
 * Numeric level codes in {@code expected-planned-levels.csv} run the other way round from the usual "0 is best"
 * intuition: 3 is the cheapest band (overcapacity) and 0 the most expensive (blocked).
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class LevelFixtureConformanceTest {

    private static final String PLANNED_LEVELS = "/fixtures/expected-planned-levels.csv";
    private static final String HEATING_CONTROL = "/fixtures/expected-heating-control.csv";
    private static final String BOILER_CONTROL = "/fixtures/expected-boiler-control.csv";

    /**
     * Vector 1 - {@code dayahead-prices.csv} classified with 4 overcapacity, 4 low-price and 4 blocked hours must
     * equal {@code expected-planned-levels.csv}.
     */
    @Test
    public void fixedCountDerivationReproducesThePlannedLevelFixture() {
        SlotSeries series = FixtureCsv.prices();

        PlannedLevelSchedule schedule = LevelDerivation.fixedCounts(LevelCounts.of(4, 4, 4)).derive(series);

        assertScheduleMatches(schedule, PLANNED_LEVELS);
    }

    /**
     * Vector 2 - the 8 cheapest slots (9 kW direct heating, 8 h needed, priority 1) must equal
     * {@code expected-heating-control.csv}.
     */
    @Test
    public void cheapestSlotsSelectionReproducesTheHeatingFixture() {
        SlotSeries series = FixtureCsv.prices();

        SlotSelection heating = SelectionStrategy.cheapestSlots().select(series, 8);

        assertSelectionMatches(heating, series, HEATING_CONTROL);
    }

    /**
     * Vector 3 - the next 3 best slots after the heating schedule, with no overlap (3 kW boiler, 3 h needed,
     * priority 2) must equal {@code expected-boiler-control.csv}.
     */
    @Test
    public void cheapestSlotsSelectionReproducesTheBoilerFixtureWithoutOverlappingHeating() {
        SlotSeries series = FixtureCsv.prices();
        SelectionStrategy strategy = SelectionStrategy.cheapestSlots();
        SlotSelection heating = strategy.select(series, 8);

        SlotSelection boiler = strategy.select(series, 3, heating);

        assertSelectionMatches(boiler, series, BOILER_CONTROL);
        for (Integer index : boiler.indices()) {
            assertThat("boiler slot " + index + " overlaps the heating schedule", heating.contains(index), is(false));
        }
    }

    /**
     * The two derivation strategies are a seam because the corpus has not chosen between them. On the fixture - which
     * has no repeated price - they agree exactly, which is worth pinning: it means the open question is about
     * behaviour on ties and on adaptivity, not about this vector.
     */
    @Test
    public void percentileDerivationAgreesWithFixedCountsOnTheTieFreeFixture() {
        SlotSeries series = FixtureCsv.prices();

        PlannedLevelSchedule schedule = LevelDerivation.percentiles(LevelPercentiles.of(4.0 / 24, 4.0 / 24, 4.0 / 24))
                .derive(series);

        assertScheduleMatches(schedule, PLANNED_LEVELS);
    }

    /**
     * The heating vector is exactly the union of the two cheap bands of the level fixture - the corpus' own
     * consistency claim, and a cross-check that selection and derivation rank prices the same way.
     */
    @Test
    public void theHeatingFixtureIsExactlyTheTwoCheapBandsOfTheLevelFixture() {
        SlotSeries series = FixtureCsv.prices();
        PlannedLevelSchedule schedule = LevelDerivation.fixedCounts(LevelCounts.of(4, 4, 4)).derive(series);

        SlotSelection cheapBands = schedule.slotsAt(EnergyLevel.OVERCAPACITY)
                .union(schedule.slotsAt(EnergyLevel.ENCOURAGED));

        assertSelectionMatches(cheapBands, series, HEATING_CONTROL);
    }

    private static void assertScheduleMatches(PlannedLevelSchedule schedule, String fixture) {
        List<FixtureCsv.Row> expected = FixtureCsv.read(fixture);
        assertThat("slot count of " + fixture, schedule.size(), is(expected.size()));
        for (int i = 0; i < expected.size(); i++) {
            FixtureCsv.Row row = expected.get(i);
            PlannedLevel entry = schedule.entries().get(i);
            assertThat("slot " + i + " start", entry.start(), is(row.timestamp()));
            assertThat("level at " + row.timestamp(), entry.level().code(), is((int) row.value()));
        }
    }

    private static void assertSelectionMatches(SlotSelection selection, SlotSeries series, String fixture) {
        List<FixtureCsv.Row> expected = FixtureCsv.read(fixture);
        assertThat("slot count of " + fixture, series.size(), is(expected.size()));
        for (int i = 0; i < expected.size(); i++) {
            FixtureCsv.Row row = expected.get(i);
            Instant slotStart = series.slotAt(i).start();
            assertThat("slot " + i + " start", slotStart, is(row.timestamp()));
            boolean expectedOn = row.value() != 0;
            assertThat("switch state at " + row.timestamp(), selection.contains(i), is(expectedOn));
        }
    }
}
