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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.CurrencyUnits;
import org.openhab.core.library.unit.Units;

/**
 * The refusals, and the three places where core's own price modelling does not reach as far as the corpus assumes.
 * <p>
 * Every test here is worth more as a report entry than as code. Two of them assert that something <em>throws</em> or
 * <em>fails</em> in core, which is unusual in a test suite and deliberate: those are the claims the build report
 * makes about the corpus, and a claim about behaviour that is not pinned by a test decays into folklore the first
 * time somebody tries it and gets a different answer.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class PriceModellingLimitsTest {

    private static final ZoneId HELSINKI = ZoneId.of("Europe/Helsinki");
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final Instant MIDNIGHT = LocalDate.of(2023, 1, 11).atStartOfDay(HELSINKI).toInstant();

    private static EnergyPriceSeries series(String currency, ZoneId zone, double... prices) {
        return EnergyPriceSeries.of(MIDNIGHT, Duration.ofHours(1), EnergyPriceUnits.currency(currency),
                EnergyPriceUnits.defaultEnergyUnit(), zone, PriceDirection.CONSUMPTION, prices);
    }

    /**
     * <strong>Finding: a price cannot be denominated in cents.</strong> {@code price-data} <em>Generic grid-price
     * provider</em>'s first scenario ends "the effective series is in ct/kWh" and the acceptance fixture's column is
     * {@code price_ct_per_kwh}, but {@code CurrencyUnit} refuses any name that is not exactly three characters, so
     * {@code ct} is not constructible and {@code ct/kWh} is not a unit this or any other openHAB code can name.
     * <p>
     * The scenario's wording is what needs correcting. This test is the evidence.
     */
    @Test
    public void aPriceCannotBeDenominatedInCents() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> CurrencyUnits.createCurrency("ct", "ct"));

        assertThat(refused.getMessage(), containsString("three characters"));
    }

    /**
     * <strong>Finding: converting EUR/MWh to EUR/kWh is not the native operation the corpus assumes.</strong>
     * {@code QuantityType#toUnit} routes through the system unit, and a currency's system converter needs an exchange
     * rate from {@code CurrencyService}. With no currency provider configured there is none, so the conversion
     * answers {@code null} - <em>even though both sides are the same currency and nothing is being exchanged</em>.
     * <p>
     * That is why this plane converts on the energy denominator alone, and why the currency is carried beside the
     * numbers rather than folded into one {@code Unit<EnergyPrice>}.
     */
    @Test
    public void theTypedUnitConversionTheCorpusAssumesDoesNotWorkWithoutACurrencyProvider() {
        QuantityType<?> perMegawattHour = new QuantityType<>(50,
                EnergyPriceUnits.priceUnit(EnergyPriceUnits.currency("EUR"), Units.MEGAWATT_HOUR));

        assertThat("core cannot convert this without an exchange rate for EUR",
                perMegawattHour
                        .toUnit(EnergyPriceUnits.priceUnit(EnergyPriceUnits.currency("EUR"), Units.KILOWATT_HOUR)),
                is(nullValue()));

        // the plane's own conversion is arithmetic on the denominator and always works
        EnergyPriceSeries raw = EnergyPriceSeries.of(MIDNIGHT, Duration.ofHours(1), EnergyPriceUnits.currency("EUR"),
                Units.MEGAWATT_HOUR, HELSINKI, PriceDirection.CONSUMPTION, 50);
        assertThat(raw.toEnergyUnit(Units.KILOWATT_HOUR).values().valueAt(0), is(closeTo(0.05, 1e-12)));
    }

    /**
     * Two components in different currencies are refused rather than converted, with the condition a status page can
     * key on.
     */
    @Test
    public void componentsInDifferentCurrenciesAreRefused() {
        List<PriceComponent> components = List.of(
                PriceComponent.of("spot", PriceRole.SPOT, series("EUR", HELSINKI, 0.10)),
                PriceComponent.of("tariff", PriceRole.GRID_TARIFF, series("DKK", HELSINKI, 0.30)));

        PriceCompositionException refused = assertThrows(PriceCompositionException.class,
                () -> PriceComposition.compose(components));

        assertThat(refused.getCondition(), is(PricePlaneCondition.CURRENCY_MISMATCH));
        assertThat(refused.getMessage(), allOf(containsString("tariff"), containsString("DKK"), containsString("EUR")));
    }

    /**
     * Two components carrying different market zones are refused, because their sum would have two candidate delivery
     * days and no rule to choose between them.
     */
    @Test
    public void componentsFromDifferentMarketZonesAreRefused() {
        List<PriceComponent> components = List.of(
                PriceComponent.of("spot", PriceRole.SPOT, series("EUR", HELSINKI, 0.10)),
                PriceComponent.of("tariff", PriceRole.GRID_TARIFF, series("EUR", PARIS, 0.05)));

        PriceCompositionException refused = assertThrows(PriceCompositionException.class,
                () -> PriceComposition.compose(components));

        assertThat(refused.getCondition(), is(PricePlaneCondition.MARKET_ZONE_MISMATCH));
    }

    /**
     * A composition nobody has configured composes nothing and says so, rather than defaulting to spot alone.
     */
    @Test
    public void anUnconfiguredCompositionIsReportedRatherThanGuessed() {
        PriceCompositionException refused = assertThrows(PriceCompositionException.class,
                () -> PriceComposition.compose(List.of()));

        assertThat(refused.getCondition(), is(PricePlaneCondition.COMPOSITION_UNCONFIGURED));
    }

    /**
     * Components that switch every one of themselves off are the same as none, and the message says how many were
     * declared so the user can tell the two apart.
     */
    @Test
    public void componentsThatAreAllSwitchedOffAreReportedAsUnconfigured() {
        List<PriceComponent> components = List
                .of(PriceComponent.of("spot", PriceRole.SPOT, series("EUR", HELSINKI, 0.10)).withIncluded(false));

        PriceCompositionException refused = assertThrows(PriceCompositionException.class,
                () -> PriceComposition.compose(components));

        assertThat(refused.getCondition(), is(PricePlaneCondition.COMPOSITION_UNCONFIGURED));
        assertThat(refused.getMessage(), containsString("switched off"));
    }

    /**
     * Components that do not overlap in time cannot be summed anywhere, and that is an answer rather than an empty
     * series.
     */
    @Test
    public void componentsThatShareNoTimeAreRefused() {
        EnergyPriceSeries today = series("EUR", HELSINKI, 0.10, 0.10);
        EnergyPriceSeries nextWeek = EnergyPriceSeries.of(MIDNIGHT.plus(Duration.ofDays(7)), Duration.ofHours(1),
                EnergyPriceUnits.currency("EUR"), EnergyPriceUnits.defaultEnergyUnit(), HELSINKI,
                PriceDirection.CONSUMPTION, 0.05, 0.05);

        PriceCompositionException refused = assertThrows(PriceCompositionException.class,
                () -> PriceComposition.compose(List.of(PriceComponent.of("a", PriceRole.SPOT, today),
                        PriceComponent.of("b", PriceRole.GRID_TARIFF, nextWeek))));

        assertThat(refused.getCondition(), is(PricePlaneCondition.NO_COMMON_TIME));
    }

    /**
     * The strict alignment is the reading in which composing differently shaped series is simply not defined, and it
     * refuses instead of refining. The union alignment composes the same pair without complaint - which is the point
     * of implementing all three rather than deciding.
     *
     * @throws PriceCompositionException never on the union path, and the test fails if it does
     */
    @Test
    public void theThreeAlignmentsDisagreeExactlyWhereTheCorpusIsSilent() throws PriceCompositionException {
        EnergyPriceSeries hourly = series("EUR", HELSINKI, 0.10, 0.20);
        EnergyPriceSeries halves = EnergyPriceSeries.of(MIDNIGHT, Duration.ofMinutes(30),
                EnergyPriceUnits.currency("EUR"), EnergyPriceUnits.defaultEnergyUnit(), HELSINKI,
                PriceDirection.CONSUMPTION, 0.01, 0.02, 0.03, 0.04);
        List<PriceComponent> components = List.of(PriceComponent.of("spot", PriceRole.SPOT, hourly),
                PriceComponent.of("tariff", PriceRole.GRID_TARIFF, halves));

        assertThat(PriceComposition.compose(components, SeriesAlignment.unionOfBoundaries()).size(), is(4));
        assertThat(PriceComposition.compose(components, SeriesAlignment.finestComponent()).size(), is(4));
        PriceCompositionException refused = assertThrows(PriceCompositionException.class,
                () -> PriceComposition.compose(components, SeriesAlignment.strict()));
        assertThat(refused.getCondition(), is(PricePlaneCondition.UNALIGNABLE));
    }

    /**
     * A gap in one component is never filled: the slots it does not cover are dropped from the sum, because a sum
     * with an unknown term is unknown rather than the sum of the known terms.
     *
     * @throws PriceCompositionException never, and the test fails if it does
     */
    @Test
    public void aGapInOneComponentRemovesThoseSlotsFromTheSumRatherThanBeingTreatedAsZero()
            throws PriceCompositionException {
        EnergyPriceSeries spot = series("EUR", HELSINKI, 0.10, 0.20, 0.30);
        List<Slot> gapped = List.of(Slot.of(MIDNIGHT, Duration.ofHours(1), 0.01),
                Slot.of(MIDNIGHT.plus(Duration.ofHours(2)), Duration.ofHours(1), 0.03));
        EnergyPriceSeries tariff = spot.withValues(new SlotSeries(gapped, spot.values().sense()));

        EnergyPriceSeries effective = PriceComposition.compose(List.of(PriceComponent.of("spot", PriceRole.SPOT, spot),
                PriceComponent.of("tariff", PriceRole.GRID_TARIFF, tariff)));

        assertThat(effective.size(), is(2));
        assertThat(effective.values().valueAt(0), is(closeTo(0.11, 1e-9)));
        assertThat(effective.values().valueAt(1), is(closeTo(0.33, 1e-9)));
        assertThat("the gap survives the composition", effective.values().isContiguous(0, 1), is(false));
    }

    /**
     * A tariff calendar is rendered over a price series' own span, and a span no real series has is refused rather
     * than enumerated into millions of slots.
     */
    @Test
    public void aTariffCalendarRefusesASpanNoPriceSeriesWouldHave() {
        TariffCalendar calendar = new TariffCalendar(HELSINKI, List.of(), 0.02);

        PriceCompositionException refused = assertThrows(PriceCompositionException.class,
                () -> calendar.toSeries(MIDNIGHT, MIDNIGHT.plus(Duration.ofDays(4000))));

        assertThat(refused.getCondition(), is(PricePlaneCondition.TARIFF_SPAN_TOO_LONG));
    }

    /**
     * A calendar with no periods at all is a constant, and it comes back as one slot rather than as one per day - the
     * merge that makes a flat fee expressible as a calendar.
     *
     * @throws PriceCompositionException never, and the test fails if it does
     */
    @Test
    public void aCalendarWithNoPeriodsIsOneFlatSlot() throws PriceCompositionException {
        TariffCalendar calendar = new TariffCalendar(HELSINKI, List.of(), 0.0279);

        SlotSeries rendered = calendar.toSeries(MIDNIGHT, MIDNIGHT.plus(Duration.ofDays(7)));

        assertThat(rendered.size(), is(1));
        assertThat(rendered.valueAt(0), is(closeTo(0.0279, 1e-12)));
    }

    /**
     * A newer publication describing something else is refused rather than merged, because a merge would silently
     * produce a series half of which is in a currency its own header does not name.
     */
    @Test
    public void aPublicationInAnotherCurrencyCannotOverwriteThisOne() {
        EnergyPriceSeries euros = series("EUR", HELSINKI, 0.10);
        EnergyPriceSeries kroner = series("DKK", HELSINKI, 0.75);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> euros.overwriteWith(kroner));

        assertThat(refused.getMessage(), containsString("same prices"));
    }
}
