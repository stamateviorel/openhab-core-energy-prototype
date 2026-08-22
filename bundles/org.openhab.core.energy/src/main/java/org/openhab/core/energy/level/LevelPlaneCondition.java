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
package org.openhab.core.energy.level;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Something the level plane cannot do and has to say so about, rather than doing nothing quietly.
 * <p>
 * Both members of this enum exist because a requirement says the absence of a thing must be <em>visible</em>. Neither
 * is an error and neither stops the engine: the level plane keeps answering, and a condition is what says the answer
 * came from a gap rather than from data.
 * <p>
 * These are machine-readable on purpose. A log line is not a report - whoever owns the observability surface
 * (one status Item, the participant condition report) needs a value it can key on, and a UI needs something to render
 * as "not configured" rather than as silence.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum LevelPlaneCondition {

    /**
     * No planned schedule covers the moment being resolved - before the first prices arrive, after the plan's last
     * slot, or inside a gap in a non-uniform series.
     * <p>
     * The level reads normal while this holds. That is a decided answer, not a fallback nobody chose, and reporting
     * it separately is what keeps a missing price feed distinguishable from a genuinely normal hour.
     */
    PLAN_ABSENT,

    /**
     * The site has declared no {@code encouragedFrom} surplus threshold, so the current level never escalates above
     * its planned value.
     * <p>
     * There is deliberately no shipped default: the right number depends on the site's PV size and load mix, and
     * inventing one would escalate at a value nobody chose. The requirement is therefore live but inert until a site
     * supplies the threshold, and this condition is what makes "inert" readable as "unconfigured".
     */
    ESCALATION_UNCONFIGURED
}
