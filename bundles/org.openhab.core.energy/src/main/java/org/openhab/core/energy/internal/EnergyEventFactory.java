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
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.events.EnergyCycleDTO;
import org.openhab.core.energy.events.EnergyCycleEvent;
import org.openhab.core.energy.events.EnergyDecisionDTO;
import org.openhab.core.energy.events.EnergyDecisionEvent;
import org.openhab.core.events.AbstractEventFactory;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventFactory;
import org.osgi.service.component.annotations.Component;

/**
 * Creates the two event types this bundle publishes, and re-creates them from a topic and a JSON payload when they
 * come back off the bus.
 * <p>
 * The factory is registered as an {@code EventFactory} service for the second half of that: without it an event that
 * has crossed the bus arrives as an opaque payload, and a subscriber - the {@code org.openhab.core.energy.publish}
 * bundle above all - could not read a level or an outcome out of it. Serialization itself is inherited: the JSON is
 * produced and parsed by {@link AbstractEventFactory}, so this bundle names no JSON library of its own.
 * <p>
 * The topics follow core's own shape, {@code openhab/<namespace>/<entity>/<action>}, so a subscriber can filter with
 * the ordinary topic filters rather than by parsing a payload.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(service = EventFactory.class, immediate = true)
public class EnergyEventFactory extends AbstractEventFactory {

    /**
     * The topic one decision's outcome is published on, the participant id filling the placeholder.
     */
    public static final String DECISION_EVENT_TOPIC = "openhab/energy/{participantId}/decision";

    /**
     * The topic a cycle's summary is published on.
     */
    public static final String CYCLE_EVENT_TOPIC = "openhab/energy/engine/cycle";

    private static final Set<String> SUPPORTED_TYPES = Set.of(EnergyDecisionEvent.TYPE, EnergyCycleEvent.TYPE);

    /**
     * Creates the factory with the two types it supports.
     */
    public EnergyEventFactory() {
        super(SUPPORTED_TYPES);
    }

    @Override
    protected Event createEventByType(String eventType, String topic, String payload, @Nullable String source) {
        if (EnergyDecisionEvent.TYPE.equals(eventType)) {
            return new EnergyDecisionEvent(topic, payload, source,
                    deserializePayload(payload, EnergyDecisionDTO.class));
        }
        if (EnergyCycleEvent.TYPE.equals(eventType)) {
            return new EnergyCycleEvent(topic, payload, source, deserializePayload(payload, EnergyCycleDTO.class));
        }
        throw new IllegalArgumentException("The event type '" + eventType + "' is not supported by this factory.");
    }

    /**
     * Creates the event announcing what became of one decision.
     *
     * @param decision the decision and its outcome
     * @return the event, ready to post
     */
    public static EnergyDecisionEvent createDecisionEvent(EnergyDecisionDTO decision) {
        String topic = DECISION_EVENT_TOPIC.replace("{participantId}", decision.participantId);
        return new EnergyDecisionEvent(topic, serializePayload(decision), null, decision);
    }

    /**
     * Creates the event summarising one cycle.
     *
     * @param cycle what the cycle saw and concluded
     * @return the event, ready to post
     */
    public static EnergyCycleEvent createCycleEvent(EnergyCycleDTO cycle) {
        return new EnergyCycleEvent(CYCLE_EVENT_TOPIC, serializePayload(cycle), null, cycle);
    }
}
