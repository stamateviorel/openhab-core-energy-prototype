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
package org.openhab.core.energy.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.openhab.core.config.core.status.ConfigStatusMessage;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.forecast.ForecastRole;
import org.openhab.core.energy.forecast.ForecastSeries;
import org.openhab.core.energy.forecast.ForecastSeriesSource;
import org.openhab.core.energy.forecast.internal.ForecastRegistryImpl;
import org.openhab.core.energy.level.PlanDerivationCondition;
import org.openhab.core.energy.level.PlannedLevelSchedule;
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
 * The loop closing: three data planes, one level plan, and nothing written anywhere.
 * <p>
 * <strong>Why this test is the point of wave 2.</strong> Wave 1 shipped
 * {@code EnergyLevelPlane.derivePlan(SlotSeries)} with no production caller, and wave 2 shipped a price plane, a
 * forecast plane and an objective plane that each answer correctly and never speak to one another. Everything was
 * green and the framework still could not turn an installed price source into a level plan. These assertions are the
 * join, driven through the real components rather than mocks - the real price registry composing a real source, the
 * real objective plane resolving a real objective, the real level plane cutting real bands - so that "the planes are
 * wired" is a fact about the code rather than a claim in a report.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EnergyPlanCoordinatorTest {

    private static final ZoneId HELSINKI = ZoneId.of("Europe/Helsinki");
    private static final Instant MIDNIGHT = LocalDate.of(2023, 1, 11).atStartOfDay(HELSINKI).toInstant();

    /**
     * A day whose value climbs every hour, so the cheapest and dearest hours are unambiguous and far apart.
     *
     * @return twenty-four ascending values
     */
    private static double[] rising() {
        double[] day = new double[24];
        for (int hour = 0; hour < day.length; hour++) {
            day[hour] = 10 + hour;
        }
        return day;
    }

    /**
     * The same day the other way up, which is what makes price and carbon disagree about every hour.
     *
     * @return twenty-four descending values
     */
    private static double[] falling() {
        double[] day = new double[24];
        for (int hour = 0; hour < day.length; hour++) {
            day[hour] = 400 - hour * 10;
        }
        return day;
    }

    /**
     * An installed price source becomes a level plan, with no configuration beyond naming the composition.
     * <p>
     * This is the whole loop in one assertion. Before the coordinator existed the plan stayed empty here no matter
     * what was installed.
     */
    @Test
    public void anInstalledPriceSourceBecomesALevelPlan() {
        Harness harness = new Harness();
        harness.withSpotPrices(10, 20, 30, 40);

        Optional<PlannedLevelSchedule> plan = harness.coordinator().derive();

        assertThat(plan.isPresent(), is(true));
        assertThat(plan.get().size(), is(4));
        // the level plane installed it, so the plan the engine would consult is the plan just derived
        assertThat(harness.levelPlane().getPlan().size(), is(4));
    }

    /**
     * The cheapest hours come out cheap and the dearest come out dear - the coordinator hands the series over in the
     * right sense rather than merely handing something over.
     */
    @Test
    public void theDerivedPlanRanksTheCheapHoursBest() {
        Harness harness = new Harness();
        // a full day, because the shipped derivation cuts fixed-size bands and a four-slot series is entirely one band
        harness.withSpotPrices(rising());

        harness.coordinator().derive();
        PlannedLevelSchedule plan = harness.levelPlane().getPlan();

        // BLOCKED(0) < NORMAL(1) < ENCOURAGED(2) < OVERCAPACITY(3), so a cheap hour carries the higher level
        EnergyLevel cheapest = plan.levelAt(MIDNIGHT).orElseThrow();
        EnergyLevel dearest = plan.levelAt(MIDNIGHT.plus(Duration.ofHours(23))).orElseThrow();
        assertThat(cheapest.code(), is(greaterThan(dearest.code())));
    }

    /**
     * A site with nothing installed derives nothing, says so, and does not fabricate a plan.
     * <p>
     * The fresh-installation case matters because core ships no price source: the honest answer is an absent plan and
     * a reported reason, never a plan derived from an invented price.
     */
    @Test
    public void aSiteWithNoPriceSourceDerivesNothingAndSaysSo() {
        Harness harness = new Harness();

        Optional<PlannedLevelSchedule> plan = harness.coordinator().derive();

        assertThat(plan.isEmpty(), is(true));
        assertThat(harness.coordinator().getConditions(), hasItems(PlanDerivationCondition.NO_SERIES_TO_DERIVE_FROM,
                PlanDerivationCondition.PRICE_COMPOSITION_FAILED));
        assertThat(harness.levelPlane().getPlan().size(), is(0));
    }

    /**
     * An unset refresh interval is reported rather than defaulted, and the consequence is stated: nothing notices a
     * source that quietly publishes new prices.
     */
    @Test
    public void anUnsetRefreshIntervalIsReported() {
        Harness harness = new Harness();
        harness.withSpotPrices(10, 20);

        harness.coordinator().derive();

        assertThat(harness.coordinator().getConditions(),
                hasItem(PlanDerivationCondition.REFRESH_INTERVAL_UNCONFIGURED));
    }

    /**
     * Configuring an interval removes the condition, which is the other half of the same statement.
     */
    @Test
    public void aConfiguredRefreshIntervalIsNotReported() {
        Harness harness = new Harness(Map.of("refreshInterval", "900"));
        harness.withSpotPrices(10, 20);

        harness.coordinator().derive();

        assertThat(harness.coordinator().getConditions(),
                not(hasItem(PlanDerivationCondition.REFRESH_INTERVAL_UNCONFIGURED)));
    }

    /**
     * A surplus forecast built from solar alone is handed over, and reported as production rather than surplus.
     * <p>
     * The quantity the self-consumption objective needs is not defined anywhere in the corpus. What this proves is
     * that the approximation is labelled where it is built, so a site optimising on an upper bound is told it is.
     */
    @Test
    public void aSurplusForecastWithoutADemandForecastIsReportedAsProductionOnly() {
        Harness harness = new Harness(Map.of("surplusForecast", "true"));
        harness.withSpotPrices(10, 20, 30);
        harness.withSolarForecast(0, 2000, 4000);

        harness.coordinator().derive();

        assertThat(harness.coordinator().getConditions(),
                hasItem(PlanDerivationCondition.SURPLUS_FORECAST_IS_PRODUCTION_ONLY));
    }

    /**
     * Owner decision D39: left alone, the surplus forecast turns itself on where the figure means something and stays
     * off where it would be the roof figure under another name.
     * <p>
     * A production forecast presented as surplus schedules a load into hours the house quietly eats first, so on a
     * site with nothing predicting its own demand nothing is handed to the objectives at all - and the reason is
     * reported rather than left as silence.
     */
    @Test
    public void anUnconfiguredSiteWithNoDemandForecastWithholdsTheSurplusForecast() {
        Harness harness = new Harness();
        harness.withSpotPrices(10, 20, 30);
        harness.withSolarForecast(0, 2000, 4000);

        harness.coordinator().derive();

        assertThat(harness.coordinator().getConditions(), hasItem(PlanDerivationCondition.SURPLUS_FORECAST_WITHHELD));
        assertThat(harness.coordinator().getConditions(),
                not(hasItem(PlanDerivationCondition.SURPLUS_FORECAST_IS_PRODUCTION_ONLY)));
    }

    /**
     * The same site once something does predict its demand: the feature is on, without anyone having switched it on.
     */
    @Test
    public void anUnconfiguredSiteWithADemandForecastUsesTheSurplusForecast() {
        Harness harness = new Harness();
        harness.withSpotPrices(10, 20, 30);
        harness.withSolarForecast(0, 2000, 4000);
        harness.withHeatingDemand(Duration.ofHours(1), 1, 1, 1);

        harness.coordinator().derive();

        assertThat(harness.coordinator().getConditions(),
                not(hasItem(PlanDerivationCondition.SURPLUS_FORECAST_WITHHELD)));
    }

    /**
     * A site that wants the upper bound anyway still gets it, and still gets told what it is. D39 changed what an
     * unconfigured site does, not what an explicit one can ask for.
     */
    @Test
    public void askingForTheSurplusForecastExplicitlyStillGivesTheUpperBound() {
        Harness harness = new Harness(Map.of("surplusForecast", "true"));
        harness.withSpotPrices(10, 20, 30);
        harness.withSolarForecast(0, 2000, 4000);

        harness.coordinator().derive();

        assertThat(harness.coordinator().getConditions(),
                hasItem(PlanDerivationCondition.SURPLUS_FORECAST_IS_PRODUCTION_ONLY));
        assertThat(harness.coordinator().getConditions(),
                not(hasItem(PlanDerivationCondition.SURPLUS_FORECAST_WITHHELD)));
    }

    /**
     * And a site that switched it off gets neither the series nor a condition about it.
     */
    @Test
    public void switchingTheSurplusForecastOffReportsNothingAboutIt() {
        Harness harness = new Harness(Map.of("surplusForecast", "false"));
        harness.withSpotPrices(10, 20, 30);
        harness.withSolarForecast(0, 2000, 4000);

        harness.coordinator().derive();

        assertThat(harness.coordinator().getConditions(),
                not(hasItem(PlanDerivationCondition.SURPLUS_FORECAST_WITHHELD)));
        assertThat(harness.coordinator().getConditions(),
                not(hasItem(PlanDerivationCondition.SURPLUS_FORECAST_IS_PRODUCTION_ONLY)));
    }

    /**
     * A demand forecast whose slots do not line up with the solar forecast is left alone rather than resampled, and
     * the mismatch is reported.
     * <p>
     * Nothing is silently interpolated here on purpose: how series of different geometry are combined is an open
     * question the price plane already faces for composition, and answering it a second time and differently inside
     * the coordinator is how a framework acquires two incompatible notions of alignment.
     */
    @Test
    public void aMisalignedDemandForecastIsReportedRatherThanResampled() {
        Harness harness = new Harness(Map.of("surplusForecast", "true"));
        harness.withSpotPrices(10, 20, 30);
        harness.withSolarForecast(0, 2000, 4000);
        harness.withHeatingDemand(Duration.ofMinutes(30), 1, 1, 1);

        harness.coordinator().derive();

        assertThat(harness.coordinator().getConditions(), hasItem(PlanDerivationCondition.SURPLUS_FORECAST_UNALIGNED));
    }

    /**
     * An aligned demand forecast is netted off, and the result is a genuine surplus rather than production - so the
     * "production only" caveat disappears.
     */
    @Test
    public void anAlignedDemandForecastIsNettedOff() {
        Harness harness = new Harness(Map.of("surplusForecast", "true"));
        harness.withSpotPrices(10, 20, 30);
        harness.withSolarForecast(0, 2000, 4000);
        harness.withHeatingDemand(Duration.ofHours(1), 1, 1, 1);

        harness.coordinator().derive();

        assertThat(harness.coordinator().getConditions(),
                not(hasItem(PlanDerivationCondition.SURPLUS_FORECAST_IS_PRODUCTION_ONLY)));
        assertThat(harness.coordinator().getConditions(),
                not(hasItem(PlanDerivationCondition.SURPLUS_FORECAST_UNALIGNED)));
    }

    /**
     * A carbon source alters the plan when the site asks for carbon-shaped levels, and does not when it does not.
     * <p>
     * This is the whole of the objective seam observed from outside: the same prices and the same carbon data give
     * two different plans depending on one configuration value, which is what makes objectives-selectable a real
     * capability rather than a registry nobody consults.
     */
    @Test
    public void theSelectedObjectiveReachesTheDerivedPlan() {
        // prices rise through the day; carbon intensity falls, so the two disagree about which hour is best
        Harness priceLevels = new Harness(Map.of());
        priceLevels.withSpotPrices(rising());
        priceLevels.withCarbon(falling());
        priceLevels.coordinator().derive();
        EnergyLevel firstHourOnPrice = priceLevels.levelPlane().getPlan().levelAt(MIDNIGHT).orElseThrow();

        Harness objectiveLevels = new Harness(Map.of(), Map.of("objective", "carbon", "levelInput", "objective"));
        objectiveLevels.withSpotPrices(rising());
        objectiveLevels.withCarbon(falling());
        objectiveLevels.coordinator().derive();
        EnergyLevel firstHourOnCarbon = objectiveLevels.levelPlane().getPlan().levelAt(MIDNIGHT).orElseThrow();

        // the cheapest hour is the dirtiest, so switching the objective moves the first hour from best to worst
        assertThat(firstHourOnPrice.code(), is(greaterThan(firstHourOnCarbon.code())));
    }

    /**
     * The coordinator never lets a broken contribution take the refresh down with it.
     * <p>
     * A contributed source is arbitrary code. If it throws, the previous plan has to stand and the next scheduled
     * derivation has to happen; an exception escaping would cancel the schedule permanently.
     */
    @Test
    public void aThrowingSourceDoesNotStopTheCoordinator() {
        Harness harness = new Harness();
        harness.withThrowingSpotSource();

        Optional<PlannedLevelSchedule> plan = harness.coordinator().derive();

        assertThat(plan.isEmpty(), is(true));
        // and it still answers afterwards
        assertThat(harness.coordinator().getConditions(), is(notNullValue()));
    }

    /**
     * What the coordinator concludes reaches the surface a person reads, and reaches it as a sentence.
     * <p>
     * <strong>Every other test here reads {@code getConditions()}</strong> - the in-memory set - which says what the
     * coordinator decided and nothing about what it said. Two defects lived in that gap and neither was visible to
     * any assertion: the plan messages had no key in the translations bundle at all, so they rendered as no text, and
     * the reporting path passed the constant's own name as its description, so the one argument that is supposed to
     * explain the condition repeated it. The unset refresh interval is the case that matters most, because it is the
     * one thing wave 2 refused to default and the whole point of refusing was that the site would be told.
     *
     * @throws Exception if the translations bundle cannot be read
     */
    @Test
    public void whatTheCoordinatorConcludesReachesTheReportAsASentence() throws Exception {
        EnergyConfigStatus status = new EnergyConfigStatus();
        Harness harness = new Harness(Map.of(), Map.of(), status);
        harness.withSpotPrices(10, 20);

        harness.coordinator().derive();

        List<ConfigStatusMessage> reported = List.copyOf(status.getConfigStatus());
        assertThat(reported, hasSize(1));
        ConfigStatusMessage message = reported.getFirst();
        assertThat(message.type, is(ConfigStatusMessage.Type.WARNING));
        assertThat(message.parameterName, is(PlanDerivationCondition.REFRESH_INTERVAL_UNCONFIGURED.name()));
        assertThat("the message has to be keyed on something the translations bundle carries", message.toString(),
                containsString(EnergyConfigStatus.KEY_PLAN_CONDITION));

        String description = describedBy(message);
        assertThat("the description must not be the constant's own name a second time", description,
                is(not(PlanDerivationCondition.REFRESH_INTERVAL_UNCONFIGURED.name())));
        assertThat(description, containsString("refresh interval"));

        Properties translations = new Properties();
        try (var reader = Files.newBufferedReader(
                Path.of("src", "main", "resources", "OH-INF", "i18n", "energy.properties"), StandardCharsets.UTF_8)) {
            translations.load(reader);
        }
        assertThat("without this key the whole message renders as nothing", translations,
                hasKey("config-status.warning." + EnergyConfigStatus.KEY_PLAN_CONDITION));
    }

    /**
     * A condition that stops applying disappears without anything having to clear it, which is what makes the plan
     * conditions the state of the last run rather than a log of runs.
     */
    @Test
    public void aPlanConditionThatStopsApplyingDisappears() {
        EnergyConfigStatus status = new EnergyConfigStatus();
        Harness harness = new Harness(Map.of("refreshInterval", "900"), Map.of(), status);

        harness.coordinator().derive();
        assertThat("nothing is installed yet, so the plan cannot be derived", status.getConfigStatus().toString(),
                containsString(PlanDerivationCondition.NO_SERIES_TO_DERIVE_FROM.name()));

        harness.withSpotPrices(10, 20);
        harness.coordinator().derive();

        assertThat(status.getConfigStatus(), is(empty()));
    }

    /**
     * Reads the second argument of a status message - its description - out of the only rendering the framework
     * offers a test.
     *
     * @param message the message
     * @return the description argument
     */
    private static String describedBy(ConfigStatusMessage message) {
        String rendered = message.toString();
        String arguments = rendered.substring(rendered.indexOf("arguments=[") + "arguments=[".length(),
                rendered.indexOf("], message="));
        return arguments.substring(arguments.indexOf(", ") + 2);
    }

    /**
     * A small assembly of the real components, so that every assertion above runs against production code paths.
     */
    private static final class Harness {

        private final EnergyPriceRegistryImpl prices;
        private final ForecastRegistryImpl forecasts;
        private final ObjectivePlane objectives;
        private final EnergyLevelPlane levels;
        private final EnergyPlanCoordinator coordinator;

        private Harness() {
            this(Map.of());
        }

        private Harness(Map<String, Object> planConfig) {
            this(planConfig, Map.of());
        }

        private Harness(Map<String, Object> planConfig, Map<String, Object> objectiveConfig) {
            this(planConfig, objectiveConfig, null);
        }

        private Harness(Map<String, Object> planConfig, Map<String, Object> objectiveConfig,
                @Nullable EnergyConfigStatus configStatus) {
            prices = new EnergyPriceRegistryImpl(Map.of("components", "spot"));
            forecasts = new ForecastRegistryImpl(Map.of());
            objectives = new ObjectivePlane(objectiveConfig);
            objectives.registerObjective(new org.openhab.core.energy.objective.internal.CostObjective());
            objectives.registerObjective(new org.openhab.core.energy.objective.internal.CarbonObjective());
            levels = new EnergyLevelPlane(Map.of());
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            coordinator = new EnergyPlanCoordinator(prices, forecasts, objectives, levels, configStatus, scheduler,
                    planConfig);
        }

        private EnergyPlanCoordinator coordinator() {
            return coordinator;
        }

        private EnergyLevelPlane levelPlane() {
            return levels;
        }

        private void withSpotPrices(double... values) {
            EnergyPriceSeries series = EnergyPriceSeries.of(MIDNIGHT, Duration.ofHours(1),
                    EnergyPriceUnits.currency("EUR"), EnergyPriceUnits.defaultEnergyUnit(), HELSINKI,
                    PriceDirection.CONSUMPTION, values);
            prices.addSource(priceSource("spot", series));
        }

        private void withThrowingSpotSource() {
            prices.addSource(new EnergyPriceSource() {
                @Override
                public String getSourceId() {
                    return "broken";
                }

                @Override
                public PriceRole getRole() {
                    return PriceRole.SPOT;
                }

                @Override
                public Optional<EnergyPriceSeries> getSeries() {
                    throw new IllegalStateException("this contribution is broken");
                }
            });
        }

        private void withSolarForecast(double... watts) {
            forecasts.addForecastSeriesSource(forecastSource(ForecastRole.SOLAR_PRODUCTION, Units.WATT,
                    SlotSeries.uniform(MIDNIGHT, Duration.ofHours(1), watts)));
        }

        private void withHeatingDemand(Duration width, double... kilowattHours) {
            forecasts.addForecastSeriesSource(forecastSource(ForecastRole.HEATING_DEMAND, Units.KILOWATT_HOUR,
                    SlotSeries.uniform(MIDNIGHT, width, kilowattHours)));
        }

        private void withCarbon(double... gramsPerKilowattHour) {
            CarbonSeries series = CarbonSeries.intensity("grid", MIDNIGHT,
                    SlotSeries.uniform(MIDNIGHT, Duration.ofHours(1), gramsPerKilowattHour));
            objectives.addCarbonSource(new CarbonSeriesSource() {
                @Override
                public String getSourceId() {
                    return "grid";
                }

                @Override
                public Optional<CarbonSeries> getSeries() {
                    return Optional.of(series);
                }
            });
        }

        private static EnergyPriceSource priceSource(String id, EnergyPriceSeries series) {
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
                public Optional<EnergyPriceSeries> getSeries() {
                    return Optional.of(series);
                }
            };
        }

        private static ForecastSeriesSource forecastSource(ForecastRole role, javax.measure.Unit<?> unit,
                SlotSeries values) {
            ForecastSeries series = ForecastSeries.of(role, role.id(), unit, Instant.now(), values);
            return new ForecastSeriesSource() {
                @Override
                public String getSourceId() {
                    return role.id();
                }

                @Override
                public ForecastRole getRole() {
                    return role;
                }

                @Override
                public Optional<ForecastSeries> getSeries() {
                    return Optional.of(series);
                }
            };
        }
    }
}
