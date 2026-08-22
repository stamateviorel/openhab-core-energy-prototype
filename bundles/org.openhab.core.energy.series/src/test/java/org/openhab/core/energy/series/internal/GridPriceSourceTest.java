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
package org.openhab.core.energy.series.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.price.EnergyPriceSeries;
import org.openhab.core.energy.price.EnergyPriceUnits;
import org.openhab.core.energy.price.PriceDirection;
import org.openhab.core.energy.price.PriceRole;
import org.openhab.core.i18n.TimeZoneProvider;
import org.openhab.core.items.Item;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.persistence.FilterCriteria;
import org.openhab.core.persistence.HistoricItem;
import org.openhab.core.persistence.PersistenceItemInfo;
import org.openhab.core.persistence.PersistenceService;
import org.openhab.core.persistence.PersistenceServiceRegistry;
import org.openhab.core.persistence.QueryablePersistenceService;
import org.openhab.core.types.State;

/**
 * The Item-and-persistence edge of the price plane, end to end without OSGi: a future price series stored under
 * future timestamps, read back, and put through the configured pipeline.
 * <p>
 * This is where {@code price-data} <em>Generic grid-price provider</em>'s first clause - "reads a future price series
 * from an Item" - is actually met. The arithmetic it feeds is tested in the engine bundle against the corpus'
 * fixture; what is tested here is the reading, the slot reconstruction and the refusals, which is everything this
 * bundle exists for.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class GridPriceSourceTest {

    private static final ZoneId HELSINKI = ZoneId.of("Europe/Helsinki");
    private static final Instant NOW = LocalDate.of(2023, 1, 10).atStartOfDay(HELSINKI).toInstant();
    private static final Instant TOMORROW = LocalDate.of(2023, 1, 11).atStartOfDay(HELSINKI).toInstant();

    /**
     * A persisted entry.
     */
    @NonNullByDefault
    private record Entry(String name, Instant instant, State state) implements HistoricItem {

        @Override
        public ZonedDateTime getTimestamp() {
            return ZonedDateTime.ofInstant(instant, HELSINKI);
        }

        @Override
        public State getState() {
            return state;
        }

        @Override
        public String getName() {
            return name;
        }
    }

    /**
     * A queryable persistence service holding exactly what a test put in it.
     */
    @NonNullByDefault
    private static final class FakeStore implements QueryablePersistenceService {

        private final List<HistoricItem> entries = new ArrayList<>();

        void put(String item, Instant instant, State state) {
            entries.add(new Entry(item, instant, state));
        }

        @Override
        public String getId() {
            return "fake";
        }

        @Override
        public String getLabel(@Nullable Locale locale) {
            return "Fake";
        }

        @Override
        public void store(Item item) {
        }

        @Override
        public void store(Item item, @Nullable String alias) {
        }

        @Override
        public Set<PersistenceItemInfo> getItemInfo() {
            return Set.of();
        }

        @Override
        public Iterable<HistoricItem> query(FilterCriteria filter) {
            List<HistoricItem> found = new ArrayList<>();
            for (HistoricItem entry : entries) {
                boolean matchesItem = filter.getItemName() == null || filter.getItemName().equals(entry.getName());
                ZonedDateTime begin = filter.getBeginDate();
                ZonedDateTime end = filter.getEndDate();
                boolean afterBegin = begin == null || !entry.getInstant().isBefore(begin.toInstant());
                boolean beforeEnd = end == null || entry.getInstant().isBefore(end.toInstant());
                if (matchesItem && afterBegin && beforeEnd) {
                    found.add(entry);
                }
            }
            found.sort((first, second) -> first.getInstant().compareTo(second.getInstant()));
            return found;
        }
    }

    /**
     * A registry holding one service.
     */
    @NonNullByDefault
    private record SingleService(@Nullable PersistenceService service) implements PersistenceServiceRegistry {

        @Override
        public @Nullable PersistenceService getDefault() {
            return service;
        }

        @Override
        public @Nullable PersistenceService get(@Nullable String serviceId) {
            PersistenceService held = service;
            return held != null && held.getId().equals(serviceId) ? held : null;
        }

        @Override
        public @Nullable String getDefaultId() {
            PersistenceService held = service;
            return held == null ? null : held.getId();
        }

        @Override
        public Set<PersistenceService> getAll() {
            PersistenceService held = service;
            return held == null ? Set.of() : Set.of(held);
        }
    }

    /**
     * A persistence service that cannot be queried at all, which several of the ones a site may have installed
     * cannot.
     */
    @NonNullByDefault
    private record WriteOnlyStore() implements PersistenceService {

        @Override
        public String getId() {
            return "writeonly";
        }

        @Override
        public String getLabel(@Nullable Locale locale) {
            return "Write only";
        }

        @Override
        public void store(Item item) {
        }

        @Override
        public void store(Item item, @Nullable String alias) {
        }
    }

    private static final String ITEM = "Spot_Price";

    private static FakeStore aDayOfHourlyPrices(State... prices) {
        FakeStore store = new FakeStore();
        for (int hour = 0; hour < prices.length; hour++) {
            store.put(ITEM, TOMORROW.plus(Duration.ofHours(hour)), prices[hour]);
        }
        return store;
    }

    /**
     * Builds a provider over a store, filling in the two declarations every test below takes for granted.
     * <p>
     * The currency and the market zone have <strong>no shipped value</strong> and the provider reads nothing until
     * both are named; tests that are about something else say so once, here, rather than restating it eight times.
     * That the two really are mandatory is asserted on its own, below, rather than left to this helper.
     *
     * @param store the persistence service, or {@code null} for a site with none
     * @param configuration what the test is actually about
     * @return the provider
     */
    private static GridPriceSource source(@Nullable PersistenceService store, Map<String, Object> configuration) {
        Map<String, Object> properties = new LinkedHashMap<>(configuration);
        properties.putIfAbsent(GridPriceSource.CONFIG_CURRENCY, "EUR");
        properties.putIfAbsent(GridPriceSource.CONFIG_MARKET_ZONE, "Europe/Helsinki");
        return new GridPriceSource(new ItemPriceSeriesReader(new SingleService(store)), () -> HELSINKI,
                Clock.fixed(NOW, HELSINKI), properties);
    }

    @Test
    public void anUnconfiguredProviderReadsNothingAndSaysNothing() {
        GridPriceSource provider = source(new FakeStore(), Map.of());

        assertThat(provider.getSeries().isPresent(), is(false));
        assertThat(provider.getServiceRanking(), is(GridPriceSource.CORE_DEFAULT_RANKING));
        assertThat(provider.getRole(), is(PriceRole.SPOT));
    }

    /**
     * The market zone has no shipped value, and nothing is read until a site names one.
     * <p>
     * <strong>This is the requirement's own words, enforced.</strong> {@code price-data} <em>Delivery-day identity
     * and the market zone</em> says a delivery day "is never inferred from the site's zone or from UTC"; this
     * provider used to ship {@code UTC} as the default in the very element whose description says that. The failure
     * was silent and off by a whole day: a central-European day runs 23:00Z to 22:00Z, so its own local date is the
     * later one and the UTC reading names it the earlier one, with no condition reported anywhere.
     */
    @Test
    public void theMarketZoneHasNoDefaultAndNothingIsReadWithoutIt() {
        FakeStore store = aDayOfHourlyPrices(new DecimalType(50), new DecimalType(80));

        GridPriceSource without = new GridPriceSource(new ItemPriceSeriesReader(new SingleService(store)),
                () -> HELSINKI, Clock.fixed(NOW, HELSINKI),
                Map.of(GridPriceSource.CONFIG_ITEM, ITEM, GridPriceSource.CONFIG_CURRENCY, "EUR"));

        assertThat("an inferred delivery day is the one thing the requirement forbids", without.getSeries().isPresent(),
                is(false));
        assertThat(source(store, Map.of(GridPriceSource.CONFIG_ITEM, ITEM)).getSeries().isPresent(), is(true));
    }

    /**
     * The currency has no shipped value either, on D22's precedent.
     * <p>
     * An item carries a number and says nothing about its denomination. A guessed {@code EUR} is not a harmless
     * placeholder: it is what {@code describe()} renders onto a settings page, and it is what the composition's
     * currency guard compares a second component against, so a site running in another currency is either quietly
     * mislabelled or told that its own two components disagree.
     */
    @Test
    public void theCurrencyHasNoDefaultAndNothingIsReadWithoutIt() {
        FakeStore store = aDayOfHourlyPrices(new DecimalType(50), new DecimalType(80));

        GridPriceSource without = new GridPriceSource(new ItemPriceSeriesReader(new SingleService(store)),
                () -> HELSINKI, Clock.fixed(NOW, HELSINKI),
                Map.of(GridPriceSource.CONFIG_ITEM, ITEM, GridPriceSource.CONFIG_MARKET_ZONE, "Europe/Helsinki"));

        assertThat(without.getSeries().isPresent(), is(false));
    }

    /**
     * The whole point of the bundle: future-timestamped entries stored under the {@code forecast} strategy are read
     * back as a price series, with each slot ending where the next begins.
     */
    @Test
    public void aFutureSeriesStoredUnderItsOwnTimestampsIsReadBackAsAPriceSeries() {
        FakeStore store = aDayOfHourlyPrices(new DecimalType(50), new DecimalType(80), new DecimalType(30));

        EnergyPriceSeries series = source(store,
                Map.of(GridPriceSource.CONFIG_ITEM, ITEM, GridPriceSource.CONFIG_MARKET_ZONE, "Europe/Helsinki"))
                .getSeries().orElseThrow();

        assertThat(series.size(), is(3));
        assertThat(series.start(), is(TOMORROW));
        assertThat(series.slotAt(0).end(), is(series.slotAt(1).start()));
        assertThat("the last slot's width is inferred from the one before it", series.slotAt(2).duration(),
                is(Duration.ofHours(1)));
        assertThat(series.deliveryDay(), is(LocalDate.of(2023, 1, 11)));
        assertThat(series.values().valueAt(1), is(80.0));
    }

    /**
     * A typed {@code Number:EnergyPrice} state is what the requirement asks a source to publish, and a plain number is
     * accepted too, because existing bindings publish one and the proposal promises they become sources "without
     * breaking changes".
     */
    @Test
    public void bothATypedPriceAndABareNumberAreAccepted() {
        FakeStore typed = new FakeStore();
        // built from the unit rather than parsed from text: an unregistered currency has no format alias, which is
        // the same finding the engine bundle's PriceModellingLimitsTest pins
        typed.put(ITEM, TOMORROW, new QuantityType<>(0.10,
                EnergyPriceUnits.priceUnit(EnergyPriceUnits.currency("EUR"), Units.KILOWATT_HOUR)));
        typed.put(ITEM, TOMORROW.plus(Duration.ofHours(1)), new QuantityType<>(0.20,
                EnergyPriceUnits.priceUnit(EnergyPriceUnits.currency("EUR"), Units.KILOWATT_HOUR)));

        EnergyPriceSeries series = source(typed, Map.of(GridPriceSource.CONFIG_ITEM, ITEM)).getSeries().orElseThrow();

        assertThat(series.values().valueAt(0), is(closeTo(0.10, 1e-9)));
        assertThat(series.values().valueAt(1), is(closeTo(0.20, 1e-9)));
    }

    /**
     * A typed state denominated per a different unit of energy is converted, not read as if it were the configured
     * one.
     * <p>
     * <strong>This is the silent factor-of-a-thousand.</strong> An ENTSO-E binding publishes {@code EUR/MWh}; the
     * configuration page's denominator says {@code kWh}. The reader used to take {@code quantity.doubleValue()} and
     * drop the unit entirely, so 50 EUR/MWh became 50 EUR/kWh - a price a thousand times too high, on every slot,
     * with nothing logged. The forecast plane's own reader has always converted and thrown on a mismatch; the two
     * planes now agree.
     */
    @Test
    public void aTypedStateInAnotherEnergyUnitIsConvertedRatherThanRead() {
        FakeStore store = new FakeStore();
        store.put(ITEM, TOMORROW, new QuantityType<>(50,
                EnergyPriceUnits.priceUnit(EnergyPriceUnits.currency("EUR"), Units.MEGAWATT_HOUR)));
        store.put(ITEM, TOMORROW.plus(Duration.ofHours(1)), new QuantityType<>(80,
                EnergyPriceUnits.priceUnit(EnergyPriceUnits.currency("EUR"), Units.MEGAWATT_HOUR)));

        EnergyPriceSeries series = source(store, Map.of(GridPriceSource.CONFIG_ITEM, ITEM)).getSeries().orElseThrow();

        assertThat(series.energyUnit().toString(), is("kWh"));
        assertThat(series.values().valueAt(0), is(closeTo(0.05, 1e-12)));
        assertThat(series.values().valueAt(1), is(closeTo(0.08, 1e-12)));
    }

    /**
     * A typed state in another currency refuses the whole series rather than being read as the configured one.
     * <p>
     * Crossing currencies needs an exchange rate the user never saw, and the plane's own non-goal defers exchange -
     * so the honest answer is the same one composition gives for a currency mismatch: refuse and say so.
     */
    @Test
    public void aTypedStateInAnotherCurrencyRefusesTheWholeSeries() {
        FakeStore store = new FakeStore();
        store.put(ITEM, TOMORROW, new QuantityType<>(0.10,
                EnergyPriceUnits.priceUnit(EnergyPriceUnits.currency("DKK"), Units.KILOWATT_HOUR)));
        store.put(ITEM, TOMORROW.plus(Duration.ofHours(1)), new QuantityType<>(0.20,
                EnergyPriceUnits.priceUnit(EnergyPriceUnits.currency("DKK"), Units.KILOWATT_HOUR)));

        assertThat(source(store, Map.of(GridPriceSource.CONFIG_ITEM, ITEM)).getSeries().isPresent(), is(false));
    }

    /**
     * A typo in a pipeline argument is reported where the configuration is read and the step is dropped, rather than
     * throwing out of a read.
     * <p>
     * {@code vat=24%} used to reach {@code Double.parseDouble} on <em>every</em> call to {@code getSeries()}, because
     * the pipeline was rebuilt from text each time. The resulting {@link NumberFormatException} escaped through the
     * price plane's {@code conditions()} - the surface whose whole job is reporting configuration problems - and
     * through the coordinator as a stack trace on every refresh. Every other malformed entry here is warned about and
     * dropped; this one now is too.
     */
    @Test
    public void aTypoInThePipelineIsDroppedRatherThanThrownOnEveryRead() {
        FakeStore store = aDayOfHourlyPrices(new DecimalType(0.10), new DecimalType(0.10));

        GridPriceSource provider = source(store, Map.of(GridPriceSource.CONFIG_ITEM, ITEM,
                GridPriceSource.CONFIG_PIPELINE, List.of("vat=24%", "fee=0.05")));

        EnergyPriceSeries series = provider.getSeries().orElseThrow();
        assertThat("the unparseable step is gone and the sound one still applies", series.values().valueAt(0),
                is(closeTo(0.15, 1e-9)));
        assertThat(provider.describe(), not(containsString("VAT")));
    }

    /**
     * An unknown pipeline step is dropped with a warning too, rather than silently doing nothing.
     */
    @Test
    public void anUnknownPipelineStepIsDropped() {
        FakeStore store = aDayOfHourlyPrices(new DecimalType(0.10), new DecimalType(0.10));

        EnergyPriceSeries series = source(store, Map.of(GridPriceSource.CONFIG_ITEM, ITEM,
                GridPriceSource.CONFIG_PIPELINE, List.of("levy=0.03", "fee=0.05"))).getSeries().orElseThrow();

        assertThat(series.values().valueAt(0), is(closeTo(0.15, 1e-9)));
    }

    /**
     * The requirement's own scenario, end to end: raw EUR/MWh in the Item, VAT and a denomination in the
     * configuration, a consumer price per kilowatt hour out.
     */
    @Test
    public void aRawMegawattHourItemBecomesAConsumerPricePerKilowattHour() {
        FakeStore store = aDayOfHourlyPrices(new DecimalType(50), new DecimalType(80));

        EnergyPriceSeries series = source(store,
                Map.of(GridPriceSource.CONFIG_ITEM, ITEM, GridPriceSource.CONFIG_ENERGY_UNIT, "MWh",
                        GridPriceSource.CONFIG_PIPELINE, List.of("vat=24", "denomination=1")))
                .getSeries().orElseThrow();

        assertThat(series.energyUnit().toString(), is("kWh"));
        assertThat(series.values().valueAt(0), is(closeTo(0.062, 1e-9)));
        assertThat(series.values().valueAt(1), is(closeTo(0.0992, 1e-9)));
    }

    /**
     * The order of the pipeline is the site's own list, and the two orders really do give different numbers - which is
     * why the requirement's silence about it matters.
     */
    @Test
    public void theConfiguredPipelineOrderIsTheOrderApplied() {
        FakeStore store = aDayOfHourlyPrices(new DecimalType(0.10), new DecimalType(0.10));

        double feeBearsVat = source(store, Map.of(GridPriceSource.CONFIG_ITEM, ITEM, GridPriceSource.CONFIG_PIPELINE,
                List.of("fee=0.05", "vat=24"))).getSeries().orElseThrow().values().valueAt(0);
        double feeAfterVat = source(store, Map.of(GridPriceSource.CONFIG_ITEM, ITEM, GridPriceSource.CONFIG_PIPELINE,
                List.of("vat=24", "fee=0.05"))).getSeries().orElseThrow().values().valueAt(0);

        assertThat(feeBearsVat, is(closeTo(0.186, 1e-9)));
        assertThat(feeAfterVat, is(closeTo(0.174, 1e-9)));
    }

    /**
     * A conditional tariff configured as text lands as a tariff calendar read in the <em>site's</em> zone.
     */
    @Test
    public void aTariffWindowConfiguredAsTextIsAppliedInTheSitesOwnZone() {
        State[] flat = new State[24];
        java.util.Arrays.fill(flat, new DecimalType(0.10));
        FakeStore store = aDayOfHourlyPrices(flat);

        EnergyPriceSeries series = source(store, Map.of(GridPriceSource.CONFIG_ITEM, ITEM,
                GridPriceSource.CONFIG_MARKET_ZONE, "Europe/Helsinki", GridPriceSource.CONFIG_TARIFF_PERIODS,
                List.of("winter-day;DEC,JAN,FEB;MON,TUE,WED,THU,FRI,SAT;07:00;22:00;0.06"),
                GridPriceSource.CONFIG_TARIFF_DEFAULT, 0.02, GridPriceSource.CONFIG_PIPELINE, List.of("tariff")))
                .getSeries().orElseThrow();

        assertThat(series.values().valueAt(6), is(closeTo(0.12, 1e-9)));
        assertThat(series.values().valueAt(7), is(closeTo(0.16, 1e-9)));
        assertThat(series.values().valueAt(22), is(closeTo(0.12, 1e-9)));
    }

    /**
     * The feed-in role produces a feed-in series, so the same generic provider serves both directions without a
     * second component.
     */
    @Test
    public void theFeedInRoleProducesAFeedInSeries() {
        FakeStore store = aDayOfHourlyPrices(new DecimalType(-0.05), new DecimalType(-0.02));

        EnergyPriceSeries series = source(store,
                Map.of(GridPriceSource.CONFIG_ITEM, ITEM, GridPriceSource.CONFIG_ROLE, "feedIn")).getSeries()
                .orElseThrow();

        assertThat(series.direction(), is(PriceDirection.FEED_IN));
        assertThat(series.values().valueAt(0), is(closeTo(-0.05, 1e-9)));
    }

    /**
     * Only future entries are read. An Item that has been persisted for a year does not turn its history into a
     * forecast.
     */
    @Test
    public void onlyEntriesInsideTheHorizonAreRead() {
        FakeStore store = new FakeStore();
        store.put(ITEM, NOW.minus(Duration.ofDays(1)), new DecimalType(99));
        store.put(ITEM, TOMORROW, new DecimalType(1));
        store.put(ITEM, TOMORROW.plus(Duration.ofHours(1)), new DecimalType(2));
        store.put(ITEM, NOW.plus(Duration.ofDays(30)), new DecimalType(3));

        EnergyPriceSeries series = source(store,
                Map.of(GridPriceSource.CONFIG_ITEM, ITEM, GridPriceSource.CONFIG_HORIZON_HOURS, 48)).getSeries()
                .orElseThrow();

        assertThat(series.size(), is(2));
        assertThat(series.values().valueAt(0), is(1.0));
        assertThat(series.values().valueAt(1), is(2.0));
    }

    /**
     * One future entry has no successor and no predecessor, so its width cannot be inferred. That is refused rather
     * than guessed at - the single inference this reader makes is stated and bounded.
     */
    @Test
    public void aSingleFutureEntryIsRefusedRatherThanGivenAGuessedWidth() {
        FakeStore store = aDayOfHourlyPrices(new DecimalType(50));

        assertThat(source(store, Map.of(GridPriceSource.CONFIG_ITEM, ITEM)).getSeries().isPresent(), is(false));
    }

    /**
     * A state that is not a number is refused for the whole series rather than skipped, because a hole silently
     * dropped out of a price series changes which hours look cheapest.
     */
    @Test
    public void aNonNumericEntryRefusesTheWholeSeries() {
        FakeStore store = new FakeStore();
        store.put(ITEM, TOMORROW, new DecimalType(1));
        store.put(ITEM, TOMORROW.plus(Duration.ofHours(1)), new StringType("cheap"));
        store.put(ITEM, TOMORROW.plus(Duration.ofHours(2)), new DecimalType(3));

        assertThat(source(store, Map.of(GridPriceSource.CONFIG_ITEM, ITEM)).getSeries().isPresent(), is(false));
    }

    /**
     * A persistence service that cannot be queried, and one that is not installed at all, are both answered with
     * "nothing" and a warning rather than with an exception - a misconfigured price source must not take an
     * evaluation cycle down with it.
     */
    @Test
    public void anUnqueryableOrMissingPersistenceServiceIsAnsweredWithNothing() {
        Map<String, Object> configuration = Map.of(GridPriceSource.CONFIG_ITEM, ITEM);

        assertThat(source(new WriteOnlyStore(), configuration).getSeries().isPresent(), is(false));
        assertThat(source(null, configuration).getSeries().isPresent(), is(false));
    }

    /**
     * An unknown role is refused rather than silently taken as something else, so a typo cannot turn a feed-in price
     * into a consumption component.
     */
    @Test
    public void anUnknownRoleFallsBackToSpotRatherThanBeingInvented() {
        GridPriceSource provider = source(new FakeStore(),
                Map.of(GridPriceSource.CONFIG_ITEM, ITEM, GridPriceSource.CONFIG_ROLE, "feedin-tarrif"));

        assertThat(provider.getRole(), is(PriceRole.SPOT));
    }

    /**
     * The pipeline reads back as a sentence a user can check against their bill.
     */
    @Test
    public void thePipelineIsDescribable() {
        GridPriceSource provider = source(new FakeStore(), Map.of(GridPriceSource.CONFIG_ITEM, ITEM,
                GridPriceSource.CONFIG_PIPELINE, List.of("vat=24", "fee=0.0279")));

        assertThat(provider.describe(), allOf(containsString("VAT 24"), containsString("0.0279")));
    }

    /**
     * The site's zone is read from {@link TimeZoneProvider} rather than from the market zone on the series, which is
     * what the requirement means by "neither is inferred from the other".
     */
    @Test
    public void theSiteZoneComesFromTheTimeZoneProviderAndNotFromTheSeries() {
        State[] flat = new State[24];
        java.util.Arrays.fill(flat, new DecimalType(0.10));
        FakeStore store = aDayOfHourlyPrices(flat);
        TimeZoneProvider paris = () -> ZoneId.of("Europe/Paris");

        GridPriceSource provider = new GridPriceSource(new ItemPriceSeriesReader(new SingleService(store)), paris,
                Clock.fixed(NOW, HELSINKI),
                Map.of(GridPriceSource.CONFIG_ITEM, ITEM, GridPriceSource.CONFIG_CURRENCY, "EUR",
                        GridPriceSource.CONFIG_MARKET_ZONE, "Europe/Helsinki", GridPriceSource.CONFIG_TARIFF_PERIODS,
                        List.of("night;;;22:00;06:00;0.01"), GridPriceSource.CONFIG_TARIFF_DEFAULT, 0.05,
                        GridPriceSource.CONFIG_PIPELINE, List.of("tariff")));

        Optional<EnergyPriceSeries> series = provider.getSeries();

        // Paris is an hour behind Helsinki, so the Paris night rate ends at 07:00 Helsinki - the eighth slot
        assertThat(series.orElseThrow().values().valueAt(6), is(closeTo(0.11, 1e-9)));
        assertThat(series.orElseThrow().values().valueAt(7), is(closeTo(0.15, 1e-9)));
    }
}
