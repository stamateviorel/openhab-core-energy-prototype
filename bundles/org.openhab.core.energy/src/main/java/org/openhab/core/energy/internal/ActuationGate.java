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

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.Decision;

/**
 * The two operator controls, which are <strong>two controls and not one</strong>.
 * <ul>
 * <li><strong>Shadow mode</strong> answers "should this decision be written?". It is layered - global, per algorithm,
 * per participant - so an operator can hand one algorithm or one device over while everything else stays observed.
 * With both sets empty it behaves exactly like a global-only flag. A fresh install starts in it.</li>
 * <li><strong>The master stop</strong> answers "should the engine run at all?". A fresh install starts with it
 * disengaged. It is <em>not</em> reached on the ordinary path: {@link EnergyEngine#runCycleNow()} returns before a
 * snapshot is taken and before any contributed algorithm is invoked, so a stopped engine produces no decisions to
 * gate. {@link #isStopped()} is still read per decision, because a stop engaged <em>during</em> a cycle has to stop
 * the decisions that cycle has not dispatched yet.</li>
 * </ul>
 * This class is deliberately not an interface with two implementations. The prototype shipped the one-control
 * reading alongside this one, selected by a {@code gate} configuration parameter; the owner's decision fixes the
 * two-control shape, so both the parameter and the alternative implementation are gone. That is a reduction in
 * flexibility a reviewer should see: a site can no longer choose to have the stop imply shadow.
 * <p>
 * It is consulted on every decision of every cycle, so it is cheap and thread-safe.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ActuationGate {

    private volatile Set<String> shadowedAlgorithms;
    private volatile Set<String> shadowedParticipants;

    private volatile boolean shadow;
    private volatile boolean stopped;

    /**
     * Creates the gate.
     *
     * @param shadow whether global shadow mode starts on - {@code true} on a fresh install
     * @param stopped whether the master stop starts engaged - {@code false} on a fresh install
     * @param shadowedAlgorithms ids of algorithms kept in shadow while global shadow is off
     * @param shadowedParticipants ids of participants kept in shadow while global shadow is off
     */
    public ActuationGate(boolean shadow, boolean stopped, Set<String> shadowedAlgorithms,
            Set<String> shadowedParticipants) {
        this.shadow = shadow;
        this.stopped = stopped;
        this.shadowedAlgorithms = Set.copyOf(shadowedAlgorithms);
        this.shadowedParticipants = Set.copyOf(shadowedParticipants);
    }

    /**
     * Installs a configuration on the gate the engine is already using, rather than handing the engine a second gate.
     * <p>
     * There is exactly one gate instance for the lifetime of the engine, and that is deliberate. A cycle captures the
     * gate it started with and consults it per decision, so that a stop engaged <em>during</em> a cycle stops the
     * decisions that cycle has not dispatched yet. Replacing the instance when a configuration lands would make that
     * true of a stop engaged through {@link EnergyEngine#setStopped(boolean)} and false of the identical stop
     * arriving from the configuration admin - one control with two behaviours, decided by which route the operator
     * happened to take.
     *
     * @param isShadow whether global shadow mode is on
     * @param isStopped whether the master stop is engaged
     * @param algorithms ids of algorithms kept in shadow while global shadow is off
     * @param participants ids of participants kept in shadow while global shadow is off
     */
    public void configure(boolean isShadow, boolean isStopped, Set<String> algorithms, Set<String> participants) {
        shadowedAlgorithms = Set.copyOf(algorithms);
        shadowedParticipants = Set.copyOf(participants);
        shadow = isShadow;
        stopped = isStopped;
    }

    /**
     * Tells whether the master stop is engaged.
     * <p>
     * A stopped engine does not evaluate: it takes no snapshot, invokes no contributed algorithm and enforces no
     * device protection, and it reports that non-enforcement for as long as the stop is engaged.
     *
     * @return {@code true} if the engine is halted
     */
    public boolean isStopped() {
        return stopped;
    }

    /**
     * Engages or releases the master stop. Releasing it resumes normal operation from the next cycle on, without any
     * reconfiguration of participants.
     *
     * @param halted {@code true} to halt the engine
     */
    public void setStopped(boolean halted) {
        stopped = halted;
    }

    /**
     * Tells whether global shadow mode is on.
     *
     * @return {@code true} if decisions are only logged
     */
    public boolean isShadow() {
        return shadow;
    }

    /**
     * Turns global shadow mode on or off.
     *
     * @param shadowed {@code true} to only log decisions
     */
    public void setShadow(boolean shadowed) {
        shadow = shadowed;
    }

    /**
     * Tells whether this decision may reach the actuation sink.
     *
     * @param decision the decision about to be dispatched
     * @return {@code true} if it may be written, {@code false} if it is to be logged as "would have done"
     */
    public boolean allowsWrite(Decision decision) {
        return !shadow && !stopped && !shadowedAlgorithms.contains(decision.algorithmId())
                && !shadowedParticipants.contains(decision.participantId());
    }
}
