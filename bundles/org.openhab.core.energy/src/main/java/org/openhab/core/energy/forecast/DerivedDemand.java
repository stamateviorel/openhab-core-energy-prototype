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
package org.openhab.core.energy.forecast;

import java.util.Optional;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * What a derivation came to: the series, where one could be computed, and everything the plane has to report about
 * how it was computed.
 * <p>
 * The conditions travel with the answer rather than being logged inside the derivation, because a derivation is a
 * pure function and a pure function has no business owning a logger - and because "there is no series, and here is
 * why" is an answer a caller has to be able to act on. A site that has declared no heat-loss coefficient gets no
 * demand series and a named reason for it, never an empty series that looks like a building needing no heat.
 *
 * @param series the derived series, or empty where the inputs or the parameters did not allow one
 * @param conditions what the plane reports about this derivation
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record DerivedDemand(Optional<ForecastSeries> series, Set<ForecastPlaneCondition> conditions) {

    /**
     * Takes a defensive immutable copy of the conditions.
     */
    public DerivedDemand {
        conditions = Set.copyOf(conditions);
    }

    /**
     * Returns an answer carrying no series.
     *
     * @param conditions why there is none
     * @return the answer
     */
    public static DerivedDemand none(Set<ForecastPlaneCondition> conditions) {
        return new DerivedDemand(Optional.empty(), conditions);
    }

    /**
     * Tells whether a series was derived.
     *
     * @return {@code true} if there is a series
     */
    public boolean isPresent() {
        return series.isPresent();
    }
}
