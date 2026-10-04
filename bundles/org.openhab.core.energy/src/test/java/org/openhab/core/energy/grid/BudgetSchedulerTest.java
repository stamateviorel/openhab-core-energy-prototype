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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.Instant;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.energy.window.WindowRequest;
import org.openhab.core.energy.window.WindowSelection;

/**
 * {@code grid-constraints} <em>Load balancing under a power budget</em>.
 * <p>
 * The requirement's own fixture pair cannot discriminate a real budget scheduler from a naive exclusion scheduler,
 * because 9 kW and 3 kW under a 10 kW budget can never share a slot whatever the scheduler does. So the scenarios
 * are tested here, and a separate case covers the part the fixture cannot reach: two loads that genuinely fit
 * together, which a scheduler that excludes globally would serialise for no reason.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class BudgetSchedulerTest {

    private static final Instant MIDNIGHT = Instant.parse("2026-09-25T00:00:00Z");

    /**
     * "Boiler and heater never together": 9 kW heating at priority 1 takes the best window, and the 3 kW boiler at
     * priority 2 takes the next best one that does not overlap it, because 9 + 3 crosses the 10 kW budget.
     */
    @Test
    public void aLowerPriorityNumberTakesTheBestWindowAndTheOtherIsSerialised() {
        SlotSeries prices = SlotSeries.hourly(MIDNIGHT, 5, 1, 2, 9, 4);
        BudgetPlan plan = new BudgetScheduler().schedule(prices, 10_000,
                List.of(BudgetedLoad.declared("boiler", 3_000, 2, WindowRequest.ofSlots(1)),
                        BudgetedLoad.declared("heating", 9_000, 1, WindowRequest.ofSlots(1))));

        WindowSelection heating = plan.windowFor("heating").orElseThrow();
        WindowSelection boiler = plan.windowFor("boiler").orElseThrow();

        assertThat(heating.indices(), is(List.of(1)));
        assertThat(boiler.indices(), is(List.of(2)));
        assertThat(heating.indices(), not(hasItem(boiler.indices().get(0))));
        assertThat(plan.isComplete(), is(true));
    }

    /**
     * "A borrowed power figure is reported": the load is scheduled, and the borrowing is reported as a declaration
     * gap rather than becoming a rejection.
     */
    @Test
    public void aBorrowedPowerFigureIsScheduledAndReported() {
        SlotSeries prices = SlotSeries.hourly(MIDNIGHT, 5, 1, 2);
        BudgetPlan plan = new BudgetScheduler().schedule(prices, 10_000,
                List.of(BudgetedLoad.borrowed("fridge", 150, 3, WindowRequest.ofSlots(1))));

        assertThat(plan.windowFor("fridge").orElseThrow().isEmpty(), is(false));
        assertThat(plan.declarationGaps(), is(List.of("fridge")));
    }

    /**
     * The case the requirement's fixture pair cannot reach: two loads that genuinely fit together must be allowed
     * to share a slot. A scheduler that closes a slot as soon as anything is booked there would serialise these,
     * and the budget would be acting as a mutex rather than a limit.
     */
    @Test
    public void loadsThatFitTogetherAreNotSerialisedForNoReason() {
        SlotSeries prices = SlotSeries.hourly(MIDNIGHT, 1, 9, 9, 9);
        BudgetPlan plan = new BudgetScheduler().schedule(prices, 10_000,
                List.of(BudgetedLoad.declared("car", 4_000, 1, WindowRequest.ofSlots(1)),
                        BudgetedLoad.declared("boiler", 3_000, 2, WindowRequest.ofSlots(1))));

        assertThat(plan.windowFor("car").orElseThrow().indices(), is(List.of(0)));
        assertThat(plan.windowFor("boiler").orElseThrow().indices(), is(List.of(0)));
    }

    /** Equal priorities break on participant id, so the same inputs always produce the same plan. */
    @Test
    public void equalPrioritiesBreakOnParticipantId() {
        SlotSeries prices = SlotSeries.hourly(MIDNIGHT, 1, 2, 3);
        BudgetPlan plan = new BudgetScheduler().schedule(prices, 9_000,
                List.of(BudgetedLoad.declared("zebra", 5_000, 1, WindowRequest.ofSlots(1)),
                        BudgetedLoad.declared("aardvark", 5_000, 1, WindowRequest.ofSlots(1))));

        assertThat(plan.windowFor("aardvark").orElseThrow().indices(), is(List.of(0)));
        assertThat(plan.windowFor("zebra").orElseThrow().indices(), is(List.of(1)));
    }

    /**
     * A load too large for the budget at all is answered with the best partial answer the window calculation has,
     * which here is nothing - never with an exception, and never by quietly dropping it from the plan.
     */
    @Test
    public void aLoadThatCannotFitIsAnsweredRatherThanDropped() {
        SlotSeries prices = SlotSeries.hourly(MIDNIGHT, 1, 2);
        BudgetPlan plan = new BudgetScheduler().schedule(prices, 1_000,
                List.of(BudgetedLoad.declared("furnace", 20_000, 1, WindowRequest.ofSlots(1))));

        assertThat(plan.assignments(), hasSize(1));
        assertThat(plan.windowFor("furnace").orElseThrow().isEmpty(), is(true));
        assertThat(plan.isComplete(), is(false));
    }
}
