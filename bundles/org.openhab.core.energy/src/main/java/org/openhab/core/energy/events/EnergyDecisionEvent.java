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
package org.openhab.core.energy.events;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.events.AbstractEvent;

/**
 * Notifies subscribers what one cycle decided about one participant, and what became of that decision.
 * <p>
 * This is the framework's half of A8's observability: every decision carries an outcome from a closed vocabulary and
 * a free-text reason, and both leave the engine as an event rather than as a log line. It is published <em>once</em>
 * per change - an engine that keeps reaching the same conclusion about an unchanged device re-emits nothing - so a
 * week of shadow decisions can be compared against a week of an existing automation.
 * <p>
 * <strong>An event is not an Item write.</strong> Nothing here commands a device or updates a state; a subscriber
 * that wants either has to do it itself. That is the whole reason this can live in a bundle whose invariant is that
 * it cannot steer anything.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EnergyDecisionEvent extends AbstractEvent {

    /**
     * The event type, as the event bus and its factory know it.
     */
    public static final String TYPE = EnergyDecisionEvent.class.getSimpleName();

    private final EnergyDecisionDTO decision;

    /**
     * Constructs a new energy decision event.
     *
     * @param topic the topic of the event
     * @param payload the payload of the event
     * @param source the source of the event, may be {@code null}
     * @param decision the decision and its outcome
     */
    public EnergyDecisionEvent(String topic, String payload, @Nullable String source, EnergyDecisionDTO decision) {
        super(topic, payload, source);
        this.decision = decision;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    /**
     * Returns the decision and its outcome.
     *
     * @return the payload object
     */
    public EnergyDecisionDTO getDecision() {
        return decision;
    }

    /**
     * Returns the participant the decision addresses.
     *
     * @return the participant id
     */
    public String getParticipantId() {
        return decision.participantId;
    }

    /**
     * Returns what became of the decision - one of the engine's closed outcome vocabulary.
     *
     * @return the outcome name
     */
    public String getStatus() {
        return decision.status;
    }

    /**
     * Returns why, in words.
     *
     * @return the reason, never empty
     */
    public String getReason() {
        return decision.reason;
    }

    @Override
    public String toString() {
        String trim = decision.trimmedFrom == null ? "" : " (trimmed from " + decision.trimmedFrom + ")";
        return decision.status + ": " + decision.participantId + " " + decision.action + trim + " [" + decision.kind
                + ", " + decision.algorithmId + "] - " + decision.reason;
    }
}
