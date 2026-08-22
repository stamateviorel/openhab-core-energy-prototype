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
package org.openhab.core.energy.forecast.store;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.forecast.ForecastPlaneCondition;

/**
 * What one layered write actually did.
 * <p>
 * A writer gets an answer rather than a silence: how many entries landed in the prediction, how many went to a
 * separate constraint series, how many were refused, where a cap and a refresh met, and what the plane has to report
 * about it. The corpus's headline hazard - a refresh erasing a cap - is a value in {@link #collisions()} rather than
 * something a site finds out from a chart three days later.
 *
 * @param written how many entries were stored into the prediction series
 * @param constrained how many entries were stored into the separate constraint series
 * @param refused how many entries were not stored at all
 * @param collisions the timestamps where an incoming entry met an entry another layer had written
 * @param conditions what the plane reports about this write
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record LayeredWriteReport(int written, int constrained, int refused, List<Instant> collisions,
        Set<ForecastPlaneCondition> conditions) {

    /**
     * Takes defensive immutable copies.
     */
    public LayeredWriteReport {
        collisions = List.copyOf(collisions);
        conditions = Set.copyOf(conditions);
    }

    /**
     * Returns a report of a write that did nothing.
     *
     * @param conditions why it did nothing
     * @return the report
     */
    public static LayeredWriteReport refused(Set<ForecastPlaneCondition> conditions) {
        return new LayeredWriteReport(0, 0, 0, List.of(), conditions);
    }

    /**
     * Tells whether anything at all reached storage.
     *
     * @return {@code true} if at least one entry was stored
     */
    public boolean storedAnything() {
        return written > 0 || constrained > 0;
    }
}
