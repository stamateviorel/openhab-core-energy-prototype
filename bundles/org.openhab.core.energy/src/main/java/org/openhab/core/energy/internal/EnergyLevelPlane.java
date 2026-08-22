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

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.ConfigurableService;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.level.CurrentLevelFunction;
import org.openhab.core.energy.level.CurrentLevelResolver;
import org.openhab.core.energy.level.LevelDerivation;
import org.openhab.core.energy.level.LevelPlaneCondition;
import org.openhab.core.energy.level.PlannedLevelPublisher;
import org.openhab.core.energy.level.PlannedLevelSchedule;
import org.openhab.core.energy.level.SurplusEscalationPolicy;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The level plane as the engine reaches it: a {@link CurrentLevelFunction} the engine <strong>calls</strong>, holding
 * the plan and the escalation policy that function is evaluated against.
 * <p>
 * <strong>The direction of this dependency is fixed and it runs one way.</strong> The engine takes one snapshot per
 * cycle and calls {@link #levelAt(Instant, OptionalDouble)} with that snapshot's own instant and its own surplus. It
 * never reads a level back from anywhere, and this component never asks anything what time it is: there is no
 * {@code Clock} field here, deliberately, because a clock of its own is exactly how the level of a cycle could come
 * to be resolved at a different moment from the readings the cycle acts on. Publishing the current level as an Item
 * and the plan as a {@code TimeSeries} is an <em>output</em> of the engine's computation - a report of what it
 * decided - and no part of this class participates in it. This bundle cannot write to an Item at all.
 * <p>
 * What the component owns is the plan and how future plans are derived. Deriving one needs a price series, and price
 * data is a later capability; until something calls {@link #setPlan} or {@link #derivePlan}, the plan is empty, every
 * moment resolves to {@link EnergyLevel#NORMAL} and {@link LevelPlaneCondition#PLAN_ABSENT} is what says so. That is
 * the correct behaviour for a site with no prices, not a placeholder: the requirement asks for normal and for the
 * absence to be reported separately, so that a missing price feed is visible rather than indistinguishable from a
 * genuinely normal hour.
 * <p>
 * Two questions the corpus once left open are now decided and this component no longer offers a choice about either
 * - a reduction in flexibility worth stating plainly:
 * <ul>
 * <li>The level outside the plan is <strong>fixed at normal</strong>. It used to be a configuration parameter that
 * could be set to blocked, which turned a failed price fetch into a cold house.</li>
 * <li>Surplus escalation is <strong>graded only</strong>. The any-surplus-jumps-to-overcapacity policy and the
 * do-not-escalate policy are gone from the shipped set; the first cycled devices on passing clouds, and the second
 * was never a policy at all but the meaning of an unset threshold, which is now
 * {@link LevelPlaneCondition#ESCALATION_UNCONFIGURED}.</li>
 * </ul>
 * The {@code derivation} parameter stays a genuine choice: percentile versus fixed-count derivation is still open
 * (change {@code define-energy-levels}, task 2.1), the two differ only on ties, and neither is a verdict.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(service = { CurrentLevelFunction.class,
        PlannedLevelPublisher.class }, configurationPid = EnergyLevelPlane.CONFIGURATION_PID, configurationPolicy = ConfigurationPolicy.OPTIONAL, property = Constants.SERVICE_PID
                + "=org.openhab.core.energy.level")
@ConfigurableService(category = "system", label = "Energy Management Levels", description_uri = EnergyLevelPlane.CONFIG_URI)
public class EnergyLevelPlane implements CurrentLevelFunction, PlannedLevelPublisher {

    /**
     * The configuration PID under which the level plane reads its parameters.
     */
    public static final String CONFIGURATION_PID = "org.openhab.core.energy.level";

    /**
     * The URI of the configuration description that renders the level parameters in the UI.
     */
    public static final String CONFIG_URI = "system:energy-level";

    /**
     * The {@code derivation} value selecting the fixed-count derivation the acceptance fixtures pin.
     */
    public static final String DERIVATION_FIXED_COUNTS = "fixed-counts";

    /**
     * The {@code derivation} value selecting the percentile derivation.
     */
    public static final String DERIVATION_PERCENTILES = "percentiles";

    /**
     * The level in force wherever the plan does not reach. Fixed centrally, not configurable.
     */
    public static final EnergyLevel LEVEL_OUTSIDE_PLAN = EnergyLevel.NORMAL;

    private final Logger logger = LoggerFactory.getLogger(EnergyLevelPlane.class);

    private volatile PlannedLevelSchedule plan = PlannedLevelSchedule.empty();
    private volatile LevelDerivation derivation = EnergyLevelConfiguration.defaults().createDerivation();
    private volatile SurplusEscalationPolicy escalation = EnergyLevelConfiguration.defaults().createEscalation();

    /**
     * Creates the level plane as OSGi activates it.
     *
     * @param properties the component configuration
     */
    @Activate
    public EnergyLevelPlane(Map<String, Object> properties) {
        applyConfiguration(properties);
    }

    /**
     * Re-reads the configuration. The plan itself is left alone: changing how future plans are derived is not a
     * statement about the plan already in force, and re-deriving the published series in place - the
     * <em>Re-derivation when the configuration changes</em> requirement - needs the publication path this bundle
     * does not have.
     *
     * @param properties the new component configuration
     */
    @Modified
    public void modified(Map<String, Object> properties) {
        applyConfiguration(properties);
    }

    /**
     * Returns the level in force at one moment, given the surplus measured at that same moment.
     * <p>
     * Both arguments come from the caller's cycle. Nothing is read here, nothing is timed here, and calling this
     * twice with the same arguments always gives the same answer.
     *
     * @param moment the instant the cycle's snapshot was taken at
     * @param surplusWatts the surplus that snapshot measured, or empty when the cycle has no usable reading
     * @return the planned level for that moment, escalated by that surplus, or {@link #LEVEL_OUTSIDE_PLAN} where no
     *         plan covers the moment
     */
    @Override
    public EnergyLevel levelAt(Instant moment, OptionalDouble surplusWatts) {
        Optional<EnergyLevel> resolved = resolver().currentAt(moment, watts(surplusWatts));
        if (resolved.isPresent()) {
            return resolved.get();
        }
        logger.trace("No planned schedule covers {}; the level is {} and the plan is reported absent", moment,
                LEVEL_OUTSIDE_PLAN);
        return LEVEL_OUTSIDE_PLAN;
    }

    /**
     * Returns what the level plane cannot currently do, for whoever renders the site's status.
     * <p>
     * This is the difference between "inert" and "unconfigured". An empty set means the plane is answering from data;
     * anything in it is a gap a site can close, never a failure.
     *
     * @param moment the moment being resolved, since plan coverage is a property of a moment rather than of the plane
     * @return the conditions in force, possibly empty
     */
    public Set<LevelPlaneCondition> conditionsAt(Instant moment) {
        Set<LevelPlaneCondition> conditions = EnumSet.noneOf(LevelPlaneCondition.class);
        if (plan.levelAt(moment).isEmpty()) {
            conditions.add(LevelPlaneCondition.PLAN_ABSENT);
        }
        if (!escalation.isConfigured()) {
            conditions.add(LevelPlaneCondition.ESCALATION_UNCONFIGURED);
        }
        return conditions;
    }

    /**
     * Returns the planned schedule currently in force.
     *
     * @return the plan, empty when nothing has supplied one
     */
    @Override
    public PlannedLevelSchedule getPlan() {
        return plan;
    }

    /**
     * Installs a planned schedule - the seam a price component, an add-on or a script uses to publish a plan.
     *
     * @param schedule the new plan
     */
    @Override
    public void setPlan(PlannedLevelSchedule schedule) {
        plan = schedule;
        logger.debug("A planned level schedule of {} slots is now in force", schedule.size());
    }

    /**
     * Derives and installs a plan from a price series with the configured derivation.
     *
     * @param series the price series to classify
     * @return the plan that is now in force
     */
    @Override
    public PlannedLevelSchedule derivePlan(SlotSeries series) {
        PlannedLevelSchedule derived = derivation.derive(series);
        setPlan(derived);
        return derived;
    }

    /**
     * Replaces the derivation, which is how the seasonal variant - and any variant a maintainer adds later - is
     * selected without this component having to invent a configuration grammar for it.
     *
     * @param levelDerivation the derivation future plans are derived with
     */
    @Override
    public void setDerivation(LevelDerivation levelDerivation) {
        derivation = levelDerivation;
    }

    /**
     * Replaces the surplus escalation policy.
     *
     * @param policy the policy applied to the planned level of the current slot
     */
    @Override
    public void setEscalation(SurplusEscalationPolicy policy) {
        escalation = policy;
    }

    private CurrentLevelResolver resolver() {
        return new CurrentLevelResolver(plan, escalation);
    }

    private static @Nullable QuantityType<Power> watts(OptionalDouble surplusWatts) {
        return surplusWatts.isPresent() ? new QuantityType<>(surplusWatts.getAsDouble(), Units.WATT) : null;
    }

    private void applyConfiguration(Map<String, Object> properties) {
        EnergyLevelConfiguration config = EnergyLevelConfiguration.fromProperties(properties,
                rejected -> logger.warn("{}", rejected));
        derivation = config.createDerivation();
        escalation = config.createEscalation();
        if (escalation.isConfigured()) {
            logger.debug("Energy level plane configured: derivation '{}', escalation {}", config.derivationId(),
                    escalation);
        } else {
            logger.info("Energy level plane configured: derivation '{}'. Surplus escalation is unconfigured - set '{}' "
                    + "in watts to let a photovoltaic surplus raise the current level; there is no shipped default",
                    config.derivationId(), EnergyLevelConfiguration.CONFIG_ENCOURAGED_FROM);
        }
    }
}
