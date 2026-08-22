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

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.window.SelectionStrategy;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.types.TimeSeries;

/**
 * One test per scenario of the {@code price-data} capability, in the order the requirements state them.
 * <p>
 * The corpus' own rule is that "requirements are the contract; scenarios are the tests", so each method below names
 * the requirement and the scenario it stands for. Where a scenario turned out not to be testable as written, or to
 * need something the corpus does not define, the method says so in its own JavaDoc rather than quietly asserting
 * something else - those are the entries the build report carries.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class PriceDataScenarioTest {

    private static final ZoneId HELSINKI = ZoneId.of("Europe/Helsinki");
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final Instant MIDNIGHT_HELSINKI = LocalDate.of(2023, 1, 11).atStartOfDay(HELSINKI).toInstant();

    private static EnergyPriceSeries hourly(Instant from, ZoneId zone, PriceDirection direction, double... prices) {
        return EnergyPriceSeries.of(from, Duration.ofHours(1), EnergyPriceUnits.currency("EUR"),
                EnergyPriceUnits.defaultEnergyUnit(), zone, direction, prices);
    }

    // ---------------------------------------------------------------- Prices as future-timestamped series

    /**
     * <em>Prices as future-timestamped series</em> - "Day-ahead arrival": tomorrow's 24 or 96 prices are published as
     * <strong>one series</strong> usable for charts, calculations and level derivation alike.
     * <p>
     * The charting half is what {@link EnergyPriceSeries#toTimeSeries} answers - a core {@link TimeSeries} of
     * {@code Number:EnergyPrice} states that persistence's {@code forecast} strategy stores and every chart already
     * understands. The calculation half is the same object's {@link EnergyPriceSeries#values()}.
     */
    @Test
    public void aDayAheadPublicationIsOneSeriesForChartsAndForCalculationsAlike() {
        EnergyPriceSeries day = hourly(MIDNIGHT_HELSINKI, HELSINKI, PriceDirection.CONSUMPTION, 0.10, 0.08, 0.12, 0.09);

        TimeSeries chartable = day.toTimeSeries(TimeSeries.Policy.REPLACE);

        assertThat(chartable.size(), is(4));
        assertThat(chartable.getBegin(), is(day.start()));
        // compared field by field rather than as whole quantities: QuantityType.equals routes through the system
        // unit, which for a currency needs an exchange rate no headless build has - see EnergyPriceUnits
        QuantityType<?> first = (QuantityType<?>) chartable.getStates().findFirst().orElseThrow().state();
        assertThat(first.doubleValue(), is(closeTo(0.10, 1e-9)));
        assertThat(first.getUnit(), is(day.priceUnit()));
        assertThat(day.priceAt(1).getUnit(), is(day.priceUnit()));
        // and the very same object answers a window request without conversion
        assertThat(SelectionStrategy.cheapestSlots().select(day.values(), 1).indices(), is(List.of(1)));
    }

    /**
     * <em>Prices as future-timestamped series</em> - "a newly published value for a timestamp overwrites the older
     * value". A republished day replaces the day; a republished hour replaces the hour and leaves the rest standing.
     */
    @Test
    public void aNewerPublicationOverwritesTheTimeItCoversAndNothingElse() {
        EnergyPriceSeries first = hourly(MIDNIGHT_HELSINKI, HELSINKI, PriceDirection.CONSUMPTION, 0.10, 0.20, 0.30);
        EnergyPriceSeries correction = hourly(MIDNIGHT_HELSINKI.plus(Duration.ofHours(1)), HELSINKI,
                PriceDirection.CONSUMPTION, 0.25);

        EnergyPriceSeries merged = first.overwriteWith(correction);

        assertThat(merged.size(), is(3));
        assertThat(merged.values().valueAt(0), is(0.10));
        assertThat(merged.values().valueAt(1), is(0.25));
        assertThat(merged.values().valueAt(2), is(0.30));
    }

    /**
     * The same rule when the two publications do not share a geometry, which {@code Time resolution} makes routine: a
     * quarter-hourly correction covering one hour replaces that hour and leaves the neighbours alone.
     * <p>
     * <strong>This is where a naive {@link TimeSeries.Policy#REPLACE} would be wrong</strong>, and the test states the
     * hazard rather than only the happy path: {@code REPLACE} is scoped to the whole span between a series' first and
     * last entry, so publishing a <em>sparse</em> correction - hour 1 and hour 3, nothing in between - with that
     * policy would delete hour 2 as well. The in-memory rule here deletes only what the correction actually covers,
     * and a publisher has to choose the policy that matches (see {@link EnergyPriceSeries#toTimeSeries}).
     */
    @Test
    public void aSparseCorrectionLeavesTheEntriesBetweenItsOwnPointsStanding() {
        EnergyPriceSeries day = hourly(MIDNIGHT_HELSINKI, HELSINKI, PriceDirection.CONSUMPTION, 0.10, 0.20, 0.30, 0.40);
        List<Slot> sparse = List.of(
                new Slot(MIDNIGHT_HELSINKI.plus(Duration.ofHours(1)), MIDNIGHT_HELSINKI.plus(Duration.ofHours(2)),
                        0.99),
                new Slot(MIDNIGHT_HELSINKI.plus(Duration.ofHours(3)), MIDNIGHT_HELSINKI.plus(Duration.ofHours(4)),
                        0.98));

        EnergyPriceSeries merged = day.overwriteWith(day.withValues(new SlotSeries(sparse, day.values().sense())));

        assertThat(merged.size(), is(4));
        assertThat(merged.values().valueAt(0), is(0.10));
        assertThat(merged.values().valueAt(1), is(0.99));
        assertThat("the hour the sparse correction skipped is untouched", merged.values().valueAt(2), is(0.30));
        assertThat(merged.values().valueAt(3), is(0.98));
    }

    // ---------------------------------------------------------------- Price component composition

    /**
     * <em>Price component composition</em> - "Spot from one binding, tariff from another": window optimization runs
     * on the summed effective price, not on the spot price.
     * <p>
     * The tariff is chosen so that it actually changes the answer. On spot alone the cheapest hour is hour 2; the
     * night tariff is cheap enough that the summed price moves the answer to hour 0, which is the whole point of
     * composing before optimising.
     *
     * @throws PriceCompositionException never, and the test fails if it does
     */
    @Test
    public void windowOptimisationRunsOnTheSummedEffectivePrice() throws PriceCompositionException {
        EnergyPriceSeries spot = hourly(MIDNIGHT_HELSINKI, HELSINKI, PriceDirection.CONSUMPTION, 0.10, 0.12, 0.09,
                0.11);
        EnergyPriceSeries tariff = hourly(MIDNIGHT_HELSINKI, HELSINKI, PriceDirection.CONSUMPTION, 0.01, 0.02, 0.05,
                0.04);

        EnergyPriceSeries effective = PriceComposition.compose(List.of(PriceComponent.of("spot", PriceRole.SPOT, spot),
                PriceComponent.of("tariff", PriceRole.GRID_TARIFF, tariff)));

        assertThat(SelectionStrategy.cheapestSlots().select(spot.values(), 1).indices(), is(List.of(2)));
        assertThat(SelectionStrategy.cheapestSlots().select(effective.values(), 1).indices(), is(List.of(0)));
        assertThat(effective.values().valueAt(0), is(closeTo(0.11, 1e-9)));
    }

    /**
     * <em>Price component composition</em> - "the user selects which components/Items are included". A component the
     * user has switched off contributes nothing, without its source being removed.
     *
     * @throws PriceCompositionException never, and the test fails if it does
     */
    @Test
    public void aComponentTheUserSwitchedOffIsNotInTheSum() throws PriceCompositionException {
        EnergyPriceSeries spot = hourly(MIDNIGHT_HELSINKI, HELSINKI, PriceDirection.CONSUMPTION, 0.10, 0.12);
        EnergyPriceSeries tariff = hourly(MIDNIGHT_HELSINKI, HELSINKI, PriceDirection.CONSUMPTION, 0.05, 0.05);
        List<PriceComponent> components = List.of(PriceComponent.of("spot", PriceRole.SPOT, spot),
                PriceComponent.of("tariff", PriceRole.GRID_TARIFF, tariff).withIncluded(false));

        EnergyPriceSeries effective = PriceComposition.compose(components);

        assertThat(effective.values().valueAt(0), is(closeTo(0.10, 1e-9)));
        assertThat(effective.values().valueAt(1), is(closeTo(0.12, 1e-9)));
    }

    /**
     * <em>Price component composition</em> - "Components sum below zero": a spot component more negative than the
     * fixed components are positive leaves a negative effective price, carried through unclamped.
     *
     * @throws PriceCompositionException never, and the test fails if it does
     */
    @Test
    public void aNegativeSpotPriceCarriesThroughTheCompositionUnclamped() throws PriceCompositionException {
        EnergyPriceSeries spot = hourly(MIDNIGHT_HELSINKI, HELSINKI, PriceDirection.CONSUMPTION, -0.09, -0.02);
        EnergyPriceSeries fees = hourly(MIDNIGHT_HELSINKI, HELSINKI, PriceDirection.CONSUMPTION, 0.04, 0.04);

        EnergyPriceSeries effective = PriceComposition.compose(List.of(PriceComponent.of("spot", PriceRole.SPOT, spot),
                PriceComponent.of("fees", PriceRole.TAXES_AND_FEES, fees)));

        assertThat(effective.values().valueAt(0), is(closeTo(-0.05, 1e-9)));
        assertThat(effective.values().valueAt(1), is(closeTo(0.02, 1e-9)));
        assertThat("the ranking uses the signed values",
                SelectionStrategy.cheapestSlots().select(effective.values(), 1).indices(), is(List.of(0)));
    }

    /**
     * Composition across differing geometry, which {@code Time resolution} guarantees and no scenario covers: an
     * hourly spot series plus a tariff that steps at half past the hour.
     * <p>
     * <strong>The corpus does not say what this means</strong>, so all three readings are implemented and this test
     * pins the default one - refine to every boundary, hold each component's value from its own start. The hour
     * containing the step comes back as two slots, which is the honest answer because the effective price really does
     * change inside it.
     *
     * @throws PriceCompositionException never, and the test fails if it does
     */
    @Test
    public void componentsOfDifferentGeometryAreRefinedToTheUnionOfTheirBoundaries() throws PriceCompositionException {
        EnergyPriceSeries spot = hourly(MIDNIGHT_HELSINKI, HELSINKI, PriceDirection.CONSUMPTION, 0.10, 0.20);
        List<Slot> stepping = List.of(new Slot(MIDNIGHT_HELSINKI, MIDNIGHT_HELSINKI.plus(Duration.ofMinutes(90)), 0.01),
                new Slot(MIDNIGHT_HELSINKI.plus(Duration.ofMinutes(90)), MIDNIGHT_HELSINKI.plus(Duration.ofHours(2)),
                        0.07));
        EnergyPriceSeries tariff = spot.withValues(new SlotSeries(stepping, spot.values().sense()));

        EnergyPriceSeries effective = PriceComposition.compose(
                List.of(PriceComponent.of("spot", PriceRole.SPOT, spot),
                        PriceComponent.of("tariff", PriceRole.GRID_TARIFF, tariff)),
                SeriesAlignment.unionOfBoundaries());

        assertThat(effective.size(), is(3));
        assertThat(effective.values().valueAt(0), is(closeTo(0.11, 1e-9)));
        assertThat(effective.values().valueAt(1), is(closeTo(0.21, 1e-9)));
        assertThat(effective.values().valueAt(2), is(closeTo(0.27, 1e-9)));
        assertThat(effective.slotAt(1).duration(), is(Duration.ofMinutes(30)));
    }

    // ---------------------------------------------------------------- Generic grid-price provider

    /**
     * <em>Generic grid-price provider</em> - "ENTSO-E raw to consumer price": a source publishing EUR/MWh without
     * VAT, the user configures a VAT multiplier and a division by ten, and the effective series comes out per
     * kilowatt hour with VAT and without any add-on.
     * <p>
     * <strong>The scenario's last clause cannot be met as written.</strong> It says the effective series is "in
     * ct/kWh", and cents are not expressible as a core currency unit - see {@link EnergyPriceUnits}. What is asserted
     * here is therefore the arithmetic the scenario describes, in EUR/kWh, plus the {@code x100} a site that
     * genuinely wants the numbers in cents would add and the fact that the declared unit then no longer matches them.
     *
     * @throws PriceCompositionException never, and the test fails if it does
     */
    @Test
    public void aRawMegawattHourFeedBecomesAConsumerPriceWithVat() throws PriceCompositionException {
        EnergyPriceSeries raw = EnergyPriceSeries.of(MIDNIGHT_HELSINKI, Duration.ofHours(1),
                EnergyPriceUnits.currency("EUR"), Units.MEGAWATT_HOUR, HELSINKI, PriceDirection.CONSUMPTION, 50, 80);

        EnergyPriceSeries effective = GridPriceProvider
                .of("entsoe", new PriceAdjustment.Vat(24), new PriceAdjustment.Denomination(Units.KILOWATT_HOUR))
                .apply(raw);

        assertThat(effective.energyUnit(), is(Units.KILOWATT_HOUR));
        assertThat(effective.values().valueAt(0), is(closeTo(0.062, 1e-9)));
        assertThat(effective.values().valueAt(1), is(closeTo(0.0992, 1e-9)));
        assertThat(effective.priceUnit().toString(), containsString("kWh"));

        EnergyPriceSeries asCents = new PriceAdjustment.Scale(100).apply(effective);
        assertThat(asCents.values().valueAt(0), is(closeTo(6.2, 1e-9)));
        assertThat("cents are a display convention, and the unit still says otherwise", asCents.energyUnit(),
                is(Units.KILOWATT_HOUR));
    }

    /**
     * The pipeline has no order of its own, which is the answer to a hole in the requirement: it lists four
     * adjustments and never says in which order they apply, and VAT-then-fee is a different number from
     * fee-then-VAT. Both orders are expressible and neither is privileged.
     *
     * @throws PriceCompositionException never, and the test fails if it does
     */
    @Test
    public void theOrderOfAdjustmentsIsTheUsersOwnList() throws PriceCompositionException {
        EnergyPriceSeries raw = hourly(MIDNIGHT_HELSINKI, HELSINKI, PriceDirection.CONSUMPTION, 0.10);
        PriceAdjustment vat = new PriceAdjustment.Vat(24);
        PriceAdjustment fee = new PriceAdjustment.FixedFee(0.05, Units.KILOWATT_HOUR);

        double feeBearsVat = GridPriceProvider.of("caruna", fee, vat).apply(raw).values().valueAt(0);
        double feeAfterVat = GridPriceProvider.of("other", vat, fee).apply(raw).values().valueAt(0);

        assertThat(feeBearsVat, is(closeTo(0.186, 1e-9)));
        assertThat(feeAfterVat, is(closeTo(0.174, 1e-9)));
        assertThat("the two orders must not be the same number, or this hole would not matter", feeBearsVat,
                is(not(closeTo(feeAfterVat, 1e-9))));
    }

    /**
     * <em>Generic grid-price provider</em> - "Seasonal/day-night tariff": the user configures "winter Mon-Sat 07-22 =
     * higher tariff" and the provider composes it onto the spot series correctly.
     * <p>
     * The day is a January Wednesday in Helsinki, so the period is in season and on a qualifying weekday; the
     * boundary hours 06:00, 07:00, 21:00 and 22:00 are asserted individually, because an off-by-one at either edge is
     * exactly the defect a scenario like this exists to catch.
     *
     * @throws PriceCompositionException never, and the test fails if it does
     */
    @Test
    public void aWinterWeekdayDaytimeTariffIsComposedOntoTheSpotSeries() throws PriceCompositionException {
        double[] flat = new double[24];
        java.util.Arrays.fill(flat, 0.10);
        EnergyPriceSeries spot = hourly(MIDNIGHT_HELSINKI, HELSINKI, PriceDirection.CONSUMPTION, flat);
        TariffCalendar calendar = new TariffCalendar(HELSINKI,
                List.of(new TariffPeriod("winter-day", EnumSet.of(Month.DECEMBER, Month.JANUARY, Month.FEBRUARY),
                        EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.SATURDAY), LocalTime.of(7, 0), LocalTime.of(22, 0),
                        0.06)),
                0.02);

        EnergyPriceSeries effective = new PriceAdjustment.ConditionalTariff(calendar).apply(spot);

        assertThat(effective.size(), is(24));
        assertThat("06:00 is still night rate", effective.values().valueAt(6), is(closeTo(0.12, 1e-9)));
        assertThat("07:00 is the first day-rate hour", effective.values().valueAt(7), is(closeTo(0.16, 1e-9)));
        assertThat("21:00 is the last day-rate hour", effective.values().valueAt(21), is(closeTo(0.16, 1e-9)));
        assertThat("22:00 is night rate again", effective.values().valueAt(22), is(closeTo(0.12, 1e-9)));
    }

    /**
     * The same calendar on a Sunday charges the night rate all day, which is the "Mon-Sat" half of the condition.
     *
     * @throws PriceCompositionException never, and the test fails if it does
     */
    @Test
    public void theSameTariffDoesNotFireOnASunday() throws PriceCompositionException {
        Instant sunday = LocalDate.of(2023, 1, 15).atStartOfDay(HELSINKI).toInstant();
        double[] flat = new double[24];
        java.util.Arrays.fill(flat, 0.10);
        EnergyPriceSeries spot = hourly(sunday, HELSINKI, PriceDirection.CONSUMPTION, flat);
        TariffCalendar calendar = new TariffCalendar(HELSINKI,
                List.of(new TariffPeriod("winter-day", EnumSet.of(Month.JANUARY),
                        EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.SATURDAY), LocalTime.of(7, 0), LocalTime.of(22, 0),
                        0.06)),
                0.02);

        EnergyPriceSeries effective = new PriceAdjustment.ConditionalTariff(calendar).apply(spot);

        // the tariff itself is one flat slot all day, but the union alignment keeps the spot series' own hours
        assertThat(effective.size(), is(24));
        for (int hour = 0; hour < 24; hour++) {
            assertThat("hour " + hour, effective.values().valueAt(hour), is(closeTo(0.12, 1e-9)));
        }
    }

    /**
     * A night tariff that wraps midnight is one period, not two, and the wrap is asserted at both ends.
     */
    @Test
    public void aTariffPeriodMayWrapMidnight() {
        TariffPeriod night = new TariffPeriod("night", Set.of(), Set.of(), LocalTime.of(22, 0), LocalTime.of(7, 0),
                0.02);

        assertThat(night.coversTime(LocalTime.of(23, 30)), is(true));
        assertThat(night.coversTime(LocalTime.of(6, 59)), is(true));
        assertThat(night.coversTime(LocalTime.of(7, 0)), is(false));
        assertThat(night.coversTime(LocalTime.of(21, 59)), is(false));
    }

    // ---------------------------------------------------------------- Feed-in pricing

    /**
     * <em>Feed-in pricing</em> - "Negative feed-in": delivering to the grid costs more in tariffs than the spot
     * revenue, the engine sees a negative feed-in price, and any local consumption is preferred.
     */
    @Test
    public void aNegativeFeedInPriceMakesExportingCostMoney() {
        EnergyPriceSeries feedIn = hourly(MIDNIGHT_HELSINKI, HELSINKI, PriceDirection.FEED_IN, -0.05);

        double costOfExporting = EnergyValuation.cost(feedIn, 0, 1);

        assertThat("exporting a kilowatt-hour at a negative price is a cost, not a revenue", costOfExporting,
                is(closeTo(0.05, 1e-9)));
    }

    /**
     * <em>Feed-in pricing</em> - "One arithmetic, both directions": a positive consumption price and a negative
     * feed-in price for the same slot, and self-consumption scores better under the cost objective and under the
     * self-consumption objective alike, with neither needing a special case.
     */
    @Test
    public void oneKilowattHourValuedBothWaysPrefersLocalConsumptionWithoutASpecialCase() {
        EnergyPriceSeries consumption = hourly(MIDNIGHT_HELSINKI, HELSINKI, PriceDirection.CONSUMPTION, 0.30);
        EnergyPriceSeries feedIn = hourly(MIDNIGHT_HELSINKI, HELSINKI, PriceDirection.FEED_IN, -0.05);

        double importing = EnergyValuation.cost(consumption, 0, 1);
        double exporting = EnergyValuation.cost(feedIn, 0, 1);
        double benefit = EnergyValuation.selfConsumptionBenefit(consumption, feedIn, 0, 1);

        assertThat(importing, is(closeTo(0.30, 1e-9)));
        assertThat(exporting, is(closeTo(0.05, 1e-9)));
        // the same expression, one sign apart, and the benefit is exactly what avoiding both costs is worth
        assertThat(benefit, is(closeTo(importing + exporting, 1e-9)));
        assertThat("consuming locally beats importing and exporting", benefit, greaterThan(0.0));
    }

    /**
     * A feed-in series ranks the other way round on purpose: the best hour to export in is the one paying most.
     */
    @Test
    public void theBestSlotOfAFeedInSeriesIsTheOnePayingMost() {
        EnergyPriceSeries feedIn = hourly(MIDNIGHT_HELSINKI, HELSINKI, PriceDirection.FEED_IN, 0.02, 0.09, 0.05);

        assertThat(SelectionStrategy.bestSlots().select(feedIn.values(), 1).indices(), is(List.of(1)));
        assertThat(SelectionStrategy.worstSlots().select(feedIn.values(), 1).indices(), is(List.of(0)));
    }

    // ---------------------------------------------------------------- Delivery-day identity and the market zone

    /**
     * <em>Delivery-day identity and the market zone</em> - "A site in a different zone": the day boundary follows the
     * market zone carried on the series while the season boundary follows the site zone, and neither is inferred from
     * the other.
     * <p>
     * The market is Finnish and the site is French. The series names a Finnish delivery day; the tariff calendar,
     * which is the site's, reads its own hours in Paris. The two disagree by an hour, and that disagreement is the
     * requirement.
     *
     * @throws PriceCompositionException never, and the test fails if it does
     */
    @Test
    public void theDeliveryDayFollowsTheMarketWhileTheTariffFollowsTheSite() throws PriceCompositionException {
        // one Helsinki day: 2023-01-11T00:00+02:00 is 2023-01-10T22:00Z, which is still 2023-01-10 in Paris
        double[] flat = new double[24];
        java.util.Arrays.fill(flat, 0.10);
        EnergyPriceSeries finnishDay = hourly(MIDNIGHT_HELSINKI, HELSINKI, PriceDirection.CONSUMPTION, flat);
        TariffCalendar parisNight = new TariffCalendar(PARIS,
                List.of(new TariffPeriod("night", Set.of(), Set.of(), LocalTime.of(22, 0), LocalTime.of(6, 0), 0.01)),
                0.05);

        assertThat(finnishDay.deliveryDay(), is(LocalDate.of(2023, 1, 11)));
        assertThat(finnishDay.coversWholeDeliveryDay(), is(true));
        assertThat("the same instants are the day before in Paris", finnishDay.start().atZone(PARIS).toLocalDate(),
                is(LocalDate.of(2023, 1, 10)));

        EnergyPriceSeries effective = new PriceAdjustment.ConditionalTariff(parisNight).apply(finnishDay);
        // the Paris night rate ends at 06:00 Paris, which is 07:00 Helsinki - the eighth slot of the Finnish day
        assertThat(effective.values().valueAt(6), is(closeTo(0.11, 1e-9)));
        assertThat(effective.values().valueAt(7), is(closeTo(0.15, 1e-9)));
    }

    /**
     * A delivery day is not twenty-four hours long. The day a market moves to summer time is twenty-three, and it is
     * still one whole delivery day.
     */
    @Test
    public void aDaylightSavingDayIsStillOneWholeDeliveryDay() {
        Instant start = LocalDate.of(2023, 3, 26).atStartOfDay(PARIS).toInstant();
        double[] flat = new double[23];
        java.util.Arrays.fill(flat, 0.10);

        EnergyPriceSeries shortDay = hourly(start, PARIS, PriceDirection.CONSUMPTION, flat);

        assertThat(shortDay.deliveryDay(), is(LocalDate.of(2023, 3, 26)));
        assertThat(shortDay.coversWholeDeliveryDay(), is(true));
        assertThat(Duration.between(shortDay.start(), shortDay.end()), is(Duration.ofHours(23)));
    }

    // ---------------------------------------------------------------- Time resolution

    /**
     * <em>Time resolution</em> - "Market switches to 15 minutes": a source starts publishing 96 slots a day and
     * composition and the shared window calculations keep working unchanged, their requests being durations rather
     * than slot counts.
     *
     * @throws PriceCompositionException never, and the test fails if it does
     */
    @Test
    public void aMarketSwitchingToQuarterHoursChangesNothingButTheSlotCount() throws PriceCompositionException {
        double[] quarters = new double[96];
        for (int quarter = 0; quarter < quarters.length; quarter++) {
            quarters[quarter] = quarter == 40 ? 0.01 : 0.10;
        }
        EnergyPriceSeries spot = EnergyPriceSeries.of(MIDNIGHT_HELSINKI, Duration.ofMinutes(15),
                EnergyPriceUnits.currency("EUR"), EnergyPriceUnits.defaultEnergyUnit(), HELSINKI,
                PriceDirection.CONSUMPTION, quarters);
        EnergyPriceSeries fee = hourly(MIDNIGHT_HELSINKI, HELSINKI, PriceDirection.CONSUMPTION, new double[24]);

        EnergyPriceSeries effective = PriceComposition.compose(List.of(PriceComponent.of("spot", PriceRole.SPOT, spot),
                PriceComponent.of("fee", PriceRole.TAXES_AND_FEES, fee)));

        assertThat(effective.size(), is(96));
        assertThat(effective.coversWholeDeliveryDay(), is(true));
        // the request is a duration, so it needs no translation into slots
        assertThat(SelectionStrategy.cheapestSlots().selectForDuration(effective.values(), Duration.ofMinutes(15))
                .indices(), is(List.of(40)));
        assertThat(SelectionStrategy.cheapestSlots().selectForDuration(effective.values(), Duration.ofHours(1)).size(),
                is(4));
    }

    /**
     * <em>Time resolution</em> - "Mixed intervals in one series": quarter-hourly day-ahead prices for tomorrow and
     * hourly predicted prices for the rest of the week, in one series, each entry treated by its own timestamp and
     * interval.
     */
    @Test
    public void oneSeriesMayHoldQuarterHoursAndHoursTogether() {
        List<Slot> mixed = new ArrayList<>();
        Instant cursor = MIDNIGHT_HELSINKI;
        for (int quarter = 0; quarter < 4; quarter++) {
            mixed.add(Slot.of(cursor, Duration.ofMinutes(15), 0.20));
            cursor = cursor.plus(Duration.ofMinutes(15));
        }
        for (int hour = 0; hour < 3; hour++) {
            mixed.add(Slot.of(cursor, Duration.ofHours(1), hour == 1 ? 0.05 : 0.30));
            cursor = cursor.plus(Duration.ofHours(1));
        }
        EnergyPriceSeries week = new EnergyPriceSeries(new SlotSeries(mixed, PriceDirection.CONSUMPTION.sense()),
                EnergyPriceUnits.currency("EUR"), EnergyPriceUnits.defaultEnergyUnit(), HELSINKI,
                PriceDirection.CONSUMPTION);

        assertThat("the series really does mix widths", week.values().uniformSlotDuration().isPresent(), is(false));
        // an hour's worth of running time is one cheap hourly slot, not four quarters of a dearer one
        assertThat(SelectionStrategy.cheapestSlots().selectForDuration(week.values(), Duration.ofHours(1)).indices(),
                is(List.of(5)));
        assertThat(week.slotAt(0).duration(), is(Duration.ofMinutes(15)));
        assertThat(week.slotAt(5).duration(), is(Duration.ofHours(1)));
    }
}
