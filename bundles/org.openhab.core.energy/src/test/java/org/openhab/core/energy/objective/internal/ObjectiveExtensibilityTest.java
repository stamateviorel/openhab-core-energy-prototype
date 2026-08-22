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
package org.openhab.core.energy.objective.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.EnergyAlgorithmRegistry;
import org.openhab.core.energy.internal.EnergyEngine;
import org.openhab.core.energy.objective.ObjectiveCondition;
import org.openhab.core.energy.objective.ObjectiveInput;
import org.openhab.core.energy.objective.ObjectiveInputs;
import org.openhab.core.energy.objective.ObjectiveResolution;
import org.openhab.core.energy.objective.OptimizationObjective;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSelection;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.energy.window.internal.RankedSlotsSelection;

/**
 * The <em>Objective extensibility</em> requirement: a contributed objective is selectable and plans under the same
 * guardrails as a built-in, through the same whiteboard the engine already uses for algorithms.
 * <p>
 * <strong>This file also records a divergence the framework should not keep.</strong> Objectives register
 * first-wins, refusing a duplicate id with a warning; the algorithm registry next door replaces silently, last-wins.
 * Both are deliberate, both are implemented, and a user who contributes a script algorithm and a script objective in
 * the same file meets both. {@link #objectivesRefuseADuplicateIdWhereAlgorithmsReplaceOne} asserts the divergence
 * rather than papering over it, so that unifying the two is a visible change rather than an accident.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ObjectiveExtensibilityTest {

    /**
     * A contributed objective a script could write with a lambda and a record: battery-wear-aware cost, which is the
     * requirement's own example.
     *
     * @author Stamate Viorel - Initial contribution
     */
    private static final class BatteryWearAwareCost implements OptimizationObjective {

        @Override
        public String getId() {
            return "battery-wear-aware-cost";
        }

        @Override
        public Set<ObjectiveInput> requiredSeries() {
            return Set.of(ObjectiveInput.CONSUMPTION_PRICE);
        }

        @Override
        public Optional<SlotSeries> rank(ObjectiveInputs inputs) {
            // a cheap hour is worth less when the battery would have to cycle for it: flatten the extremes
            return inputs.consumptionPriceSeries().map(BatteryWearAwareCost::flattened);
        }

        /**
         * Flattens the extremes of a price series: a cheap hour is worth less when the battery has to cycle for it.
         *
         * @param prices the price series
         * @return the flattened series, in the same slot geometry and the same sense
         */
        private static SlotSeries flattened(SlotSeries prices) {
            List<Slot> scored = new java.util.ArrayList<>(prices.size());
            for (Slot slot : prices.slots()) {
                scored.add(new Slot(slot.start(), slot.end(), Math.sqrt(slot.value())));
            }
            return new SlotSeries(scored, prices.sense());
        }
    }

    /**
     * Scenario "Contributed objective": a script contributes one, the user selects it, and planning uses it under
     * the same guardrails as the built-ins.
     */
    @Test
    public void aContributedObjectiveIsSelectedAndPlansLikeABuiltIn() {
        ObjectivePlane plane = ObjectiveFixtures.planeWithBuiltIns(Map.of("objective", "battery-wear-aware-cost"));
        assertThat(plane.registerObjective(new BatteryWearAwareCost()), is(true));

        ObjectiveResolution resolution = plane.resolve(ObjectiveFixtures.fullyEquippedSite());

        assertThat(resolution.effectiveId().orElseThrow(), is("battery-wear-aware-cost"));
        assertThat(resolution.isAsRequested(), is(true));
        assertThat("nothing was degraded", resolution.conditions(), is(empty()));

        SlotSeries ranking = resolution.ranking().orElseThrow();
        assertThat("and its ranking answers a window request exactly as a built-in's does",
                new RankedSlotsSelection(false).select(ranking, 2, SlotSelection.empty()).size(), is(2));
    }

    /**
     * Core's own objectives register at a negative service ranking, so a contributed one of the default ranking
     * outranks all three with no configuration at all. That is what the extension surface means by core defaults
     * having no privilege, and the number is core's own convention for it.
     */
    @Test
    public void coreObjectivesRegisterBelowAnyContributedOne() {
        ObjectivePlane plane = ObjectiveFixtures.planeWithBuiltIns(Map.of());
        plane.registerObjective(new BatteryWearAwareCost());

        assertThat(new CostObjective().getServiceRanking(), is(-2));
        assertThat(new SelfConsumptionObjective().getServiceRanking(), is(-2));
        assertThat(new CarbonObjective().getServiceRanking(), is(-2));
        assertThat("the contributed one is offered first", plane.objectives().get(0).getId(),
                is("battery-wear-aware-cost"));
        assertThat("and the built-ins follow in an order that does not depend on registration order",
                plane.objectives().stream().map(OptimizationObjective::getId).toList(),
                contains("battery-wear-aware-cost", CarbonObjective.ID, CostObjective.ID, SelfConsumptionObjective.ID));
    }

    /**
     * Identity is first-registration-wins: a second objective under a taken id is refused, the incumbent keeps the
     * id, and the refusal is reported rather than silently swallowed.
     */
    @Test
    public void aDuplicateIdIsRefusedAndTheIncumbentKeepsIt() {
        ObjectivePlane plane = ObjectiveFixtures.planeWithBuiltIns(Map.of());
        OptimizationObjective incumbent = plane.objective(CostObjective.ID).orElseThrow();

        assertThat(plane.registerObjective(new CostObjective()), is(false));

        assertThat(plane.objective(CostObjective.ID).orElseThrow(), is(sameInstance(incumbent)));
        assertThat(plane.refusedDuplicateIds(), contains(CostObjective.ID));
        assertThat(plane.resolve(ObjectiveFixtures.fullyEquippedSite())
                .reports(ObjectiveCondition.DUPLICATE_OBJECTIVE_REFUSED), is(true));
    }

    /**
     * <strong>The divergence, asserted.</strong> One framework, two whiteboards, opposite duplicate rules: an
     * objective refuses the second registration, an algorithm accepts it and replaces the first. Neither is wrong on
     * its own terms - a reloaded script needs to replace itself, and an id nobody can take over is safer - but a
     * user meets both, and consistency towards the user is the first thing a reviewer weighs. This test is the
     * record of it; unifying the two would change this file.
     */
    @Test
    public void objectivesRefuseADuplicateIdWhereAlgorithmsReplaceOne() {
        ObjectivePlane plane = ObjectiveFixtures.planeWithBuiltIns(Map.of());
        assertThat("objectives: first registration wins", plane.registerObjective(new CostObjective()), is(false));

        EnergyAlgorithmRegistry algorithms = new EnergyEngine(mock(ScheduledExecutorService.class), Clock.systemUTC(),
                itemName -> null, Map.of());
        algorithms.registerAlgorithm("shared-id", 1, context -> List.of());
        algorithms.registerAlgorithm("shared-id", 1, context -> List.of());

        assertThat("algorithms: last registration wins, and the count stays one",
                algorithms.algorithms().stream().filter(algorithm -> "shared-id".equals(algorithm.getId())).count(),
                is(1L));
    }

    /**
     * Withdrawing a contributed objective returns the id, which is what an add-on being uninstalled has to mean.
     */
    @Test
    public void withdrawingAContributedObjectiveReturnsItsId() {
        ObjectivePlane plane = ObjectiveFixtures.planeWithBuiltIns(Map.of());
        plane.registerObjective(new BatteryWearAwareCost());

        assertThat(plane.unregisterObjective("battery-wear-aware-cost"), is(true));
        assertThat(plane.objective("battery-wear-aware-cost").isPresent(), is(false));
        assertThat(plane.registerObjective(new BatteryWearAwareCost()), is(true));
    }

    /**
     * A contributed objective that throws is skipped and reported, exactly as a contributed algorithm is: one
     * add-on's bug never stops a site planning.
     */
    @Test
    public void aContributedObjectiveThatThrowsIsSkippedRatherThanFatal() {
        ObjectivePlane plane = ObjectiveFixtures.planeWithBuiltIns(Map.of("objective", "broken"));
        plane.registerObjective(new OptimizationObjective() {

            @Override
            public String getId() {
                return "broken";
            }

            @Override
            public Set<ObjectiveInput> requiredSeries() {
                return Set.of(ObjectiveInput.CONSUMPTION_PRICE);
            }

            @Override
            public Optional<SlotSeries> rank(ObjectiveInputs inputs) {
                throw new IllegalStateException("this contribution is broken");
            }
        });

        ObjectiveResolution resolution = plane.resolve(ObjectiveFixtures.fullyEquippedSite());

        assertThat("the site keeps planning", resolution.effectiveId().orElseThrow(), is(CostObjective.ID));
        assertThat(resolution.reports(ObjectiveCondition.DEGRADED_TO_COST), is(true));
    }
}
