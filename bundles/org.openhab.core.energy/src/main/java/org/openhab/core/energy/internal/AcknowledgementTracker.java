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

import java.time.Instant;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.EnergyContext;

/**
 * Keeps the engine from re-sending a command while the previous one is still unacknowledged.
 * <p>
 * <strong>Where this belongs is an open question</strong> ({@code define-engine-contract/design.md} §3: "The ACK
 * window could live in the core engine, or in per-device adapters"). Both readings ship:
 * {@link EngineAcknowledgementTracker} polices repeats centrally by comparing the participant's reported state
 * against the last command; {@link AdapterAcknowledgementTracker} steps aside and leaves it to the actuation
 * adapter behind the sink. The {@code ackHandling} configuration parameter chooses.
 * <p>
 * A cycle calls {@link #observe} once, then {@link #isSuppressed} per decision, then {@link #recordDispatch} for
 * every decision that actually reached the sink.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface AcknowledgementTracker {

    /**
     * Returns the stable id of this tracker, used in log lines.
     *
     * @return the tracker id
     */
    String getId();

    /**
     * Reconciles outstanding commands against the fresh snapshot: a command whose value the participant now
     * reports counts as acknowledged, and a command older than the acknowledgement window is given up on.
     *
     * @param context the snapshot of the cycle that is starting
     */
    void observe(EnergyContext context);

    /**
     * Tells whether this decision must be held back because an earlier command has not been acknowledged.
     *
     * @param decision the decision about to be dispatched
     * @param context the snapshot of the current cycle
     * @return {@code true} if the decision is to be suppressed this cycle
     */
    boolean isSuppressed(Decision decision, EnergyContext context);

    /**
     * Records that a decision was dispatched, opening its acknowledgement window.
     *
     * @param decision the dispatched decision
     * @param at the moment it was dispatched
     */
    void recordDispatch(Decision decision, Instant at);

    /**
     * Returns the participants with an outstanding, unacknowledged command.
     *
     * @return the participant ids, never {@code null}
     */
    Set<String> pendingParticipants();

    /**
     * Forgets every outstanding command - used when the master stop is engaged, so that releasing it starts from a
     * clean slate rather than from commands nobody is waiting for any more.
     */
    void reset();

    /**
     * Takes over the outstanding commands of the tracker this one replaces.
     * <p>
     * A tracker is rebuilt whenever the engine's configuration changes - and a configuration change can be as
     * unrelated as a service binding. Dropping the open acknowledgement windows there would let the next cycle
     * re-send a command that is still in flight, which is exactly what the acknowledgement requirement exists to
     * prevent. The default does nothing, which is right for a tracker that keeps no state of its own.
     *
     * @param previous the tracker being replaced
     */
    default void adopt(AcknowledgementTracker previous) {
    }
}
