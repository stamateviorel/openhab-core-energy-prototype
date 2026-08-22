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
 * Notifies subscribers what one evaluation cycle saw and concluded: the level in force, whether the site was frozen
 * on stale measurements or halted by the master stop, how the cycle's decisions came out, and which participants are
 * being steered with a gap in their declaration.
 * <p>
 * Two things ride on this event that nothing else carries. The <strong>current level</strong> is computed by the
 * engine from the cycle's own snapshot and published here, which is how a level Item can exist without the engine
 * writing one. And the <strong>summary</strong> is what a status Item is made of - the same participant conditions
 * the engine's configuration-status report carries, in the same vocabulary, so the pull surface and the push surface
 * never disagree.
 * <p>
 * Like the decision event, this is published on change rather than on every tick: an unchanged site with an
 * unchanged verdict emits nothing.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EnergyCycleEvent extends AbstractEvent {

    /**
     * The event type, as the event bus and its factory know it.
     */
    public static final String TYPE = EnergyCycleEvent.class.getSimpleName();

    private final EnergyCycleDTO cycle;

    /**
     * Constructs a new energy cycle event.
     *
     * @param topic the topic of the event
     * @param payload the payload of the event
     * @param source the source of the event, may be {@code null}
     * @param cycle what the cycle saw and concluded
     */
    public EnergyCycleEvent(String topic, String payload, @Nullable String source, EnergyCycleDTO cycle) {
        super(topic, payload, source);
        this.cycle = cycle;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    /**
     * Returns what the cycle saw and concluded.
     *
     * @return the payload object
     */
    public EnergyCycleDTO getCycle() {
        return cycle;
    }

    /**
     * Returns the energy level in force for the cycle.
     *
     * @return the level name
     */
    public String getLevel() {
        return cycle.level;
    }

    /**
     * Tells whether the master stop was engaged for the cycle.
     *
     * @return {@code true} if nothing was read, evaluated or enforced
     */
    public boolean isStopped() {
        return cycle.stopped;
    }

    /**
     * Tells whether the cycle only logged its decisions.
     *
     * @return {@code true} if shadow mode was in force
     */
    public boolean isShadow() {
        return cycle.shadow;
    }

    @Override
    public String toString() {
        if (cycle.stopped) {
            return "Energy cycle at " + cycle.timestamp + ": halted by the master stop";
        }
        return "Energy cycle at " + cycle.timestamp + ": level " + cycle.level + ", " + cycle.participants
                + " participants" + (cycle.shadow ? ", shadow" : "")
                + (cycle.measurementsStale ? ", measurements stale" : "")
                + (cycle.outcomes.isEmpty() ? ", nothing to decide" : ", " + cycle.outcomes);
    }
}
