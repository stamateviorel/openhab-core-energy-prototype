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
package org.openhab.core.energy.forecast.internal;

import static java.util.stream.Collectors.joining;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.ConfigurableService;
import org.openhab.core.energy.forecast.ForecastPlaneCondition;
import org.openhab.core.energy.forecast.ForecastRegistry;
import org.openhab.core.energy.forecast.ForecastRole;
import org.openhab.core.energy.forecast.ForecastSeries;
import org.openhab.core.energy.forecast.ForecastSeriesSource;
import org.openhab.core.energy.internal.EnergyConfigStatus;
import org.openhab.core.energy.spi.SourceRanking;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregates every registered {@link ForecastSeriesSource} and answers one series per role.
 * <p>
 * <strong>Resolution is deterministic and configurable, in that order.</strong> A site's own preference for a role
 * wins; otherwise the highest {@code service.ranking} wins; a tie between equal rankings is broken by the source id,
 * so two restarts that register the same sources in opposite orders answer identically. A named source that is not
 * installed does not silently become "no forecast": the plane falls through to the ranking and reports
 * {@link ForecastPlaneCondition#PREFERRED_SOURCE_ABSENT}, because an uninstalled add-on and a mistyped id look the
 * same from here and both need saying out loud.
 * <p>
 * <strong>Degradation is answered, not thrown.</strong> _Forecast source fails_ requires a site to keep planning when
 * a provider goes dark, and `extension-surface` _Graceful degradation on contributor loss_ requires the degraded
 * source to be reported. Both are the same mechanism here: sources are ranked, the best one that actually has a
 * series answers, and what had to be settled for is readable through {@link #getConditions(ForecastRole)}. A stored
 * baseline registered as a low-ranked source is therefore not a special case in the code - it is simply the source
 * that answers when nothing better does, which is exactly the shape the requirement describes.
 * <p>
 * <strong>This component reads and reports; it writes nothing.</strong> No Item, no persistence, no event: the
 * conditions are pulled by whoever renders them, in the same way the level plane's conditions are.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(immediate = true, service = ForecastRegistry.class, configurationPid = ForecastRegistryImpl.CONFIGURATION_PID, configurationPolicy = ConfigurationPolicy.OPTIONAL, property = Constants.SERVICE_PID
        + "=" + ForecastRegistryImpl.CONFIGURATION_PID)
@ConfigurableService(category = "system", label = "Energy Management Forecasts", description_uri = ForecastRegistryImpl.CONFIG_URI)
public class ForecastRegistryImpl implements ForecastRegistry {

    /**
     * The configuration pid of the forecast plane.
     */
    public static final String CONFIGURATION_PID = "org.openhab.core.energy.forecast";

    /**
     * The configuration description uri of the forecast plane.
     */
    public static final String CONFIG_URI = "system:energy-forecast";

    private final Logger logger = LoggerFactory.getLogger(ForecastRegistryImpl.class);

    private final Map<String, ForecastSeriesSource> sources = new ConcurrentHashMap<>();
    private final Map<ForecastRole, String> answering = new ConcurrentHashMap<>();
    private final Map<ForecastRole, Set<ForecastPlaneCondition>> conditions = new ConcurrentHashMap<>();
    private final Clock clock;

    private volatile @Nullable EnergyConfigStatus configStatus;

    private volatile ForecastConfiguration configuration = ForecastConfiguration.defaults();

    /**
     * Creates the registry as the framework does.
     *
     * @param properties the component properties
     */
    @Activate
    public ForecastRegistryImpl(Map<String, Object> properties) {
        this(Clock.systemUTC(), properties);
    }

    /**
     * Creates the registry with a clock of the caller's choosing, which is what makes staleness testable.
     *
     * @param clock the clock the plane measures a run's age against
     * @param properties the component properties
     */
    public ForecastRegistryImpl(Clock clock, Map<String, Object> properties) {
        this.clock = clock;
        modified(properties);
    }

    /**
     * Re-reads the configuration.
     *
     * @param properties the component properties
     */
    @Modified
    public void modified(Map<String, Object> properties) {
        configuration = ForecastConfiguration.fromProperties(properties,
                rejected -> logger.warn("Energy forecast configuration: {}", rejected));
        conditions.clear();
    }

    /**
     * Binds the report a user reads, so that what the plane settled for is visible on the settings page rather than
     * only in a log line. Optional on purpose: the plane answers the same either way.
     *
     * @param status the configuration-status report
     */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    public void setEnergyConfigStatus(EnergyConfigStatus status) {
        configStatus = status;
        report();
    }

    /**
     * Unbinds the report.
     *
     * @param status the configuration-status report
     */
    public void unsetEnergyConfigStatus(EnergyConfigStatus status) {
        configStatus = null;
    }

    /**
     * Adds a source.
     *
     * @param source the source that appeared
     */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    public void addForecastSeriesSource(ForecastSeriesSource source) {
        @Nullable
        ForecastSeriesSource previous = sources.put(source.getSourceId(), source);
        if (previous != null && !previous.equals(source)) {
            logger.warn("Two forecast sources call themselves '{}'; the one registered later answers for role {}",
                    source.getSourceId(), source.getRole().id());
        }
        logger.debug("Forecast source '{}' registered for role {}", source.getSourceId(), source.getRole().id());
    }

    /**
     * Removes a source.
     *
     * @param source the source that went away
     */
    public void removeForecastSeriesSource(ForecastSeriesSource source) {
        sources.remove(source.getSourceId(), source);
        logger.debug("Forecast source '{}' for role {} went away", source.getSourceId(), source.getRole().id());
    }

    @Override
    public Optional<ForecastSeries> getSeries(ForecastRole role) {
        Set<ForecastPlaneCondition> reported = EnumSet.noneOf(ForecastPlaneCondition.class);
        List<ForecastSeriesSource> candidates = ranked(role, reported);
        Optional<ForecastSeries> answer = Optional.empty();
        for (ForecastSeriesSource candidate : candidates) {
            Optional<ForecastSeries> series = candidate.getSeries();
            if (series.isEmpty()) {
                continue;
            }
            ForecastSeries found = series.get();
            if (!role.accepts(found.unit())) {
                reported.add(ForecastPlaneCondition.UNIT_MISMATCH);
                logger.warn("Forecast source '{}' published {} under role {}, which does not carry that quantity",
                        candidate.getSourceId(), found.unit(), role.id());
                continue;
            }
            answer = series;
            answering.put(role, candidate.getSourceId());
            break;
        }
        if (answer.isEmpty()) {
            reported.add(ForecastPlaneCondition.SOURCE_UNAVAILABLE);
            answering.remove(role);
        } else {
            reportAge(answer.get(), reported);
        }
        Set<ForecastPlaneCondition> previous = conditions.put(role, Set.copyOf(reported));
        if (!Set.copyOf(reported).equals(previous)) {
            report();
        }
        return answer;
    }

    @Override
    public List<String> getSourceIds(ForecastRole role) {
        return ranked(role, EnumSet.noneOf(ForecastPlaneCondition.class)).stream()
                .map(ForecastSeriesSource::getSourceId).toList();
    }

    @Override
    public Optional<String> getAnsweringSourceId(ForecastRole role) {
        return Optional.ofNullable(answering.get(role));
    }

    @Override
    public Set<ForecastPlaneCondition> getConditions(ForecastRole role) {
        return conditions.getOrDefault(role, Set.of());
    }

    @Override
    public Set<ForecastPlaneCondition> getConditions() {
        Set<ForecastPlaneCondition> all = EnumSet.noneOf(ForecastPlaneCondition.class);
        conditions.values().forEach(all::addAll);
        return Set.copyOf(all);
    }

    /**
     * Hands the current conditions to the report a user reads, one line per role.
     * <p>
     * Only what a role actually has to say is reported, and only when the set changes, so a site with nothing to say
     * produces nothing and a plane answering every few seconds does not flood the surface.
     */
    private void report() {
        @Nullable
        EnergyConfigStatus status = configStatus;
        if (status == null) {
            return;
        }
        Map<String, String> reported = new LinkedHashMap<>();
        conditions.forEach((role, roleConditions) -> {
            if (!roleConditions.isEmpty()) {
                reported.put(role.id(), roleConditions.stream().map(Enum::name).sorted().collect(joining(", ")));
            }
        });
        status.forecastConditions(reported);
    }

    /**
     * Returns every source that could answer for a role, best first.
     *
     * @param role the role
     * @param reported receives anything the resolution itself has to report
     * @return the candidate sources, in resolution order
     */
    private List<ForecastSeriesSource> ranked(ForecastRole role, Set<ForecastPlaneCondition> reported) {
        List<ForecastSeriesSource> candidates = new ArrayList<>();
        for (ForecastSeriesSource source : sources.values()) {
            if (source.getRole() == role) {
                candidates.add(source);
            }
        }
        candidates.sort(SourceRanking.byPrecedence());
        @Nullable
        String preferred = configuration.preferredSources().get(role);
        if (preferred == null) {
            return List.copyOf(candidates);
        }
        Optional<ForecastSeriesSource> named = candidates.stream()
                .filter(source -> preferred.equals(source.getSourceId())).findFirst();
        if (named.isEmpty()) {
            reported.add(ForecastPlaneCondition.PREFERRED_SOURCE_ABSENT);
            return List.copyOf(candidates);
        }
        List<ForecastSeriesSource> ordered = new ArrayList<>();
        ordered.add(named.get());
        candidates.stream().filter(source -> !preferred.equals(source.getSourceId())).forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private void reportAge(ForecastSeries series, Set<ForecastPlaneCondition> reported) {
        @Nullable
        Duration staleAfter = configuration.staleAfter();
        if (staleAfter == null) {
            reported.add(ForecastPlaneCondition.STALENESS_UNCONFIGURED);
            return;
        }
        if (series.isStale(clock.instant(), staleAfter)) {
            reported.add(ForecastPlaneCondition.SOURCE_STALE);
            logger.debug("Forecast '{}' for role {} was generated at {}, which is older than the declared {}",
                    series.sourceId(), series.role().id(), series.generatedAt(), staleAfter);
        }
    }

    /**
     * Returns the roles that currently have a source at all, which is what a diagnostic view lists.
     *
     * @return the roles, in enumeration order
     */
    Map<ForecastRole, Integer> registeredSourceCounts() {
        Map<ForecastRole, Integer> counts = new EnumMap<>(ForecastRole.class);
        for (ForecastSeriesSource source : sources.values()) {
            counts.merge(source.getRole(), 1, Integer::sum);
        }
        return counts;
    }
}
