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

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.ProtectionHistory;
import org.openhab.core.types.State;

/**
 * The engine's whole view of the Item world: read a state, optionally tell how old it is, and optionally say whether
 * the site is keeping that Item's history at all.
 * <p>
 * Keeping it this narrow means the snapshot builder can be unit-tested with a map, and it makes visible exactly how
 * little of openHAB the engine spine needs. {@link RegistryItemStateReader} is the production implementation.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface ItemStateReader {

    /**
     * Whether the site is keeping an Item's state history across a restart.
     * <p>
     * This is a question about the site's <em>configuration</em>, not about the Item's value, and it is the only
     * thing that can tell {@link ProtectionHistory#NOT_KEPT} from {@link ProtectionHistory#NO_CHANGE_YET} - an
     * absent {@link ItemStateReader#lastChange} cannot, which is what made D7+'s report unimplementable before D28.
     *
     * @author Stamate Viorel - Initial contribution
     */
    enum HistoryRetention {

        /**
         * Some persistence service is configured to restore this Item on startup, so its last state change survives.
         */
        KEPT,

        /**
         * Nothing restores this Item on startup, so its history is not being kept.
         */
        NOT_KEPT,

        /**
         * The persistence configuration cannot be seen, so neither answer can be given.
         */
        UNKNOWN
    }

    /**
     * Reads the current state of an Item.
     *
     * @param itemName the Item name
     * @return the state, or {@code null} when the Item does not exist or has no usable state
     */
    @Nullable
    State readState(String itemName);

    /**
     * Returns when the Item last received a state update, used to decide whether a safety-relevant measurement has
     * gone stale.
     *
     * @param itemName the Item name
     * @return the moment of the last update, or {@code null} when it is unknown
     */
    default @Nullable Instant lastUpdate(String itemName) {
        return null;
    }

    /**
     * Returns when the Item last changed state, which is where <strong>every</strong> protection duration is measured
     * from: the engine keeps no timers of its own, so a compressor's cooldown survives a restart exactly where the
     * site persists that Item with {@code restoreOnStartup}, and an uncommanded OFF&rarr;ON transition starts the
     * minimum runtime without the engine having to recognise who caused it.
     * <p>
     * {@code null} means the history is unreadable. It is never read as "long enough": the participant is reported as
     * protection-unknown and its clock starts at the first cycle that observed it - see
     * {@link DeviceProtectionAlgorithm}. <strong>Which</strong> of the two conditions produced the {@code null} is a
     * separate question, answered by {@link #retentionOf(String)}, because this method cannot tell them apart.
     *
     * @param itemName the Item name
     * @return the moment of the last state change, or {@code null} when it is unknown
     */
    default @Nullable Instant lastChange(String itemName) {
        return null;
    }

    /**
     * Tells whether the site keeps this Item's state history across a restart, which is what decides whether an
     * absent {@link #lastChange(String)} is a misconfiguration or the ordinary state of a just-restarted system.
     * <p>
     * The default is {@link HistoryRetention#UNKNOWN}: a reader that cannot see the persistence configuration says
     * so rather than guessing, and the participant is then reported as {@link ProtectionHistory#UNDETERMINED}.
     *
     * @param itemName the Item name
     * @return whether that Item's history is being kept
     */
    default HistoryRetention retentionOf(String itemName) {
        return HistoryRetention.UNKNOWN;
    }
}
