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
package org.openhab.core.energy.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * What became of a proposed decision by the end of a cycle. Every proposal a cycle sees ends in exactly one of
 * these, carrying a free-text reason alongside it, which is what makes a shadow run comparable against an existing
 * automation line by line.
 * <p>
 * The vocabulary is closed and it is the engine's, not an algorithm's: nothing a contributed algorithm returns can
 * produce a status, only meet one.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum DecisionStatus {

    /**
     * Dispatched to the actuation sink. In this prototype that still writes nothing - the only sink logs.
     */
    APPLIED,

    /**
     * Would have been dispatched, but shadow mode is on for it. The engine logs the "would have done" line.
     */
    SHADOWED,

    /**
     * Lost the conflict for its participant to a stronger decision in the same cycle.
     */
    SUPERSEDED,

    /**
     * Dropped by the electrical-limit floor: there was no headroom for it, and the action could not be trimmed to
     * fit within the participant's declared minimum.
     */
    DEFERRED,

    /**
     * Held back because an earlier command to the same participant has not been acknowledged yet.
     */
    SUPPRESSED,

    /**
     * Withheld by one of the engine-owned prohibitions - the participant is hands-off, its user-declared level gate
     * is closed, or its readiness interlock is open - rather than by anything the engine or an algorithm wanted.
     */
    WITHHELD,

    /**
     * Not dispatched because the master stop was engaged while the cycle was in flight.
     * <p>
     * This is the <em>only</em> way the status is reached. A stopped engine takes no snapshot and invokes no
     * algorithm, so a cycle that starts while the stop is engaged has no decisions to mark; only a cycle already
     * running when the stop lands does.
     */
    STOPPED,

    /**
     * Discarded before it was ever considered - a decision naming a participant this cycle does not know, or one the
     * actuation sink refused. Rejections are counted as well as named.
     */
    REJECTED
}
