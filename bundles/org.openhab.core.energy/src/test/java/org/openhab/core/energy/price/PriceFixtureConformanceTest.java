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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.level.FixtureCsv;
import org.openhab.core.energy.window.SelectionStrategy;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSelection;
import org.openhab.core.energy.window.SlotSeries;

/**
 * The price plane's conformance gate: the corpus' own day-ahead vector, read through the <em>typed</em> price series
 * rather than through a bare list of doubles, must produce the same answers the level plane already produces.
 * <p>
 * This is the point of the fixture for this change. {@code fixtures/dayahead-prices.csv} is real market data with a
 * known cheapest-slot answer, and wave 1 reproduced it; wave 2 puts a currency, a unit of energy, a market zone and a
 * direction around those numbers, and the risk that creates is that the wrapper quietly changes an answer - by
 * rounding on the way through a {@code QuantityType}, by reordering on the way through a sense, or by resolving a tie
 * differently. Every test here asserts against the shipped vectors or against the wave-1 path, element by element.
 * <p>
 * The fixture also carries the evidence for {@code price-data} <em>Delivery-day identity and the market zone</em>: its
 * slots run 2023-03-23T23:00Z to 2023-03-24T22:00Z, which is exactly the CET calendar day 2023-03-24 and neither the
 * UTC day it straddles nor the publisher's own EET day.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class PriceFixtureConformanceTest {

    private static final ZoneId MARKET_ZONE = ZoneId.of("CET");
    private static final String PLANNED_LEVELS = "/fixtures/expected-planned-levels.csv";
    private static final String HEATING_CONTROL = "/fixtures/expected-heating-control.csv";
    private static final String BOILER_CONTROL = "/fixtures/expected-boiler-control.csv";

    /**
     * Reads the corpus' day-ahead vector as a typed consumption-price series.
     * <p>
     * The prices are the fixture's own numbers, which its column names as cents per kilowatt hour. They are carried
     * here as EUR per kilowatt hour, because cents cannot be a currency in core (see {@link EnergyPriceUnits}) and
     * because <em>no test in this file depends on the scale</em>: every one of them is about an ordering, a selection
     * or an identity, and multiplying a whole series by a hundred changes none of those.
     *
     * @return the fixture as a price series
     */
    private static EnergyPriceSeries fixture() {
        List<FixtureCsv.Row> rows = FixtureCsv.read("/fixtures/dayahead-prices.csv");
        List<Slot> slots = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            FixtureCsv.Row row = rows.get(i);
            Instant end = i + 1 < rows.size() ? rows.get(i + 1).timestamp()
                    : row.timestamp().plus(Duration.between(rows.get(i - 1).timestamp(), row.timestamp()));
            slots.add(new Slot(row.timestamp(), end, row.value()));
        }
        return new EnergyPriceSeries(new SlotSeries(slots, PriceDirection.CONSUMPTION.sense()),
                EnergyPriceUnits.currency("EUR"), EnergyPriceUnits.defaultEnergyUnit(), MARKET_ZONE,
                PriceDirection.CONSUMPTION);
    }

    /**
     * Vector 1 - the delivery day is named in the market's zone, and it is neither the UTC day nor a guess.
     */
    @Test
    public void theFixtureDayIsTheCetCalendarDayAndNotTheUtcOne() {
        EnergyPriceSeries prices = fixture();

        assertThat(prices.deliveryDay(), is(LocalDate.of(2023, 3, 24)));
        assertThat(prices.coversWholeDeliveryDay(), is(true));
        // the same instants under UTC name the day before, which is what makes the market zone load-bearing
        assertThat(prices.start().atZone(ZoneOffset.UTC).toLocalDate(), is(LocalDate.of(2023, 3, 23)));
        assertThat(prices.end().atZone(ZoneOffset.UTC).toLocalDate(), is(LocalDate.of(2023, 3, 24)));
    }

    /**
     * Vector 2 - the eight cheapest slots of the typed series are exactly
     * {@code fixtures/expected-heating-control.csv}, the 9 kW direct heating schedule.
     */
    @Test
    public void theSharedWindowCalculationReproducesTheHeatingVectorThroughTheTypedSeries() {
        EnergyPriceSeries prices = fixture();

        SlotSelection heating = SelectionStrategy.cheapestSlots().select(prices.values(), 8);

        assertSelectionMatches(heating, prices, HEATING_CONTROL);
    }

    /**
     * Vector 3 - the next three cheapest slots, with the heating schedule excluded, are exactly
     * {@code fixtures/expected-boiler-control.csv}.
     */
    @Test
    public void theSharedWindowCalculationReproducesTheBoilerVectorThroughTheTypedSeries() {
        EnergyPriceSeries prices = fixture();
        SelectionStrategy strategy = SelectionStrategy.cheapestSlots();

        SlotSelection heating = strategy.select(prices.values(), 8);
        SlotSelection boiler = strategy.select(prices.values(), 3, heating);

        assertSelectionMatches(boiler, prices, BOILER_CONTROL);
        for (Integer slot : boiler.indices()) {
            assertThat("the boiler must not share a slot with the heating", heating.contains(slot), is(false));
        }
    }

    /**
     * The wrapper changes nothing. The same request answered off the wave-1 series of bare doubles and off the typed
     * price series gives the identical selection, so a currency and a market zone cost no arithmetic.
     */
    @Test
    public void theTypedSeriesAndTheBareOneGiveTheIdenticalAnswer() {
        SlotSeries bare = FixtureCsv.prices();
        EnergyPriceSeries typed = fixture();

        for (int count = 1; count <= bare.size(); count++) {
            assertThat("selection of " + count + " slots",
                    SelectionStrategy.cheapestSlots().select(typed.values(), count),
                    is(SelectionStrategy.cheapestSlots().select(bare, count)));
        }
        assertThat(SelectionStrategy.consecutiveWindow().selectForDuration(typed.values(), Duration.ofHours(3)),
                is(SelectionStrategy.consecutiveWindow().selectForDuration(bare, Duration.ofHours(3))));
    }

    /**
     * {@code price-data} <em>Shared window calculations</em> requires the most expensive selections as well as the
     * cheapest, and no scenario in the corpus exercises one. The fixture can: the four most expensive slots of the
     * day are exactly the four the level fixture classifies as blocked, so the vector already contains the answer.
     */
    @Test
    public void theMostExpensiveSelectionIsTheBlockedBandOfTheLevelFixture() {
        EnergyPriceSeries prices = fixture();

        SlotSelection dearest = SelectionStrategy.worstSlots().select(prices.values(), 4);

        List<Integer> blocked = new ArrayList<>();
        List<FixtureCsv.Row> levels = FixtureCsv.read(PLANNED_LEVELS);
        for (int slot = 0; slot < levels.size(); slot++) {
            if (levels.get(slot).value() == 0) {
                blocked.add(slot);
            }
        }
        assertThat("the level fixture must contain a blocked band for this to prove anything", blocked, hasSize(4));
        assertThat(dearest.indices(), is(blocked));
    }

    /**
     * The best and the worst selection of one day never overlap, and together with the middle they account for every
     * slot exactly once - which is the property that would break first if the two rankings disagreed about a tie.
     */
    @Test
    public void theCheapestAndTheMostExpensiveSelectionsArePartitionsOfTheSameDay() {
        EnergyPriceSeries prices = fixture();

        SlotSelection cheapest = SelectionStrategy.cheapestSlots().select(prices.values(), 12);
        SlotSelection dearest = SelectionStrategy.worstSlots().select(prices.values(), 12);

        assertThat(cheapest.union(dearest).size(), is(prices.size()));
        for (Integer slot : cheapest.indices()) {
            assertThat(dearest.contains(slot), is(false));
        }
    }

    /**
     * The tie-break is owner decision D21 - <strong>the earlier slot wins</strong> - and it must not flip when the
     * ranking does. The fixture contains no repeated price, so the tie is introduced deliberately: two slots are given
     * the day's cheapest price and two the day's dearest, and both selections take the earlier of each pair.
     */
    @Test
    public void aTieGoesToTheEarlierSlotInBothDirections() {
        EnergyPriceSeries prices = fixture();
        List<Slot> tied = new ArrayList<>(prices.values().slots());
        // slots 13 and 23 already hold the two lowest prices; make them equal, and the same for the two highest
        tied.set(13, new Slot(tied.get(13).start(), tied.get(13).end(), 1.0));
        tied.set(23, new Slot(tied.get(23).start(), tied.get(23).end(), 1.0));
        tied.set(8, new Slot(tied.get(8).start(), tied.get(8).end(), 9.0));
        tied.set(9, new Slot(tied.get(9).start(), tied.get(9).end(), 9.0));
        EnergyPriceSeries withTies = prices.withValues(new SlotSeries(tied, prices.values().sense()));

        assertThat(SelectionStrategy.cheapestSlots().select(withTies.values(), 1).indices(), is(List.of(13)));
        assertThat(SelectionStrategy.worstSlots().select(withTies.values(), 1).indices(), is(List.of(8)));
        // and asking for both of a tied pair returns both, so the tie-break orders rather than excludes
        assertThat(SelectionStrategy.cheapestSlots().select(withTies.values(), 2).indices(), is(List.of(13, 23)));
    }

    /**
     * A day whose every effective price is below zero, from {@code price-data} <em>Shared window calculations</em>.
     * <p>
     * <strong>This vector is derived, not sourced.</strong> The corpus has no all-negative fixture -
     * {@code fixtures/README.md} asks for one - so the day is produced by negating the real one, and the expected
     * answer is derived with it: negation reverses the order exactly, so the cheapest slots of the negated day are the
     * most expensive slots of the real one. It is a genuine test of the requirement and it is <em>not</em> a
     * substitute for a real negative-price day, which would also exercise a mixture of signs within one day.
     */
    @Test
    public void aDayWhoseEveryPriceIsNegativeRanksInReverseAndIsNeverClamped() {
        EnergyPriceSeries prices = fixture();

        EnergyPriceSeries negated = prices.scaledBy(-1)
                .withValues(prices.scaledBy(-1).values().withSense(prices.values().sense()));

        for (int slot = 0; slot < negated.size(); slot++) {
            assertThat("nothing may be clamped at zero", negated.values().valueAt(slot), lessThan(0.0));
        }
        assertThat(SelectionStrategy.cheapestSlots().select(negated.values(), 8).indices(),
                is(SelectionStrategy.worstSlots().select(prices.values(), 8).indices()));
        // and the most negative total wins: the chosen window really is the day's cheapest under the signed values
        assertThat(SelectionStrategy.cheapestSlots().select(negated.values(), 1).indices(),
                is(SelectionStrategy.worstSlots().select(prices.values(), 1).indices()));
    }

    /**
     * A grid-price pipeline that only scales and shifts cannot reorder the day, so a site that adds VAT and a
     * transfer fee optimises in exactly the same hours it would have without them. The point is not that the
     * adjustments do nothing - the numbers change - but that the vector's answer is stable under them.
     *
     * @throws PriceCompositionException never, and the test fails if it does
     */
    @Test
    public void vatAndAFixedFeeChangeEveryPriceAndNoSelection() throws PriceCompositionException {
        EnergyPriceSeries raw = fixture();

        EnergyPriceSeries effective = GridPriceProvider.of("test", new PriceAdjustment.Vat(24),
                new PriceAdjustment.FixedFee(2.79, EnergyPriceUnits.defaultEnergyUnit())).apply(raw);

        for (int slot = 0; slot < raw.size(); slot++) {
            assertThat(effective.values().valueAt(slot), is(closeTo(raw.values().valueAt(slot) * 1.24 + 2.79, 1e-9)));
        }
        assertThat(SelectionStrategy.cheapestSlots().select(effective.values(), 8),
                is(SelectionStrategy.cheapestSlots().select(raw.values(), 8)));
    }

    /**
     * Compares a selection against a control fixture slot by slot, in the ON/OFF shape those files carry.
     *
     * @param selection the selection under test
     * @param prices the series the selection indexes into
     * @param resource the control fixture
     */
    private static void assertSelectionMatches(SlotSelection selection, EnergyPriceSeries prices, String resource) {
        List<FixtureCsv.Row> expected = FixtureCsv.read(resource);
        assertThat("the fixture and the series must describe the same day", expected, hasSize(prices.size()));
        List<Boolean> actual = selection.onOffFlags(prices.size());
        for (int slot = 0; slot < expected.size(); slot++) {
            FixtureCsv.Row row = expected.get(slot);
            assertThat("slot " + slot + " starting " + row.timestamp(), prices.slotAt(slot).start(),
                    is(row.timestamp()));
            assertThat("slot " + slot + " starting " + row.timestamp(), actual.get(slot), is(row.value() == 1));
        }
    }
}
