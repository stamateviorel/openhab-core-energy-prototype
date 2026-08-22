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
package org.openhab.core.energy.objective;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.window.SlotSeries;

/**
 * What "best" means when a load is placed - the metric the planner optimizes for, made explicit and selectable
 * instead of being the standing assumption that best means cheapest.
 * <p>
 * <strong>An objective is a ranking function, and deliberately nothing more.</strong> It turns the series available
 * this run into one {@link SlotSeries}, and every existing calculation - the consecutive-window strategy, the
 * cheapest-slots strategy, the window cost, the level derivations - then works on that ranking unchanged, because a
 * ranking series carries the sense that tells them which end is good. That is what lets the carbon objective use the
 * shared window calculation "exactly as the cost objective uses the price series", and it is why adding an objective
 * needs no core change: an objective adds a number, not a scheduler.
 * <p>
 * <strong>Two answers, because the corpus asks two different questions.</strong> {@link #rank(ObjectiveInputs)} is
 * the planning-time answer, over future slots. {@link #availableWattsFor(EnergyContext, int)} is the dispatch-time
 * answer, over the one instantaneous snapshot the engine has - which is what the self-consumption scenarios about a
 * charging battery actually turn on, since a reclaimable-charge figure is a fact about now and not about tomorrow
 * afternoon. An objective that has no opinion at one of the two points says so by answering empty, and the caller
 * falls through to whatever it would have done anyway.
 * <p>
 * <strong>Implementations must be pure.</strong> No clock, no Item, no network, no blocking. An implementation that
 * throws is skipped and reported; it never takes the plane down.
 * <p>
 * Core's own objectives register at a negative {@code service.ranking} so that a contributed objective displaces
 * them, and a contributed objective is registered through the same whiteboard - an OSGi service, or
 * {@link ObjectiveRegistry#registerObjective(OptimizationObjective)} for a script. That is what "on equal terms"
 * means here: the same SPI, the same registry, no special case for the built-ins beyond a ranking any contribution
 * beats by default.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface OptimizationObjective {

    /**
     * The {@code service.ranking} core's own objectives register at, so that a contributed objective of the default
     * ranking outranks them without a site having to configure anything.
     * <p>
     * The number is core's own convention for a default that must not privilege itself: it is what the framework's
     * default state-description provider registers at.
     */
    int CORE_DEFAULT_RANKING = -2;

    /**
     * Returns the stable id this objective is selected by.
     * <p>
     * It appears in configuration and in the reported conditions, so it must be stable across restarts and unique
     * among the installed objectives. A duplicate is refused rather than allowed to replace the incumbent.
     *
     * @return the objective id
     */
    String getId();

    /**
     * Returns the roles whose series this objective needs before it can rank anything.
     * <p>
     * This is what makes "the carbon objective was selected but no carbon source is installed" answerable before a
     * planning run rather than by watching it produce nothing.
     *
     * @return the required inputs, never empty for an objective that ranks on data
     */
    Set<ObjectiveInput> requiredSeries();

    /**
     * Ranks the future slots.
     *
     * @param inputs the series resolved for this run and the export share they are read with
     * @return the ranking, or empty when the inputs do not carry what {@link #requiredSeries()} asks for
     */
    Optional<SlotSeries> rank(ObjectiveInputs inputs);

    /**
     * Returns everything this objective has to report about ranking on these inputs.
     * <p>
     * A ranking that came out of a rule the objective could not evaluate is not the same answer as one that came out
     * of a rule it could, and the difference is invisible in the numbers. An objective says so here; the plane
     * collects it into the resolution and onto the site's configuration status.
     *
     * @param inputs the inputs it would rank on
     * @return the conditions in force, empty for an objective with nothing to report
     */
    default Set<ObjectiveCondition> conditionsFor(ObjectiveInputs inputs) {
        return Set.of();
    }

    /**
     * Returns how much power this objective offers one consumer right now, on its own terms.
     * <p>
     * The self-consumption objective answers the site surplus that consumer may claim - grid export plus the battery
     * charge it outranks, netted against import - which is the figure the corpus defines and the one the reclaimable
     * charge scenarios are about. A purely planning objective answers empty.
     *
     * @param context the cycle snapshot
     * @param consumerPriority the priority of the consumer asking, lower being better
     * @return the power on offer in watts, or empty when this objective has no instantaneous opinion
     */
    default OptionalDouble availableWattsFor(EnergyContext context, int consumerPriority) {
        return OptionalDouble.empty();
    }

    /**
     * Returns the {@code service.ranking} this objective registers at, which orders it against the others and lets a
     * contributed objective outrank a core one.
     *
     * @return the service ranking, higher winning, defaulting to the OSGi default of zero
     */
    default int getServiceRanking() {
        return 0;
    }
}
