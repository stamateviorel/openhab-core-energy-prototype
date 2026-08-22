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
package org.openhab.core.energy.publish;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The names of the two Items this bundle owns, so a rule, a sitemap or a UI can refer to them without guessing.
 * <p>
 * Both are supplied by this bundle rather than created by the user: installing the publishing component makes them
 * appear, removing it makes them go away, and nothing else in openHAB writes them. They are the only Items the
 * energy framework has, and they are <em>reports</em> - writing to either of them steers nothing.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class EnergyItems {

    /**
     * The engine's status line: what the last cycle saw and concluded, in one sentence.
     */
    public static final String ENGINE_STATUS = "EnergyEngineStatus";

    /**
     * The current energy level, as the engine derived it from the cycle's own snapshot.
     */
    public static final String CURRENT_LEVEL = "EnergyCurrentLevel";

    /**
     * The state both Items carry until the framework has reported a cycle.
     * <p>
     * It is a real state rather than {@code UNDEF} because it says something an operator needs to be able to tell
     * apart from a fault: the events are deduplicated, so a publishing component that starts after the engine hears
     * nothing until something changes. "Nothing has been reported yet" and "the engine is broken" are different
     * conditions and this is the first one.
     */
    public static final String AWAITING_FIRST_CYCLE = "Awaiting the first reported cycle";

    /**
     * The level while the engine's master stop is engaged.
     * <p>
     * A stopped engine takes no snapshot, so it has no level to report and publishing the last one would be a lie
     * that outlives the fact. Saying so is the honest answer, and it is also the more useful one: a rule keyed on
     * the level stops matching rather than acting on a stale verdict.
     */
    public static final String UNKNOWN_WHILE_STOPPED = "Unknown - the master stop is engaged";

    private EnergyItems() {
    }
}
