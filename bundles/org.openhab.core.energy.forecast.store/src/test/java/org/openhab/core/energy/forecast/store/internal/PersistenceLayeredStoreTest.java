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
package org.openhab.core.energy.forecast.store.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.forecast.ForecastPlaneCondition;
import org.openhab.core.energy.forecast.ForecastRole;
import org.openhab.core.energy.forecast.ForecastSeries;
import org.openhab.core.energy.forecast.SeriesLayer;
import org.openhab.core.energy.forecast.store.LayeredWriteReport;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * _Layered prediction series_ end to end: a baseline pre-filled, a forecast refining the days it covers, a cap written
 * onto today, and the collision the corpus leaves open - all against a persistence service that behaves the way the
 * interface says one does.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class PersistenceLayeredStoreTest {

    private static final String ITEM = "PV_Forecast";
    private static final String CAP_ITEM = "PV_Forecast_Cap";
    private static final Instant NOW = StoreFixtures.DAY_START.plus(Duration.ofHours(6));

    private final StoreFixtures.InMemoryPersistence persistence = new StoreFixtures.InMemoryPersistence();
    private final ItemRegistry items = StoreFixtures.itemRegistry(ITEM, CAP_ITEM);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private PersistenceLayeredStore store(Map<String, Object> configuration) {
        return new PersistenceLayeredStore(items, new StoreFixtures.FakeServiceRegistry(persistence), clock,
                configuration);
    }

    /**
     * _PV baseline refined by forecast_: the baseline covers the whole day, the forecast covers three hours of it, and
     * the hours it does not cover keep the baseline.
     */
    @Test
    public void aForecastOverwritesTheHoursItCoversAndTheRestKeepsTheBaseline() {
        PersistenceLayeredStore store = store(Map.of());
        store.apply(ITEM, SeriesLayer.BASELINE, StoreFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 0, 0, 0,
                0, 0, 0, 0, 0, 500, 1500, 2000, 2200, 2200, 2000, 1500, 500, 0, 0, 0, 0, 0, 0, 0, 0));

        LayeredWriteReport report = store.apply(ITEM, SeriesLayer.FORECAST,
                StoreFixtures.hourlyFrom(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 11, 4000, 4200, 4100));

        assertThat(report.written(), is(3));
        assertThat("nothing was appended: the same timestamps were replaced", persistence.size(ITEM), is(24));
        assertThat(watts(hour(11)), is(4000.0));
        assertThat(watts(hour(12)), is(4200.0));
        assertThat("an hour the forecast did not cover still carries the baseline", watts(hour(9)), is(1500.0));
        assertThat(watts(hour(20)), is(0.0));
    }

    /**
     * _Hard generation cap written into today_: the cap is written straight onto the affected entries, which is what
     * makes it visible to everything that reads the prediction.
     */
    @Test
    public void aCapIsWrittenOntoTheAffectedEntriesOfToday() {
        PersistenceLayeredStore store = store(Map.of());
        store.apply(ITEM, SeriesLayer.FORECAST,
                StoreFixtures.hourlyFrom(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 11, 4000, 4200, 4100));

        LayeredWriteReport report = store.apply(ITEM, SeriesLayer.CAP,
                StoreFixtures.hourlyFrom(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 12, 2500, 2500));

        assertThat(report.written(), is(2));
        assertThat(watts(hour(11)), is(4000.0));
        assertThat(watts(hour(12)), is(2500.0));
        assertThat(watts(hour(13)), is(2500.0));
    }

    /**
     * <strong>The open collision, end to end.</strong> A site that has chosen no policy gets the requirement's own
     * words - the newest write wins - and the report says a cap was overwritten. Neither the store nor this test says
     * that is the right answer.
     */
    @Test
    public void aRefreshAfterACapErasesItAndTheReportSaysSo() {
        PersistenceLayeredStore store = store(Map.of());
        store.apply(ITEM, SeriesLayer.CAP,
                StoreFixtures.hourlyFrom(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 12, 2500));

        LayeredWriteReport report = store.apply(ITEM, SeriesLayer.FORECAST,
                StoreFixtures.hourlyFrom(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 12, 4200));

        assertThat(watts(hour(12)), is(4200.0));
        assertThat(report.collisions(), contains(hour(12)));
        assertThat(report.conditions(), hasItems(ForecastPlaneCondition.CAP_OVERWRITTEN_BY_REFRESH,
                ForecastPlaneCondition.WRITE_POLICY_UNCONFIGURED));
    }

    /**
     * The same site with the re-applying policy keeps the cap, because the cap is written again on top of the refresh.
     */
    @Test
    public void theReapplyingPolicyKeepsTheCapThroughARefresh() {
        PersistenceLayeredStore store = store(Map.of("writePolicy", "reapply-caps"));
        store.apply(ITEM, SeriesLayer.CAP,
                StoreFixtures.hourlyFrom(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 12, 2500));

        store.apply(ITEM, SeriesLayer.FORECAST,
                StoreFixtures.hourlyFrom(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 12, 4200));

        assertThat(watts(hour(12)), is(2500.0));
    }

    /**
     * The layer-order policy refuses the refresh where the cap outranks it, and the stored value never changes.
     */
    @Test
    public void theLayerOrderPolicyRefusesTheRefreshWhereTheCapOutranksIt() {
        PersistenceLayeredStore store = store(
                Map.of("writePolicy", "writer-precedence", "layerPrecedence", List.of("forecast=10", "cap=20")));
        store.apply(ITEM, SeriesLayer.CAP,
                StoreFixtures.hourlyFrom(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 12, 2500));

        LayeredWriteReport report = store.apply(ITEM, SeriesLayer.FORECAST,
                StoreFixtures.hourlyFrom(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 12, 4200));

        assertThat(report.written(), is(0));
        assertThat(report.refused(), is(1));
        assertThat(watts(hour(12)), is(2500.0));
    }

    /**
     * The read-time policy keeps the cap out of the prediction entirely and applies it when the series is read, which
     * is the one option that needs nothing to be remembered about who wrote what.
     */
    @Test
    public void theReadTimePolicyKeepsTheCapInItsOwnSeriesAndAppliesItOnRead() {
        PersistenceLayeredStore store = store(
                Map.of("writePolicy", "cap-at-read-time", "capSeries", List.of(ITEM + "=" + CAP_ITEM)));
        store.apply(ITEM, SeriesLayer.FORECAST,
                StoreFixtures.hourlyFrom(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 11, 4000, 4200, 4100, 3000));

        LayeredWriteReport report = store.apply(ITEM, SeriesLayer.CAP,
                StoreFixtures.hourlyFrom(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 12, 2500, 2500));

        assertThat(report.written(), is(0));
        assertThat(report.constrained(), is(2));
        assertThat("the prediction still holds what was predicted", watts(hour(12)), is(4200.0));

        ForecastSeries effective = store.read(ITEM, ForecastRole.SOLAR_PRODUCTION, StoreFixtures.DAY_START,
                StoreFixtures.DAY_START.plus(Duration.ofDays(1))).orElseThrow();

        assertThat("and a reader sees the capped value", effective.valueAt(hour(12)).getAsDouble(), is(2500.0));
        assertThat(effective.valueAt(hour(11)).getAsDouble(), is(4000.0));
        assertThat("an hour the cap does not cover is untouched", effective.valueAt(hour(14)).getAsDouble(),
                is(3000.0));
    }

    /**
     * A site that chose to keep caps apart and named nowhere to keep them is told, rather than having its cap dropped
     * quietly.
     */
    @Test
    public void keepingCapsApartWithNoConstraintSeriesIsReported() {
        PersistenceLayeredStore store = store(Map.of("writePolicy", "cap-at-read-time"));

        LayeredWriteReport report = store.apply(ITEM, SeriesLayer.CAP,
                StoreFixtures.hourlyFrom(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 12, 2500));

        assertThat(report.storedAnything(), is(false));
        assertThat(report.conditions(), hasItem(ForecastPlaneCondition.CAP_SERIES_UNCONFIGURED));
    }

    /**
     * <strong>The feasibility dependency, enforced.</strong> A layered series needs a persistence service that can
     * change what it stored; a site pointed at one that cannot is refused and told, rather than given a series that
     * silently only ever appends.
     */
    @Test
    public void aPersistenceServiceThatCannotModifyIsRefused() {
        PersistenceLayeredStore store = new PersistenceLayeredStore(items,
                new StoreFixtures.FakeServiceRegistry(new StoreFixtures.AppendOnlyPersistence()), clock, Map.of());

        LayeredWriteReport report = store.apply(ITEM, SeriesLayer.BASELINE,
                StoreFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 1000, 2000));

        assertThat(report.storedAnything(), is(false));
        assertThat(report.conditions(), contains(ForecastPlaneCondition.PERSISTENCE_NOT_MODIFIABLE));
        assertThat(store.read(ITEM, ForecastRole.SOLAR_PRODUCTION, StoreFixtures.DAY_START, NOW),
                is(java.util.Optional.empty()));
    }

    /**
     * A prediction series pointed at an Item that does not exist writes nothing and says which condition it is, since
     * an unresolved name and a broken service are different problems with different fixes.
     */
    @Test
    public void anItemThatDoesNotExistIsReportedRatherThanCreated() {
        PersistenceLayeredStore store = store(Map.of());

        LayeredWriteReport report = store.apply("Not_An_Item", SeriesLayer.BASELINE,
                StoreFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 1000, 2000));

        assertThat(report.storedAnything(), is(false));
        assertThat(report.conditions(), contains(ForecastPlaneCondition.ITEM_UNRESOLVED));
        assertThat(persistence.size("Not_An_Item"), is(0));
    }

    /**
     * Reading gives back slots rather than points: each stored value applies until the next one, which is the same
     * left-hold rule every calculation in the framework uses.
     */
    @Test
    public void aStoredSeriesIsReadBackAsSlotsThatHoldUntilTheNextEntry() {
        PersistenceLayeredStore store = store(Map.of());
        store.apply(ITEM, SeriesLayer.BASELINE,
                StoreFixtures.hourlyFrom(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 10, 1000, 2000, 3000));

        ForecastSeries read = store.read(ITEM, ForecastRole.SOLAR_PRODUCTION, StoreFixtures.DAY_START,
                StoreFixtures.DAY_START.plus(Duration.ofDays(1))).orElseThrow();

        assertThat(read.size(), is(3));
        assertThat(read.slotAt(0).duration(), is(Duration.ofHours(1)));
        assertThat(read.unit(), is(Units.WATT));
        assertThat(read.role(), is(ForecastRole.SOLAR_PRODUCTION));
        assertThat("the last entry's width is not stored anywhere, so it takes the one before it",
                read.slotAt(2).duration(), is(Duration.ofHours(1)));
        assertThat(read.valueAt(hour(11)).getAsDouble(), is(2000.0));
    }

    /**
     * _Forecast source fails_, in storage: nothing at all arrives, and everything that was there is still there.
     */
    @Test
    public void aBaselineOutlivesAForecastThatNeverComes() {
        PersistenceLayeredStore store = store(Map.of());
        store.apply(ITEM, SeriesLayer.BASELINE, StoreFixtures.hourly(ForecastRole.SOLAR_PRODUCTION, Units.WATT, 0, 0, 0,
                0, 0, 0, 0, 0, 500, 1500, 2000, 2200, 2200, 2000, 1500, 500, 0, 0, 0, 0, 0, 0, 0, 0));

        ForecastSeries read = store.read(ITEM, ForecastRole.SOLAR_PRODUCTION, StoreFixtures.DAY_START,
                StoreFixtures.DAY_START.plus(Duration.ofDays(1))).orElseThrow();

        assertThat(read.size(), is(24));
        assertThat(read.valueAt(hour(12)).getAsDouble(), is(2200.0));
    }

    private static Instant hour(int hour) {
        return StoreFixtures.DAY_START.plus(Duration.ofHours(hour));
    }

    private double watts(Instant timestamp) {
        org.openhab.core.types.State state = persistence.valueAt(ITEM, timestamp);
        assertThat("nothing is stored at " + timestamp, state, is(notNullValue()));
        return ((QuantityType<?>) Objects.requireNonNull(state)).doubleValue();
    }
}
