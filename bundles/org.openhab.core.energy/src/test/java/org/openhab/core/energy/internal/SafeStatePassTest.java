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
import static org.openhab.core.energy.internal.EngineTestFixtures.*;

import java.time.Duration;
import java.util.List;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.BatchProfile;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.ControllableProfile;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.ElectricalLimits;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.LevelGate;
import org.openhab.core.energy.ModeControllableProfile;
import org.openhab.core.energy.ParticipantState;
import org.openhab.core.energy.SimpleProfile;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * Freeze and floor: what the engine does while a reading it counts as a safety input is stale.
 * <p>
 * The freeze half - refusing increases - lives in the electrical-limit floor and is asserted there. What is asserted
 * here is the floor half, one case per profile class, plus the three things it must <em>not</em> do. Getting the
 * protection case backwards would shed a compressor inside its minimum runtime every time a measurement bridge went
 * quiet, which is a safety bug rather than a behavioural preference.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class SafeStatePassTest {

    private static final String TEST_ALGORITHM = "test";

    private final SafeStatePass safeState = new SafeStatePass();

    private static EnergyContext.Builder stale(boolean measurementsStale) {
        return context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(20000)).measurementsStale(measurementsStale);
    }

    private static ControlAction actionFor(List<ElectricalLimitFloor.Admission> admissions, String participantId) {
        return admissions.stream().filter(admission -> participantId.equals(admission.decision().participantId()))
                .map(admission -> admission.decision().action()).findFirst().orElseThrow();
    }

    @Test
    public void aControllableLoadIsFlooredAtItsDeclaredMinimum() {
        EnergyConsumer wallbox = wallbox("wallbox", 1, 6, 32);
        EnergyContext context = stale(true).participant(ParticipantState.of(wallbox).withReportedState("32 A")).build();

        List<ElectricalLimitFloor.Admission> result = safeState.apply(context, List.of());

        assertThat(result, hasSize(1));
        assertThat(actionFor(result, "wallbox"), is(ControlAction.amperes(6)));
    }

    @Test
    public void aModeControllableLoadDropsToItsMostRestrictedMode() {
        EnergyConsumer heatPump = EnergyConsumer.of("heatpump", "HeatPump_Mode",
                ModeControllableProfile.of("blocked", "normal", "encouraged", "forced"), 1);
        EnergyContext context = stale(true).participant(ParticipantState.of(heatPump).withReportedState("forced"))
                .build();

        List<ElectricalLimitFloor.Admission> result = safeState.apply(context, List.of());

        assertThat(actionFor(result, "heatpump"), is(ControlAction.mode("blocked")));
    }

    @Test
    public void aSimpleLoadIsSwitchedOff() {
        EnergyConsumer heating = simple("heating", 1, 9000);
        EnergyContext context = stale(true).participant(ParticipantState.of(heating).withReportedState("ON")).build();

        List<ElectricalLimitFloor.Admission> result = safeState.apply(context, List.of());

        assertThat(actionFor(result, "heating"), is(ControlAction.off()));
    }

    /**
     * The whole thing is subject to device protections: a Simple load inside its declared minimum runtime is
     * <strong>held</strong>, not shed. The freeze still applies immediately - increases are refused by the floor -
     * while the shedding waits for the protection to expire.
     */
    @Test
    public void aProtectionHoldsALoadTheSafeStateWouldOtherwiseShed() {
        EnergyConsumer compressor = EnergyConsumer.of("compressor", "Compressor_Switch",
                new SimpleProfile(new QuantityType<Power>(900, Units.WATT), null, Duration.ofMinutes(15), null, null,
                        null, LevelGate.always()),
                1);
        EnergyContext context = stale(true).participant(ParticipantState.of(compressor).withReportedState("ON")
                .withLastChangedAt(T0.minus(Duration.ofMinutes(5)))).build();

        assertThat(safeState.apply(context, List.of()), is(empty()));
    }

    /**
     * The same hold on a site that does not persist the compressor's Item: the protection runs on the
     * first-observation clock, and the safe state reads that clock rather than treating an unreadable history as no
     * protection at all.
     */
    @Test
    public void aProtectionOnTheFirstObservationClockHoldsTheLoadHereToo() {
        EnergyConsumer compressor = EnergyConsumer.of("compressor", "Compressor_Switch",
                new SimpleProfile(new QuantityType<Power>(900, Units.WATT), null, Duration.ofMinutes(15), null, null,
                        null, LevelGate.always()),
                1);
        EnergyContext context = stale(true).participant(ParticipantState.of(compressor).withReportedState("ON")
                .withFirstObservedAt(T0.minus(Duration.ofMinutes(5)))).build();

        assertThat(safeState.apply(context, List.of()), is(empty()));
    }

    @Test
    public void aRunningBatchProgrammeIsNeverInterrupted() {
        EnergyConsumer dishwasher = EnergyConsumer.of("dishwasher", "Dishwasher_Switch",
                new BatchProfile(new QuantityType<Power>(2000, Units.WATT), Duration.ofHours(2), null), 1);
        EnergyContext context = stale(true).participant(ParticipantState.of(dishwasher).withReportedState("ON"))
                .build();

        assertThat(safeState.apply(context, List.of()), is(empty()));
    }

    @Test
    public void aHandsOffLoadIsNotShedByTheSafeStateEither() {
        EnergyConsumer manual = handsOff("manual", 1, 500);
        EnergyContext context = stale(true).participant(ParticipantState.of(manual).withReportedState("ON")).build();

        assertThat(safeState.apply(context, List.of()), is(empty()));
    }

    /**
     * The safe state floors what nobody is steering; it does not overrule a reduction somebody already asked for.
     */
    @Test
    public void aParticipantTheCycleAlreadyDecidedAboutIsLeftAlone() {
        EnergyConsumer heating = simple("heating", 1, 9000);
        EnergyContext context = stale(true).participant(ParticipantState.of(heating).withReportedState("ON")).build();
        ElectricalLimitFloor.Admission already = new ElectricalLimitFloor.Admission(
                Decision.of("heating", ControlAction.off(), TEST_ALGORITHM, 1), null, "");

        List<ElectricalLimitFloor.Admission> result = safeState.apply(context, List.of(already));

        assertThat(result, hasSize(1));
        assertThat(result.get(0), is(already));
    }

    /**
     * A load already sitting at its floor is not commanded there again, so a bridge that stays quiet for an hour does
     * not produce one identical command per cycle.
     */
    @Test
    public void aLoadAlreadyAtItsFloorIsNotCommandedAgain() {
        EnergyConsumer wallbox = EnergyConsumer.of("wallbox", "Wallbox_Current", ControllableProfile.amperes(6, 32), 1);
        EnergyContext context = stale(true).participant(ParticipantState.of(wallbox).withReportedState("6 A")).build();

        assertThat(safeState.apply(context, List.of()), is(empty()));
    }

    @Test
    public void nothingIsFlooredWhileTheSafetyInputsAreHealthy() {
        EnergyConsumer wallbox = wallbox("wallbox", 1, 6, 32);
        EnergyConsumer heating = simple("heating", 2, 9000);
        EnergyContext context = stale(false).participant(ParticipantState.of(wallbox).withReportedState("32 A"))
                .participant(ParticipantState.of(heating).withReportedState("ON")).build();

        assertThat(safeState.apply(context, List.of()), is(empty()));
    }
}
