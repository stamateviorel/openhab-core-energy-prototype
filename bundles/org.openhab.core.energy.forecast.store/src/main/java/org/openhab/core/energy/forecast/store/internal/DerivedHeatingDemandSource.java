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

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.ConfigParser;
import org.openhab.core.config.core.ConfigurableService;
import org.openhab.core.energy.forecast.DerivedDemand;
import org.openhab.core.energy.forecast.ForecastPlaneCondition;
import org.openhab.core.energy.forecast.ForecastRegistry;
import org.openhab.core.energy.forecast.ForecastRole;
import org.openhab.core.energy.forecast.ForecastSeries;
import org.openhab.core.energy.forecast.ForecastSeriesSource;
import org.openhab.core.energy.forecast.ForecastTimeSeries;
import org.openhab.core.energy.forecast.HeatingDemandDerivation;
import org.openhab.core.energy.forecast.HeatingDemandParameters;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.types.TimeSeries;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The derived heating demand, as a source of its own and - when a site asks for it - as a series published onto an
 * Item.
 * <p>
 * <strong>The derivation itself is not here.</strong> It is
 * {@link HeatingDemandDerivation}, a pure function in the framework bundle, where it can be tested against a
 * temperature series and a solar series with no OSGi, no Item and no persistence anywhere near it. What this component
 * adds is the three things that need a running framework: reading its inputs through the
 * {@link ForecastRegistry}, answering as a {@link ForecastSeriesSource} so that a consumer reads a derived demand
 * exactly as it reads a fetched one, and the optional publication.
 * <p>
 * <strong>THIS CLASS CAN WRITE AN ITEM, AND ONLY ONE.</strong> When - and only when - a site names an Item in
 * {@code demandItem}, the derived series is published to it as a time series, which is how openHAB carries a forecast
 * since 4.1 and what _Derived-demand forecasts_ means by "published as first-class series". The bound:
 * <ul>
 * <li>one Item, the one the site named, and nothing is published at all until it does;</li>
 * <li>a <strong>time series</strong>, never a command and never a state update, so nothing it does moves a device or
 * changes what the Item currently reads;</li>
 * <li>{@link TimeSeries.Policy#ADD}, deliberately: {@code REPLACE} would delete every stored entry between this
 * series' own first and last timestamps, including the baseline entries in any gap it happens to have - which is the
 * very series _Layered prediction series_ exists to protect.</li>
 * </ul>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(immediate = true, service = ForecastSeriesSource.class, configurationPid = DerivedHeatingDemandSource.CONFIGURATION_PID, configurationPolicy = ConfigurationPolicy.OPTIONAL, property = Constants.SERVICE_PID
        + "=" + DerivedHeatingDemandSource.CONFIGURATION_PID)
@ConfigurableService(category = "system", label = "Energy Management Derived Demand", description_uri = DerivedHeatingDemandSource.CONFIG_URI)
public class DerivedHeatingDemandSource implements ForecastSeriesSource {

    /**
     * The configuration pid of the derived-demand component.
     */
    public static final String CONFIGURATION_PID = "org.openhab.core.energy.demand";

    /**
     * The configuration description uri of the derived-demand component.
     */
    public static final String CONFIG_URI = "system:energy-demand";

    /**
     * The parameter naming the Item the derived series is published to.
     */
    public static final String CONFIG_DEMAND_ITEM = "demandItem";

    /**
     * The source id the derived series carries.
     */
    public static final String SOURCE_ID = "derived-heating-demand";

    private static final String EVENT_SOURCE = DerivedHeatingDemandSource.class.getSimpleName();

    private final Logger logger = LoggerFactory.getLogger(DerivedHeatingDemandSource.class);

    private final ForecastRegistry registry;
    private final EventPublisher eventPublisher;

    private volatile HeatingDemandParameters parameters = HeatingDemandParameters.unconfigured();
    private volatile @Nullable String demandItem;
    private volatile Set<ForecastPlaneCondition> conditions = Set.of();

    /**
     * Creates the component.
     *
     * @param registry the plane the temperature and solar series are read from
     * @param eventPublisher the bus the derived series is published on, where a site asked for that
     * @param properties the component properties
     */
    @Activate
    public DerivedHeatingDemandSource(final @Reference ForecastRegistry registry,
            final @Reference EventPublisher eventPublisher, Map<String, Object> properties) {
        this.registry = registry;
        this.eventPublisher = eventPublisher;
        modified(properties);
    }

    /**
     * Re-reads the configuration and republishes, because a changed building constant changes every future entry.
     *
     * @param properties the component properties
     */
    @Modified
    public void modified(Map<String, Object> properties) {
        parameters = HeatingDemandParameters.fromProperties(properties,
                rejected -> logger.warn("Energy derived-demand configuration: {}", rejected));
        String item = ConfigParser.valueAsOrElse(properties.get(CONFIG_DEMAND_ITEM), String.class, "").trim();
        demandItem = item.isEmpty() ? null : item;
        refresh();
    }

    @Override
    public String getSourceId() {
        return SOURCE_ID;
    }

    @Override
    public ForecastRole getRole() {
        return ForecastRole.HEATING_DEMAND;
    }

    @Override
    public Optional<ForecastSeries> getSeries() {
        return derive().series();
    }

    @Override
    public int getServiceRanking() {
        return ItemForecastSource.CORE_DEFAULT_RANKING;
    }

    /**
     * Returns what the last derivation had to report, which is where an unconfigured building becomes visible.
     *
     * @return the conditions of the last derivation
     */
    public Set<ForecastPlaneCondition> getConditions() {
        return conditions;
    }

    /**
     * Derives the series again and publishes it where the site named an Item for it.
     *
     * @return what was derived
     */
    public DerivedDemand refresh() {
        DerivedDemand derived = derive();
        String item = demandItem;
        if (item != null) {
            derived.series().ifPresent(series -> publish(item, series));
        }
        return derived;
    }

    private DerivedDemand derive() {
        Optional<ForecastSeries> temperature = registry.getSeries(ForecastRole.TEMPERATURE);
        if (temperature.isEmpty()) {
            conditions = Set.of(ForecastPlaneCondition.SOURCE_UNAVAILABLE);
            return DerivedDemand.none(conditions);
        }
        @Nullable
        ForecastSeries solar = registry.getSeries(ForecastRole.SOLAR_PRODUCTION).orElse(null);
        DerivedDemand derived = HeatingDemandDerivation.derive(temperature.get(), solar, parameters, SOURCE_ID);
        conditions = derived.conditions();
        return derived;
    }

    private void publish(String itemName, ForecastSeries series) {
        TimeSeries timeSeries = ForecastTimeSeries.toTimeSeries(series, TimeSeries.Policy.ADD);
        eventPublisher.post(ItemEventFactory.createTimeSeriesEvent(itemName, timeSeries, EVENT_SOURCE));
        logger.debug("Published {} derived heating-demand entries to '{}'", timeSeries.size(), itemName);
    }
}
