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

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The replaceable part of the engine: a pure function from one cycle's {@link EnergyContext} to the decisions it
 * proposes.
 * <p>
 * Everything <em>around</em> this function stays engine-owned - the snapshot, conflict resolution, the
 * electrical-limit floor, shadow gating, the master stop and the acknowledgement window - so a user-supplied
 * algorithm runs under exactly the same guardrails as the built-in one.
 * <p>
 * The interface is deliberately <strong>functional</strong> and free of OSGi types, wildcards and openHAB type
 * hierarchies: {@link #evaluate} is the single abstract method, its argument is one immutable value and its result
 * is a plain {@code List}. A rule script can implement it with a lambda and hand it to the engine's
 * {@code registerAlgorithm(id, priority, algorithm)} entry point; an add-on implements it as a class and publishes
 * it as an OSGi service. Both paths end up in the same evaluation pass.
 * <p>
 * Implementations must not block, must not write to Items, and must tolerate being called with an empty snapshot.
 * An implementation that throws is skipped for that cycle and logged; the rest of the cycle continues.
 *
 * @author Stamate Viorel - Initial contribution
 */
@FunctionalInterface
@NonNullByDefault
public interface EnergyAlgorithm {

    /**
     * The priority an algorithm gets when it declares none.
     */
    int DEFAULT_PRIORITY = 100;

    /**
     * Proposes what should happen this cycle.
     *
     * @param context the one immutable snapshot of the cycle, shared with every other algorithm
     * @return the proposed decisions, possibly empty, never {@code null}
     */
    List<Decision> evaluate(EnergyContext context);

    /**
     * Returns the stable id of this algorithm, used to order algorithms deterministically, to tag every decision it
     * proposes, and to address it in the per-algorithm shadow configuration.
     * <p>
     * The default is the implementation's simple class name, which is meaningless for a lambda - a script should
     * register through the engine's {@code registerAlgorithm(id, priority, algorithm)} overload, which supplies the
     * identity separately.
     *
     * @return the algorithm id
     */
    default String getId() {
        return getClass().getSimpleName();
    }

    /**
     * Returns the priority this algorithm's decisions carry when they do not state one themselves. A numerically
     * lower value is stronger.
     *
     * @return the default priority of this algorithm's decisions
     */
    default int getPriority() {
        return DEFAULT_PRIORITY;
    }
}
