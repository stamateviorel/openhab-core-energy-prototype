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
 * Where a participant's protection clock is coming from this cycle, and - when it is not the device's own history -
 * <strong>which</strong> of the two very different reasons applies.
 * <p>
 * Every protection duration is measured from the steered Item's last state change. {@code Item.getLastStateChange()}
 * returns nothing in two cases that need opposite responses from a site:
 * <ul>
 * <li>no persistence service is keeping that Item's history, so nothing will restore it and the guarantee will not
 * survive a restart - a misconfiguration the site can fix in one line;</li>
 * <li>the history is kept and simply holds no state change yet, which is the ordinary state of affairs for the first
 * minutes after a restart and needs nothing done at all.</li>
 * </ul>
 * Reporting a bare "protection unknown" for both names a condition the user cannot act on, because one of the two is
 * not a fault. Telling them apart cannot be done from the absent timestamp: it means asking the persistence
 * configuration whether that Item is restored on startup, which is the whole reason this bundle takes a dependency
 * on {@code org.openhab.core.persistence}.
 * <p>
 * Source: owner decision D28 ({@code openhab-ems-spec/docs/OWNER_DECISIONS.md}), answering the wave-1 slice's report
 * that D7+'s "report a participant whose state is not persisted" was not implementable as written. The alternatives
 * are preserved there: keep the weaker protection-unknown report, or drop the clause entirely.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum ProtectionHistory {

    /**
     * The steered Item's own last state change is readable, so every protection is measured from the device. The
     * ordinary case, and the only one that is not degraded.
     */
    FROM_DEVICE(false),

    /**
     * No persistence service is configured to restore that Item on startup, so its history is not being kept: the
     * protection is running on the first-observation clock and will start over on every restart. A misconfiguration
     * the site can close by persisting the Item with {@code restoreOnStartup}.
     */
    NOT_KEPT(true),

    /**
     * The history is kept and holds no state change yet - the harmless just-restarted case. The protection is still
     * on the first-observation clock, but nothing is wrong and there is nothing to fix.
     */
    NO_CHANGE_YET(true),

    /**
     * The engine cannot tell the two apart, because it cannot see the persistence configuration at all. Reported as
     * exactly that rather than guessed at either way.
     */
    UNDETERMINED(true);

    private final boolean degraded;

    ProtectionHistory(boolean degraded) {
        this.degraded = degraded;
    }

    /**
     * Tells whether the protection is running on the first-observation clock rather than on the device's own history.
     *
     * @return {@code true} for every condition other than {@link #FROM_DEVICE}
     */
    public boolean isDegraded() {
        return degraded;
    }

    /**
     * Tells whether this condition is something the site can fix.
     * <p>
     * Only {@link #NOT_KEPT} is: {@link #NO_CHANGE_YET} needs nothing done, and {@link #UNDETERMINED} says the engine
     * does not know which of the two it is looking at.
     *
     * @return {@code true} if a site can close this condition by configuring persistence
     */
    public boolean isFixable() {
        return this == NOT_KEPT;
    }
}
