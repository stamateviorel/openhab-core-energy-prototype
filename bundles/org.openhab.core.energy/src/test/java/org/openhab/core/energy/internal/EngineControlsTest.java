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
import static org.mockito.Mockito.mock;
import static org.openhab.core.energy.internal.EngineTestFixtures.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.ActuationSink;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.EnergyAlgorithmRegistry;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.energy.EnergyProvider;
import org.openhab.core.energy.ProviderRole;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.test.java.JavaTest;

/**
 * The engine's operator-facing controls, tested for the failure modes an adversarial review found rather than for
 * the happy path: a master stop that survives an unrelated configuration edit and an unrelated service binding, a
 * shadow line that really is logged, an actuation sink nobody can become by accident, and an acknowledgement window
 * that survives the tracker being rebuilt.
 * <p>
 * The "unrelated service binding" used to be a contributed precedence strategy; that whiteboard is gone with the
 * ladder it configured, so these tests bind an actuation sink instead - the remaining dynamic whiteboard whose
 * binding runs the same reconciliation between what an operator engaged at runtime and what configuration says.
 * <p>
 * Every one of these was broken at some point in this prototype and is silent while shadow mode is on, which is
 * exactly why they are asserted rather than reasoned about.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EngineControlsTest extends JavaTest {

    private final MutableClock clock = new MutableClock(T0);
    private final MapItemStateReader reader = new MapItemStateReader();
    private final RecordingSink sink = new RecordingSink();
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

    private final EnergyProvider grid = EnergyProvider.of("grid", "Grid_Power", ProviderRole.GRID);
    private final EnergyConsumer wallbox = wallbox("wallbox", 1, 6, 32);

    @BeforeEach
    public void captureTheEngineLog() {
        setupInterceptedLogger(EnergyEngine.class, LogLevel.INFO);
    }

    @AfterEach
    public void releaseTheEngineLog() {
        stopInterceptedLogger(EnergyEngine.class);
    }

    private EnergyEngine engine(Map<String, Object> configuration, EnergyParticipant... participants) {
        EnergyEngine engine = new EnergyEngine(scheduler, clock, reader, configuration);
        engine.setParticipantSnapshotSource(new FixedParticipants(participants));
        engine.addActuationSink(sink);
        return engine;
    }

    /**
     * An actuation sink that does nothing, used only as an unrelated service binding: binding one rebuilds the
     * engine's derived components, which is the code path that once silently released a master stop.
     *
     * @param id the sink id
     * @return the sink
     */
    private static ActuationSink unrelatedSink(String id) {
        return new ActuationSink() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public void dispatch(Decision decision, EnergyContext context) {
            }
        };
    }

    private static Decision charge() {
        return Decision.of("wallbox", ControlAction.amperes(16), "charger", 1);
    }

    @Test
    public void aMasterStopEngagedAtRuntimeSurvivesAnUnrelatedConfigurationEdit() {
        EnergyEngine engine = engine(withRecordingSink(Map.of("shadow", false)), wallbox);
        engine.registerAlgorithm("charger", 1, context -> List.of(charge()));
        engine.setStopped(true);

        engine.modified(withRecordingSink(Map.of("shadow", false, "cycleInterval", 30)));

        assertThat(engine.isStopped(), is(true));
        assertThat(engine.getConfiguration().stopped(), is(true));
        // the stop halts everything, so a cycle started while it is engaged decides nothing at all
        assertThat(engine.runCycleNow().outcomes(), is(empty()));
        assertThat(engine.runCycleNow().stopped(), is(true));
        assertThat(sink.dispatched(), is(empty()));
    }

    @Test
    public void shadowModeEngagedAtRuntimeSurvivesAnUnrelatedConfigurationEdit() {
        EnergyEngine engine = engine(withRecordingSink(Map.of("shadow", false)), wallbox);
        engine.registerAlgorithm("charger", 1, context -> List.of(charge()));
        engine.setShadow(true);

        engine.modified(withRecordingSink(Map.of("shadow", false, "cycleInterval", 30)));

        assertThat(engine.isShadow(), is(true));
        assertThat(engine.runCycleNow().shadowed(), hasSize(1));
        assertThat(sink.dispatched(), is(empty()));
    }

    @Test
    public void anExplicitConfigurationChangeOfTheStopItselfStillWins() {
        EnergyEngine engine = engine(withRecordingSink(Map.of("shadow", false, "stopped", true)), wallbox);
        engine.registerAlgorithm("charger", 1, context -> List.of(charge()));
        engine.setStopped(false);
        assertThat(engine.isStopped(), is(false));

        engine.modified(withRecordingSink(Map.of("shadow", false, "stopped", false)));
        assertThat(engine.isStopped(), is(false));

        engine.modified(withRecordingSink(Map.of("shadow", false, "stopped", true)));
        assertThat(engine.isStopped(), is(true));
    }

    @Test
    public void aServiceBindingDoesNotReleaseTheMasterStopEither() {
        EnergyEngine engine = engine(withRecordingSink(Map.of("shadow", false)), wallbox);
        engine.setStopped(true);

        engine.addActuationSink(unrelatedSink("unrelated"));

        assertThat(engine.isStopped(), is(true));
    }

    /**
     * A service binding used to be routed through the same path as a configuration edit, and the only configuration
     * it had to hand was the effective one - runtime override already folded in. The binding therefore read as an
     * operator changing the configured value, cleared the override, and let the <em>next</em> unrelated edit restore
     * the ConfigAdmin setting. The engine came out of a master stop nobody had released.
     * <p>
     * Asserting the state immediately after the bind cannot see this: the stop is still engaged at that point. The
     * regression only appears one configuration edit later, which is what these three tests do.
     */
    @Test
    public void aMasterStopSurvivesAServiceBindingFollowedByAnUnrelatedEdit() {
        EnergyEngine engine = engine(withRecordingSink(Map.of("shadow", false)), wallbox);
        engine.setStopped(true);

        engine.addActuationSink(unrelatedSink("unrelated"));
        engine.modified(withRecordingSink(Map.of("shadow", false, "cycleInterval", 30)));

        assertThat(engine.isStopped(), is(true));
    }

    @Test
    public void shadowEngagedAtRuntimeSurvivesAServiceBindingFollowedByAnUnrelatedEdit() {
        EnergyEngine engine = engine(withRecordingSink(Map.of("shadow", false)), wallbox);
        engine.registerAlgorithm("charger", 1, context -> List.of(charge()));
        engine.setShadow(true);

        engine.addActuationSink(unrelatedSink("unrelated"));
        engine.modified(withRecordingSink(Map.of("shadow", false, "cycleInterval", 30)));

        assertThat(engine.isShadow(), is(true));
        assertThat(engine.runCycleNow().shadowed(), hasSize(1));
        assertThat(sink.dispatched(), is(empty()));
    }

    @Test
    public void shadowReleasedAtRuntimeSurvivesAServiceBindingFollowedByAnUnrelatedEdit() {
        EnergyEngine engine = engine(withRecordingSink(Map.of("shadow", true)), wallbox);
        engine.setShadow(false);

        engine.addActuationSink(unrelatedSink("unrelated"));
        engine.modified(withRecordingSink(Map.of("shadow", true, "cycleInterval", 30)));

        assertThat(engine.isShadow(), is(false));
    }

    @Test
    public void unbindingAServiceDoesNotDisturbARuntimeStopEither() {
        ActuationSink contributed = unrelatedSink("unrelated");
        EnergyEngine engine = engine(withRecordingSink(Map.of("shadow", false)), wallbox);
        engine.addActuationSink(contributed);
        engine.setStopped(true);

        engine.removeActuationSink(contributed);
        engine.modified(withRecordingSink(Map.of("shadow", false, "cycleInterval", 30)));

        assertThat(engine.isStopped(), is(true));
    }

    /**
     * Shadow and the master stop are two controls, so shadow on its own logs the "would have applied" line the
     * requirement is demonstrated with. The prototype offered a second gate that collapsed the two onto one flag,
     * under which this line was reported as a master-stop line instead; that gate and its configuration value are
     * gone, and this asserts the surviving behaviour.
     */
    @Test
    public void theShadowLineIsLoggedWhileTheStopIsDisengaged() {
        EnergyEngine engine = engine(withRecordingSink(Map.of()), wallbox);
        engine.registerAlgorithm("charger", 1, context -> List.of(charge()));

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(engine.isShadow(), is(true));
        assertThat(engine.isStopped(), is(false));
        assertThat(outcome.shadowed(), hasSize(1));
        assertLogMessage(EnergyEngine.class, LogLevel.INFO,
                "Shadow mode: would have applied " + outcome.shadowed().get(0).describe());
        assertThat(sink.dispatched(), is(empty()));
    }

    /**
     * What a stopped engine says is <em>that it is stopped and what that costs</em>, not what it would have done: it
     * never evaluated, so it does not know. The warning naming the unenforced protections is written once per
     * engagement, not once per cycle.
     */
    @Test
    public void aStoppedEngineReportsThatItIsEnforcingNothing() {
        EnergyEngine engine = engine(withRecordingSink(Map.of("shadow", false)), wallbox);
        engine.registerAlgorithm("charger", 1, context -> List.of(charge()));
        engine.setStopped(true);

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.outcomes(), is(empty()));
        assertThat(outcome.stopped(), is(true));
        assertLogMessage(EnergyEngine.class, LogLevel.WARN,
                "The energy engine master stop is engaged: no evaluation runs, no contributed algorithm is invoked, "
                        + "and the device protections the engine would enforce are not enforced while it lasts");

        // a second stopped cycle repeats nothing
        stopInterceptedLogger(EnergyEngine.class);
        setupInterceptedLogger(EnergyEngine.class, LogLevel.INFO);
        engine.runCycleNow();
        assertNoLogMessage(EnergyEngine.class);
    }

    /**
     * The one place a {@code stopped} outcome still comes from: a cycle already in flight when the stop lands. Its
     * remaining decisions are not dispatched and are named, which is the narrow home the halt-everything reading
     * leaves that outcome value.
     */
    @Test
    public void aCycleAlreadyInFlightWhenTheStopLandsDispatchesNothingFurther() {
        EnergyEngine engine = engine(withRecordingSink(Map.of("shadow", false)), wallbox);
        engine.registerAlgorithm("charger", 1, context -> {
            engine.setStopped(true);
            return List.of(charge());
        });

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.withStatus(DecisionStatus.STOPPED), hasSize(1));
        assertThat(outcome.applied(), is(empty()));
        assertThat(sink.dispatched(), is(empty()));
        Decision blocked = outcome.withStatus(DecisionStatus.STOPPED).get(0);
        assertLogMessage(EnergyEngine.class, LogLevel.INFO,
                "Master stop engaged: would have applied " + blocked.describe());
    }

    /**
     * The same stop, arriving from the configuration admin rather than through {@code setStopped}, stops the same
     * cycle at the same point. One control must not have two behaviours decided by which route the operator took, so
     * a configuration landing mid-cycle reconfigures the gate the cycle is already holding rather than handing the
     * engine a second one the cycle in flight will never look at.
     */
    @Test
    public void aConfiguredStopLandingMidCycleAlsoDispatchesNothingFurther() {
        EnergyEngine engine = engine(withRecordingSink(Map.of("shadow", false)), wallbox);
        engine.registerAlgorithm("charger", 1, context -> {
            engine.modified(withRecordingSink(Map.of("shadow", false, "stopped", true)));
            return List.of(charge());
        });

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.withStatus(DecisionStatus.STOPPED), hasSize(1));
        assertThat(outcome.applied(), is(empty()));
        assertThat(sink.dispatched(), is(empty()));
    }

    /**
     * Nothing contributed runs while the engine is stopped - not "runs and is ignored", not at all. A stop that
     * relied on every add-on to no-op would have stopped the engine's writes rather than the site's.
     */
    @Test
    public void nothingContributedRunsWhileTheEngineIsStopped() {
        List<String> invocations = new ArrayList<>();
        EnergyEngine engine = engine(withRecordingSink(Map.of("shadow", false)), wallbox);
        engine.registerAlgorithm("spy", 1, context -> {
            invocations.add("evaluated");
            return List.of(charge());
        });

        engine.setStopped(true);
        engine.runCycleNow();
        assertThat(invocations, is(empty()));
        assertThat(engine.triggerEvaluation(), is(false));

        engine.setStopped(false);
        engine.runCycleNow();
        assertThat(invocations, hasSize(1));
    }

    /**
     * Releasing the stop starts from a clean slate for what the engine itself held: an acknowledgement window opened
     * before the stop is not still counting down afterwards, because nobody is waiting for that command any more.
     */
    @Test
    public void releasingTheStopStartsFromACleanSlateForWhatTheEngineHeld() {
        reader.put("wallbox_Current", new DecimalType(10));
        EnergyEngine engine = engine(withRecordingSink(Map.of("shadow", false)), wallbox);
        engine.registerAlgorithm("charger", 1, context -> List.of(charge()));
        assertThat(engine.runCycleNow().applied(), hasSize(1));

        engine.setStopped(true);
        engine.setStopped(false);

        // without the reset this would come back SUPPRESSED, waiting on a command nobody is tracking any more
        assertThat(engine.runCycleNow().applied(), hasSize(1));
        assertThat(sink.dispatched(), hasSize(2));
    }

    @Test
    public void aBoundActuationSinkIsNotUsedUntilAnOperatorNamesIt() {
        EnergyEngine engine = engine(Map.of("shadow", false), wallbox);
        engine.registerAlgorithm("charger", 1, context -> List.of(charge()));

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.applied(), hasSize(1));
        assertThat(sink.dispatched(), is(empty()));
    }

    @Test
    public void namingTheSinkIsWhatPutsItInCharge() {
        EnergyEngine engine = engine(Map.of("shadow", false, "actuationSink", "recording"), wallbox);
        engine.registerAlgorithm("charger", 1, context -> List.of(charge()));

        engine.runCycleNow();

        assertThat(sink.dispatched(), hasSize(1));
    }

    @Test
    public void aSinkThatIsNamedButAbsentFallsBackToLoggingRatherThanFailing() {
        EnergyEngine engine = engine(Map.of("shadow", false, "actuationSink", "nowhere"), wallbox);
        engine.registerAlgorithm("charger", 1, context -> List.of(charge()));

        assertThat(engine.runCycleNow().applied(), hasSize(1));
        assertThat(sink.dispatched(), is(empty()));
    }

    @Test
    public void twoSinksNeverRaceForTheSiteSinceOnlyTheNamedOneIsUsed() {
        List<Decision> otherDispatched = new ArrayList<>();
        ActuationSink other = new ActuationSink() {
            @Override
            public String getId() {
                return "other";
            }

            @Override
            public void dispatch(Decision decision, EnergyContext context) {
                otherDispatched.add(decision);
            }
        };
        EnergyEngine engine = engine(Map.of("shadow", false, "actuationSink", "other"), wallbox);
        engine.addActuationSink(other);
        engine.registerAlgorithm("charger", 1, context -> List.of(charge()));

        engine.runCycleNow();

        assertThat(sink.dispatched(), is(empty()));
        assertThat(otherDispatched, hasSize(1));
    }

    /**
     * The requirement's "one site-wide sink, with a per-participant override": a participant that names its own sink
     * is written through that one, and every participant that names none keeps the site-wide sink.
     */
    @Test
    public void aParticipantMayNameItsOwnSinkAndTheRestKeepTheSiteWideOne() {
        RecordingSink ocpp = new RecordingSink("ocpp");
        EnergyConsumer viaOcpp = wallbox("wallbox", 1, 6, 32).withSink("ocpp");
        EnergyConsumer boiler = simple("boiler", 2, 2000);
        EnergyEngine engine = engine(Map.of("shadow", false, "actuationSink", "recording"), viaOcpp, boiler);
        engine.addActuationSink(ocpp);
        engine.registerAlgorithm("charger", 1,
                context -> List.of(charge(), Decision.of("boiler", ControlAction.on(), "charger", 2)));

        engine.runCycleNow();

        assertThat("the participant's own sink took its decision", ocpp.dispatched(), hasSize(1));
        assertThat(ocpp.dispatched().get(0).participantId(), is("wallbox"));
        assertThat("and only its own", sink.dispatched(), hasSize(1));
        assertThat(sink.dispatched().get(0).participantId(), is("boiler"));
    }

    /**
     * A sink a participant names but that is not installed logs instead of dispatching. It is deliberately not
     * replaced by the site-wide one: a named sink that is missing means the site is unsteered, which is safer than
     * being steered through a component nobody chose for that device.
     */
    @Test
    public void aParticipantSinkThatIsNamedButAbsentDoesNotFallBackToTheSiteWideOne() {
        EnergyConsumer viaMissing = wallbox("wallbox", 1, 6, 32).withSink("nowhere");
        EnergyEngine engine = engine(Map.of("shadow", false, "actuationSink", "recording"), viaMissing);
        engine.registerAlgorithm("charger", 1, context -> List.of(charge()));

        assertThat(engine.runCycleNow().applied(), hasSize(1));
        assertThat(sink.dispatched(), is(empty()));
    }

    @Test
    public void anOutstandingCommandSurvivesTheTrackerBeingRebuilt() {
        reader.put("wallbox_Current", new DecimalType(10));
        EnergyEngine engine = engine(withRecordingSink(Map.of("shadow", false)), wallbox);
        engine.registerAlgorithm("charger", 1, context -> List.of(charge()));
        assertThat(engine.runCycleNow().applied(), hasSize(1));

        // an unrelated service binding rebuilds the tracker mid-flight
        engine.addActuationSink(unrelatedSink("unrelated"));
        clock.advance(Duration.ofSeconds(5));

        CycleOutcome second = engine.runCycleNow();
        assertThat(second.withStatus(DecisionStatus.SUPPRESSED), hasSize(1));
        assertThat(sink.dispatched(), hasSize(1));
    }

    @Test
    public void aCoreShippedAlgorithmIsBoundExactlyLikeAContributedOne() {
        EnergyConsumer boiler = simple("boiler", 2, 2000);
        reader.putWatts("Grid_Power", 6000);
        reader.put("boiler_Switch", OnOffType.OFF);
        EnergyEngine engine = engine(withRecordingSink(Map.of()), grid, boiler);

        engine.addAlgorithm(new DefaultSurplusAlgorithm());
        assertThat(engine.algorithms().stream().map(algorithm -> algorithm.getId()).toList(),
                hasItem(DefaultSurplusAlgorithm.ID));

        // and removing it is equally ordinary: an add-on going away leaves the engine with nothing to run
        engine.removeAlgorithm(engine.algorithms().get(0));
        assertThat(engine.algorithms(), is(empty()));
        assertThat(engine.runCycleNow().outcomes(), is(empty()));
    }

    @Test
    public void aContributedAlgorithmCanDisplaceTheCoreShippedOneOnItsOwnId() {
        EnergyConsumer boiler = simple("boiler", 2, 2000);
        reader.putWatts("Grid_Power", 6000);
        reader.put("boiler_Switch", OnOffType.OFF);
        EnergyEngine engine = engine(withRecordingSink(Map.of()), grid, boiler);
        engine.addAlgorithm(new DefaultSurplusAlgorithm());

        // a contribution registered under the core algorithm's own id replaces it entirely
        engine.registerAlgorithm(DefaultSurplusAlgorithm.ID, 1,
                context -> List.of(Decision.of("boiler", ControlAction.off(), DefaultSurplusAlgorithm.ID, 1)));

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.shadowed(), hasSize(1));
        assertThat(outcome.shadowed().get(0).action(), is(ControlAction.off()));
    }

    @Test
    public void anExportedRegistryIsHowSomethingOutsideTheBundleContributes() {
        EnergyAlgorithmRegistry registry = engine(withRecordingSink(Map.of()), grid);

        registry.registerAlgorithm("outside", 1, context -> List.of());

        assertThat(registry.algorithms().stream().map(algorithm -> algorithm.getId()).toList(), hasItem("outside"));
        assertThat(registry.unregisterAlgorithm("outside"), is(true));
        assertThat(registry.algorithms(), is(empty()));
    }

    /**
     * Every decision a cycle sees ends in exactly one named outcome carrying a free-text reason, and the finished
     * cycle is readable as a structure rather than only as log lines. That is the half of the observability
     * requirement this bundle can satisfy; publishing it as events, over REST or as an engine status Item all need a
     * write, and this bundle is structurally incapable of one.
     */
    @Test
    public void everyDecisionEndsInANamedOutcomeAndTheCycleIsReadable() {
        EnergyEngine engine = engine(withRecordingSink(Map.of("shadow", false)), wallbox);
        engine.registerAlgorithm("charger", 1, context -> List.of(charge()));
        engine.registerAlgorithm("other", 5,
                context -> List.of(Decision.of("wallbox", ControlAction.amperes(6), "other", 5)));

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.outcomes(), hasSize(2));
        for (DecisionOutcome each : outcome.outcomes()) {
            assertThat(each.status(), is(notNullValue()));
            assertThat("every outcome carries a reason", each.detail(), is(not(emptyString())));
        }
        assertThat(outcome.withStatus(DecisionStatus.SUPERSEDED), hasSize(1));
        assertThat(engine.lastCycle(), is(sameInstance(outcome)));
    }

    /**
     * A decision naming a participant the cycle does not know is rejected with a reason that names it, and counted -
     * an algorithm addressing a device nobody declared is a configuration fault somebody has to be able to see.
     */
    @Test
    public void aDecisionForAnUnknownParticipantIsRejectedWithAReasonAndCounted() {
        EnergyEngine engine = engine(withRecordingSink(Map.of("shadow", false)), wallbox);
        engine.registerAlgorithm("ghosts", 1,
                context -> List.of(Decision.of("not-declared", ControlAction.on(), "ghosts", 1)));

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.withStatus(DecisionStatus.REJECTED), hasSize(1));
        assertThat(outcome.outcomes().get(0).detail(), containsString("not-declared"));
        assertThat(engine.rejectedDecisionCount(), is(1L));
        engine.runCycleNow();
        assertThat(engine.rejectedDecisionCount(), is(2L));
        assertThat(sink.dispatched(), is(empty()));
    }

    @Test
    public void theOnlySinkTheBundleShipsWritesNothing() {
        ActuationSink logging = new LoggingActuationSink();
        EnergyContext context = context(EnergyLevel.NORMAL).build();

        logging.dispatch(charge(), context);

        assertThat(logging.getId(), is("log"));
        assertThat(logging.tracksAcknowledgements(), is(false));
    }
}
