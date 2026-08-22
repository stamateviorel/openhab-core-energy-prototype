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
package org.openhab.core.energy;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Why a {@link Decision} was proposed. The kind is what the engine's constraint ladder ranks when two decisions
 * collide, and what tells the electrical-limit floor whether it may trim a decision away.
 * <p>
 * <strong>The relative strength of these kinds is fixed</strong>, in one place inside the engine:
 * <em>electrical limits &gt; device protections &gt; level gates &gt; optimization</em>, with the master stop above
 * all of it. No configuration value and no contributed service can reorder it - the prototype shipped that ordering
 * as a swappable strategy with a second, protections-first implementation, and both are gone. This enum still only
 * <em>labels</em> decisions; it does not carry the order.
 * <p>
 * The declaration order below deliberately carries no meaning. Do not compare ordinals.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum DecisionKind {

    /**
     * A decision that exists to keep the site within its declared electrical limits, e.g. a throttle proposed by a
     * peak-shaving algorithm.
     */
    ELECTRICAL_LIMIT,

    /**
     * A decision that exists to honour a declared device protection - minimum runtime, cooldown, or the
     * duty-cycle guarantee of {@link SimpleProfile#maxOff()}.
     */
    DEVICE_PROTECTION,

    /**
     * A decision that exists to honour a {@link LevelGate} against the current site {@link EnergyLevel}.
     */
    LEVEL_GATE,

    /**
     * A decision that exists to optimize - cost, self-consumption, comfort. The default kind for an algorithm that
     * does not say otherwise.
     */
    OPTIMIZATION
}
