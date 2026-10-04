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
package org.openhab.core.energy.grid;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.window.CostWeights;
import org.openhab.core.energy.window.WindowRequest;

/**
 * One consumer asking for time under a shared power budget.
 * <p>
 * The power figure is whatever the participant's class declares, and {@link PowerFigure} records whether it was
 * declared or borrowed. That distinction is carried rather than hidden because a borrowed figure makes the budget
 * arithmetic quietly wrong in one direction - an on-threshold is set with margin - and the requirement is explicit
 * that such a load is still scheduled and the borrowing reported, never rejected.
 *
 * @param participantId the participant this load belongs to, which is also the tie-break when priorities are equal
 * @param powerW what it draws while running, as an unsigned magnitude in watts
 * @param priority the scheduling rank, where a lower number is the better one
 * @param request how much time it needs
 * @param weights its shape over that time, {@link CostWeights#flat()} for a rectangular load
 * @param powerFigure where {@code powerW} came from
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record BudgetedLoad(String participantId, double powerW, int priority, WindowRequest request,
        CostWeights weights, PowerFigure powerFigure) {

    /** The rank a participant gets when it declares none, fixed by the corpus at 100. */
    public static final int DEFAULT_PRIORITY = 100;

    /** Where a load's power figure came from, which decides whether the budget can trust it. */
    public enum PowerFigure {
        /** The participant declared it, so the budget books exactly what was promised. */
        DECLARED,
        /**
         * No figure was declared and the on-threshold was used instead. Thresholds are set with margin, in either
         * direction, so the booking is approximate and says so.
         */
        BORROWED_FROM_THRESHOLD
    }

    /**
     * Validates the load.
     *
     * @throws IllegalArgumentException if the power is not a finite non-negative magnitude
     */
    public BudgetedLoad {
        if (!Double.isFinite(powerW) || powerW < 0) {
            throw new IllegalArgumentException("powerW must be a finite magnitude but was " + powerW);
        }
    }

    /** A rectangular load whose power figure its participant declared. */
    public static BudgetedLoad declared(String participantId, double powerW, int priority, WindowRequest request) {
        return new BudgetedLoad(participantId, powerW, priority, request, CostWeights.flat(), PowerFigure.DECLARED);
    }

    /** A rectangular load booked against an on-threshold because nothing better was declared. */
    public static BudgetedLoad borrowed(String participantId, double thresholdW, int priority, WindowRequest request) {
        return new BudgetedLoad(participantId, thresholdW, priority, request, CostWeights.flat(),
                PowerFigure.BORROWED_FROM_THRESHOLD);
    }

    /** Whether this load's power figure was borrowed rather than declared. */
    public boolean isBorrowed() {
        return powerFigure == PowerFigure.BORROWED_FROM_THRESHOLD;
    }
}
