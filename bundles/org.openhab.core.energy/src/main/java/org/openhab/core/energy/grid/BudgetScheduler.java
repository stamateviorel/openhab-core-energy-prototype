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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.window.SelectionStrategy;
import org.openhab.core.energy.window.SlotSelection;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.energy.window.WindowSelection;

/**
 * Places consumers on the best slots a shared power budget allows, serialising the ones that cannot run together.
 * <p>
 * Two loads that each fit the budget alone but not together are the whole point: a 9 kW heater and a 3 kW boiler
 * under 10 kW can never share an hour, so the budget turns into mutual exclusion and someone has to go second.
 * Priority decides who - a lower number is the better rank, and ties break on participant id so the same inputs
 * always produce the same plan without the scheduler remembering anything between runs.
 * <p>
 * <strong>The exclusion is computed per load rather than once.</strong> A slot is closed to a load when what is
 * already booked there plus what this load draws would cross the budget, which means a small load can still slip
 * into a slot a large one could not. Excluding slots globally as soon as anything is booked would serialise loads
 * that genuinely fit together, and the budget would be doing the work of a mutex instead of a limit.
 * <p>
 * This is the planner's side of the corpus's split: it schedules ahead over a series. The runtime floor inside a
 * cycle is {@code define-engine-contract}'s, it only ever defers, and the deferral is terminal for that cycle.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class BudgetScheduler {

    private final SelectionStrategy strategy;

    /** A scheduler placing each load on its best consecutive window. */
    public BudgetScheduler() {
        this(SelectionStrategy.consecutiveWindow());
    }

    public BudgetScheduler(SelectionStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * Schedules the loads under one total power budget.
     *
     * @param series the ranked series to place them on, cheapest-first by its own sense
     * @param budgetW the total the slots may carry at once, as an unsigned magnitude in watts
     * @param loads the loads competing for them, in any order
     * @return the plan, with one assignment per load offered
     * @throws IllegalArgumentException if the budget is not a finite magnitude
     */
    public BudgetPlan schedule(SlotSeries series, double budgetW, List<BudgetedLoad> loads) {
        if (!Double.isFinite(budgetW) || budgetW < 0) {
            throw new IllegalArgumentException("budgetW must be a finite magnitude but was " + budgetW);
        }
        List<BudgetedLoad> ordered = new ArrayList<>(loads);
        ordered.sort(Comparator.comparingInt(BudgetedLoad::priority).thenComparing(BudgetedLoad::participantId));

        double[] booked = new double[series.size()];
        List<BudgetPlan.Assignment> assignments = new ArrayList<>();
        List<String> gaps = new ArrayList<>();

        for (BudgetedLoad load : ordered) {
            SlotSelection closed = slotsThatCannotCarry(booked, load.powerW(), budgetW);
            WindowSelection window = strategy.select(series, load.request(), closed, load.weights());
            for (int index : window.indices()) {
                booked[index] += load.powerW();
            }
            assignments.add(new BudgetPlan.Assignment(load, window));
            if (load.isBorrowed()) {
                gaps.add(load.participantId());
            }
        }
        return new BudgetPlan(assignments, gaps);
    }

    private static SlotSelection slotsThatCannotCarry(double[] booked, double powerW, double budgetW) {
        List<Integer> closed = new ArrayList<>();
        for (int i = 0; i < booked.length; i++) {
            if (booked[i] + powerW > budgetW) {
                closed.add(i);
            }
        }
        return closed.isEmpty() ? SlotSelection.empty() : new SlotSelection(List.copyOf(closed));
    }
}
