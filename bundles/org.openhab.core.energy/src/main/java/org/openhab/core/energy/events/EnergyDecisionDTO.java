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

/**
 * The payload of an {@link EnergyDecisionEvent}: one decision and what the cycle did with it.
 * <p>
 * A DTO in the openHAB sense - public mutable fields, a no-argument constructor, no {@code @NonNullByDefault} (the
 * review checklist exempts DTOs) - because it is serialized to and from JSON by the event bus. Everything in it is a
 * {@code String}, a primitive or absent, so a rule reading it over SSE needs no knowledge of this bundle's types.
 *
 * @author Stamate Viorel - Initial contribution
 */
public class EnergyDecisionDTO {

    /**
     * The participant the decision addresses.
     */
    public String participantId;

    /**
     * The algorithm that proposed it.
     */
    public String algorithmId;

    /**
     * The strength of the proposal, lower is stronger.
     */
    public int priority;

    /**
     * Why it was proposed - the rung of the constraint ladder it claims.
     */
    public String kind;

    /**
     * What became of it: one of the engine's closed outcome vocabulary.
     */
    public String status;

    /**
     * The action as it ended up, rendered.
     */
    public String action;

    /**
     * The action as proposed, rendered, when the electrical-limit floor trimmed it - otherwise absent.
     */
    public String trimmedFrom;

    /**
     * Why, in words. Never empty: an outcome with nothing of its own to say falls back to the algorithm's reason and
     * then to the outcome name.
     */
    public String reason;

    /**
     * The instant of the snapshot the deciding cycle ran against, ISO-8601.
     */
    public String cycle;

    /**
     * Whether the cycle that produced it was running in shadow mode.
     */
    public boolean shadow;

    /**
     * Creates an empty DTO, as the JSON deserializer needs.
     */
    public EnergyDecisionDTO() {
        participantId = "";
        algorithmId = "";
        kind = "";
        status = "";
        action = "";
        reason = "";
        cycle = "";
    }
}
