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

import java.math.BigDecimal;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.level.internal.GradedSurplusEscalation;
import org.openhab.core.energy.level.internal.UnconfiguredSurplusEscalation;
import org.openhab.core.library.types.QuantityType;

/**
 * Raises the planned level of the current slot as live site surplus rises - the <em>Surplus escalation of the
 * current level</em> requirement.
 * <p>
 * Escalation is <strong>graded</strong>: the level reaches {@code ENCOURAGED} at a site-declared
 * {@code encouragedFrom} threshold in watts and {@code OVERCAPACITY} at {@code overcapacityFrom}, which defaults to
 * twice {@code encouragedFrom}. <strong>{@code encouragedFrom} has no shipped default and none is invented
 * here</strong>: a site that has never set one gets {@link #unconfigured()}, which never escalates and says so
 * through {@link #isConfigured()}, so the absence of a threshold is what stops escalation rather than escalation
 * happening at a number nobody chose.
 * <p>
 * The <em>surplus</em> the thresholds apply to is the site's grid export <em>plus the battery charging the engine
 * can reclaim</em>, read under the corpus' single sign convention (grid + = export, battery + = charging, PV + =
 * producing, consumers + = consuming). Computing that figure is the engine's job - a policy is handed the number and
 * never derives it - and whether it is instantaneous, averaged or forecast is still open.
 * <p>
 * A policy may only raise a level, never lower it. The requirement's verb is "escalate", and a policy that could
 * demote would silently turn the live plane into a second, competing derivation.
 * <p>
 * The interface stays open to contributors, but core now ships exactly one escalating policy. The
 * any-surplus-jumps-straight-to-overcapacity reading is a preserved alternative and is no longer selectable: 50 W of
 * export would have made the engine believe power was abundant and cycled devices on passing clouds.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface SurplusEscalationPolicy {

    /**
     * Returns the level to publish given the planned level and the live surplus.
     *
     * @param plannedLevel the level the plan holds for the current slot
     * @param surplus the live site surplus, or {@code null} when it is unknown - an unknown surplus never escalates
     * @return the planned level or a more permissive one, never a less permissive one
     */
    EnergyLevel escalate(EnergyLevel plannedLevel, @Nullable QuantityType<Power> surplus);

    /**
     * Tells whether this policy has the thresholds it needs to escalate at all.
     * <p>
     * This is what lets an unconfigured site be <em>reported</em> as unconfigured rather than appearing silently
     * inert: a policy that answers {@code false} is not broken and not disabled, it is waiting for a number only the
     * site can supply.
     *
     * @return {@code true} if the policy can escalate, {@code false} if it is waiting for configuration
     */
    default boolean isConfigured() {
        return true;
    }

    /**
     * Returns the policy of a site that has declared no threshold: the current level never rises above its planned
     * value, and {@link #isConfigured()} answers {@code false} so the gap can be reported.
     *
     * @return the unconfigured policy
     */
    static SurplusEscalationPolicy unconfigured() {
        return new UnconfiguredSurplusEscalation();
    }

    /**
     * Returns the graded policy with {@code overcapacityFrom} at twice {@code encouragedFrom}, which is the relation
     * the corpus fixes for a site that declares only the first threshold.
     *
     * @param encouragedFrom the surplus from which the level is raised to {@code ENCOURAGED}
     * @return the policy
     * @throws IllegalArgumentException if the threshold is not a power or is negative
     */
    static SurplusEscalationPolicy graded(QuantityType<Power> encouragedFrom) {
        return graded(encouragedFrom,
                new QuantityType<>(encouragedFrom.toBigDecimal().multiply(BigDecimal.TWO), encouragedFrom.getUnit()));
    }

    /**
     * Returns the graded policy: {@code ENCOURAGED} from one threshold, {@code OVERCAPACITY} from a higher one.
     *
     * @param encouragedFrom the surplus from which the level is raised to {@code ENCOURAGED}
     * @param overcapacityFrom the surplus from which the level is raised to {@code OVERCAPACITY}
     * @return the policy
     * @throws IllegalArgumentException if a threshold is not a power, is negative, or the thresholds are out of order
     */
    static SurplusEscalationPolicy graded(QuantityType<Power> encouragedFrom, QuantityType<Power> overcapacityFrom) {
        return new GradedSurplusEscalation(encouragedFrom, overcapacityFrom);
    }
}
