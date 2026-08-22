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
package org.openhab.core.energy.objective.internal;

import java.util.ArrayList;
import java.util.Comparator;
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
import org.openhab.core.energy.internal.EnergyConfigStatus;
import org.openhab.core.energy.objective.AbsentDataPlanePolicy;
import org.openhab.core.energy.objective.CarbonSeries;
import org.openhab.core.energy.objective.CarbonSeriesSource;
import org.openhab.core.energy.objective.ObjectiveCondition;
import org.openhab.core.energy.objective.ObjectiveInput;
import org.openhab.core.energy.objective.ObjectiveInputs;
import org.openhab.core.energy.objective.ObjectiveRegistry;
import org.openhab.core.energy.objective.ObjectiveResolution;
import org.openhab.core.energy.objective.OptimizationObjective;
import org.openhab.core.energy.spi.SourceRanking;
import org.openhab.core.energy.window.SlotSeries;
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
 * Which objective is in force, what it ranks on, and what had to be said about getting there.
 * <p>
 * It is the objective-side twin of the level plane: a component that owns a configuration, aggregates whatever
 * contributors are installed, and answers questions with pure values. It reads no clock, touches no Item, queries no
 * persistence, publishes nothing and starts no thread. A ranking is a value; whoever wants one published publishes it
 * in a bundle that is allowed to.
 * <p>
 * Three jobs, and they are deliberately separable:
 * <ol>
 * <li><strong>Resolve the carbon data.</strong> One series, taken from the installed carbon sources - the site's
 * preferred source if it named one, otherwise the highest {@code service.ranking}, ties broken by source id so that
 * the answer never depends on start order. The price and forecast series are handed in by their own planes rather
 * than resolved here, because an objective must not become the place where every data plane is wired together.</li>
 * <li><strong>Resolve the objective.</strong> The configured one if it is registered and its data plane is there;
 * and if it is not, whichever of the three framed behaviours the site selected. That question is open in the corpus,
 * so all three are implemented and nothing here prefers one.</li>
 * <li><strong>Report.</strong> Every degradation reaches the site's configuration status, which is what a settings
 * page renders and the only publication surface a bundle that may not write Items has.</li>
 * </ol>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(service = { ObjectiveRegistry.class,
        ObjectivePlane.class }, configurationPid = ObjectivePlaneConfiguration.CONFIGURATION_PID, configurationPolicy = ConfigurationPolicy.OPTIONAL, property = Constants.SERVICE_PID
                + "=org.openhab.core.energy.objective")
@ConfigurableService(category = "system", label = "Energy Management Objective", description_uri = ObjectivePlaneConfiguration.CONFIG_URI)
public class ObjectivePlane implements ObjectiveRegistry {

    private final Logger logger = LoggerFactory.getLogger(ObjectivePlane.class);

    private final Map<String, OptimizationObjective> objectives = new ConcurrentHashMap<>();
    private final List<CarbonSeriesSource> carbonSources = new ArrayList<>();

    /**
     * Ids a second objective tried to take and was refused. Kept rather than reported once, because a refusal is a
     * standing condition of the installation and not an event: the objective that lost is still installed and still
     * not reachable.
     */
    private final Set<String> refusedDuplicates = ConcurrentHashMap.newKeySet();

    private volatile ObjectivePlaneConfiguration configuration = ObjectivePlaneConfiguration.defaults();
    private volatile @Nullable EnergyConfigStatus configStatus;

    /**
     * Creates the plane as OSGi activates it.
     *
     * @param properties the component configuration
     */
    @Activate
    public ObjectivePlane(Map<String, Object> properties) {
        applyConfiguration(properties);
    }

    /**
     * Re-reads the configuration.
     *
     * @param properties the new component configuration
     */
    @Modified
    public void modified(Map<String, Object> properties) {
        applyConfiguration(properties);
    }

    /**
     * Registers an objective, first registration winning.
     * <p>
     * A second registration under a taken id is refused whether or not it is the same object: an id that is already
     * in use is already in use, and making an exception for "the same instance again" would be a second rule for a
     * case nothing in the framework produces - a service binds once, and a script that wants to re-register withdraws
     * first.
     *
     * @param objective the objective
     * @return {@code true} if it was registered, {@code false} if the id was taken
     */
    @Override
    public boolean registerObjective(OptimizationObjective objective) {
        String id = objective.getId();
        OptimizationObjective incumbent = objectives.putIfAbsent(id, objective);
        if (incumbent != null) {
            logger.warn(
                    "An optimization objective with id '{}' exists already, contributed by {}; the registration "
                            + "by {} is refused and the existing objective keeps the id",
                    id, incumbent.getClass().getName(), objective.getClass().getName());
            refusedDuplicates.add(id);
            return false;
        }
        logger.debug("Optimization objective '{}' is available at ranking {}", id, objective.getServiceRanking());
        return true;
    }

    @Override
    public boolean unregisterObjective(String id) {
        refusedDuplicates.remove(id);
        return objectives.remove(id) != null;
    }

    @Override
    public List<OptimizationObjective> objectives() {
        List<OptimizationObjective> ordered = new ArrayList<>(objectives.values());
        ordered.sort(Comparator.comparingInt(OptimizationObjective::getServiceRanking).reversed()
                .thenComparing(OptimizationObjective::getId));
        return List.copyOf(ordered);
    }

    @Override
    public Optional<OptimizationObjective> objective(String id) {
        return Optional.ofNullable(objectives.get(id));
    }

    /**
     * Returns the ids a second objective tried to take and was refused.
     *
     * @return the refused ids
     */
    public Set<String> refusedDuplicateIds() {
        return Set.copyOf(refusedDuplicates);
    }

    /**
     * Returns the objectives a site may choose between.
     * <p>
     * Every registered one, unless the site selected the policy that hides an objective whose data plane is absent -
     * in which case the hiding happens here and nowhere else, so that a hidden objective is still resolvable if it
     * was selected before its source was uninstalled.
     *
     * @param inputs the series available this run
     * @return the objectives to offer, in an order that does not depend on registration order
     */
    public List<OptimizationObjective> offeredObjectives(ObjectiveInputs inputs) {
        if (configuration.absentDataPlane() != AbsentDataPlanePolicy.HIDE_UNAVAILABLE) {
            return objectives();
        }
        return objectives().stream().filter(objective -> hasDataPlane(objective, inputs)).toList();
    }

    /**
     * Returns the carbon series in force, from the source in force.
     *
     * @return the series, or empty when nothing publishes carbon data or the source in force has none yet
     */
    public Optional<CarbonSeries> carbonSeries() {
        return carbonSource().flatMap(CarbonSeriesSource::getSeries);
    }

    /**
     * Returns the carbon source in force: the site's preferred source if it named one that is installed, otherwise
     * the highest ranked, ties broken by source id.
     *
     * @return the source, or empty when nothing publishes carbon data
     */
    public Optional<CarbonSeriesSource> carbonSource() {
        List<CarbonSeriesSource> candidates;
        synchronized (carbonSources) {
            candidates = new ArrayList<>(carbonSources);
        }
        String preferred = configuration.preferredCarbonSource();
        if (!preferred.isEmpty()) {
            Optional<CarbonSeriesSource> named = candidates.stream()
                    .filter(source -> preferred.equals(source.getSourceId())).findFirst();
            if (named.isPresent()) {
                return named;
            }
        }
        return SourceRanking.best(candidates);
    }

    /**
     * Returns the inputs of this planning run with the carbon series this plane resolves filled in.
     *
     * @param base the inputs the price and forecast planes assembled
     * @return the inputs, with carbon added when a source publishes it
     */
    public ObjectiveInputs withResolvedCarbon(ObjectiveInputs base) {
        Optional<CarbonSeries> resolved = carbonSeries();
        return resolved.isPresent() ? base.withCarbon(resolved.get()) : base;
    }

    /**
     * Resolves which objective is in force and what it ranks, reporting every degradation on the way.
     *
     * @param inputs the series available this run
     * @return the resolution
     */
    public ObjectiveResolution resolve(ObjectiveInputs inputs) {
        String requested = configuration.objectiveId();
        Set<ObjectiveCondition> conditions = EnumSet.noneOf(ObjectiveCondition.class);
        if (!isConfigured()) {
            conditions.add(ObjectiveCondition.OBJECTIVE_UNCONFIGURED);
        }
        if (!refusedDuplicates.isEmpty()) {
            conditions.add(ObjectiveCondition.DUPLICATE_OBJECTIVE_REFUSED);
        }

        OptimizationObjective selected = objectives.get(requested);
        if (selected == null) {
            conditions.add(ObjectiveCondition.OBJECTIVE_UNKNOWN);
            return degrade(requested, inputs, conditions);
        }

        conditions.addAll(selected.conditionsFor(inputs));
        Optional<SlotSeries> ranking = rankSafely(selected, inputs);
        if (ranking.isPresent()) {
            return publish(new ObjectiveResolution(requested, Optional.of(selected), ranking, conditions));
        }
        conditions.add(ObjectiveCondition.DATA_PLANE_ABSENT);
        return degrade(requested, inputs, conditions);
    }

    /**
     * Returns the series the level bands are to be cut out of.
     * <p>
     * <strong>Open question, and a seam rather than an answer.</strong> Whether the four-level signal follows the
     * active objective or stays price-based is undecided in the corpus, and every production system behind the corpus
     * only ever ran price-based levels. The shipped value is therefore the consumption price - the behaviour that
     * already exists - and a site that wants carbon-shaped levels selects that instead. Nothing here prefers either.
     *
     * @param inputs the series available this run
     * @param resolution the objective resolution of the same run
     * @return the series to derive the level plan from, or empty when there is nothing to derive from
     */
    public Optional<SlotSeries> levelDerivationInput(ObjectiveInputs inputs, ObjectiveResolution resolution) {
        return configuration.levelsFollowObjective() ? resolution.ranking() : inputs.consumptionPriceSeries();
    }

    /**
     * Returns whether the site has selected an objective at all, as opposed to running on the incumbent.
     *
     * @return {@code true} if an objective is configured
     */
    public boolean isConfigured() {
        return !ObjectivePlaneConfiguration.DEFAULT_OBJECTIVE.equals(configuration.objectiveId());
    }

    /**
     * Returns the policy in force for an objective whose data plane is absent.
     *
     * @return the policy
     */
    public AbsentDataPlanePolicy absentDataPlanePolicy() {
        return configuration.absentDataPlane();
    }

    /**
     * Returns the inputs an objective needs and does not have, which is what a report names.
     *
     * @param objective the objective
     * @param inputs the series available this run
     * @return the missing inputs, empty when the objective can rank
     */
    public static Set<ObjectiveInput> missingInputs(OptimizationObjective objective, ObjectiveInputs inputs) {
        Set<ObjectiveInput> missing = EnumSet.noneOf(ObjectiveInput.class);
        for (ObjectiveInput input : objective.requiredSeries()) {
            if (!inputs.has(input)) {
                missing.add(input);
            }
        }
        return missing;
    }

    /**
     * Binds an objective contributed as an OSGi service - an add-on's, or one of core's own three.
     *
     * @param objective the objective
     */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    protected void addObjective(OptimizationObjective objective) {
        registerObjective(objective);
    }

    /**
     * Unbinds an objective.
     *
     * @param objective the objective
     */
    protected void removeObjective(OptimizationObjective objective) {
        objectives.remove(objective.getId(), objective);
    }

    /**
     * Binds a carbon source.
     *
     * @param source the source
     */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    public void addCarbonSource(CarbonSeriesSource source) {
        synchronized (carbonSources) {
            carbonSources.add(source);
        }
        logger.debug("Carbon series source '{}' is available at ranking {}", source.getSourceId(),
                source.getServiceRanking());
    }

    /**
     * Unbinds a carbon source.
     *
     * @param source the source
     */
    public void removeCarbonSource(CarbonSeriesSource source) {
        synchronized (carbonSources) {
            carbonSources.remove(source);
        }
    }

    /**
     * Binds the configuration status, which is where every degradation is reported.
     *
     * @param status the status provider
     */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    protected void setConfigStatus(EnergyConfigStatus status) {
        configStatus = status;
    }

    /**
     * Unbinds the configuration status.
     *
     * @param status the status provider
     */
    protected void unsetConfigStatus(EnergyConfigStatus status) {
        configStatus = null;
    }

    private ObjectiveResolution degrade(String requested, ObjectiveInputs inputs, Set<ObjectiveCondition> conditions) {
        if (configuration.absentDataPlane() == AbsentDataPlanePolicy.REFUSE_SELECTION) {
            conditions.add(ObjectiveCondition.SELECTION_REFUSED);
            return publish(new ObjectiveResolution(requested, Optional.empty(), Optional.empty(), conditions));
        }
        OptimizationObjective fallback = objectives.get(CostObjective.ID);
        if (fallback == null) {
            return publish(new ObjectiveResolution(requested, Optional.empty(), Optional.empty(), conditions));
        }
        if (!CostObjective.ID.equals(requested)) {
            conditions.add(ObjectiveCondition.DEGRADED_TO_COST);
        }
        Optional<SlotSeries> ranking = rankSafely(fallback, inputs);
        if (ranking.isEmpty()) {
            conditions.add(ObjectiveCondition.DATA_PLANE_ABSENT);
            return publish(new ObjectiveResolution(requested, Optional.empty(), Optional.empty(), conditions));
        }
        return publish(new ObjectiveResolution(requested, Optional.of(fallback), ranking, conditions));
    }

    private boolean hasDataPlane(OptimizationObjective objective, ObjectiveInputs inputs) {
        return objective.requiredSeries().stream().allMatch(inputs::has);
    }

    private Optional<SlotSeries> rankSafely(OptimizationObjective objective, ObjectiveInputs inputs) {
        try {
            return objective.rank(inputs);
        } catch (RuntimeException e) {
            logger.warn("Optimization objective '{}' failed to rank and is treated as having no answer: {}",
                    objective.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private ObjectiveResolution publish(ObjectiveResolution resolution) {
        Map<String, String> actionable = new LinkedHashMap<>();
        Map<String, String> notes = new LinkedHashMap<>();
        for (ObjectiveCondition condition : resolution.conditions()) {
            (isNote(condition) ? notes : actionable).put(condition.name(), describe(condition, resolution));
        }
        EnergyConfigStatus status = configStatus;
        if (status != null) {
            status.objectiveConditions(actionable);
            status.objectiveNotes(notes);
        }
        return resolution;
    }

    /**
     * Returns whether a condition is something a site can read rather than something it should fix.
     * <p>
     * Optimizing for cost because nothing else was selected is not a fault, and neither is a rule that is present but
     * inert because the quantity it turns on is not forecast anywhere. Presenting either as a warning would make a
     * correctly configured site look broken.
     *
     * @param condition the condition
     * @return {@code true} if it needs nothing done
     */
    private static boolean isNote(ObjectiveCondition condition) {
        return condition == ObjectiveCondition.OBJECTIVE_UNCONFIGURED
                || condition == ObjectiveCondition.EXPORT_SHARE_UNKNOWN
                || condition == ObjectiveCondition.CARBON_SERIES_NOT_INTENSITY;
    }

    private static String describe(ObjectiveCondition condition, ObjectiveResolution resolution) {
        return switch (condition) {
            case OBJECTIVE_UNCONFIGURED ->
                "no optimization objective is selected, so the site is optimizing for cost, which is what it did "
                        + "before the objective became selectable";
            case OBJECTIVE_UNKNOWN -> "no installed objective is called '" + resolution.requestedId() + "'";
            case DATA_PLANE_ABSENT -> "the '" + resolution.requestedId()
                    + "' objective has no series to rank on; install a source for its data or select another "
                    + "objective";
            case DEGRADED_TO_COST -> "the '" + resolution.requestedId()
                    + "' objective is selected but cannot rank, so the site is optimizing for cost instead";
            case SELECTION_REFUSED -> "the '" + resolution.requestedId()
                    + "' objective is selected but cannot rank, and this site refuses to optimize for anything else, "
                    + "so nothing is being planned";
            case EXPORT_SHARE_UNKNOWN ->
                "the carbon objective's export credit cannot be applied to a plan because nothing forecasts how much "
                        + "of a load's energy would otherwise have been exported; the ranking is the plain carbon "
                        + "series";
            case CARBON_SERIES_NOT_INTENSITY ->
                "the carbon series is not an emission intensity, so the export credit cannot be applied to it; the "
                        + "ranking is unaffected";
            case DUPLICATE_OBJECTIVE_REFUSED ->
                "a second optimization objective tried to register under an id that was taken and was refused";
        };
    }

    private void applyConfiguration(Map<String, Object> properties) {
        configuration = ObjectivePlaneConfiguration.fromProperties(properties, rejected -> logger.warn("{}", rejected));
        logger.debug(
                "Energy objective plane configured: objective '{}', absent data plane '{}', export credit '{}', "
                        + "level input '{}'",
                configuration.objectiveId(), configuration.absentDataPlane().id(), configuration.exportCarbonCreditId(),
                configuration.levelInput());
    }
}
