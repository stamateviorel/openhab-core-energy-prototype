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
import java.util.Set;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.BatchProfile;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.DecisionKind;
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
 * The electrical-limit floor, requirement by requirement.
 * <p>
 * Covers the scenarios of <em>Electrical limits outrank optimization</em> ("Budget respected at dispatch time",
 * "Phase-aware headroom"), the four dispositions - trim, defer, leave untouched, refuse with a reason - with the
 * ladder's own two exceptions, and the freeze half of the safe state.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ElectricalLimitFloorTest {

    private static final String TEST_ALGORITHM = "test";

    private ElectricalLimitFloor floor() {
        return new ElectricalLimitFloor(new PowerEstimator(230));
    }

    @Test
    public void budgetIsRespectedAtDispatchTime() {
        EnergyConsumer heating = simple("heating", 1, 9000);
        EnergyConsumer boiler = simple("boiler", 2, 3000);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(10000)).uncontrolledWatts(0)
                .participant(ParticipantState.of(heating)).participant(ParticipantState.of(boiler)).build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("heating", ControlAction.on(), TEST_ALGORITHM, 1),
                        Decision.of("boiler", ControlAction.on(), TEST_ALGORITHM, 2)));

        assertThat(result.admitted(), hasSize(1));
        assertThat(result.admitted().get(0).decision().participantId(), is("heating"));
        assertThat(result.rejected(), hasSize(1));
        assertThat(result.rejected().get(0).decision().participantId(), is("boiler"));
        assertThat(result.rejected().get(0).status(), is(DecisionStatus.DEFERRED));
    }

    @Test
    public void anAlreadyLoadedSiteLeavesLessHeadroom() {
        EnergyConsumer heating = simple("heating", 1, 9000);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(10000))
                .uncontrolledWatts(4000).participant(ParticipantState.of(heating)).build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("heating", ControlAction.on(), TEST_ALGORITHM, 1)));

        assertThat(result.admitted(), is(empty()));
        assertThat(result.rejected().get(0).detail(), containsString("6000 W available"));
    }

    @Test
    public void phaseHeadroomIsAccountedForBeyondTheSiteTotal() {
        EnergyConsumer wallbox = wallbox("wallbox", 1, 6, 32);
        ParticipantState state = ParticipantState.of(wallbox).withPhases(Set.of(1, 2, 3));
        ElectricalLimits limits = ElectricalLimits.ofWatts(20000).withPhaseWatts(1, 3680).withPhaseWatts(2, 3680)
                .withPhaseWatts(3, 3680);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(limits).uncontrolledWatts(2000)
                .uncontrolledPhaseWatts(1, 2000).participant(state).build();
        Decision sixteenAmps = Decision.of("wallbox", ControlAction.amperes(16), TEST_ALGORITHM, 1);

        ElectricalLimitFloor.Result result = floor().apply(context, List.of(sixteenAmps));

        assertThat(result.rejected(), is(empty()));
        assertThat(result.admitted(), hasSize(1));
        ElectricalLimitFloor.Admission admission = result.admitted().get(0);
        assertThat(admission.original(), is(sixteenAmps));
        ControlAction trimmed = admission.decision().action();
        assertThat(trimmed, instanceOf(ControlAction.SetCurrent.class));
        double amperes = ((ControlAction.SetCurrent) trimmed).current().doubleValue();
        assertThat(amperes, closeTo(7.304, 0.01));
        assertThat(amperes * 230 + 2000, lessThanOrEqualTo(3680.0));
    }

    @Test
    public void theSameDecisionPassesUntrimmedWhenOnlyTheTotalIsDeclared() {
        EnergyConsumer wallbox = wallbox("wallbox", 1, 6, 32);
        ParticipantState state = ParticipantState.of(wallbox).withPhases(Set.of(1, 2, 3));
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(20000))
                .uncontrolledWatts(2000).uncontrolledPhaseWatts(1, 2000).participant(state).build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("wallbox", ControlAction.amperes(16), TEST_ALGORITHM, 1)));

        assertThat(result.admitted(), hasSize(1));
        assertThat(result.admitted().get(0).original(), is(nullValue()));
    }

    @Test
    public void aTrimBelowTheDeclaredMinimumBecomesADeferral() {
        EnergyConsumer wallbox = wallbox("wallbox", 1, 6, 32);
        ParticipantState state = ParticipantState.of(wallbox).withPhases(Set.of(1, 2, 3));
        ElectricalLimits limits = ElectricalLimits.ofWatts(20000).withPhaseWatts(1, 3680).withPhaseWatts(2, 3680)
                .withPhaseWatts(3, 3680);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(limits).uncontrolledWatts(3000)
                .uncontrolledPhaseWatts(1, 3000).participant(state).build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("wallbox", ControlAction.amperes(16), TEST_ALGORITHM, 1)));

        assertThat(result.admitted(), is(empty()));
        assertThat(result.rejected().get(0).status(), is(DecisionStatus.DEFERRED));
    }

    @Test
    public void decisionsThatReduceLoadAreNeverTrimmed() {
        EnergyConsumer heating = simple("heating", 1, 9000);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(1000))
                .uncontrolledWatts(9000).participant(ParticipantState.of(heating)).build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("heating", ControlAction.off(), TEST_ALGORITHM, 1)));

        assertThat(result.admitted(), hasSize(1));
        assertThat(result.rejected(), is(empty()));
    }

    @Test
    public void theFloorAppliesWhicheverAlgorithmProposed() {
        EnergyConsumer heating = simple("heating", 1, 9000);
        EnergyConsumer boiler = simple("boiler", 2, 3000);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(10000)).uncontrolledWatts(0)
                .participant(ParticipantState.of(heating)).participant(ParticipantState.of(boiler)).build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("heating", ControlAction.on(), "compiled-add-on", 1),
                        Decision.of("boiler", ControlAction.on(), "user-script", 2)));

        assertThat(result.rejected().get(0).decision().algorithmId(), is("user-script"));
        assertThat(result.rejected().get(0).status(), is(DecisionStatus.DEFERRED));
    }

    /**
     * The ladder is fixed and states its own two exceptions. A device protection is <em>not</em> one of them: it
     * outranks optimization and level gates, and it is outranked by the electrical limits, so a duty-cycle guarantee
     * that would push the site past its budget is deferred like anything else and runs as soon as headroom exists.
     * The prototype made this a configuration choice with a second, protections-first ladder; there is now one ladder
     * and nothing selects another.
     */
    @Test
    public void aProtectionIsOutrankedByTheElectricalLimitAndNothingCanReorderThat() {
        EnergyConsumer fridge = simple("fridge", 1, 3000);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(10000))
                .uncontrolledWatts(9000).participant(ParticipantState.of(fridge)).build();
        Decision dutyCycle = new Decision("fridge", ControlAction.on(), "protections", 1,
                DecisionKind.DEVICE_PROTECTION, "maximum OFF time exceeded");

        ElectricalLimitFloor.Result result = floor().apply(context, List.of(dutyCycle));

        assertThat(result.admitted(), is(empty()));
        assertThat(result.rejected().get(0).status(), is(DecisionStatus.DEFERRED));
    }

    /**
     * The first of the ladder's two stated exceptions: a consumer its owner marked hands-off is never shed, and its
     * draw is booked so that everything else is trimmed <em>against</em> it rather than competing with it. Marking a
     * device hands-off therefore costs the floor nothing - which is the whole reason the flag exists instead of users
     * deleting the declaration.
     */
    @Test
    public void aHandsOffLoadIsBookedAndNeverSteered() {
        EnergyConsumer manual = handsOff("manual", 1, 3000);
        EnergyConsumer heating = simple("heating", 2, 9000);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(10000)).uncontrolledWatts(0)
                .participant(ParticipantState.of(manual).withReportedState("ON").withMeasuredWatts(3000))
                .participant(ParticipantState.of(heating)).build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("manual", ControlAction.off(), TEST_ALGORITHM, 1),
                        Decision.of("heating", ControlAction.on(), TEST_ALGORITHM, 2)));

        // the hands-off decision passes the floor untouched; the engine's own prohibitions withhold it afterwards
        ElectricalLimitFloor.Admission untouched = result.admitted().stream()
                .filter(admission -> "manual".equals(admission.decision().participantId())).findFirst().orElseThrow();
        assertThat(untouched.decision().action(), is(ControlAction.off()));
        assertThat(untouched.detail(), containsString("hands-off"));
        // its 3 kW was booked, so the 9 kW heating no longer fits under the 10 kW budget, and the refusal says why
        assertThat(result.rejected(), hasSize(1));
        assertThat(result.rejected().get(0).decision().participantId(), is("heating"));
        assertThat(result.rejected().get(0).detail(), containsString("manual"));
        assertThat(result.rejected().get(0).detail(), containsString("does not allow the floor to shed"));
    }

    /**
     * The second stated exception: a Batch programme already running is left alone, whatever the overload.
     */
    @Test
    public void aRunningBatchProgrammeIsNotInterrupted() {
        EnergyConsumer dishwasher = EnergyConsumer.of("dishwasher", "Dishwasher_Switch",
                new BatchProfile(new QuantityType<Power>(2000, Units.WATT), Duration.ofHours(2), null), 1);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(1000)).uncontrolledWatts(0)
                .participant(ParticipantState.of(dishwasher).withReportedState("ON").withMeasuredWatts(2000)).build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("dishwasher", ControlAction.off(), TEST_ALGORITHM, 1)));

        assertThat(result.rejected(), is(empty()));
        assertThat(result.admitted(), hasSize(1));
        assertThat(result.admitted().get(0).detail(), containsString("running batch programme"));
    }

    /**
     * Shape decides how, priority decides who. Two loads, one continuous and one a switch: the continuous one is
     * trimmed to the boundary and the switch is deferred, because a switch has no intermediate value - not because
     * one of them happened to be easier to act on.
     */
    @Test
    public void theShapeOfALoadDecidesWhetherItIsTrimmedOrDeferred() {
        EnergyConsumer wallbox = wallbox("wallbox", 1, 6, 32);
        EnergyConsumer heating = simple("heating", 2, 9000);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(5000)).uncontrolledWatts(0)
                .participant(ParticipantState.of(wallbox)).participant(ParticipantState.of(heating)).build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("wallbox", ControlAction.amperes(32), TEST_ALGORITHM, 1),
                        Decision.of("heating", ControlAction.on(), TEST_ALGORITHM, 2)));

        assertThat(result.admitted(), hasSize(1));
        assertThat(result.admitted().get(0).decision().participantId(), is("wallbox"));
        assertThat(result.admitted().get(0).original(), is(not(nullValue())));
        assertThat(result.rejected(), hasSize(1));
        assertThat(result.rejected().get(0).decision().participantId(), is("heating"));
        assertThat(result.rejected().get(0).status(), is(DecisionStatus.DEFERRED));
    }

    /**
     * Untrimmability never reorders the shedding. With the roles reversed - the better-priority load a switch that
     * can only be deferred, the worse-priority one a continuous load that could be trimmed to fit - the switch is
     * still served first and the continuous load is the one that gives way. A floor that resolved overloads by
     * reaching for whatever was easiest to act on would quietly invert the user's priorities.
     */
    @Test
    public void untrimmabilityDoesNotChangeTheShedOrder() {
        EnergyConsumer heating = simple("heating", 1, 4000);
        EnergyConsumer wallbox = wallbox("wallbox", 2, 6, 32);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(6000)).uncontrolledWatts(0)
                .participant(ParticipantState.of(heating)).participant(ParticipantState.of(wallbox)).build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("heating", ControlAction.on(), TEST_ALGORITHM, 1),
                        Decision.of("wallbox", ControlAction.amperes(32), TEST_ALGORITHM, 2)));

        assertThat(result.rejected(), is(empty()));
        assertThat(result.admitted().get(0).decision().participantId(), is("heating"));
        assertThat(result.admitted().get(0).original(), is(nullValue()));
        // the worse-priority load is the one that gives way, by trimming rather than by being skipped over
        assertThat(result.admitted().get(1).decision().participantId(), is("wallbox"));
        assertThat(result.admitted().get(1).original(), is(not(nullValue())));
    }

    /**
     * Deferring a Simple load that is <em>already running</em> means switching it off - the deferral is still
     * published so a planner can reschedule it, and a switch-off is admitted in its place so that the site actually
     * comes back inside its limits.
     */
    @Test
    public void deferringALoadThatIsAlreadyRunningSwitchesItOff() {
        EnergyConsumer heating = simple("heating", 1, 9000);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(3000)).uncontrolledWatts(0)
                .participant(ParticipantState.of(heating).withReportedState("ON").withMeasuredWatts(9000)).build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("heating", ControlAction.on(), TEST_ALGORITHM, 1)));

        assertThat(result.rejected().get(0).status(), is(DecisionStatus.DEFERRED));
        assertThat(result.admitted(), hasSize(1));
        assertThat(result.admitted().get(0).decision().action(), is(ControlAction.off()));
        assertThat(result.admitted().get(0).decision().kind(), is(DecisionKind.ELECTRICAL_LIMIT));
    }

    /**
     * A running load inside its declared minimum runtime is <strong>held</strong>, not shed: the ladder puts device
     * protections above optimization, and the floor may not resolve an overload by breaking one. Getting this
     * backwards would cut a compressor's run short every time the site got busy.
     */
    @Test
    public void aProtectionHoldsALoadTheOverloadWouldOtherwiseShed() {
        EnergyConsumer compressor = EnergyConsumer.of("compressor", "Compressor_Switch",
                new SimpleProfile(new QuantityType<Power>(9000, Units.WATT), null, Duration.ofMinutes(15), null, null,
                        null, LevelGate.always()),
                1);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(3000)).uncontrolledWatts(0)
                .participant(ParticipantState.of(compressor).withReportedState("ON").withMeasuredWatts(9000)
                        .withLastChangedAt(T0.minus(Duration.ofMinutes(5))))
                .build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("compressor", ControlAction.on(), TEST_ALGORITHM, 1)));

        assertThat(result.rejected().get(0).status(), is(DecisionStatus.DEFERRED));
        // no switch-off was admitted in its place: the protection outranks the deferral
        assertThat(result.admitted(), is(empty()));
    }

    /**
     * The same hold, on a site that does not persist the compressor's Item. The protection then runs on the
     * first-observation clock, and the floor has to read <em>that</em> clock too: if it read the device history alone
     * it would find no protection, shed the load, and break a minimum runtime the engine is simultaneously reporting
     * that it is enforcing.
     */
    @Test
    public void aProtectionOnTheFirstObservationClockHoldsTheLoadJustTheSame() {
        EnergyConsumer compressor = EnergyConsumer.of("compressor", "Compressor_Switch",
                new SimpleProfile(new QuantityType<Power>(9000, Units.WATT), null, Duration.ofMinutes(15), null, null,
                        null, LevelGate.always()),
                1);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(3000)).uncontrolledWatts(0)
                .participant(ParticipantState.of(compressor).withReportedState("ON").withMeasuredWatts(9000)
                        .withFirstObservedAt(T0.minus(Duration.ofMinutes(5))))
                .build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("compressor", ControlAction.on(), TEST_ALGORITHM, 1)));

        assertThat(result.rejected().get(0).status(), is(DecisionStatus.DEFERRED));
        assertThat(result.admitted(), is(empty()));
    }

    /**
     * A managed load that is running, measured and decided about by nobody this cycle still occupies headroom.
     * <p>
     * That is the ordinary case rather than an exotic one: a device already in the state it would be commanded to
     * gets no decision at all, and the snapshot subtracts its measured draw from the uncontrolled figure precisely
     * because the floor is supposed to be accounting for it directly. Booked in neither place, three kilowatts of
     * boiler would be invisible and the site would be dispatched past the budget the floor exists to defend.
     */
    @Test
    public void aRunningLoadNobodyDecidedAboutStillOccupiesHeadroom() {
        EnergyConsumer boiler = simple("boiler", 2, 3000).withMeasurement("Boiler_Power");
        EnergyConsumer heating = simple("heating", 1, 9000);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(10000)).uncontrolledWatts(0)
                .participant(ParticipantState.of(boiler).withReportedState("ON").withMeasuredWatts(3000))
                .participant(ParticipantState.of(heating)).build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("heating", ControlAction.on(), TEST_ALGORITHM, 1)));

        assertThat("9 kW does not fit beside a boiler already drawing 3 kW of a 10 kW budget", result.admitted(),
                is(empty()));
        assertThat(result.rejected(), hasSize(1));
        assertThat(result.rejected().get(0).detail(), containsString("7000 W available"));
    }

    /**
     * Its own pre-booking is available to its own decision, because a command replaces what a device draws rather
     * than adding to it - otherwise every metered load would be counted twice the moment anything addressed it.
     */
    @Test
    public void aRunningLoadIsNotChargedTwiceForItsOwnDecision() {
        EnergyConsumer boiler = simple("boiler", 2, 3000).withMeasurement("Boiler_Power");
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(4000)).uncontrolledWatts(0)
                .participant(ParticipantState.of(boiler).withReportedState("ON").withMeasuredWatts(3000)).build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("boiler", ControlAction.on(), TEST_ALGORITHM, 2)));

        assertThat(result.rejected(), is(empty()));
        assertThat(result.admitted(), hasSize(1));
    }

    /**
     * And when the floor does shed a running load, the headroom it was holding is released to what comes after it -
     * otherwise shedding would free nothing within the cycle that decided to shed.
     */
    @Test
    public void sheddingARunningLoadFreesItsHeadroomForTheRest() {
        EnergyConsumer boiler = simple("boiler", 1, 8000).withMeasurement("Boiler_Power");
        EnergyConsumer heating = simple("heating", 2, 3000);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(10000))
                .uncontrolledWatts(3000)
                .participant(ParticipantState.of(boiler).withReportedState("ON").withMeasuredWatts(8000))
                .participant(ParticipantState.of(heating)).build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("boiler", ControlAction.on(), TEST_ALGORITHM, 1),
                        Decision.of("heating", ControlAction.on(), TEST_ALGORITHM, 2)));

        assertThat("8 kW does not fit beside 3 kW of uncontrolled load", result.rejected(), hasSize(1));
        assertThat(result.rejected().get(0).decision().participantId(), is("boiler"));
        assertThat("the boiler is deferred and switched off", result.admitted().get(0).decision().action(),
                is(ControlAction.off()));
        assertThat("and the 3 kW heating then fits in what it freed", result.admitted(), hasSize(2));
        assertThat(result.admitted().get(1).decision().participantId(), is("heating"));
    }

    /**
     * A mode change carries no power figure and is exempt: it is admitted, booked at nothing, and the gap is
     * reported rather than turned into a refusal. The prototype offered "defer it instead" as a configuration value;
     * that choice is gone.
     */
    @Test
    public void aModeChangeIsExemptFromTheBudgetRatherThanDeferredByIt() {
        EnergyConsumer heatPump = EnergyConsumer.of("heatpump", "HeatPump_Mode",
                ModeControllableProfile.of("blocked", "normal", "encouraged", "forced"), 1);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(1000))
                .uncontrolledWatts(1000).participant(ParticipantState.of(heatPump)).build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("heatpump", ControlAction.mode("forced"), TEST_ALGORITHM, 1)));

        assertThat(result.admitted(), hasSize(1));
        assertThat(result.admitted().get(0).detail(), containsString("demand unknown"));
        assertThat(result.rejected(), is(empty()));
    }

    /**
     * What the floor books is the larger of the declared figure and the live measurement <em>while the load is
     * running</em> - so an appliance that ignores the envelope it was given is constrained rather than hidden behind
     * the command it did not honour.
     */
    @Test
    public void aDeviceThatIgnoresItsEnvelopeIsBookedAtWhatItActuallyDraws() {
        EnergyConsumer heating = simple("heating", 1, 3000);
        EnergyConsumer boiler = simple("boiler", 2, 3000);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(7000)).uncontrolledWatts(0)
                .participant(ParticipantState.of(heating).withReportedState("ON").withMeasuredWatts(5000))
                .participant(ParticipantState.of(boiler)).build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("heating", ControlAction.on(), TEST_ALGORITHM, 1),
                        Decision.of("boiler", ControlAction.on(), TEST_ALGORITHM, 2)));

        // 5000 booked for the heating rather than its declared 3000, so 3000 for the boiler no longer fits
        assertThat(result.rejected(), hasSize(1));
        assertThat(result.rejected().get(0).decision().participantId(), is("boiler"));
    }

    /**
     * An idle load is booked at its declaration, not at the nothing it currently measures - otherwise a site could
     * admit every load it owns on the grounds that none of them is drawing yet.
     */
    @Test
    public void anIdleLoadIsBookedAtItsDeclarationRatherThanAtItsMeasurement() {
        EnergyConsumer heating = simple("heating", 1, 9000);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(5000)).uncontrolledWatts(0)
                .participant(ParticipantState.of(heating).withReportedState("OFF").withMeasuredWatts(0)).build();

        ElectricalLimitFloor.Result result = floor().apply(context,
                List.of(Decision.of("heating", ControlAction.on(), TEST_ALGORITHM, 1)));

        assertThat(result.admitted(), is(empty()));
        assertThat(result.rejected().get(0).status(), is(DecisionStatus.DEFERRED));
    }

    /**
     * Every cycle books from scratch: nothing a previous cycle admitted is carried forward as a reserve.
     */
    @Test
    public void noReserveIsCarriedBetweenCycles() {
        EnergyConsumer heating = simple("heating", 1, 9000);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(10000)).uncontrolledWatts(0)
                .participant(ParticipantState.of(heating)).build();
        List<Decision> decisions = List.of(Decision.of("heating", ControlAction.on(), TEST_ALGORITHM, 1));
        ElectricalLimitFloor floor = floor();

        assertThat(floor.apply(context, decisions).admitted(), hasSize(1));
        assertThat(floor.apply(context, decisions).admitted(), hasSize(1));
        assertThat(floor.apply(context, decisions).admitted(), hasSize(1));
    }

    @Test
    public void staleSafetyMeasurementsRefuseIncreasesButAllowReductions() {
        EnergyConsumer heating = simple("heating", 1, 9000);
        EnergyContext context = context(EnergyLevel.NORMAL).limits(ElectricalLimits.ofWatts(20000))
                .participant(ParticipantState.of(heating)).measurementsStale(true).build();

        ElectricalLimitFloor.Result increase = floor().apply(context,
                List.of(Decision.of("heating", ControlAction.on(), TEST_ALGORITHM, 1)));
        assertThat(increase.rejected().get(0).status(), is(DecisionStatus.DEFERRED));
        assertThat(increase.rejected().get(0).detail(), containsString("stale"));

        ElectricalLimitFloor.Result reduction = floor().apply(context,
                List.of(Decision.of("heating", ControlAction.off(), TEST_ALGORITHM, 1)));
        assertThat(reduction.admitted(), hasSize(1));
    }
}
