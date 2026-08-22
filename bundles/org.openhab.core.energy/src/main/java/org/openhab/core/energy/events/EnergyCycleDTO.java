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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The payload of an {@link EnergyCycleEvent}: what one evaluation cycle saw and what it concluded, without the
 * decisions themselves - those travel one per {@link EnergyDecisionEvent}.
 * <p>
 * This is the summary a status Item is made of, and it is also how the current level leaves the engine. The engine
 * computes the level from the same one-cycle snapshot it reasons about and publishes it here; whoever wants that
 * level <em>as an Item</em> subscribes and writes one, which is exactly what the separate
 * {@code org.openhab.core.energy.publish} bundle does.
 * <p>
 * A DTO in the openHAB sense - public mutable fields, a no-argument constructor, no {@code @NonNullByDefault} (the
 * review checklist exempts DTOs) - because it is serialized to and from JSON by the event bus.
 *
 * @author Stamate Viorel - Initial contribution
 */
public class EnergyCycleDTO {

    /**
     * The instant the cycle's snapshot was taken at, ISO-8601.
     */
    public String timestamp;

    /**
     * The energy level in force for the cycle, derived from that same snapshot.
     */
    public String level;

    /**
     * Whether decisions were only logged rather than dispatched.
     */
    public boolean shadow;

    /**
     * Whether the master stop was engaged, in which case nothing was read, evaluated or enforced.
     */
    public boolean stopped;

    /**
     * Whether a safety input was too old to reason on, which freezes the site rather than optimizing blind.
     */
    public boolean measurementsStale;

    /**
     * How many participants the cycle knew about.
     */
    public int participants;

    /**
     * How many decisions ended in each outcome, keyed by outcome name. Outcomes nothing reached are absent.
     */
    public Map<String, Integer> outcomes;

    /**
     * The participants the engine is steering with a gap in their declaration, keyed by participant id, each with the
     * gap in words. The same set the engine's configuration-status report carries, so a reader of either surface sees
     * the same conditions in the same vocabulary.
     */
    public Map<String, String> participantGaps;

    /**
     * Creates an empty DTO, as the JSON deserializer needs.
     */
    public EnergyCycleDTO() {
        timestamp = "";
        level = "";
        outcomes = new LinkedHashMap<>();
        participantGaps = new LinkedHashMap<>();
    }
}
