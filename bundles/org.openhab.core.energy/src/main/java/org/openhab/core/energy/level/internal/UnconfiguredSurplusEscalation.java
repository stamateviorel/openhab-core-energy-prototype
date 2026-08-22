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

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.level.SurplusEscalationPolicy;
import org.openhab.core.library.types.QuantityType;

/**
 * What a site that has declared no {@code encouragedFrom} threshold gets: the current level stays at its planned
 * value whatever the surplus is.
 * <p>
 * This is deliberately not "the policy that never escalates" - it is <em>the absence of a threshold</em>, and it
 * exists as its own type rather than as a lambda so that it can answer {@link #isConfigured()} with {@code false}.
 * The corpus ships no default threshold and refuses to invent one, so the honest behaviour is to do nothing and to
 * be visible about why.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class UnconfiguredSurplusEscalation implements SurplusEscalationPolicy {

    @Override
    public EnergyLevel escalate(EnergyLevel plannedLevel, @Nullable QuantityType<Power> surplus) {
        return plannedLevel;
    }

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public String toString() {
        return "UnconfiguredSurplusEscalation";
    }
}
