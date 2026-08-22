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

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.config.core.ConfigurableService;
import org.openhab.core.energy.forecast.ForecastRegistry;
import org.openhab.core.energy.forecast.ForecastRole;
import org.openhab.core.energy.forecast.ForecastSeries;
import org.openhab.core.energy.level.PlanDerivationCondition;
import org.openhab.core.energy.level.PlannedLevelPublisher;
import org.openhab.core.energy.level.PlannedLevelSchedule;
import org.openhab.core.energy.objective.ObjectiveInputs;
import org.openhab.core.energy.objective.ObjectiveResolution;
import org.openhab.core.energy.objective.internal.ObjectivePlane;
import org.openhab.core.energy.price.EnergyPriceRegistry;
import org.openhab.core.energy.price.EnergyPriceSeries;
import org.openhab.core.energy.price.PriceCompositionException;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place the data planes become a level plan.
 *
 * <h2>What it is for</h2>
 * Wave 1 built {@code EnergyLevelPlane.derivePlan(SlotSeries)} and nothing ever called it: a site could configure a
 * derivation, install a price source and still get an empty plan, because no component joined the two. Wave 2 built
 * three data planes that each answer questions correctly and none of which talks to another. This component is the
 * join, and it is deliberately the <em>only</em> one - a second thing that also derives plans would mean two plans
 * racing to be installed.
 *
 * <h2>The order, and why it is this order</h2>
 * <ol>
 * <li>ask the price plane for the effective consumption price and, if there is one, the feed-in price;</li>
 * <li>hand those to the objective plane as {@link ObjectiveInputs}, adding a forecast surplus when the forecast
 * plane can supply one and the carbon series the objective plane resolves itself;</li>
 * <li>ask the objective plane to resolve the configured objective against what actually arrived;</li>
 * <li>ask it which series the level bands should be cut out of - the price, or the objective's own ranking, which is
 * the open question objectives design §1 leaves open and which is configuration rather than a decision taken
 * here;</li>
 * <li>hand that series to the level plane.</li>
 * </ol>
 * Each plane is asked only what it owns. The coordinator resolves nothing itself, which is why it can be read as a
 * sequence rather than as a policy.
 *
 * <h2>It writes nothing, and could not</h2>
 * This is the component wave 2's invariant was most likely to break on, because joining planes is exactly where a
 * naive implementation would publish the resulting plan to an Item. It does not: it hands the derived schedule to
 * {@link PlannedLevelPublisher}, an in-process interface, and whoever wants that plan published publishes it from a
 * bundle allowed to write. The component holds no {@code EventPublisher}, names no Item type and touches no
 * persistence - the three structural tests over this bundle's sources cover it like any other file here, and it was
 * written to pass them rather than having them relaxed for it.
 *
 * <h2>Threads</h2>
 * No thread is created. The optional periodic refresh runs on openHAB's shared scheduler under the pool the engine
 * already uses, with {@code scheduleWithFixedDelay} so that a slow derivation delays the next rather than
 * overlapping it.
 *
 * <h2>Configuration ({@value #CONFIGURATION_PID})</h2>
 * <dl>
 * <dt>{@value #CONFIG_REFRESH_INTERVAL}</dt>
 * <dd>How often, in seconds, to re-derive the plan. <strong>There is no default</strong>, and an unset value is
 * reported as {@link PlanDerivationCondition#REFRESH_INTERVAL_UNCONFIGURED}: the plan is then re-derived only when
 * the configuration changes, because the source SPI is pull-only and a source that has fetched new prices has no way
 * to say so. This is the one number in wave 2 whose absence has a visible consequence, so its absence is reported
 * rather than defaulted.</dd>
 * <dt>{@value #CONFIG_SURPLUS_FORECAST}</dt>
 * <dd>Whether to assemble a forecast surplus series for the objectives that need one. Off by default, because the
 * quantity is not defined anywhere in the corpus and what this builds is an approximation that reports itself as
 * one.</dd>
 * </dl>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(immediate = true, service = EnergyPlanCoordinator.class, configurationPid = EnergyPlanCoordinator.CONFIGURATION_PID, configurationPolicy = ConfigurationPolicy.OPTIONAL, property = Constants.SERVICE_PID
        + "=" + EnergyPlanCoordinator.CONFIGURATION_PID)
@ConfigurableService(category = "system", label = "Energy Management Plan", description_uri = EnergyPlanCoordinator.CONFIG_URI)
public class EnergyPlanCoordinator {

    /**
     * The configuration PID under which the coordinator reads its parameters.
     */
    public static final String CONFIGURATION_PID = "org.openhab.core.energy.plan";

    /**
     * The URI of the configuration description that renders these parameters in the UI.
     */
    public static final String CONFIG_URI = "system:energy-plan";

    /**
     * The {@code refreshInterval} configuration key, in seconds.
     */
    public static final String CONFIG_REFRESH_INTERVAL = "refreshInterval";

    /**
     * The {@code surplusForecast} configuration key.
     */
    public static final String CONFIG_SURPLUS_FORECAST = "surplusForecast";

    /**
     * Every configuration key this component reads, which the configuration description is held to.
     */
    public static final Set<String> CONFIG_KEYS = Set.of(CONFIG_REFRESH_INTERVAL, CONFIG_SURPLUS_FORECAST);

    private final Logger logger = LoggerFactory.getLogger(EnergyPlanCoordinator.class);

    private final EnergyPriceRegistry priceRegistry;
    private final ForecastRegistry forecastRegistry;
    private final ObjectivePlane objectivePlane;
    private final PlannedLevelPublisher levelPlane;
    private final ScheduledExecutorService scheduler;
    private final @Nullable EnergyConfigStatus configStatus;

    private volatile @Nullable Duration refreshInterval;
    private volatile boolean surplusForecastEnabled;
    private volatile Set<PlanDerivationCondition> conditions = Set.of();
    private volatile @Nullable ScheduledFuture<?> refreshHandle;

    /**
     * Creates the coordinator as OSGi activates it.
     *
     * @param priceRegistry the price plane
     * @param forecastRegistry the forecast plane
     * @param objectivePlane the objective plane
     * @param levelPlane the level plane the derived schedule is handed to
     * @param configStatus the surface degradations are reported on
     * @param properties the component configuration
     */
    @Activate
    public EnergyPlanCoordinator(@Reference EnergyPriceRegistry priceRegistry,
            @Reference ForecastRegistry forecastRegistry, @Reference ObjectivePlane objectivePlane,
            @Reference PlannedLevelPublisher levelPlane, @Reference EnergyConfigStatus configStatus,
            Map<String, Object> properties) {
        this(priceRegistry, forecastRegistry, objectivePlane, levelPlane, configStatus,
                ThreadPoolManager.getScheduledPool(EnergyEngine.THREAD_POOL_NAME), properties);
    }

    /**
     * Creates the coordinator with an explicit scheduler, which is what a test drives it with.
     *
     * @param priceRegistry the price plane
     * @param forecastRegistry the forecast plane
     * @param objectivePlane the objective plane
     * @param levelPlane the level plane the derived schedule is handed to
     * @param configStatus the surface degradations are reported on, or {@code null} to report nowhere
     * @param scheduler the scheduler the periodic refresh runs on
     * @param properties the component configuration
     */
    public EnergyPlanCoordinator(EnergyPriceRegistry priceRegistry, ForecastRegistry forecastRegistry,
            ObjectivePlane objectivePlane, PlannedLevelPublisher levelPlane, @Nullable EnergyConfigStatus configStatus,
            ScheduledExecutorService scheduler, Map<String, Object> properties) {
        this.priceRegistry = priceRegistry;
        this.forecastRegistry = forecastRegistry;
        this.objectivePlane = objectivePlane;
        this.levelPlane = levelPlane;
        this.configStatus = configStatus;
        this.scheduler = scheduler;
        applyConfiguration(properties);
    }

    /**
     * Re-reads the configuration and re-derives, which is {@code energy-levels} <em>Re-derivation when the
     * configuration changes</em> in one line.
     *
     * @param properties the new component configuration
     */
    @Modified
    public void modified(Map<String, Object> properties) {
        applyConfiguration(properties);
    }

    /**
     * Stops the periodic refresh when OSGi deactivates the component.
     */
    @Deactivate
    public void deactivate() {
        cancelRefresh();
        logger.debug("Energy plan coordinator deactivated");
    }

    /**
     * Returns what the last derivation had to say.
     *
     * @return the conditions of the most recent run
     */
    public Set<PlanDerivationCondition> getConditions() {
        return conditions;
    }

    /**
     * Derives the level plan from whatever the data planes currently offer and installs it.
     * <p>
     * Public because it is the seam a test drives and the operation a future "re-plan now" action would call. It
     * never throws: a data plane that cannot answer produces a reported condition and leaves the previous plan
     * standing, because an exception escaping here would take the scheduled refresh down with it.
     *
     * @return the plan now in force, or empty when there was nothing to derive one from
     */
    public Optional<PlannedLevelSchedule> derive() {
        Set<PlanDerivationCondition> found = EnumSet.noneOf(PlanDerivationCondition.class);
        if (refreshInterval == null) {
            found.add(PlanDerivationCondition.REFRESH_INTERVAL_UNCONFIGURED);
        }
        try {
            Optional<PlannedLevelSchedule> derived = deriveWith(found);
            conditions = Set.copyOf(found);
            report();
            return derived;
        } catch (RuntimeException e) {
            // a contributed objective or source is arbitrary code; it must not be able to stop the refresh
            logger.warn("Deriving the energy level plan failed and the previous plan stands", e);
            conditions = Set.copyOf(found);
            report();
            return Optional.empty();
        }
    }

    private Optional<PlannedLevelSchedule> deriveWith(Set<PlanDerivationCondition> found) {
        Optional<EnergyPriceSeries> consumption = effectiveConsumptionPrice(found);
        if (consumption.isEmpty()) {
            found.add(PlanDerivationCondition.NO_SERIES_TO_DERIVE_FROM);
            return Optional.empty();
        }

        ObjectiveInputs inputs = ObjectiveInputs.fromPrices(consumption.get(),
                priceRegistry.feedInPrice().orElse(null));
        if (surplusForecastEnabled) {
            Optional<SlotSeries> surplus = surplusForecast(found);
            if (surplus.isPresent()) {
                inputs = inputs.withSurplusForecast(surplus.get());
            }
        }
        inputs = objectivePlane.withResolvedCarbon(inputs);

        ObjectiveResolution resolution = objectivePlane.resolve(inputs);
        if (!resolution.isAsRequested()) {
            found.add(PlanDerivationCondition.OBJECTIVE_DEGRADED);
        }

        Optional<SlotSeries> series = objectivePlane.levelDerivationInput(inputs, resolution);
        if (series.isEmpty()) {
            found.add(PlanDerivationCondition.NO_SERIES_TO_DERIVE_FROM);
            return Optional.empty();
        }

        PlannedLevelSchedule schedule = levelPlane.derivePlan(series.get());
        logger.debug("Derived a level plan of {} slots from the {} objective", schedule.size(),
                resolution.effectiveId().orElse("unresolved"));
        return Optional.of(schedule);
    }

    private Optional<EnergyPriceSeries> effectiveConsumptionPrice(Set<PlanDerivationCondition> found) {
        try {
            return Optional.of(priceRegistry.effectiveConsumptionPrice());
        } catch (PriceCompositionException e) {
            // the price plane reports which of its own conditions caused this; here it is only the plan that suffered
            found.add(PlanDerivationCondition.PRICE_COMPOSITION_FAILED);
            logger.debug("No effective consumption price, so no level plan was derived: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Assembles the forecast surplus the self-consumption and carbon objectives ask for.
     * <p>
     * <strong>This quantity is not defined anywhere in the corpus, and this is an approximation that says so.</strong>
     * Requirement O1 places a deferrable load "into the surplus" - a statement about future slots - while surplus is
     * defined only as an instantaneous figure from the cycle snapshot. There is no total-demand forecast role, so the
     * closest available reading is solar production netted against the one demand role that exists. When no demand
     * forecast is installed the series is production alone, which is an upper bound rather than a surplus, and that
     * is reported. Nothing is resampled: if the two series disagree on geometry the netting is skipped and reported,
     * because inventing an alignment rule here would be a second answer to a question the price plane already faces.
     *
     * @param found the conditions of this run, added to
     * @return the surplus series in watts, or empty when nothing forecasts production
     */
    private Optional<SlotSeries> surplusForecast(Set<PlanDerivationCondition> found) {
        Optional<ForecastSeries> solar = forecastRegistry.getSeries(ForecastRole.SOLAR_PRODUCTION);
        if (solar.isEmpty()) {
            return Optional.empty();
        }
        ForecastSeries production = solar.get();
        Optional<ForecastSeries> demand = forecastRegistry.getSeries(ForecastRole.HEATING_DEMAND);
        if (demand.isEmpty()) {
            found.add(PlanDerivationCondition.SURPLUS_FORECAST_IS_PRODUCTION_ONLY);
            return Optional.of(wattsOf(production));
        }
        if (!sameGeometry(production.values(), demand.get().values())) {
            found.add(PlanDerivationCondition.SURPLUS_FORECAST_UNALIGNED);
            found.add(PlanDerivationCondition.SURPLUS_FORECAST_IS_PRODUCTION_ONLY);
            return Optional.of(wattsOf(production));
        }
        SlotSeries producedWatts = wattsOf(production);
        SlotSeries demandWatts = wattsOf(demand.get());
        List<Slot> netted = new ArrayList<>(producedWatts.size());
        for (int index = 0; index < producedWatts.size(); index++) {
            Slot slot = producedWatts.slotAt(index);
            double surplus = Math.max(0, slot.value() - demandWatts.slotAt(index).value());
            netted.add(new Slot(slot.start(), slot.end(), surplus));
        }
        return Optional.of(new SlotSeries(netted, producedWatts.sense()));
    }

    /**
     * Converts a forecast to average watts per slot, whatever unit it arrived in.
     * <p>
     * The surplus seam is declared in watts, and the two roles that feed it are not in the same unit - production is
     * a power and demand is energy per period - so both are taken through the series' own energy conversion and back
     * out over the slot's width. That keeps the unit arithmetic in one place instead of at each call site.
     *
     * @param series the forecast
     * @return the same slots carrying average watts
     */
    private static SlotSeries wattsOf(ForecastSeries series) {
        SlotSeries values = series.values();
        List<Slot> watts = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            Slot slot = values.slotAt(index);
            double hours = Duration.between(slot.start(), slot.end()).toMillis() / 3_600_000d;
            double kilowattHours = series.energyKilowattHoursAt(index);
            watts.add(new Slot(slot.start(), slot.end(), hours == 0 ? 0 : kilowattHours * 1000 / hours));
        }
        return new SlotSeries(watts, values.sense());
    }

    private static boolean sameGeometry(SlotSeries first, SlotSeries second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            Slot left = first.slotAt(index);
            Slot right = second.slotAt(index);
            if (!left.start().equals(right.start()) || !left.end().equals(right.end())) {
                return false;
            }
        }
        return true;
    }

    private void report() {
        EnergyConfigStatus status = configStatus;
        if (status == null) {
            return;
        }
        Map<String, String> reported = new LinkedHashMap<>();
        for (PlanDerivationCondition condition : conditions) {
            reported.put(condition.name(), describe(condition));
        }
        status.planConditions(reported);
    }

    /**
     * Says in a sentence what a condition means for the site, which is the half of a status message a person can act
     * on.
     * <p>
     * The constant's own name is already the message's first argument, so repeating it as the second said nothing
     * twice. The objective plane has always described its conditions this way; this is the same treatment, and the
     * two are deliberately worded for the same reader - somebody on a settings page asking why the levels are not
     * what they expected.
     *
     * @param condition the condition to describe
     * @return the description
     */
    private static String describe(PlanDerivationCondition condition) {
        return switch (condition) {
            case NO_SERIES_TO_DERIVE_FROM ->
                "no data plane could supply a series to cut the level bands out of, so the previous plan - possibly "
                        + "none at all - still stands";
            case PRICE_COMPOSITION_FAILED ->
                "the price components installed here do not compose into an effective consumption price; the price "
                        + "plane's own condition says which of them disagree";
            case OBJECTIVE_DEGRADED ->
                "the plan was derived, but not against the objective this site selected; the objective plane reports "
                        + "why it could not be used";
            case SURPLUS_FORECAST_IS_PRODUCTION_ONLY ->
                "no demand forecast is installed, so the surplus series handed to the objectives is solar production "
                        + "alone - an upper bound rather than a surplus";
            case SURPLUS_FORECAST_UNALIGNED ->
                "the production and demand forecasts do not share slot boundaries, so they were not netted; nothing "
                        + "is resampled here";
            case REFRESH_INTERVAL_UNCONFIGURED ->
                "no refresh interval is set, so new prices published by an already-installed source are not picked "
                        + "up until the configuration changes";
        };
    }

    private void applyConfiguration(Map<String, Object> properties) {
        refreshInterval = readInterval(properties.get(CONFIG_REFRESH_INTERVAL));
        surplusForecastEnabled = Boolean.parseBoolean(String.valueOf(properties.get(CONFIG_SURPLUS_FORECAST)));
        scheduleRefresh();
        derive();
    }

    private @Nullable Duration readInterval(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(String.valueOf(value).trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            logger.warn("'{}' is not a whole number of seconds, so the plan is re-derived on configuration change only",
                    value);
            return null;
        }
    }

    private void scheduleRefresh() {
        cancelRefresh();
        Duration interval = refreshInterval;
        if (interval == null) {
            return;
        }
        long millis = interval.toMillis();
        refreshHandle = scheduler.scheduleWithFixedDelay(this::derive, millis, millis, TimeUnit.MILLISECONDS);
        logger.debug("The energy level plan is re-derived every {}", interval);
    }

    private void cancelRefresh() {
        ScheduledFuture<?> handle = refreshHandle;
        if (handle != null) {
            handle.cancel(false);
            refreshHandle = null;
        }
    }
}
