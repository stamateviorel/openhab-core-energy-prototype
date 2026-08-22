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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.openhab.core.energy.internal.EngineTestFixtures.simple;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.ElectricalLimits;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.ParticipantState;
import org.openhab.core.energy.level.FixtureCsv;

/**
 * The acceptance fixtures of @masipila's worked example, replayed through the runtime floor: 9 kW heating for 8 h
 * at priority 1 and a 3 kW boiler for 3 h at priority 2, under a 10 kW budget.
 * <p>
 * Two things are proven here, and one thing is deliberately <em>not</em>:
 * <ul>
 * <li>fed its own plan, the floor dispatches both control series unchanged - it never trims a plan that already
 * fits, which is the failure mode a safety floor most easily falls into;</li>
 * <li>fed a naive plan that puts both loads in the same cheap slots, the floor keeps the priority-1 load and defers
 * the priority-2 one, so no slot ever exceeds the budget - the "zero overlap with the heating schedule" property
 * the fixture README describes.</li>
 * </ul>
 * What the floor cannot do is <em>reproduce</em> {@code expected-boiler-control.csv} from a naive plan: moving the
 * boiler to the next-best hours is planning, not runtime enforcement, and it belongs to the {@code grid-constraints}
 * capability that is out of this slice. The fixture README attributes that file to {@code grid-constraints} and to
 * {@code engine-contract}'s conflict resolution together, and this test marks exactly where the line runs.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class BudgetFixtureConformanceTest {

    private static final double BUDGET_WATTS = 10000;
    private static final EnergyConsumer HEATING = simple("heating", 1, 9000);
    private static final EnergyConsumer BOILER = simple("boiler", 2, 3000);

    private final ElectricalLimitFloor floor = new ElectricalLimitFloor(new PowerEstimator(230));

    private EnergyContext slotContext(Instant slot) {
        return EnergyContext.builder(slot, EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(BUDGET_WATTS))
                .uncontrolledWatts(0).participant(ParticipantState.of(HEATING)).participant(ParticipantState.of(BOILER))
                .build();
    }

    private static List<Decision> proposals(boolean heatingOn, boolean boilerOn) {
        return List.of(Decision.of("heating", heatingOn ? ControlAction.on() : ControlAction.off(), "fixture-plan", 1),
                Decision.of("boiler", boilerOn ? ControlAction.on() : ControlAction.off(), "fixture-plan", 2));
    }

    private static Set<String> switchedOn(ElectricalLimitFloor.Result result) {
        Set<String> on = new LinkedHashSet<>();
        for (ElectricalLimitFloor.Admission admission : result.admitted()) {
            if (admission.decision().action() instanceof ControlAction.Switch onOff && onOff.on()) {
                on.add(admission.decision().participantId());
            }
        }
        return on;
    }

    @Test
    public void theFixturePlanIsDispatchedUnchanged() {
        List<FixtureCsv.Row> heating = FixtureCsv.read("/fixtures/expected-heating-control.csv");
        List<FixtureCsv.Row> boiler = FixtureCsv.read("/fixtures/expected-boiler-control.csv");
        assertThat(heating, hasSize(24));

        List<Integer> dispatchedHeating = new ArrayList<>();
        List<Integer> dispatchedBoiler = new ArrayList<>();
        for (int slot = 0; slot < heating.size(); slot++) {
            boolean heatingOn = heating.get(slot).value() > 0;
            boolean boilerOn = boiler.get(slot).value() > 0;
            ElectricalLimitFloor.Result result = floor.apply(slotContext(heating.get(slot).timestamp()),
                    proposals(heatingOn, boilerOn));

            assertThat("slot " + slot + " must not defer anything", result.rejected(), is(empty()));
            Set<String> on = switchedOn(result);
            dispatchedHeating.add(on.contains("heating") ? 1 : 0);
            dispatchedBoiler.add(on.contains("boiler") ? 1 : 0);
            assertThat("slot " + slot + " must stay within the budget",
                    (on.contains("heating") ? 9000 : 0) + (on.contains("boiler") ? 3000 : 0), lessThanOrEqualTo(10000));
        }

        assertThat(dispatchedHeating, is(heating.stream().map(row -> (int) row.value()).toList()));
        assertThat(dispatchedBoiler, is(boiler.stream().map(row -> (int) row.value()).toList()));
    }

    @Test
    public void aNaivePlanIsTrimmedToThePriorityOrderRatherThanToTheBudgetBeingBroken() {
        List<FixtureCsv.Row> heating = FixtureCsv.read("/fixtures/expected-heating-control.csv");
        List<FixtureCsv.Row> prices = FixtureCsv.read("/fixtures/dayahead-prices.csv");
        Set<Instant> naiveBoilerSlots = prices.stream().sorted(Comparator.comparingDouble(FixtureCsv.Row::value))
                .limit(3).map(FixtureCsv.Row::timestamp).collect(java.util.stream.Collectors.toSet());

        int collisions = 0;
        for (int slot = 0; slot < heating.size(); slot++) {
            Instant timestamp = heating.get(slot).timestamp();
            boolean heatingOn = heating.get(slot).value() > 0;
            boolean boilerOn = naiveBoilerSlots.contains(timestamp);
            ElectricalLimitFloor.Result result = floor.apply(slotContext(timestamp), proposals(heatingOn, boilerOn));
            Set<String> on = switchedOn(result);

            if (heatingOn && boilerOn) {
                collisions++;
                assertThat("the priority-1 load keeps running in slot " + slot, on, hasItem("heating"));
                assertThat("the priority-2 load is deferred in slot " + slot, on, not(hasItem("boiler")));
                assertThat(result.rejected().get(0).decision().participantId(), is("boiler"));
                assertThat(result.rejected().get(0).status(), is(DecisionStatus.DEFERRED));
            }
            assertThat("slot " + slot + " must stay within the budget",
                    (on.contains("heating") ? 9000 : 0) + (on.contains("boiler") ? 3000 : 0), lessThanOrEqualTo(10000));
        }
        assertThat("the naive plan must actually collide with the heating schedule", collisions, is(3));
    }
}
