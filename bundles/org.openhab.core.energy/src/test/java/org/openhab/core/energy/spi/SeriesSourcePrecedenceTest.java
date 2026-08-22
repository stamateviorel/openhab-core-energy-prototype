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
package org.openhab.core.energy.spi;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.forecast.ForecastRole;
import org.openhab.core.energy.forecast.ForecastSeries;
import org.openhab.core.energy.forecast.ForecastSeriesSource;
import org.openhab.core.energy.forecast.internal.ForecastRegistryImpl;
import org.openhab.core.energy.objective.CarbonSeries;
import org.openhab.core.energy.objective.CarbonSeriesSource;
import org.openhab.core.energy.objective.internal.ObjectivePlane;
import org.openhab.core.energy.price.EnergyPriceSeries;
import org.openhab.core.energy.price.EnergyPriceSource;
import org.openhab.core.energy.price.EnergyPriceUnits;
import org.openhab.core.energy.price.PriceDirection;
import org.openhab.core.energy.price.PriceRole;
import org.openhab.core.energy.price.internal.EnergyPriceRegistryImpl;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.library.unit.Units;

/**
 * The one precedence rule, proved to be one rule in all three planes at once.
 * <p>
 * <strong>What this test is defending against, and why it is not paranoid.</strong> The price, forecast and carbon
 * planes were built in parallel and each hand-rolled the rule that decides which of several sources answers. All
 * three happened to agree - highest {@code service.ranking} wins, ties broken by the lowest source id - but nothing
 * made them agree, no test compared them, and each expressed it differently enough ({@code max} over an ascending
 * comparator, a descending {@code sort}, {@code min} over a reversed one) that a later edit to any one of them would
 * plausibly have changed only that plane. The user-visible failure is nasty precisely because it is quiet: a site
 * with two equally-ranked sources sees its price source honoured and its forecast source ignored, no error is logged
 * anywhere, and the symptom is "the plan is wrong sometimes".
 * <p>
 * So the rule now lives in {@link SourceRanking} and the three planes call it. That is only half a fix - a later
 * agent could inline it again in one plane. This test is the other half: it drives the three <em>real</em>
 * registries, not the helper, with the same deliberately awkward set of sources, and asserts that the answers agree.
 * Re-introducing a private comparator anywhere breaks it.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class SeriesSourcePrecedenceTest {

    private static final ZoneId HELSINKI = ZoneId.of("Europe/Helsinki");
    private static final Instant MIDNIGHT = LocalDate.of(2023, 1, 11).atStartOfDay(HELSINKI).toInstant();

    private static SlotSeries values() {
        return SlotSeries.uniform(MIDNIGHT, Duration.ofHours(1), 1, 2, 3);
    }

    /**
     * The highest ranking answers, in every plane, when the rankings differ.
     */
    @Test
    public void theHighestRankedSourceAnswersInEveryPlane() {
        assertThat(priceWinner(List.of(source("low", -2), source("high", 5), source("middle", 0))), is("high"));
        assertThat(forecastWinner(List.of(forecast("low", -2), forecast("high", 5), forecast("middle", 0))),
                is("high"));
        assertThat(carbonWinner(List.of(carbon("low", -2), carbon("high", 5), carbon("middle", 0))), is("high"));
    }

    /**
     * Equal rankings are broken the same way in every plane - and the answer does not depend on the order the
     * sources were registered in, which is the property that makes it survive a restart.
     */
    @Test
    public void anEqualRankingIsBrokenTheSameWayInEveryPlaneAndDoesNotDependOnRegistrationOrder() {
        List<String> ids = List.of("zulu", "alpha", "mike");
        List<String> reversed = List.of("mike", "alpha", "zulu");

        String price = priceWinner(ids.stream().map(id -> source(id, 3)).toList());
        String priceReversed = priceWinner(reversed.stream().map(id -> source(id, 3)).toList());
        String forecast = forecastWinner(ids.stream().map(id -> forecast(id, 3)).toList());
        String forecastReversed = forecastWinner(reversed.stream().map(id -> forecast(id, 3)).toList());
        String carbon = carbonWinner(ids.stream().map(id -> carbon(id, 3)).toList());
        String carbonReversed = carbonWinner(reversed.stream().map(id -> carbon(id, 3)).toList());

        // the decided rule: lowest source id
        assertThat(price, is("alpha"));
        // and the three planes agree with each other, which is the thing that was not previously true by construction
        assertThat(List.of(forecast, carbon), everyItem(is(price)));
        // registration order changes nothing anywhere
        assertThat(List.of(priceReversed, forecastReversed, carbonReversed), everyItem(is(price)));
    }

    /**
     * A ranking beats an alphabetically earlier id: the tie-break is only ever reached on a genuine tie, in every
     * plane. Without this the previous assertion would also pass on an implementation that ignored the ranking.
     */
    @Test
    public void theTieBreakIsOnlyReachedOnAGenuineTie() {
        assertThat(priceWinner(List.of(source("alpha", 0), source("zulu", 1))), is("zulu"));
        assertThat(forecastWinner(List.of(forecast("alpha", 0), forecast("zulu", 1))), is("zulu"));
        assertThat(carbonWinner(List.of(carbon("alpha", 0), carbon("zulu", 1))), is("zulu"));
    }

    /**
     * Core's own sources sit below anything a site installs, so an add-on registering at the OSGi default of zero
     * takes over with no configuration at all. That is what the {@code -2} in
     * {@link EnergySeriesSource#CORE_DEFAULT_RANKING} buys, and the number is asserted rather than described because
     * it is the thing a contributor has to be able to beat without knowing it.
     */
    @Test
    public void aCoreShippedSourceIsOutrankedByAnythingInstalled() {
        assertThat(EnergySeriesSource.CORE_DEFAULT_RANKING, is(-2));
        assertThat(priceWinner(List.of(source("core", EnergySeriesSource.CORE_DEFAULT_RANKING), source("addon", 0))),
                is("addon"));
        assertThat(
                forecastWinner(
                        List.of(forecast("core", EnergySeriesSource.CORE_DEFAULT_RANKING), forecast("addon", 0))),
                is("addon"));
        assertThat(carbonWinner(List.of(carbon("core", EnergySeriesSource.CORE_DEFAULT_RANKING), carbon("addon", 0))),
                is("addon"));
    }

    /**
     * The three plane SPIs really are one contract now, rather than three interfaces that merely look alike. This is
     * a compile-time fact given form, so that deleting the shared supertype fails a test instead of quietly
     * reintroducing three shapes.
     */
    @Test
    public void allThreePlaneSourcesAreTheSameContract() {
        assertThat(EnergySeriesSource.class.isAssignableFrom(EnergyPriceSource.class), is(true));
        assertThat(EnergySeriesSource.class.isAssignableFrom(ForecastSeriesSource.class), is(true));
        assertThat(EnergySeriesSource.class.isAssignableFrom(CarbonSeriesSource.class), is(true));
    }

    private static String priceWinner(List<EnergyPriceSource> sources) {
        EnergyPriceRegistryImpl registry = new EnergyPriceRegistryImpl(Map.of());
        sources.forEach(registry::addSource);
        return registry.sourceFor(PriceRole.SPOT).orElseThrow().getSourceId();
    }

    private static String forecastWinner(List<ForecastSeriesSource> sources) {
        ForecastRegistryImpl registry = new ForecastRegistryImpl(Map.of());
        sources.forEach(registry::addForecastSeriesSource);
        // resolution is what records which source answered, so ask for the series and then for its origin
        return registry.getSeries(ForecastRole.SOLAR_PRODUCTION)
                .flatMap(series -> registry.getAnsweringSourceId(ForecastRole.SOLAR_PRODUCTION)).orElseThrow();
    }

    private static String carbonWinner(List<CarbonSeriesSource> sources) {
        ObjectivePlane plane = new ObjectivePlane(Map.of());
        sources.forEach(plane::addCarbonSource);
        return plane.carbonSource().orElseThrow().getSourceId();
    }

    private static EnergyPriceSource source(String id, int ranking) {
        EnergyPriceSeries series = EnergyPriceSeries.of(MIDNIGHT, Duration.ofHours(1), EnergyPriceUnits.currency("EUR"),
                EnergyPriceUnits.defaultEnergyUnit(), HELSINKI, PriceDirection.CONSUMPTION, 1, 2, 3);
        return new EnergyPriceSource() {
            @Override
            public String getSourceId() {
                return id;
            }

            @Override
            public PriceRole getRole() {
                return PriceRole.SPOT;
            }

            @Override
            public int getServiceRanking() {
                return ranking;
            }

            @Override
            public Optional<EnergyPriceSeries> getSeries() {
                return Optional.of(series);
            }
        };
    }

    private static ForecastSeriesSource forecast(String id, int ranking) {
        ForecastSeries series = ForecastSeries.of(ForecastRole.SOLAR_PRODUCTION, id, Units.WATT, MIDNIGHT, values());
        return new ForecastSeriesSource() {
            @Override
            public String getSourceId() {
                return id;
            }

            @Override
            public ForecastRole getRole() {
                return ForecastRole.SOLAR_PRODUCTION;
            }

            @Override
            public int getServiceRanking() {
                return ranking;
            }

            @Override
            public Optional<ForecastSeries> getSeries() {
                return Optional.of(series);
            }
        };
    }

    private static CarbonSeriesSource carbon(String id, int ranking) {
        CarbonSeries series = CarbonSeries.intensity(id, MIDNIGHT, values());
        return new CarbonSeriesSource() {
            @Override
            public String getSourceId() {
                return id;
            }

            @Override
            public int getServiceRanking() {
                return ranking;
            }

            @Override
            public Optional<CarbonSeries> getSeries() {
                return Optional.of(series);
            }
        };
    }
}
