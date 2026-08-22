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

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * What a layered write comes to: the entries that should end up in the prediction, the entries that belong to a
 * separate constraint series instead, the entries that were refused, and what has to be reported about it.
 * <p>
 * <strong>It is a plan, not a write.</strong> Producing it is arithmetic over timestamps and needs no Item and no
 * persistence service, which is why it is computed in the framework bundle - the one that is structurally incapable
 * of writing anything. Carrying it out is a separate bundle's job. That is the same split D23 drew for the status
 * Item: the value is computed where nothing can be written, and the write happens where writing is the point.
 *
 * @param entries what to put into the prediction series, <strong>in the order they have to be applied</strong> -
 *            which is chronological except where a policy deliberately re-applies an entry on top of another for the
 *            same timestamp
 * @param constraints what to put into the separate cap series, empty unless the policy keeps caps out of the
 *            prediction
 * @param refused incoming entries that were not applied at all
 * @param collisions the timestamps where an incoming entry met an entry a different layer had written
 * @param conditions what the plane has to report about this write
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record LayeredWritePlan(List<LayeredEntry> entries, List<LayeredEntry> constraints, List<LayeredEntry> refused,
        List<Instant> collisions, Set<ForecastPlaneCondition> conditions) {

    /**
     * Takes defensive immutable copies.
     */
    public LayeredWritePlan {
        entries = List.copyOf(entries);
        constraints = List.copyOf(constraints);
        refused = List.copyOf(refused);
        collisions = List.copyOf(collisions);
        conditions = Set.copyOf(conditions);
    }

    /**
     * Tells whether this plan writes anything at all.
     *
     * @return {@code true} if neither the prediction nor the constraint series receives an entry
     */
    public boolean isEmpty() {
        return entries.isEmpty() && constraints.isEmpty();
    }

    /**
     * Tells whether a cap and a refresh met on at least one entry.
     *
     * @return {@code true} if the plan had a collision to resolve
     */
    public boolean hasCollision() {
        return !collisions.isEmpty();
    }
}
