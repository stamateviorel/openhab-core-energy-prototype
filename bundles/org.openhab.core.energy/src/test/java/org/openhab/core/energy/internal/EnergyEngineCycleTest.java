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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.openhab.core.energy.internal.EngineTestFixtures.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.config.core.status.ConfigStatusMessage;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.energy.EnergyProvider;
import org.openhab.core.energy.LevelGate;
import org.openhab.core.energy.ModeControllableProfile;
import org.openhab.core.energy.ProviderRole;
import org.openhab.core.energy.SimpleProfile;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.types.UnDefType;

/**
 * The engine spine end to end: one snapshot per cycle, the shadow default, the master stop, the acknowledgement
 * window, the electrical-limit floor applied to a script-contributed algorithm, and the fixed cadence.
 * <p>
 * Every test drives the engine through {@code runCycleNow()} on the test thread with a mocked scheduler, so a cycle
 * is a plain function call and nothing is timing-dependent.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EnergyEngineCycleTest {

    private final MutableClock clock = new MutableClock(T0);
    private final MapItemStateReader reader = new MapItemStateReader();
    private final RecordingSink sink = new RecordingSink();
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

    private final EnergyProvider grid = EnergyProvider.of("grid", "Grid_Power", ProviderRole.GRID);

    private EnergyEngine engine(Map<String, Object> configuration, EnergyParticipant... participants) {
        EnergyEngine engine = new EnergyEngine(scheduler, clock, reader, withRecordingSink(configuration));
        engine.setParticipantSnapshotSource(new FixedParticipants(participants));
        engine.addActuationSink(sink);
        engine.activate();
        return engine;
    }

    @Test
    public void aFreshInstallComputesLogsAndWritesNothing() {
        EnergyConsumer heating = simple("heating", 1, 3000);
        reader.putWatts("Grid_Power", 4000);
        EnergyEngine engine = engine(Map.of(), grid, heating);
        engine.addAlgorithm(new DefaultSurplusAlgorithm());

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(engine.isShadow(), is(true));
        assertThat(outcome.shadowed(), hasSize(1));
        assertThat(outcome.shadowed().get(0).action(), is(ControlAction.on()));
        assertThat(outcome.applied(), is(empty()));
        assertThat(sink.dispatched(), is(empty()));
    }

    @Test
    public void everyAlgorithmOfACycleSeesTheSameSnapshot() {
        EnergyConsumer heating = simple("heating", 1, 3000);
        reader.putWatts("Grid_Power", 4000);
        EnergyEngine engine = engine(Map.of(), grid, heating);
        List<EnergyContext> seen = new ArrayList<>();

        engine.registerAlgorithm("first", 1, context -> {
            seen.add(context);
            // the world moves while the cycle is running; the snapshot must not
            reader.putWatts("Grid_Power", 0);
            return List.of();
        });
        engine.registerAlgorithm("second", 2, context -> {
            seen.add(context);
            return List.of();
        });

        engine.runCycleNow();

        assertThat(seen, hasSize(2));
        assertThat(seen.get(1), sameInstance(seen.get(0)));
        assertThat(seen.get(0).surplusWatts().getAsDouble(), is(4000.0));

        engine.runCycleNow();
        assertThat(seen.get(2).surplusWatts().getAsDouble(), is(0.0));
    }

    @Test
    public void twoConsumersOfOneCycleAreJudgedAgainstTheSameSurplusFigure() {
        EnergyConsumer heating = simple("heating", 1, 3000);
        EnergyConsumer boiler = simple("boiler", 2, 3000);
        reader.putWatts("Grid_Power", 4000);
        EnergyEngine engine = engine(Map.of(), grid, heating, boiler);
        engine.addAlgorithm(new DefaultSurplusAlgorithm());

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.shadowed(), hasSize(1));
        assertThat(outcome.shadowed().get(0).participantId(), is("heating"));
    }

    @Test
    public void theBudgetIsEnforcedOnWhateverTheAlgorithmProposed() {
        EnergyConsumer heating = simple("heating", 1, 9000);
        EnergyConsumer boiler = simple("boiler", 2, 3000);
        reader.putWatts("Grid_Power", 12000);
        EnergyEngine engine = engine(Map.of("budget", 10000), grid, heating, boiler);
        engine.addAlgorithm(new DefaultSurplusAlgorithm());

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.shadowed(), hasSize(1));
        assertThat(outcome.shadowed().get(0).participantId(), is("heating"));
        assertThat(outcome.withStatus(DecisionStatus.DEFERRED), hasSize(1));
        assertThat(outcome.withStatus(DecisionStatus.DEFERRED).get(0).participantId(), is("boiler"));
    }

    /**
     * The master stop halts the whole engine, not just its writes: a cycle started while it is engaged takes no
     * snapshot, invokes no algorithm and therefore produces no decision at all. Releasing it resumes normal
     * operation from the next cycle without any reconfiguration.
     */
    @Test
    public void theMasterStopHaltsEverythingAndReleasingItResumes() {
        EnergyConsumer wallbox = wallbox("wallbox", 1, 6, 32);
        EnergyEngine engine = engine(Map.of("shadow", false, "ackHandling", AdapterAcknowledgementTracker.ID), wallbox);
        engine.registerAlgorithm("charger", 1,
                context -> List.of(Decision.of("wallbox", ControlAction.amperes(16), "charger", 1)));

        assertThat(engine.runCycleNow().applied(), hasSize(1));
        assertThat(sink.dispatched(), hasSize(1));

        engine.setStopped(true);
        CycleOutcome stopped = engine.runCycleNow();
        assertThat(stopped.stopped(), is(true));
        assertThat(stopped.outcomes(), is(empty()));
        assertThat(stopped.context().participants().keySet(), is(empty()));
        assertThat(sink.dispatched(), hasSize(1));

        engine.setStopped(false);
        assertThat(engine.runCycleNow().applied(), hasSize(1));
        assertThat(sink.dispatched(), hasSize(2));
    }

    @Test
    public void aChargerWhoseStateLagsIsNotCommandedEveryCycle() {
        EnergyConsumer wallbox = wallbox("wallbox", 1, 6, 32);
        reader.put("wallbox_Current", new DecimalType(10));
        EnergyEngine engine = engine(Map.of("shadow", false), wallbox);
        engine.registerAlgorithm("charger", 1,
                context -> List.of(Decision.of("wallbox", ControlAction.amperes(16), "charger", 1)));

        assertThat(engine.runCycleNow().applied(), hasSize(1));

        clock.advance(Duration.ofSeconds(30));
        CycleOutcome second = engine.runCycleNow();
        assertThat(second.withStatus(DecisionStatus.SUPPRESSED), hasSize(1));
        assertThat(sink.dispatched(), hasSize(1));

        reader.put("wallbox_Current", new DecimalType(16));
        clock.advance(Duration.ofSeconds(30));
        assertThat(engine.runCycleNow().applied(), hasSize(1));
        assertThat(sink.dispatched(), hasSize(2));
    }

    @Test
    public void aScriptAlgorithmMeetsTheSameGuardrailsAsTheBuiltInOne() {
        // the phases are declared on the device, not assigned centrally: a phase is a property of the wallbox
        EnergyConsumer wallbox = wallbox("wallbox", 1, 6, 32).withPhases(Set.of(1, 2, 3));
        EnergyEngine engine = engine(Map.of("budget", 6000), wallbox);
        engine.registerAlgorithm("night-script", 1,
                context -> List.of(Decision.of("wallbox", ControlAction.amperes(32), "night-script", 1, "cheap hour")));

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.applied(), is(empty()));
        assertThat(sink.dispatched(), is(empty()));
        assertThat(outcome.shadowed(), hasSize(1));
        Decision shadowed = outcome.shadowed().get(0);
        assertThat(shadowed.algorithmId(), is("night-script"));
        assertThat(shadowed.action(), instanceOf(ControlAction.SetCurrent.class));
        assertThat(((ControlAction.SetCurrent) shadowed.action()).current().doubleValue(), closeTo(8.696, 0.01));
        assertThat(outcome.outcomes().get(0).trimmed(), is(true));
    }

    @Test
    public void theEngineDrivesItselfOnTheConfiguredCadenceWithoutCreatingThreads() {
        engine(Map.of());
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(60_000L), eq(60_000L),
                eq(TimeUnit.MILLISECONDS));

        engine(Map.of("cycleInterval", 15));
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(15_000L), eq(15_000L),
                eq(TimeUnit.MILLISECONDS));
    }

    @Test
    public void anEvaluationTriggerIsOnlyHonouredWhenTheEngineIsConfiguredForIt() {
        EnergyEngine fixedCadence = engine(Map.of());
        assertThat(fixedCadence.triggerEvaluation(), is(false));

        EnergyEngine responsive = engine(Map.of("eventResponsive", true, "triggerDebounce", 5));
        assertThat(responsive.triggerEvaluation(), is(true));

        // and a stopped engine refuses one whatever it is configured for
        responsive.setStopped(true);
        assertThat(responsive.triggerEvaluation(), is(false));
        responsive.setStopped(false);

        responsive.runCycleNow();
        assertThat(responsive.triggerEvaluation(), is(false));
        clock.advance(Duration.ofSeconds(6));
        assertThat(responsive.triggerEvaluation(), is(true));
    }

    @Test
    public void anImportingSiteHasNoSurplusToHandOut() {
        EnergyConsumer heating = simple("heating", 1, 3000);
        // the participant model fixes the grid sign convention: negative means import
        reader.putWatts("Grid_Power", -900);
        reader.put("heating_Switch", OnOffType.OFF);
        EnergyEngine engine = engine(Map.of(), grid, heating);
        engine.addAlgorithm(new DefaultSurplusAlgorithm());

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.context().surplusWatts().getAsDouble(), is(0.0));
        assertThat(outcome.outcomes(), is(empty()));
    }

    /**
     * Scenario "Battery charging is part of the surplus": nothing exported and 3 kW absorbed by a battery the engine
     * may reclaim, and a solar-first consumer is started on it rather than starved.
     * <p>
     * This is the composition half of the requirement - that the engine builds the figure out of the grid reading and
     * the battery reading under the one sign convention. The level plane's half, that a policy handed the number
     * cannot tell reclaimed charge from export, is pinned separately by the level scenarios.
     */
    @Test
    public void batteryChargingIsPartOfTheSurplus() {
        EnergyProvider battery = EnergyProvider.of("battery", "Battery_Power", ProviderRole.BATTERY);
        EnergyConsumer heating = simple("heating", 1, 3000);
        // grid + = export, battery + = charging: the site exports nothing and puts 3 kW into storage
        reader.putWatts("Grid_Power", 0);
        reader.putWatts("Battery_Power", 3000);
        reader.put("heating_Switch", OnOffType.OFF);
        EnergyEngine engine = engine(Map.of(), grid, battery, heating);
        engine.addAlgorithm(new DefaultSurplusAlgorithm());

        CycleOutcome outcome = engine.runCycleNow();

        assertThat("the charge the engine can reclaim counts exactly as exported power would",
                outcome.context().surplusWatts().getAsDouble(), is(3000.0));
        assertThat(outcome.outcomes(), is(not(empty())));
        assertThat(outcome.outcomes().get(0).decision().participantId(), is("heating"));
    }

    /**
     * Scenario "Battery charging is part of the surplus", the discriminating half: the charge is surplus to a
     * consumer that outranks the battery and is not surplus to one that does not.
     * <p>
     * A tie does not outrank - the requirement says a better-priority consumer, which is a strictly lower priority
     * number.
     */
    @Test
    public void whoseChargeIsReclaimableDependsOnWhoIsAsking() {
        EnergyProvider battery = EnergyProvider.of("battery", "Battery_Power", ProviderRole.BATTERY).withPriority(100);
        reader.putWatts("Grid_Power", 0);
        reader.putWatts("Battery_Power", 3000);
        EnergyEngine engine = engine(Map.of(), grid, battery);

        EnergyContext context = engine.runCycleNow().context();

        assertThat("a better-priority consumer may reclaim it", context.surplusWattsFor(40).getAsDouble(), is(3000.0));
        assertThat("a worse-priority consumer may not", context.surplusWattsFor(140).getAsDouble(), is(0.0));
        assertThat("a tie does not outrank", context.surplusWattsFor(100).getAsDouble(), is(0.0));
        assertThat("and the site-wide figure counts it all", context.surplusWatts().getAsDouble(), is(3000.0));
    }

    /**
     * A discharging battery is not surplus: the sign convention says battery + = charging, so a negative reading is
     * storage being drained and there is nothing to reclaim.
     */
    @Test
    public void aDischargingBatteryContributesNoSurplus() {
        EnergyProvider battery = EnergyProvider.of("battery", "Battery_Power", ProviderRole.BATTERY);
        reader.putWatts("Grid_Power", 500);
        reader.putWatts("Battery_Power", -2000);
        EnergyEngine engine = engine(Map.of(), grid, battery);

        EnergyContext context = engine.runCycleNow().context();

        assertThat(context.surplusWatts().getAsDouble(), is(500.0));
        assertThat(context.surplusWattsFor(1).getAsDouble(), is(500.0));
    }

    /**
     * Scenario "Importing while the battery charges" - the vector that discriminates the two readings of the
     * composition, which the corpus did not have. The site imports 1 kW while 3 kW goes into the battery: the
     * literal sum of two non-negative terms reports 3 kW, and stopping the battery would actually free 2 kW.
     * <p>
     * Source: owner decision D27 ({@code openhab-ems-spec/docs/OWNER_DECISIONS.md}), amending D10 as literally
     * worded. Everything else in this file agrees under either reading - the corpus's own battery scenario has the
     * grid at exactly zero - so without this test the amendment is unobservable.
     */
    @Test
    public void importingWhileTheBatteryChargesReportsOnlyWhatStoppingItWouldFree() {
        EnergyProvider battery = EnergyProvider.of("battery", "Battery_Power", ProviderRole.BATTERY).withPriority(100);
        reader.putWatts("Grid_Power", -1000);
        reader.putWatts("Battery_Power", 3000);
        EnergyEngine engine = engine(Map.of(), grid, battery);

        EnergyContext context = engine.runCycleNow().context();

        assertThat("the import is netted off the reclaimable charge before anything is handed out",
                context.surplusWatts().getAsDouble(), is(2000.0));
        assertThat("and the per-consumer figure composes the same way", context.surplusWattsFor(40).getAsDouble(),
                is(2000.0));
        assertThat("a consumer that may not reclaim the battery sees an importing site with nothing to spare",
                context.surplusWattsFor(140).getAsDouble(), is(0.0));
    }

    /**
     * Scenario "Surplus is never negative": the clamp keeps the figure a magnitude, so the escalation thresholds it
     * feeds stay comparable and a deficit is read from the grid figure that carries it.
     */
    @Test
    public void surplusIsNeverNegative() {
        reader.putWatts("Grid_Power", -2000);

        EnergyContext context = engine(Map.of(), grid).runCycleNow().context();

        assertThat(context.surplusWatts().getAsDouble(), is(0.0));
        assertThat(context.gridPower(), is(new QuantityType<Power>(-2000, Units.WATT)));
    }

    /**
     * The netting reaches the level plane too, because the level is resolved from the very figure the snapshot was
     * built with. A site importing 1 kW while 3 kW charges the battery does not escalate past a 2500 W threshold -
     * under the literal sum it would have, and the consumer started on that would have pushed the site further into
     * import.
     */
    @Test
    public void theNettedSurplusIsWhatTheLevelPlaneIsHandedForTheSameCycle() {
        EnergyProvider battery = EnergyProvider.of("battery", "Battery_Power", ProviderRole.BATTERY);
        reader.putWatts("Grid_Power", -1000);
        reader.putWatts("Battery_Power", 3000);
        List<Double> handed = new ArrayList<>();
        EnergyEngine engine = engine(Map.of(), grid, battery);
        engine.setCurrentLevelFunction((moment, surplus) -> {
            handed.add(surplus.getAsDouble());
            return surplus.getAsDouble() >= 2500 ? EnergyLevel.ENCOURAGED : EnergyLevel.NORMAL;
        });

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(handed, contains(2000.0));
        assertThat(outcome.context().level(), is(EnergyLevel.NORMAL));
    }

    /**
     * The battery a worse-priority consumer cannot reclaim does not start it either - the pool each consumer is
     * served from is its own, not the site-wide figure.
     */
    @Test
    public void aWorsePriorityConsumerIsNotStartedOnChargeItDoesNotOutrank() {
        EnergyProvider battery = EnergyProvider.of("battery", "Battery_Power", ProviderRole.BATTERY).withPriority(100);
        EnergyConsumer trickle = simple("trickle", 140, 2000);
        reader.putWatts("Grid_Power", 0);
        reader.putWatts("Battery_Power", 3000);
        reader.put("trickle_Switch", OnOffType.OFF);
        EnergyEngine engine = engine(Map.of(), grid, battery, trickle);
        engine.addAlgorithm(new DefaultSurplusAlgorithm());

        CycleOutcome outcome = engine.runCycleNow();

        assertThat("the battery outranks it, so the charge is not its to take", outcome.outcomes(), is(empty()));
    }

    /**
     * Scenario "Declared age exceeded": a participant declaring a maximum reading age of 30 seconds has its reading
     * treated as stale once it has not updated for longer than that, on an engine configured with no age of its own.
     * <p>
     * The declaration is what does the work here - without it the same frozen reading is not stale, which is the
     * discriminating half.
     */
    @Test
    public void aReadingOlderThanTheDeclaredAgeIsStale() {
        EnergyProvider ageing = EnergyProvider.of("grid", "Grid_Power", ProviderRole.GRID)
                .withMaxReadingAge(Duration.ofSeconds(30));
        reader.putWatts("Grid_Power", 4000);
        reader.putLastUpdate("Grid_Power", clock.instant().minusSeconds(45));

        assertThat(engine(Map.of(), ageing).runCycleNow().context().measurementsStale(), is(true));

        EnergyProvider silent = EnergyProvider.of("grid", "Grid_Power", ProviderRole.GRID);
        assertThat("the same frozen reading, undeclared, is not judged by its age",
                engine(Map.of(), silent).runCycleNow().context().measurementsStale(), is(false));
    }

    /**
     * Scenario "Undefined state with no declared age": an {@code UNDEF} reading counts as stale anyway, which is what
     * makes the age optional rather than load-bearing.
     * <p>
     * Scenario "A frozen item made observable" rides on the same rule: a site that puts core's {@code expire}
     * namespace on a source that stops updating without going undefined turns it into exactly this case.
     */
    @Test
    public void anUndefinedReadingIsStaleWithNoDeclaredAge() {
        EnergyProvider provider = EnergyProvider.of("grid", "Grid_Power", ProviderRole.GRID);
        reader.put("Grid_Power", UnDefType.UNDEF);

        assertThat(provider.maxReadingAge(), is(nullValue()));
        assertThat(engine(Map.of(), provider).runCycleNow().context().measurementsStale(), is(true));
    }

    /**
     * A participant's declared age overrides the engine-wide setting for that participant alone: a grid clamp
     * updating twice a second and a battery reporting once a minute cannot share one number.
     */
    @Test
    public void aDeclaredAgeOverridesTheEngineWideSettingForThatParticipantAlone() {
        EnergyProvider patient = EnergyProvider.of("grid", "Grid_Power", ProviderRole.GRID)
                .withMaxReadingAge(Duration.ofMinutes(5));
        reader.putWatts("Grid_Power", 4000);
        reader.putLastUpdate("Grid_Power", clock.instant().minusSeconds(60));

        // the engine would call this stale after 30 s; the participant says five minutes and is believed
        assertThat(engine(Map.of("staleAfter", 30), patient).runCycleNow().context().measurementsStale(), is(false));

        EnergyProvider quiet = EnergyProvider.of("grid", "Grid_Power", ProviderRole.GRID);
        assertThat("a participant declaring nothing still falls under the engine-wide setting",
                engine(Map.of("staleAfter", 30), quiet).runCycleNow().context().measurementsStale(), is(true));
    }

    @Test
    public void aModeControllableConsumerIsNeverHandedAPowerValue() {
        EnergyConsumer heatPump = EnergyConsumer.of("heatpump", "HeatPump_Mode",
                ModeControllableProfile.of("off", "eco", "comfort"), 1);
        reader.putWatts("Grid_Power", 9000);
        EnergyEngine engine = engine(Map.of(), grid, heatPump);
        engine.addAlgorithm(new DefaultSurplusAlgorithm());
        engine.addAlgorithm(new DeviceProtectionAlgorithm());

        CycleOutcome outcome = engine.runCycleNow();

        // neither core-shipped algorithm understands a mode, so neither invents a power for one
        assertThat(outcome.outcomes(), is(empty()));
    }

    /**
     * A gap in an <em>accepted</em> declaration reaches the configuration status surface, not only the log: a Simple
     * consumer that declares no rated power is managed on its on-threshold, and a participant naming an actuation
     * sink nothing installed is steered by nothing at all. Both are things an operator can only fix if they can see
     * them.
     */
    @Test
    public void aGapInAnAcceptedDeclarationIsReportedAsConfigurationStatus() {
        EnergyConsumer heating = simple("heating", 1, 3000).withSink("ocpp");
        reader.putWatts("Grid_Power", 4000);
        EnergyConfigStatus status = new EnergyConfigStatus();
        EnergyEngine engine = engine(Map.of(), grid, heating);
        engine.setConfigStatus(status);

        engine.runCycleNow();

        List<ConfigStatusMessage> reported = List.copyOf(status.getConfigStatus());
        assertThat(reported, hasSize(1));
        assertThat(reported.getFirst().parameterName, is("heating"));
        assertThat(reported.getFirst().type, is(ConfigStatusMessage.Type.WARNING));

        // and it clears when the declaration stops having a gap
        SimpleProfile declared = new SimpleProfile(new QuantityType<Power>(3000, Units.WATT),
                new QuantityType<Power>(3200, Units.WATT), null, null, null, null, LevelGate.always());
        engine.setParticipantSnapshotSource(
                new FixedParticipants(grid, EnergyConsumer.of("heating", "heating_Switch", declared, 1)));
        engine.runCycleNow();

        assertThat(status.getConfigStatus(), is(empty()));
    }

    @Test
    public void anEngineWithoutAParticipantSourceEvaluatesAnEmptySite() {
        EnergyEngine engine = new EnergyEngine(scheduler, clock, reader, Map.of());
        engine.addAlgorithm(new DefaultSurplusAlgorithm());

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.context().participants().keySet(), is(empty()));
        assertThat(outcome.outcomes(), is(empty()));
    }
}
