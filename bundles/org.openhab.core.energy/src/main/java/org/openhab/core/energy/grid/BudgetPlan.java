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

import java.util.List;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.window.WindowSelection;

/**
 * What a budget scheduler decided, load by load.
 * <p>
 * A load that could not be placed in full carries a partial window rather than disappearing, because the window
 * calculation always answers with the best partial answer it has; a caller that needs "all or nothing" asks
 * {@link WindowSelection#isComplete()}. Loads whose power figure was borrowed are listed separately, so the
 * declaration gap is reportable without walking the assignments to look for it.
 *
 * @param assignments one entry per load offered, in the order they were scheduled
 * @param declarationGaps the participants whose power figure was borrowed from an on-threshold
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record BudgetPlan(List<Assignment> assignments, List<String> declarationGaps) {

    /**
     * One load's place in the plan.
     *
     * @param load the load as offered
     * @param window the slots it was given, which may be empty or partial
     */
    public record Assignment(BudgetedLoad load, WindowSelection window) {
    }

    public BudgetPlan {
        assignments = List.copyOf(assignments);
        declarationGaps = List.copyOf(declarationGaps);
    }

    /** The window a participant was given, if it was offered to this plan at all. */
    public Optional<WindowSelection> windowFor(String participantId) {
        return assignments.stream().filter(a -> a.load().participantId().equals(participantId)).map(Assignment::window)
                .findFirst();
    }

    /** Whether every load was placed in full. */
    public boolean isComplete() {
        return assignments.stream().allMatch(a -> a.window().isComplete());
    }
}
