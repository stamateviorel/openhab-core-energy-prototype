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
package org.openhab.core.energy.level;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.window.SlotSeries;

/**
 * How a price component, an add-on or a script hands the level plane a plan.
 * <p>
 * Deriving a planned schedule needs a price series, and price data is a wave-2 capability that does not exist yet.
 * Rather than stub a price source, wave 1 exposes the seam: whoever has prices either derives a schedule itself and
 * publishes it, or hands over the series and lets the configured {@link LevelDerivation} classify it.
 * <p>
 * <strong>Publication runs the other way and is not this interface.</strong> The plan as a future-timestamped
 * {@code TimeSeries}, and the current level as an Item state, are <em>reports</em> of what the engine computed. They
 * are never the path by which a level reaches the engine, so this interface exists precisely so that the
 * classifier-to-engine path stays in-process: whoever has prices hands over a schedule, and the engine calls
 * {@link CurrentLevelFunction} against it. A version of this that read the plan back off an Item would invert the
 * dependency the corpus fixed.
 * <p>
 * Still open: what a re-plan does to the schedule it replaces at the level of already-elapsed entries. The decided
 * answer for the published series is a re-derivation over the whole series republished as one {@code TimeSeries} with
 * {@code Policy.REPLACE}, leaving elapsed entries untouched - but that is a statement about publication, which this
 * bundle cannot perform. Here a re-plan is simply a whole new immutable schedule: no merge, no partial update, no
 * protection of a slot already acted on.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface PlannedLevelPublisher {

    /**
     * Returns the planned schedule currently in force.
     *
     * @return the plan, empty when nothing has supplied one
     */
    PlannedLevelSchedule getPlan();

    /**
     * Installs a planned schedule.
     *
     * @param schedule the new plan
     */
    void setPlan(PlannedLevelSchedule schedule);

    /**
     * Derives and installs a plan from a price series with the configured derivation.
     *
     * @param series the price series to classify
     * @return the plan that is now in force
     */
    PlannedLevelSchedule derivePlan(SlotSeries series);

    /**
     * Replaces the derivation, which is how the seasonal variant - and any variant a maintainer adds later - is
     * selected without this component having to invent a configuration grammar for it.
     *
     * @param derivation the derivation future plans are derived with
     */
    void setDerivation(LevelDerivation derivation);

    /**
     * Replaces the surplus escalation policy.
     *
     * @param policy the policy applied to the planned level of the current slot
     */
    void setEscalation(SurplusEscalationPolicy policy);
}
