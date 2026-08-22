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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.DecisionKind;
import org.openhab.core.energy.EnergyAlgorithm;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.energy.EnergyProvider;
import org.openhab.core.energy.LevelGate;
import org.openhab.core.energy.ProviderRole;
import org.openhab.core.energy.SimpleProfile;
import org.openhab.core.library.types.OnOffType;

/**
 * The engine-owned prohibitions a single decision meets: the hands-off flag, the user-declared level gate and the
 * readiness interlock.
 * <p>
 * The point of these tests is not that the built-in algorithm honours them; that was already true. It is that a
 * <em>contributed</em> algorithm, which is what {@code define-engine-contract} promises anyone may supply, meets the
 * same prohibitions whichever algorithm produced the decision. The prototype shipped a second reading in which the
 * algorithm owned them and the engine vetoed nothing, selected by configuration; that reading is gone, and no
 * configuration brings it back.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ParticipantGuardTest {

    private final MutableClock clock = new MutableClock(T0);
    private final MapItemStateReader reader = new MapItemStateReader();
    private final RecordingSink sink = new RecordingSink();
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

    private final EnergyProvider grid = EnergyProvider.of("grid", "Grid_Power", ProviderRole.GRID);

    private EnergyEngine engine(Map<String, Object> configuration, EnergyParticipant... participants) {
        reader.putWatts("Grid_Power", 6000);
        EnergyEngine engine = new EnergyEngine(scheduler, clock, reader, withRecordingSink(configuration));
        engine.setParticipantSnapshotSource(new FixedParticipants(participants));
        engine.addActuationSink(sink);
        return engine;
    }

    @Test
    public void theBuiltInAlgorithmProposesNothingForAHandsOffDevice() {
        EnergyConsumer manual = handsOff("manual", 1, 500);
        reader.putWatts("Grid_Power", 6000);
        reader.put("manual_Switch", OnOffType.OFF);
        EnergyEngine engine = engine(Map.of(), grid, manual);
        engine.addAlgorithm(new DefaultSurplusAlgorithm());

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.outcomes(), is(empty()));
    }

    @Test
    public void aContributedAlgorithmCannotSteerAHandsOffDevice() {
        EnergyConsumer manual = handsOff("manual", 1, 500);
        reader.put("manual_Switch", OnOffType.OFF);
        EnergyEngine engine = engine(Map.of("shadow", false), grid, manual);
        engine.registerAlgorithm("eager", 1, context -> List.of(Decision.of("manual", ControlAction.on(), "eager", 1)));

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.withStatus(DecisionStatus.WITHHELD), hasSize(1));
        assertThat(outcome.withStatus(DecisionStatus.WITHHELD).get(0).participantId(), is("manual"));
        assertThat(outcome.applied(), is(empty()));
        assertThat(sink.dispatched(), is(empty()));
    }

    @Test
    public void switchingAHandsOffDeviceOffIsSteeringItTooAndIsWithheldAsWell() {
        EnergyConsumer manual = handsOff("manual", 1, 500);
        reader.put("manual_Switch", OnOffType.ON);
        EnergyEngine engine = engine(Map.of("shadow", false), grid, manual);
        engine.registerAlgorithm("eager", 1,
                context -> List.of(Decision.of("manual", ControlAction.off(), "eager", 1)));

        assertThat(engine.runCycleNow().withStatus(DecisionStatus.WITHHELD), hasSize(1));
    }

    /**
     * Whichever algorithm proposed it, the decision is withheld. This used to be the test proving that a site could
     * configure the engine to trust the algorithm instead; the prohibition is now engine-owned and closed, so what
     * is asserted is that no algorithm gets past it - the core-shipped one, a contributed service and a scripted
     * lambda alike.
     */
    @Test
    public void theProhibitionHoldsWhicheverAlgorithmProposedTheDecision() {
        EnergyConsumer manual = handsOff("manual", 1, 500);
        reader.put("manual_Switch", OnOffType.OFF);
        EnergyEngine engine = engine(Map.of("shadow", false), grid, manual);
        engine.addAlgorithm(new DefaultSurplusAlgorithm());
        engine.registerAlgorithm("eager", 1, context -> List.of(Decision.of("manual", ControlAction.on(), "eager", 1)));
        engine.addAlgorithm(new EnergyAlgorithm() {
            @Override
            public String getId() {
                return "contributed";
            }

            @Override
            public List<Decision> evaluate(EnergyContext context) {
                return List.of(Decision.of("manual", ControlAction.off(), "contributed", 2));
            }
        });

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.withStatus(DecisionStatus.WITHHELD), hasSize(1));
        assertThat(outcome.applied(), is(empty()));
        assertThat(sink.dispatched(), is(empty()));
    }

    /**
     * A hands-off device is still <em>measured</em>: the engine keeps reading everything the declaration names, so
     * marking a device hands-off never costs the electrical-limit floor its sight of the load. That is exactly why
     * the flag exists instead of users deleting the declaration.
     */
    @Test
    public void aHandsOffDeviceIsStillMeasured() {
        EnergyConsumer manual = handsOff("manual", 1, 500).withMeasurement("Manual_Power");
        reader.put("manual_Switch", OnOffType.ON);
        reader.putWatts("Manual_Power", 480);
        EnergyEngine engine = engine(Map.of(), grid, manual);

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.context().participants(), hasKey("manual"));
        assertThat(outcome.context().participants().get("manual").measuredWatts(), is(480.0));
    }

    /**
     * A hands-off declaration is available to every profile class, not only to Simple consumers - an EV or a
     * dishwasher can say it too.
     */
    @Test
    public void handsOffIsAvailableOnAClassOtherThanSimple() {
        EnergyConsumer wallbox = wallbox("wallbox", 1, 6, 32).withHandsOff();
        EnergyEngine engine = engine(Map.of("shadow", false), grid, wallbox);
        engine.registerAlgorithm("eager", 1,
                context -> List.of(Decision.of("wallbox", ControlAction.amperes(16), "eager", 1)));

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.withStatus(DecisionStatus.WITHHELD), hasSize(1));
        assertThat(sink.dispatched(), is(empty()));
    }

    /**
     * A contributed algorithm cannot lower the user's level gate. The gate is enforced by the engine for every
     * algorithm, so an algorithm that never consults it still cannot start a gated device below its level.
     */
    @Test
    public void aContributedAlgorithmCannotLowerTheUserDeclaredGate() {
        EnergyConsumer pump = gated("pump", 1, 500, LevelGate.atLeast(EnergyLevel.ENCOURAGED));
        reader.put("pump_Switch", OnOffType.OFF);
        EnergyEngine engine = engine(Map.of("shadow", false), grid, pump);
        engine.setCurrentLevelFunction(new FixedLevel(EnergyLevel.NORMAL));
        engine.registerAlgorithm("eager", 1, context -> List.of(Decision.of("pump", ControlAction.on(), "eager", 1)));

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.withStatus(DecisionStatus.WITHHELD), hasSize(1));
        assertThat(outcome.outcomes().stream().filter(each -> each.status() == DecisionStatus.WITHHELD)
                .map(DecisionOutcome::detail).toList(), hasItem(containsString("below the declared gate")));
        assertThat(sink.dispatched(), is(empty()));
    }

    /**
     * The gate keeps a device off; it never keeps one on. Switching a gated device off is always allowed.
     */
    @Test
    public void aClosedGateNeverBlocksSwitchingTheDeviceOff() {
        EnergyConsumer pump = gated("pump", 1, 500, LevelGate.atLeast(EnergyLevel.ENCOURAGED));
        reader.put("pump_Switch", OnOffType.ON);
        EnergyEngine engine = engine(Map.of("shadow", false), grid, pump);
        engine.setCurrentLevelFunction(new FixedLevel(EnergyLevel.NORMAL));
        engine.registerAlgorithm("eager", 1, context -> List.of(Decision.of("pump", ControlAction.off(), "eager", 1)));

        assertThat(engine.runCycleNow().applied(), hasSize(1));
    }

    /**
     * A device protection outranks the level gate, because that is where the ladder puts it: a compressor whose
     * duty-cycle guarantee has expired starts even in an hour the user's gate blocks.
     * <p>
     * This test used to register an <em>arbitrary</em> contributed algorithm that merely asserted
     * {@code DEVICE_PROTECTION}, which made it pass for the wrong reason - it proved the label was enough, and the
     * label is now not enough. It drives the engine's own protection algorithm off a real expired {@code maxOff}
     * instead, which is what the scenario always meant.
     */
    @Test
    public void aDeviceProtectionOutranksTheUserDeclaredGate() {
        SimpleProfile dutyCycled = new SimpleProfile(null, null, null, null, null, Duration.ofMinutes(30),
                LevelGate.atLeast(EnergyLevel.ENCOURAGED));
        EnergyConsumer pump = EnergyConsumer.of("pump", "pump_Switch", dutyCycled, 1);
        reader.put("pump_Switch", OnOffType.OFF);
        reader.putLastChange("pump_Switch", T0.minus(Duration.ofHours(2)));
        EnergyEngine engine = engine(Map.of("shadow", false), grid, pump);
        engine.setCurrentLevelFunction(new FixedLevel(EnergyLevel.NORMAL));
        engine.addAlgorithm(new DeviceProtectionAlgorithm());

        List<Decision> applied = engine.runCycleNow().applied();

        assertThat(applied, hasSize(1));
        assertThat(applied.get(0).action(), is(ControlAction.on()));
        assertThat(applied.get(0).kind(), is(DecisionKind.DEVICE_PROTECTION));
    }

    /**
     * And the label alone is not enough. A contributed algorithm that calls its own decision a device protection is
     * read at the level-gate rung, so the user's gate is consulted after all - which is the difference between a
     * prohibition the engine owns and one any add-on can talk its way past.
     */
    @Test
    public void aContributedAlgorithmCannotBypassTheGateByCallingItsDecisionADeviceProtection() {
        EnergyConsumer pump = gated("pump", 1, 500, LevelGate.atLeast(EnergyLevel.ENCOURAGED));
        reader.put("pump_Switch", OnOffType.OFF);
        EnergyEngine engine = engine(Map.of("shadow", false), grid, pump);
        engine.setCurrentLevelFunction(new FixedLevel(EnergyLevel.BLOCKED));
        engine.registerAlgorithm("rogue", 0, context -> List.of(new Decision("pump", ControlAction.on(), "rogue", 0,
                DecisionKind.DEVICE_PROTECTION, "maxOff exceeded, honest")));

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.applied(), is(empty()));
        assertThat(outcome.withStatus(DecisionStatus.WITHHELD), hasSize(1));
        assertThat(outcome.outcomes().stream().filter(each -> each.status() == DecisionStatus.WITHHELD)
                .map(DecisionOutcome::detail).toList(), hasItem(containsString("below the declared gate")));
        assertThat(sink.dispatched(), is(empty()));
    }

    @Test
    public void aConsumerWhoseReadinessInterlockIsOpenIsNotStarted() {
        EnergyConsumer boiler = EnergyConsumer.of("boiler", "boiler", SimpleProfile.withGate(LevelGate.always()), 1)
                .withReadiness("Boiler_Ready");
        reader.putWatts("Grid_Power", 6000);
        reader.put("boiler", OnOffType.OFF);
        reader.put("Boiler_Ready", OnOffType.OFF);
        EnergyEngine engine = engine(Map.of("shadow", false), grid, boiler);
        engine.registerAlgorithm("eager", 1, context -> List.of(Decision.of("boiler", ControlAction.on(), "eager", 1)));

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.withStatus(DecisionStatus.WITHHELD), hasSize(1));
        assertThat(outcome.outcomes().stream().filter(each -> each.status() == DecisionStatus.WITHHELD)
                .map(DecisionOutcome::detail).toList(), hasItem(containsString("readiness")));
    }

    @Test
    public void anOpenInterlockDoesNotStopTheEngineSwitchingTheConsumerOff() {
        EnergyConsumer boiler = EnergyConsumer.of("boiler", "boiler", SimpleProfile.withGate(LevelGate.always()), 1)
                .withReadiness("Boiler_Ready");
        reader.put("boiler", OnOffType.ON);
        reader.put("Boiler_Ready", OnOffType.OFF);
        EnergyEngine engine = engine(Map.of("shadow", false), grid, boiler);
        engine.registerAlgorithm("eager", 1,
                context -> List.of(Decision.of("boiler", ControlAction.off(), "eager", 1)));

        assertThat(engine.runCycleNow().applied(), hasSize(1));
    }

    @Test
    public void aConsumerBecomesStartableOnceItsInterlockCloses() {
        EnergyConsumer boiler = EnergyConsumer.of("boiler", "boiler", SimpleProfile.withGate(LevelGate.always()), 1)
                .withReadiness("Boiler_Ready");
        reader.put("boiler", OnOffType.OFF);
        reader.put("Boiler_Ready", OnOffType.ON);
        EnergyEngine engine = engine(Map.of("shadow", false), grid, boiler);
        engine.registerAlgorithm("eager", 1, context -> List.of(Decision.of("boiler", ControlAction.on(), "eager", 1)));

        assertThat(engine.runCycleNow().applied(), hasSize(1));
    }

    @Test
    public void aGatedConsumerIsSwitchedOffWhenTheSiteLevelDropsBelowItsGate() {
        EnergyConsumer pump = gated("pump", 1, 500, LevelGate.atLeast(EnergyLevel.ENCOURAGED));
        reader.putWatts("Grid_Power", 6000);
        reader.put("pump_Switch", OnOffType.ON);
        EnergyEngine engine = engine(Map.of(), grid, pump);
        engine.setCurrentLevelFunction(new FixedLevel(EnergyLevel.NORMAL));
        engine.addAlgorithm(new DefaultSurplusAlgorithm());

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.shadowed(), hasSize(1));
        assertThat(outcome.shadowed().get(0).action(), is(ControlAction.off()));
        assertThat(outcome.shadowed().get(0).kind(), is(DecisionKind.LEVEL_GATE));
    }
}
