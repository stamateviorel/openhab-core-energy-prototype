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
package org.openhab.core.energy.objective;

import java.util.Locale;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * What happens when the selected objective's data plane is not there - the carbon objective selected on a site with
 * no carbon source installed.
 * <p>
 * <strong>This is an open question in the corpus and it is not answered here.</strong> The objectives design file
 * frames exactly three options and records no decision between them; all three are implemented, the choice is one
 * configuration value, and nothing in the code prefers one on the merits. What the shipped default follows is not a
 * verdict on the question but the disposition already on record elsewhere - fall back to cost and report the degraded
 * source - which is also the shape the extension surface's graceful-degradation requirement mandates for the harder
 * case of a source that vanishes after selection. If the question is answered the other way, the change is one
 * default in one configuration description.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum AbsentDataPlanePolicy {

    /**
     * Fall back to the cost objective and report the degradation.
     * <p>
     * The site keeps planning, on the metric every planning requirement in the corpus assumed before the objective
     * became selectable. If the cost objective has no price series either, nothing ranks and the level plane's own
     * answer for an absent plan takes over - normal everywhere, plus surplus escalation.
     */
    FALL_BACK_TO_COST("fall-back"),

    /**
     * Do not offer an objective whose data plane is absent, and fall back if it is selected anyway.
     * <p>
     * The difference from {@link #FALL_BACK_TO_COST} is what a user is shown, not what a planner does: an objective
     * filtered out of the offered set cannot be chosen by mistake, and one that was chosen before its source was
     * uninstalled still has to do something.
     */
    HIDE_UNAVAILABLE("hide"),

    /**
     * Refuse the selection: rank nothing at all rather than rank on a metric the user did not choose.
     * <p>
     * The most conservative reading, and the one that never silently optimizes for something other than what was
     * asked for. The cost is that a site whose carbon feed is down stops planning altogether until the feed returns
     * or the objective is changed.
     */
    REFUSE_SELECTION("refuse");

    private final String id;

    AbsentDataPlanePolicy(String id) {
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
     * @return the policy, or empty if no policy carries that id
     */
    public static Optional<AbsentDataPlanePolicy> parse(String text) {
        String normalised = text.trim().toLowerCase(Locale.ROOT);
        for (AbsentDataPlanePolicy policy : values()) {
            if (policy.id.equals(normalised)) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }
}
