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
package org.openhab.core.energy.level.internal;

import java.util.OptionalDouble;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.level.SurplusEscalationPolicy;
import org.openhab.core.library.types.QuantityType;

/**
 * Graded escalation, the one shape core ships: a moderate surplus lifts the site to {@code ENCOURAGED}, a large one
 * to {@code OVERCAPACITY}.
 * <p>
 * Both thresholds come from the site. Neither is guessed here and neither has a shipped default; a site that
 * declares only {@code encouragedFrom} gets {@code overcapacityFrom} at twice that, which is the one relation the
 * corpus fixes. The surplus the thresholds are compared against is grid export plus reclaimable battery charging,
 * computed by the engine and handed in - this class never derives it.
 * <p>
 * The escalated level never drops below the planned one, so a cheap hour stays cheap even when the sun is only
 * half out.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class GradedSurplusEscalation implements SurplusEscalationPolicy {

    private final double encouragedWatts;
    private final double overcapacityWatts;
    private final QuantityType<Power> encouragedFrom;
    private final QuantityType<Power> overcapacityFrom;

    /**
     * Creates the policy.
     *
     * @param encouragedFrom the surplus from which the level is raised to {@code ENCOURAGED}
     * @param overcapacityFrom the surplus from which the level is raised to {@code OVERCAPACITY}
     * @throws IllegalArgumentException if a threshold is not a power, is negative, or the thresholds are out of order
     */
    public GradedSurplusEscalation(QuantityType<Power> encouragedFrom, QuantityType<Power> overcapacityFrom) {
        this.encouragedFrom = encouragedFrom;
        this.overcapacityFrom = overcapacityFrom;
        this.encouragedWatts = SurplusChecks.requireNotNegativeWatts(encouragedFrom, "encouragedFrom");
        this.overcapacityWatts = SurplusChecks.requireNotNegativeWatts(overcapacityFrom, "overcapacityFrom");
        if (encouragedWatts > overcapacityWatts) {
            throw new IllegalArgumentException("encouragedFrom (" + encouragedFrom + ") must not exceed "
                    + "overcapacityFrom (" + overcapacityFrom + ")");
        }
    }

    @Override
    public EnergyLevel escalate(EnergyLevel plannedLevel, @Nullable QuantityType<Power> surplus) {
        OptionalDouble measured = SurplusChecks.toWatts(surplus);
        if (measured.isEmpty()) {
            return plannedLevel;
        }
        double watts = measured.getAsDouble();
        EnergyLevel escalated;
        if (watts >= overcapacityWatts) {
            escalated = EnergyLevel.OVERCAPACITY;
        } else if (watts >= encouragedWatts) {
            escalated = EnergyLevel.ENCOURAGED;
        } else {
            escalated = plannedLevel;
        }
        return escalated.atLeast(plannedLevel) ? escalated : plannedLevel;
    }

    /**
     * Returns the threshold for {@code ENCOURAGED}.
     *
     * @return the surplus from which the level is raised to {@code ENCOURAGED}
     */
    public QuantityType<Power> encouragedFrom() {
        return encouragedFrom;
    }

    /**
     * Returns the threshold for {@code OVERCAPACITY}.
     *
     * @return the surplus from which the level is raised to {@code OVERCAPACITY}
     */
    public QuantityType<Power> overcapacityFrom() {
        return overcapacityFrom;
    }

    @Override
    public String toString() {
        return "GradedSurplusEscalation[encouraged=" + encouragedFrom + ", overcapacity=" + overcapacityFrom + "]";
    }
}
