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
package org.openhab.core.energy.forecast;

import java.util.Locale;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * What happens when two writers of a layered prediction series land on the same entry.
 * <p>
 * <strong>This enum is an open question, not an answer.</strong> `define-forecast-providers` design.md §2 records the
 * collision and leaves it undecided: _Layered prediction series_ says "the new values replace the old ones for those
 * timestamps", and its very next scenario says the capped entries hold the capped value, so a refresh arriving after
 * a cap silently erases it. The corpus frames three ways out and picks none. All three are here, as configuration,
 * plus the requirement's own literal behaviour - and the framework ships with <em>nothing selected</em>, so a site
 * that has not chosen gets the words of the requirement and a raised
 * {@link ForecastPlaneCondition#CAP_OVERWRITTEN_BY_REFRESH} rather than a silent erasure or an invented rule.
 * <p>
 * <strong>What building them proved, and what it did not.</strong> The decision record's own disposition of this
 * question (Part C · FP-2) holds that two of the three options are not merely worse but <em>unimplementable</em>,
 * "because a published time series carries no writer identity at all". That is exactly right for a series that
 * arrives over the event bus or is read back out of persistence - and it is not the whole picture for writes that go
 * through this framework's own layered surface, which knows which layer is calling because the caller says so. So
 * both write-time options do compile and do work, with one caveat that has to be read as part of the option:
 * <em>the layer bookkeeping lives in memory and does not survive a restart</em>, because persistence stores a number
 * and a timestamp and nothing else. After a restart, a re-applied cap is only as good as a cap the site re-writes,
 * whereas a cap held as its own series is still there. The finding to carry back to the corpus is therefore narrower
 * and sharper than "two options are impossible": they are implementable, they are not durable, and the option that
 * is durable is the one that never writes the cap into the prediction at all.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum LayeredWritePolicy {

    /**
     * The requirement's literal words: whatever arrives last wins the entry.
     * <p>
     * A refresh that lands on a capped entry erases the cap, and the erasure is reported rather than silent. This is
     * what an unconfigured site gets, because it is what the requirement says - not because it is the recommended
     * answer.
     */
    FRESH_OVERWRITES_OLD("fresh-overwrites-old"),

    /**
     * Design §2's first option: the refresh is applied, then every cap it covered is written back on top of it.
     * <p>
     * The prediction ends up capped, and a reader needs to know nothing. The cost is the one above - the caps being
     * re-applied are the ones this framework remembers writing, so a restart between the cap and the refresh loses
     * them.
     */
    REAPPLY_CAPS_AFTER_REFRESH("reapply-caps"),

    /**
     * Design §2's second option: each layer has a rank the site declares, and a write is refused where an entry is
     * already held by a layer of higher rank.
     * <p>
     * No rank ships with the framework. A site that selects this policy and declares no order gets its writes refused
     * and {@link ForecastPlaneCondition#LAYER_PRECEDENCE_UNCONFIGURED} reported, because applying an order nobody
     * chose is the failure this policy exists to prevent.
     */
    WRITER_PRECEDENCE("writer-precedence"),

    /**
     * Design §2's third option: caps are never written into the prediction at all. They are a series of their own, and
     * the effective prediction is the two composed at read time.
     * <p>
     * The one option that survives a restart, since nothing about it depends on remembering who wrote what, and the
     * one that lets a reader see both what was predicted and what the cap did to it. The cost is that a consumer
     * reading the stored prediction directly, without going through this surface, sees the uncapped values.
     */
    CAP_COMPOSED_AT_READ_TIME("cap-at-read-time");

    private final String id;

    LayeredWritePolicy(String id) {
        this.id = id;
    }

    /**
     * Returns the stable configuration id of this policy.
     *
     * @return the policy id
     */
    public String id() {
        return id;
    }

    /**
     * Resolves a policy from its configuration id.
     *
     * @param text the id, case-insensitive, surrounding whitespace ignored
     * @return the policy, or empty if no policy carries that id - which is also what an unconfigured site produces
     */
    public static Optional<LayeredWritePolicy> fromId(String text) {
        String wanted = text.trim().toLowerCase(Locale.ROOT);
        for (LayeredWritePolicy policy : values()) {
            if (policy.id.equals(wanted)) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }
}
