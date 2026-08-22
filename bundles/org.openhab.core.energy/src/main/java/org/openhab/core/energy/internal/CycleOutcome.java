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

import java.util.List;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.EnergyContext;

/**
 * Everything one evaluation cycle produced: the snapshot it ran against and the fate of every proposal.
 * <p>
 * The engine returns this from each cycle so that a test - or, later, a UI or a report - can compare a shadow run
 * against an existing automation without reading log lines.
 *
 * @param context the snapshot the cycle ran against
 * @param outcomes the fate of every proposal, in the order the cycle processed them
 * @param shadow whether shadow mode was in force for this cycle
 * @param stopped whether the master stop was engaged for this cycle
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record CycleOutcome(EnergyContext context, List<DecisionOutcome> outcomes, boolean shadow, boolean stopped) {

    /**
     * Takes an immutable copy of the outcome list.
     */
    public CycleOutcome {
        outcomes = List.copyOf(outcomes);
    }

    /**
     * Returns the decisions that reached the actuation sink.
     *
     * @return the applied decisions, never {@code null}
     */
    public List<Decision> applied() {
        return withStatus(DecisionStatus.APPLIED);
    }

    /**
     * Returns the decisions that would have been applied had shadow mode been off.
     *
     * @return the shadowed decisions, never {@code null}
     */
    public List<Decision> shadowed() {
        return withStatus(DecisionStatus.SHADOWED);
    }

    /**
     * Returns the decisions carrying one status.
     *
     * @param status the status to filter on
     * @return the matching decisions, never {@code null}
     */
    public List<Decision> withStatus(DecisionStatus status) {
        return outcomes.stream().filter(outcome -> outcome.status() == status).map(DecisionOutcome::decision).toList();
    }

    /**
     * Returns the outcome recorded for one participant, if the cycle produced one.
     *
     * @param participantId the participant id
     * @return the outcome with the strongest disposition for that participant, or empty when the cycle had none
     */
    public Optional<DecisionOutcome> forParticipant(String participantId) {
        return outcomes.stream().filter(outcome -> outcome.decision().participantId().equals(participantId))
                .findFirst();
    }
}
