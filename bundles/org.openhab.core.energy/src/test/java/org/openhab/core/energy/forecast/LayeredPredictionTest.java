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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.window.SeriesSense;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;

/**
 * The scenarios of _Layered prediction series_, and the collision the corpus left open.
 * <p>
 * These test the <em>decision</em> half of a layered write - what should end up in the series - which is what lives in
 * the framework bundle. Carrying the plan out against a real persistence service is
 * {@code org.openhab.core.energy.forecast.store}'s, and is tested there.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class LayeredPredictionTest {

    private static final Instant DAY = ForecastFixtures.DAY_START;

    /**
     * _PV baseline refined by forecast_: a year of baseline, a forecast for the coming days, and everything the
     * forecast does not cover keeps the baseline.
     */
    @Test
    public void aForecastRefinesTheDaysItCoversAndLeavesTheRestOfTheYearAlone() {
        List<LayeredEntry> baseline = baselineYear();
        List<LayeredEntry> refresh = List.of(
                new LayeredEntry(DAY.plus(Duration.ofHours(10)), 3900, SeriesLayer.FORECAST),
                new LayeredEntry(DAY.plus(Duration.ofHours(11)), 4200, SeriesLayer.FORECAST));

        LayeredWritePlan plan = LayeredSeriesResolver.resolve(baseline, refresh,
                LayeredWritePolicy.FRESH_OVERWRITES_OLD, Map.of());

        assertThat("only the covered entries are written", plan.entries(), hasSize(2));
        assertThat(plan.entries().getFirst().value(), is(3900.0));
        assertThat(plan.hasCollision(), is(false));
        // the rest of the year is untouched, which is what "not writing it" means
        Map<Instant, LayeredEntry> after = applied(baseline, plan);
        assertThat(after, hasKey(DAY.plus(Duration.ofDays(200))));
        assertThat(at(after, DAY.plus(Duration.ofHours(10))).value(), is(3900.0));
        assertThat(at(after, DAY.plus(Duration.ofHours(12))).layer(), is(SeriesLayer.BASELINE));
    }

    /**
     * _Hard generation cap written into today_: the affected entries of today's prediction carry the capped value.
     */
    @Test
    public void aCapIsWrittenOntoTheEntriesItAppliesTo() {
        List<LayeredEntry> stored = List.of(
                new LayeredEntry(DAY.plus(Duration.ofHours(11)), 4000, SeriesLayer.FORECAST),
                new LayeredEntry(DAY.plus(Duration.ofHours(12)), 4200, SeriesLayer.FORECAST),
                new LayeredEntry(DAY.plus(Duration.ofHours(13)), 4100, SeriesLayer.FORECAST));
        List<LayeredEntry> cap = List.of(new LayeredEntry(DAY.plus(Duration.ofHours(12)), 2500, SeriesLayer.CAP),
                new LayeredEntry(DAY.plus(Duration.ofHours(13)), 2500, SeriesLayer.CAP));

        LayeredWritePlan plan = LayeredSeriesResolver.resolve(stored, cap, LayeredWritePolicy.FRESH_OVERWRITES_OLD,
                Map.of());
        Map<Instant, LayeredEntry> after = applied(stored, plan);

        assertThat(at(after, DAY.plus(Duration.ofHours(11))).value(), is(4000.0));
        assertThat(at(after, DAY.plus(Duration.ofHours(12))).value(), is(2500.0));
        assertThat(at(after, DAY.plus(Duration.ofHours(13))).value(), is(2500.0));
        assertThat("a cap landing on a forecast is not the contested direction", plan.hasCollision(), is(false));
    }

    /**
     * <strong>The collision the corpus does not resolve, and this test does not resolve either.</strong> A site that
     * has configured nothing gets the requirement's own words - the refresh wins - and the erased cap is reported
     * rather than lost silently. What this test pins is that the erasure is <em>visible</em>, not that it is right.
     */
    @Test
    public void aRefreshOverAnUnconfiguredSiteErasesTheCapAndSaysSo() {
        List<LayeredEntry> stored = List.of(new LayeredEntry(DAY.plus(Duration.ofHours(12)), 2500, SeriesLayer.CAP));
        List<LayeredEntry> refresh = List
                .of(new LayeredEntry(DAY.plus(Duration.ofHours(12)), 4200, SeriesLayer.FORECAST));

        LayeredWritePlan plan = LayeredSeriesResolver.resolve(stored, refresh, null, Map.of());

        assertThat(at(applied(stored, plan), DAY.plus(Duration.ofHours(12))).value(), is(4200.0));
        assertThat(plan.collisions(), contains(DAY.plus(Duration.ofHours(12))));
        assertThat(plan.conditions(), containsInAnyOrder(ForecastPlaneCondition.CAP_OVERWRITTEN_BY_REFRESH,
                ForecastPlaneCondition.WRITE_POLICY_UNCONFIGURED));
    }

    /**
     * Design §2 option one: the cap is put back on after the refresh, so the prediction ends up capped.
     */
    @Test
    public void reapplyingCapsPutsTheCapBackOnTopOfTheRefresh() {
        List<LayeredEntry> stored = List.of(new LayeredEntry(DAY.plus(Duration.ofHours(12)), 2500, SeriesLayer.CAP));
        List<LayeredEntry> refresh = List
                .of(new LayeredEntry(DAY.plus(Duration.ofHours(12)), 4200, SeriesLayer.FORECAST));

        LayeredWritePlan plan = LayeredSeriesResolver.resolve(stored, refresh,
                LayeredWritePolicy.REAPPLY_CAPS_AFTER_REFRESH, Map.of());

        assertThat("the refresh is written and then the cap goes back on", plan.entries(), hasSize(2));
        assertThat(plan.entries().getLast().layer(), is(SeriesLayer.CAP));
        assertThat(at(applied(stored, plan), DAY.plus(Duration.ofHours(12))).value(), is(2500.0));
        assertThat(plan.conditions(), contains(ForecastPlaneCondition.CAP_OVERWRITTEN_BY_REFRESH));
    }

    /**
     * Design §2 option two, with the order the site declared: the refresh is refused where the cap outranks it.
     */
    @Test
    public void writerPrecedenceRefusesTheRefreshWhereTheCapOutranksIt() {
        List<LayeredEntry> stored = List.of(new LayeredEntry(DAY.plus(Duration.ofHours(12)), 2500, SeriesLayer.CAP));
        List<LayeredEntry> refresh = List
                .of(new LayeredEntry(DAY.plus(Duration.ofHours(12)), 4200, SeriesLayer.FORECAST));

        LayeredWritePlan plan = LayeredSeriesResolver.resolve(stored, refresh, LayeredWritePolicy.WRITER_PRECEDENCE,
                Map.of(SeriesLayer.BASELINE, 0, SeriesLayer.FORECAST, 10, SeriesLayer.CAP, 20));

        assertThat(plan.entries(), is(empty()));
        assertThat(plan.refused(), hasSize(1));
        assertThat(at(applied(stored, plan), DAY.plus(Duration.ofHours(12))).value(), is(2500.0));
        assertThat(plan.conditions(), contains(ForecastPlaneCondition.WRITE_REFUSED_BY_PRECEDENCE));
    }

    /**
     * The same option with no order declared refuses the write whole and says why, rather than applying an order
     * nobody chose. This is D22's pattern: ship the shape, ship no number, report unconfigured.
     */
    @Test
    public void writerPrecedenceWithNoDeclaredOrderWritesNothingAndSaysWhy() {
        List<LayeredEntry> stored = List.of(new LayeredEntry(DAY.plus(Duration.ofHours(12)), 2500, SeriesLayer.CAP));
        List<LayeredEntry> refresh = List
                .of(new LayeredEntry(DAY.plus(Duration.ofHours(12)), 4200, SeriesLayer.FORECAST));

        LayeredWritePlan plan = LayeredSeriesResolver.resolve(stored, refresh, LayeredWritePolicy.WRITER_PRECEDENCE,
                Map.of());

        assertThat(plan.isEmpty(), is(true));
        assertThat(plan.refused(), hasSize(1));
        assertThat(plan.conditions(), contains(ForecastPlaneCondition.LAYER_PRECEDENCE_UNCONFIGURED));
    }

    /**
     * Design §2 option three, and the decision record's own answer: the cap never enters the prediction at all, and
     * the effective series is the two composed when it is read.
     */
    @Test
    public void composingAtReadTimeKeepsTheCapOutOfThePredictionEntirely() {
        List<LayeredEntry> cap = List.of(new LayeredEntry(DAY.plus(Duration.ofHours(12)), 2500, SeriesLayer.CAP));

        LayeredWritePlan plan = LayeredSeriesResolver.resolve(List.of(), cap,
                LayeredWritePolicy.CAP_COMPOSED_AT_READ_TIME, Map.of());

        assertThat("nothing is written into the prediction", plan.entries(), is(empty()));
        assertThat(plan.constraints(), hasSize(1));
        assertThat(plan.hasCollision(), is(false));

        SlotSeries prediction = SlotSeries.hourly(DAY, 0, 1000, 4000, 4200, 4100)
                .withSense(SeriesSense.HIGHER_IS_BETTER);
        SlotSeries capSeries = new SlotSeries(
                List.of(new Slot(DAY.plus(Duration.ofHours(2)), DAY.plus(Duration.ofHours(5)), 2500)),
                SeriesSense.HIGHER_IS_BETTER);

        SlotSeries effective = LayeredSeriesResolver.composeCap(prediction, capSeries);

        assertThat(effective.valueAt(1), is(1000.0));
        assertThat(effective.valueAt(2), is(2500.0));
        assertThat(effective.valueAt(3), is(2500.0));
        assertThat(effective.valueAt(4), is(2500.0));
        assertThat("the prediction keeps its own boundaries", effective.size(), is(prediction.size()));
    }

    /**
     * The composition is a minimum and not a replacement: an hour that was never going to reach the cap keeps its
     * own, lower value.
     */
    @Test
    public void aCapNeverRaisesAPrediction() {
        SlotSeries prediction = SlotSeries.hourly(DAY, 100, 4000);
        SlotSeries cap = SlotSeries.hourly(DAY, 2500, 2500);

        SlotSeries effective = LayeredSeriesResolver.composeCap(prediction, cap);

        assertThat(effective.valueAt(0), is(100.0));
        assertThat(effective.valueAt(1), is(2500.0));
    }

    /**
     * _Forecast source fails_ in its storage half: entries nothing has overwritten are still there, whatever happened
     * to the provider. The registry half - a dark source degrading to the stored baseline - is
     * {@link ForecastRegistryImplTest}'s.
     */
    @Test
    public void anEntryNoRefreshCoveredKeepsWhateverWroteItLast() {
        List<LayeredEntry> baseline = baselineYear();

        LayeredWritePlan nothingArrived = LayeredSeriesResolver.resolve(baseline, List.of(), null, Map.of());

        assertThat(nothingArrived.isEmpty(), is(true));
        assertThat(applied(baseline, nothingArrived).size(), is(baseline.size()));
    }

    private static List<LayeredEntry> baselineYear() {
        List<LayeredEntry> entries = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            entries.add(new LayeredEntry(DAY.plus(Duration.ofHours(hour)), hour >= 8 && hour <= 17 ? 2000 : 0,
                    SeriesLayer.BASELINE));
        }
        // one far-away day, standing in for the rest of the year the baseline covers
        entries.add(new LayeredEntry(DAY.plus(Duration.ofDays(200)), 3000, SeriesLayer.BASELINE));
        return List.copyOf(entries);
    }

    private static LayeredEntry at(Map<Instant, LayeredEntry> series, Instant timestamp) {
        return Objects.requireNonNull(series.get(timestamp));
    }

    private static Map<Instant, LayeredEntry> applied(List<LayeredEntry> stored, LayeredWritePlan plan) {
        Map<Instant, LayeredEntry> series = new LinkedHashMap<>();
        stored.forEach(entry -> series.put(entry.timestamp(), entry));
        plan.entries().forEach(entry -> series.put(entry.timestamp(), entry));
        return series;
    }
}
