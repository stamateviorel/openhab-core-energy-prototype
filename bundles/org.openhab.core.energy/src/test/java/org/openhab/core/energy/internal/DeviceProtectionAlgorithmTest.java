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

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.DecisionKind;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.energy.LevelGate;
import org.openhab.core.energy.SimpleProfile;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * The two scenarios of the <em>Simple-consumer protection parameters</em> requirement - the fridge duty-cycle
 * guarantee and the compressor cooldown - plus the two remaining parameters and the precedence question they make
 * reachable.
 * <p>
 * These were the two wave-1 scenarios the prototype could originally only declare, never exercise. They are also
 * the only route by which a {@link DecisionKind#DEVICE_PROTECTION} decision exists at all, and therefore the only
 * route by which {@code define-engine-contract/design.md} §5 - protections versus the electrical-limit floor - is
 * reachable outside a hand-built test decision.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class DeviceProtectionAlgorithmTest {

    private final MutableClock clock = new MutableClock(T0);
    private final MapItemStateReader reader = new MapItemStateReader();
    private final RecordingSink sink = new RecordingSink();
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

    private EnergyEngine engine(Map<String, Object> configuration, EnergyParticipant... participants) {
        EnergyEngine engine = new EnergyEngine(scheduler, clock, reader, withRecordingSink(configuration));
        engine.setParticipantSnapshotSource(new FixedParticipants(participants));
        engine.addActuationSink(sink);
        engine.addAlgorithm(new DeviceProtectionAlgorithm());
        return engine;
    }

    /**
     * Builds a Simple consumer with protection times.
     *
     * @param id the participant and Item name
     * @param minOn the minimum ON runtime, or {@code null}
     * @param maxOn the maximum ON runtime, or {@code null}
     * @param minOff the cooldown, or {@code null}
     * @param maxOff the duty-cycle guarantee, or {@code null}
     * @return the consumer
     */
    private static EnergyConsumer protectedConsumer(String id, @Nullable Duration minOn, @Nullable Duration maxOn,
            @Nullable Duration minOff, @Nullable Duration maxOff) {
        return EnergyConsumer.of(id, id, new SimpleProfile(new QuantityType<Power>(200, Units.WATT), null, minOn, maxOn,
                minOff, maxOff, LevelGate.always()), 1);
    }

    @Test
    public void aFridgeOffLongerThanItsDutyCycleIsSwitchedBackOnRegardlessOfPrice() {
        EnergyConsumer fridge = protectedConsumer("fridge", null, null, null, Duration.ofMinutes(30));
        reader.put("fridge", OnOffType.OFF);
        reader.putLastChange("fridge", T0.minus(Duration.ofMinutes(31)));
        EnergyEngine engine = engine(Map.of(), fridge);

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.shadowed(), hasSize(1));
        Decision decision = outcome.shadowed().get(0);
        assertThat(decision.action(), is(ControlAction.on()));
        assertThat(decision.kind(), is(DecisionKind.DEVICE_PROTECTION));
        assertThat(decision.describe(), containsString("maxOff"));
    }

    @Test
    public void theSameFridgeIsLeftAloneWhileItIsStillInsideItsDutyCycle() {
        EnergyConsumer fridge = protectedConsumer("fridge", null, null, null, Duration.ofMinutes(30));
        reader.put("fridge", OnOffType.OFF);
        reader.putLastChange("fridge", T0.minus(Duration.ofMinutes(29)));

        assertThat(engine(Map.of(), fridge).runCycleNow().outcomes(), is(empty()));
    }

    @Test
    public void aCompressorInsideItsCooldownIsKeptOff() {
        EnergyConsumer compressor = protectedConsumer("compressor", null, null, Duration.ofMinutes(10), null);
        reader.put("compressor", OnOffType.OFF);
        reader.putLastChange("compressor", T0.minus(Duration.ofMinutes(4)));

        CycleOutcome outcome = engine(Map.of(), compressor).runCycleNow();

        assertThat(outcome.shadowed(), hasSize(1));
        assertThat(outcome.shadowed().get(0).action(), is(ControlAction.off()));
        assertThat(outcome.shadowed().get(0).describe(), containsString("minOff"));
    }

    @Test
    public void theCooldownDecisionOutranksAnOptimizerThatWantsToStartTheCompressor() {
        EnergyConsumer compressor = protectedConsumer("compressor", null, null, Duration.ofMinutes(10), null);
        reader.put("compressor", OnOffType.OFF);
        reader.putLastChange("compressor", T0.minus(Duration.ofMinutes(4)));
        EnergyEngine engine = engine(Map.of("shadow", false), compressor);
        engine.registerAlgorithm("optimizer", 5,
                context -> List.of(Decision.of("compressor", ControlAction.on(), "optimizer", 5)));

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.applied(), hasSize(1));
        assertThat(outcome.applied().get(0).action(), is(ControlAction.off()));
        assertThat(outcome.applied().get(0).kind(), is(DecisionKind.DEVICE_PROTECTION));
        assertThat(outcome.withStatus(DecisionStatus.SUPERSEDED), hasSize(1));
    }

    @Test
    public void aRunningDeviceInsideItsMinimumRuntimeIsKeptOn() {
        EnergyConsumer compressor = protectedConsumer("compressor", Duration.ofMinutes(15), null, null, null);
        reader.put("compressor", OnOffType.ON);
        reader.putLastChange("compressor", T0.minus(Duration.ofMinutes(5)));

        CycleOutcome outcome = engine(Map.of(), compressor).runCycleNow();

        assertThat(outcome.shadowed(), hasSize(1));
        assertThat(outcome.shadowed().get(0).action(), is(ControlAction.on()));
        assertThat(outcome.shadowed().get(0).describe(), containsString("minOn"));
    }

    @Test
    public void aDeviceThatHasRunLongerThanItsMaximumIsSwitchedOff() {
        EnergyConsumer heater = protectedConsumer("heater", null, Duration.ofHours(2), null, null);
        reader.put("heater", OnOffType.ON);
        reader.putLastChange("heater", T0.minus(Duration.ofHours(3)));

        CycleOutcome outcome = engine(Map.of(), heater).runCycleNow();

        assertThat(outcome.shadowed(), hasSize(1));
        assertThat(outcome.shadowed().get(0).action(), is(ControlAction.off()));
        assertThat(outcome.shadowed().get(0).describe(), containsString("maxOn"));
    }

    /**
     * A protection whose elapsed time cannot be read is <strong>reported</strong>, and its clock starts at the first
     * cycle that observed the device - it is not silently disabled, which is what the prototype did. Nothing fires
     * yet, because nothing has been observed for long enough to prove a duty cycle has expired, and that is the
     * conservative half of the answer.
     */
    @Test
    public void aProtectionWhoseHistoryIsUnreadableIsReportedRatherThanSilentlyDropped() {
        EnergyConsumer fridge = protectedConsumer("fridge", null, null, null, Duration.ofMinutes(30));
        reader.put("fridge", OnOffType.OFF);
        DeviceProtectionAlgorithm protections = new DeviceProtectionAlgorithm();
        EnergyEngine engine = new EnergyEngine(scheduler, clock, reader, withRecordingSink(Map.of()));
        engine.setParticipantSnapshotSource(new FixedParticipants(fridge));
        engine.addAlgorithm(protections);

        assertThat(engine.runCycleNow().outcomes(), is(empty()));
        assertThat(protections.protectionUnknown(), contains("fridge"));

        // the clock started at the first observation, so the duty cycle expires half an hour after it, not never
        clock.advance(Duration.ofMinutes(31));
        CycleOutcome later = engine.runCycleNow();
        assertThat(later.shadowed(), hasSize(1));
        assertThat(later.shadowed().get(0).action(), is(ControlAction.on()));
    }

    /**
     * The report clears the moment real history becomes readable, and the protection goes back to being measured
     * from the device rather than from the engine's first sight of it.
     */
    @Test
    public void theProtectionUnknownReportClearsOnceTheHistoryIsReadable() {
        EnergyConsumer fridge = protectedConsumer("fridge", null, null, null, Duration.ofMinutes(30));
        reader.put("fridge", OnOffType.OFF);
        DeviceProtectionAlgorithm protections = new DeviceProtectionAlgorithm();
        EnergyEngine engine = new EnergyEngine(scheduler, clock, reader, withRecordingSink(Map.of()));
        engine.setParticipantSnapshotSource(new FixedParticipants(fridge));
        engine.addAlgorithm(protections);
        engine.runCycleNow();
        assertThat(protections.protectionUnknown(), contains("fridge"));

        reader.putLastChange("fridge", T0.minus(Duration.ofMinutes(10)));
        engine.runCycleNow();

        assertThat(protections.protectionUnknown(), is(empty()));
    }

    /**
     * A consumer with no declared protection is not reported as protection-unknown: it has no guarantee to degrade.
     */
    @Test
    public void aConsumerWithoutProtectionsIsNotReportedAsUnknown() {
        EnergyConsumer plain = protectedConsumer("plain", null, null, null, null);
        reader.put("plain", OnOffType.OFF);
        DeviceProtectionAlgorithm protections = new DeviceProtectionAlgorithm();
        EnergyEngine engine = new EnergyEngine(scheduler, clock, reader, withRecordingSink(Map.of()));
        engine.setParticipantSnapshotSource(new FixedParticipants(plain));
        engine.addAlgorithm(protections);

        engine.runCycleNow();

        assertThat(protections.protectionUnknown(), is(empty()));
    }

    /**
     * A start the engine did not command starts the minimum runtime exactly as an engine-initiated start would -
     * which falls out for free, because the elapsed time is measured from the device's own last state change and the
     * algorithm never learns who caused it.
     */
    @Test
    public void aStartTheEngineDidNotCommandStartsTheMinimumRuntime() {
        EnergyConsumer compressor = protectedConsumer("compressor", Duration.ofMinutes(15), null, null, null);
        // somebody flipped it on by hand two minutes ago
        reader.put("compressor", OnOffType.ON);
        reader.putLastChange("compressor", T0.minus(Duration.ofMinutes(2)));
        EnergyEngine engine = engine(Map.of(), compressor);
        engine.registerAlgorithm("optimizer", 5,
                context -> List.of(Decision.of("compressor", ControlAction.off(), "optimizer", 5)));

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.shadowed(), hasSize(1));
        assertThat(outcome.shadowed().get(0).action(), is(ControlAction.on()));
        assertThat(outcome.shadowed().get(0).kind(), is(DecisionKind.DEVICE_PROTECTION));
    }

    @Test
    public void aHandsOffDeviceIsNotEvenProtected() {
        EnergyConsumer manual = EnergyConsumer
                .of("manual", "manual",
                        new SimpleProfile(null, null, null, null, null, Duration.ofMinutes(30), LevelGate.always()), 1)
                .withHandsOff();
        reader.put("manual", OnOffType.OFF);
        reader.putLastChange("manual", T0.minus(Duration.ofHours(4)));

        assertThat(engine(Map.of(), manual).runCycleNow().outcomes(), is(empty()));
    }

    /**
     * The stop halts the protections too, and that is the owner's decision rather than an oversight: a stopped engine
     * does not even invoke this algorithm, so no protection decision exists to be blocked. What the engine does
     * instead is say so - see {@code EngineControlsTest}.
     */
    @Test
    public void aProtectionIsNotEvenEvaluatedWhileTheMasterStopIsEngaged() {
        EnergyConsumer fridge = protectedConsumer("fridge", null, null, null, Duration.ofMinutes(30));
        reader.put("fridge", OnOffType.OFF);
        reader.putLastChange("fridge", T0.minus(Duration.ofHours(1)));
        EnergyEngine engine = engine(Map.of("shadow", false), fridge);
        engine.setStopped(true);

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.outcomes(), is(empty()));
        assertThat(outcome.stopped(), is(true));
        assertThat(sink.dispatched(), is(empty()));

        // and the protection fires the moment the stop is released
        engine.setStopped(false);
        assertThat(engine.runCycleNow().applied(), hasSize(1));
    }
}
