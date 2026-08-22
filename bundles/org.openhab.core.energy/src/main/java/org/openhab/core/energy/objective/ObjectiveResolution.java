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
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.window.SlotSeries;

/**
 * Which objective is actually in force, what was asked for, what it ranked, and what had to be reported to get there.
 * <p>
 * The four are answered together on purpose. "The carbon objective is selected" and "the carbon objective is what
 * ranked this plan" are different statements on a site whose carbon feed is down, and a caller that can only see the
 * second has no way to tell a user why their choice is not taking effect.
 *
 * @param requestedId the objective id the site asked for, empty when it configured none
 * @param effective the objective that ranked, empty when nothing did
 * @param ranking the ranking it produced, empty when nothing ranked
 * @param conditions everything the plane had to report about this resolution
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record ObjectiveResolution(String requestedId, Optional<OptimizationObjective> effective,
        Optional<SlotSeries> ranking, Set<ObjectiveCondition> conditions) {

    /**
     * Copies the condition set defensively.
     */
    public ObjectiveResolution {
        conditions = Set.copyOf(conditions);
    }

    /**
     * Returns whether the objective that ranked is the one that was asked for.
     *
     * @return {@code true} if nothing was degraded or refused
     */
    public boolean isAsRequested() {
        return effective.isPresent() && effective.get().getId().equals(requestedId);
    }

    /**
     * Returns the id of the objective that ranked.
     *
     * @return the effective objective id, or empty when nothing ranked
     */
    public Optional<String> effectiveId() {
        return effective.map(OptimizationObjective::getId);
    }

    /**
     * Returns whether a condition is in force.
     *
     * @param condition the condition
     * @return {@code true} if the plane reported it
     */
    public boolean reports(ObjectiveCondition condition) {
        return conditions.contains(condition);
    }
}
