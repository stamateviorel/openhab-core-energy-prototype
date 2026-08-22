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

import java.util.Comparator;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.DecisionKind;

/**
 * The one fixed constraint ladder, written out once so that the order is readable rather than emergent.
 *
 * <pre>
 * master stop  &gt;  electrical limits  &gt;  device protections  &gt;  level gates  &gt;  optimization
 * </pre>
 *
 * The master stop is not a rung here because it is not a decision kind: it is enforced in
 * {@link EnergyEngine#runCycleNow()}, which returns before a snapshot is taken, before any algorithm is invoked
 * and therefore before any decision of any kind exists. Everything below it is ranked by {@link #rank(DecisionKind)}
 * and nothing may reorder it - no configuration parameter selects a different ladder and no contributed service can
 * supply one. That is a deliberate reduction in flexibility: the prototype shipped this as a contributable
 * {@code PrecedenceStrategy} with a second, protections-first implementation, and both are gone.
 * <p>
 * <strong>The ladder states its own two exceptions</strong>, so that a reader does not have to derive them by
 * holding two requirements against each other: a Batch programme already running and a consumer its owner marked
 * hands-off are <em>booked</em> by the electrical rung as load the others are trimmed against, never shed. They live
 * in {@link ElectricalLimitFloor}, which is the rung they belong to.
 * <p>
 * <strong>Where this order is used, and where priority takes over.</strong> The ladder ranks a decision's
 * <em>kind</em>; within one rung the ordinary priority order applies, better priority first. Both uses fall out of
 * {@link #STRENGTH_ORDER}:
 * <ul>
 * <li>{@link EvaluationPass} resolves two decisions about the <em>same</em> participant with it - a protection beats
 * an optimization on that device however good the optimizer's priority is;</li>
 * <li>{@link ElectricalLimitFloor} hands out headroom in the same order, so an overload is resolved from the bottom
 * of the ladder upwards and, inside a rung, worst-priority-first. Whether a load happens to be trimmable never
 * enters the ordering.</li>
 * </ul>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
final class ConstraintLadder {

    /**
     * The total order the ladder induces on decisions: strongest rung first, then {@link Decision#PRIORITY_ORDER},
     * which breaks every remaining tie on the decision's own content and therefore carries nothing between cycles.
     */
    static final Comparator<Decision> STRENGTH_ORDER = (left, right) -> {
        int byRung = Integer.compare(rank(left.kind()), rank(right.kind()));
        return byRung != 0 ? byRung : Decision.PRIORITY_ORDER.compare(left, right);
    };

    private ConstraintLadder() {
    }

    /**
     * Returns the rung a decision kind sits on. A numerically lower rung is stronger.
     *
     * @param kind the kind to rank
     * @return the rung
     */
    static int rank(DecisionKind kind) {
        return switch (kind) {
            case ELECTRICAL_LIMIT -> 0;
            case DEVICE_PROTECTION -> 1;
            case LEVEL_GATE -> 2;
            case OPTIMIZATION -> 3;
        };
    }
}
