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

import java.time.Instant;
import java.util.Optional;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.library.types.QuantityType;

/**
 * The live half of the <em>Planned schedule vs. current level</em> requirement: reads the plan for a given moment and
 * applies the surplus escalation on top of it.
 * <p>
 * The plan is never modified. {@link #plannedAt(Instant)} and {@link #currentAt(Instant, QuantityType)} can and do
 * disagree, which is the whole point of the requirement - "the current-level Item reads overcapacity while the stored
 * plan stays intact". Both are <em>reports</em> of what was computed; neither is an input to the computation.
 * <p>
 * <strong>Time is an argument, and that is not a convenience.</strong> This type has no clock, no scheduler and no
 * state, so the moment it answers for is always the moment its caller asked about - the instant of the engine's own
 * snapshot. A resolver with a clock could answer for a different slot than the readings the cycle acts on came from,
 * which is exactly the disagreement the corpus forbids. As a side effect a test can walk a whole day through this in
 * microseconds.
 *
 * @param plan the planned level schedule
 * @param escalation the policy applied to the planned level of the current slot
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record CurrentLevelResolver(PlannedLevelSchedule plan, SurplusEscalationPolicy escalation) {

    /**
     * Returns the planned level for the given moment, ignoring live conditions entirely.
     *
     * @param moment the moment to look up
     * @return the planned level, or {@link Optional#empty()} if the plan does not cover the moment
     */
    public Optional<EnergyLevel> plannedAt(Instant moment) {
        return plan.levelAt(moment);
    }

    /**
     * Returns the level to publish for the given moment, escalated by the live surplus.
     * <p>
     * The result is empty when the plan does not cover the moment - before the first prices arrive, after the plan
     * runs out, or inside a gap. That is deliberately <em>not</em> a level: the decided answer is normal with the
     * absence of the plan reported separately, and reporting is not this type's job. Emptiness is how the two halves
     * stay distinguishable, so a missing price feed cannot look like a genuinely normal hour.
     *
     * @param moment the moment to look up
     * @param surplus the live surplus, or {@code null} when it is unknown
     * @return the current level, or {@link Optional#empty()} if the plan does not cover the moment
     */
    public Optional<EnergyLevel> currentAt(Instant moment, @Nullable QuantityType<Power> surplus) {
        return plannedAt(moment).map(planned -> {
            EnergyLevel escalated = escalation.escalate(planned, surplus);
            return escalated.atLeast(planned) ? escalated : planned;
        });
    }
}
