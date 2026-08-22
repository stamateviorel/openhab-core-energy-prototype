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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.DecisionKind;
import org.openhab.core.energy.EnergyAlgorithm;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.LevelGate;
import org.openhab.core.energy.ParticipantState;
import org.openhab.core.energy.ProtectionHistory;
import org.openhab.core.energy.SimpleProfile;

/**
 * Conflict resolution: the <em>Deterministic conflict resolution</em> requirement and its "Higher priority wins"
 * scenario, plus the isolation an engine needs when one contributed algorithm misbehaves.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EvaluationPassTest {

    private final EnergyConsumer boiler = simple("boiler", 2, 3000);

    /**
     * A fridge that declares both halves of a duty cycle: a ten-minute cooldown and a half-hour guarantee. It is the
     * participant the D25 corroboration tests need, because a claim can only be corroborated against a declaration
     * that carries the protection being claimed.
     */
    private final EnergyConsumer fridge = EnergyConsumer.of("fridge", "fridge_Switch", new SimpleProfile(null, null,
            null, null, Duration.ofMinutes(10), Duration.ofMinutes(30), LevelGate.always()), 1);

    private EnergyContext snapshot() {
        return context(EnergyLevel.NORMAL).participant(ParticipantState.of(boiler)).build();
    }

    /**
     * The fridge off for two hours with a readable history: its declared {@code maxOff} is due and requires ON.
     *
     * @return the snapshot
     */
    private EnergyContext dutyCycleOverdue() {
        return context(EnergyLevel.NORMAL).participant(
                ParticipantState.of(fridge).withReportedState("OFF").withLastChangedAt(T0.minus(Duration.ofHours(2))))
                .build();
    }

    /**
     * The fridge off for five minutes with a readable history: its declared {@code minOff} is due and requires it to
     * stay off.
     *
     * @return the snapshot
     */
    private EnergyContext cooldownRunning() {
        return context(EnergyLevel.NORMAL).participant(
                ParticipantState.of(fridge).withReportedState("OFF").withLastChangedAt(T0.minus(Duration.ofMinutes(5))))
                .build();
    }

    /**
     * The fridge off for two hours by the first-observation clock, with no device history at all - the
     * protection-unknown condition the engine reports and never treats as evidence.
     *
     * @return the snapshot
     */
    private EnergyContext historyUnreadable() {
        return context(EnergyLevel.NORMAL).participant(ParticipantState.of(fridge).withReportedState("OFF")
                .withFirstObservedAt(T0.minus(Duration.ofHours(2))).withProtectionHistory(ProtectionHistory.NOT_KEPT))
                .build();
    }

    /**
     * An algorithm the engine wrote, standing in for the built-in protections and the floor. It may claim any rung,
     * and it can only be written here because this test lives inside the bundle: {@link EngineOwnedAlgorithm} is in a
     * package the bundle does not export, so no contribution can implement it however it is registered.
     *
     * @param id the algorithm id
     * @param priority its priority
     * @param decisions what it proposes
     * @return the algorithm
     */
    private static EnergyAlgorithm engineOwned(String id, int priority, Decision... decisions) {
        record Owned(String id, int priority,
                List<Decision> decisions) implements EnergyAlgorithm, EngineOwnedAlgorithm {

            @Override
            public String getId() {
                return id;
            }

            @Override
            public int getPriority() {
                return priority;
            }

            @Override
            public List<Decision> evaluate(EnergyContext context) {
                return decisions;
            }
        }
        return new Owned(id, priority, List.of(decisions));
    }

    private static EnergyAlgorithm algorithm(String id, int priority, Decision... decisions) {
        return new EnergyAlgorithm() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public int getPriority() {
                return priority;
            }

            @Override
            public List<Decision> evaluate(EnergyContext context) {
                return List.of(decisions);
            }
        };
    }

    @Test
    public void theHigherPriorityDecisionWinsAndTheOtherIsRecordedAsSuperseded() {
        EvaluationPass pass = new EvaluationPass();
        Decision shedding = Decision.of("boiler", ControlAction.off(), "shedding", 1);
        Decision optimizing = Decision.of("boiler", ControlAction.on(), "optimizing", 5);

        EvaluationPass.Result result = pass.run(snapshot(),
                List.of(algorithm("optimizing", 5, optimizing), algorithm("shedding", 1, shedding)));

        assertThat(result.winners(), is(List.of(shedding)));
        assertThat(result.discarded(), hasSize(1));
        assertThat(result.discarded().get(0).decision(), is(optimizing));
        assertThat(result.discarded().get(0).status(), is(DecisionStatus.SUPERSEDED));
        assertThat(result.discarded().get(0).detail(), containsString("shedding"));
    }

    @Test
    public void theOutcomeIsIdenticalWhateverTheRegistrationOrder() {
        EvaluationPass pass = new EvaluationPass();
        List<EnergyAlgorithm> algorithms = new ArrayList<>(
                List.of(algorithm("a", 4, Decision.of("boiler", ControlAction.on(), "a", 4)),
                        algorithm("b", 2, Decision.of("boiler", ControlAction.off(), "b", 2)),
                        algorithm("c", 7, Decision.of("boiler", ControlAction.on(), "c", 7)),
                        algorithm("d", 2, Decision.of("boiler", ControlAction.off(), "d", 2))));

        List<Decision> reference = pass.run(snapshot(), algorithms).winners();
        for (int shuffle = 0; shuffle < 25; shuffle++) {
            Collections.shuffle(algorithms);
            assertThat(pass.run(snapshot(), algorithms).winners(), is(reference));
        }
        assertThat(reference.get(0).algorithmId(), is("b"));
    }

    /**
     * The ladder is fixed - <em>electrical limits &gt; device protections &gt; level gates &gt; optimization</em> -
     * and neither a configuration value nor a contributed service can reorder it. The rung decides before the
     * priority does, so an optimizer with the best priority on the site still loses its participant to a protection.
     * The prototype shipped a second, protections-first ladder selectable by configuration; it is gone.
     */
    @Test
    public void theFixedLadderOutranksThePriorityAndNothingCanReorderIt() {
        Decision limit = new Decision("boiler", ControlAction.off(), "limits", 9, DecisionKind.ELECTRICAL_LIMIT, "");
        Decision protection = new Decision("boiler", ControlAction.on(), "protections", 5,
                DecisionKind.DEVICE_PROTECTION, "");
        Decision gate = new Decision("boiler", ControlAction.off(), "gates", 3, DecisionKind.LEVEL_GATE, "");
        Decision optimization = new Decision("boiler", ControlAction.on(), "optimizer", 0, DecisionKind.OPTIMIZATION,
                "");
        EnergyAlgorithm limits = engineOwned("limits", 9, limit);
        EnergyAlgorithm protections = engineOwned("protections", 5, protection);
        EnergyAlgorithm gates = algorithm("gates", 3, gate);
        EnergyAlgorithm optimizer = algorithm("optimizer", 0, optimization);

        assertThat(new EvaluationPass().run(snapshot(), List.of(optimizer, gates, protections, limits)).winners(),
                is(List.of(limit)));
        assertThat(new EvaluationPass().run(snapshot(), List.of(optimizer, gates, protections)).winners(),
                is(List.of(protection)));
        assertThat(new EvaluationPass().run(snapshot(), List.of(optimizer, gates)).winners(), is(List.of(gate)));
    }

    /**
     * A contributed claim to the device-protection rung with nothing behind it is demoted to the level gate, judged
     * there like any other contributed proposal, and the demotion is reported on the decision itself.
     * <p>
     * The boiler of this fixture declares no protection parameters at all, which is the requirement's own
     * <em>A claim with nothing behind it</em> scenario. Before owner decision D25 this test asserted the strict cap -
     * that <em>no</em> contributed decision ever rose above the level gate - and it still proves the half of that
     * which survives: a label alone buys nothing. What changed is that a claim the engine can check for itself is
     * now honoured (see the three tests below), so the general assertion the old name made is no longer true.
     */
    @Test
    public void aContributedClaimWithNothingBehindItIsDemotedToTheLevelGate() {
        Decision claimed = new Decision("boiler", ControlAction.on(), "rogue", 0, DecisionKind.DEVICE_PROTECTION, "");

        EvaluationPass.Result result = new EvaluationPass().run(snapshot(), List.of(algorithm("rogue", 0, claimed)));

        assertThat(result.winners(), hasSize(1));
        Decision demoted = result.winners().get(0);
        assertThat(demoted.kind(), is(DecisionKind.LEVEL_GATE));
        assertThat("everything but the rung and the reason survives the demotion", demoted.withReason(claimed.reason()),
                is(claimed.withKind(DecisionKind.LEVEL_GATE)));
        assertThat("the contributor is told why, on the decision rather than only in a log line", demoted.reason(),
                allOf(containsString("demoted"), containsString("no protection parameters")));
    }

    /**
     * The binding that really does know the compressor. A contributed algorithm proposing exactly what the
     * participant's own declared, currently-due protection requires is honoured at the device-protection rung.
     * <p>
     * Source: owner decision D25. This is the case the strict cap the slice shipped made unshippable.
     */
    @Test
    public void aContributedClaimTheDeclarationCorroboratesIsHonouredAtThatRung() {
        Decision claimed = new Decision("fridge", ControlAction.on(), "fridge-binding", 0,
                DecisionKind.DEVICE_PROTECTION, "the compressor has been off too long");

        EvaluationPass.Result result = new EvaluationPass().run(dutyCycleOverdue(),
                List.of(algorithm("fridge-binding", 0, claimed)));

        assertThat(result.winners(), is(List.of(claimed)));
    }

    /**
     * A declared protection that is due is not a general-purpose licence: the third conjunct is that the rendered
     * action is the one the protection <em>requires</em>. A cooldown that is still running requires the device to
     * stay off, so a claim to that rung in order to start it is not corroborated by it.
     */
    @Test
    public void aClaimPointingTheWrongWayIsNotCorroborated() {
        Decision claimed = new Decision("fridge", ControlAction.on(), "fridge-binding", 0,
                DecisionKind.DEVICE_PROTECTION, "start it now");

        EvaluationPass.Result result = new EvaluationPass().run(cooldownRunning(),
                List.of(algorithm("fridge-binding", 0, claimed)));

        assertThat(result.winners(), hasSize(1));
        assertThat(result.winners().get(0).kind(), is(DecisionKind.LEVEL_GATE));
        assertThat(result.winners().get(0).reason(), containsString("requires OFF"));
    }

    /**
     * Protection-unknown does not corroborate. An Item whose state history cannot be read leaves the engine's own
     * protections running on the first-observation clock - a degraded guarantee it reports rather than assumes away -
     * but a contributor's claim gets no benefit of that doubt, because nothing about it has been checked.
     */
    @Test
    public void anUnreadableHistoryDoesNotCorroborate() {
        Decision claimed = new Decision("fridge", ControlAction.on(), "fridge-binding", 0,
                DecisionKind.DEVICE_PROTECTION, "trust me");

        EvaluationPass.Result result = new EvaluationPass().run(historyUnreadable(),
                List.of(algorithm("fridge-binding", 0, claimed)));

        assertThat(result.winners(), hasSize(1));
        assertThat(result.winners().get(0).kind(), is(DecisionKind.LEVEL_GATE));
        assertThat(result.winners().get(0).reason(), containsString("state history cannot be read"));
    }

    /**
     * Corroboration is a check, not a channel: which claim is honoured is a function of the snapshot and the
     * declaration, never of which contributor asked or of the order they asked in.
     */
    @Test
    public void theSameSnapshotCorroboratesTheSameClaimEveryTime() {
        Decision honest = new Decision("fridge", ControlAction.on(), "honest", 0, DecisionKind.DEVICE_PROTECTION, "");
        Decision rogue = new Decision("fridge", ControlAction.off(), "rogue", 0, DecisionKind.DEVICE_PROTECTION, "");
        List<EnergyAlgorithm> algorithms = new ArrayList<>(
                List.of(algorithm("honest", 0, honest), algorithm("rogue", 0, rogue)));
        EnergyContext snapshot = dutyCycleOverdue();

        for (int shuffle = 0; shuffle < 10; shuffle++) {
            Collections.shuffle(algorithms);
            EvaluationPass.Result result = new EvaluationPass().run(snapshot, algorithms);
            assertThat(result.winners(), is(List.of(honest)));
            assertThat(result.discarded().get(0).decision().kind(), is(DecisionKind.LEVEL_GATE));
        }
    }

    /**
     * The electrical-limit rung stays closed to contributors, because a site's limits are the engine's own inputs
     * and there is no participant declaration to corroborate a claim against. Owner decision D25 names the
     * <em>protection</em> rung and nothing else, and the peak-shaver half of the question the slice raised is
     * recorded as still open rather than read into an answer that did not mention it.
     */
    @Test
    public void aContributedClaimToTheElectricalLimitRungIsNeverCorroborated() {
        Decision claimed = new Decision("fridge", ControlAction.on(), "peak-shaver", 0, DecisionKind.ELECTRICAL_LIMIT,
                "");

        EvaluationPass.Result result = new EvaluationPass().run(dutyCycleOverdue(),
                List.of(algorithm("peak-shaver", 0, claimed)));

        assertThat(result.winners(), hasSize(1));
        assertThat(result.winners().get(0).kind(), is(DecisionKind.LEVEL_GATE));
        assertThat(result.winners().get(0).reason(), containsString("engine's inputs"));
    }

    /**
     * The genuine protection therefore still wins the participant when a contribution claims the same rung against
     * it, whatever priority the contribution gives itself.
     */
    @Test
    public void aClaimedProtectionDoesNotBeatTheEnginesOwnProtection() {
        Decision claimed = new Decision("boiler", ControlAction.off(), "device-protection", 0,
                DecisionKind.DEVICE_PROTECTION, "");
        Decision genuine = new Decision("boiler", ControlAction.on(), "device-protection", 0,
                DecisionKind.DEVICE_PROTECTION, "minOn");

        EvaluationPass.Result result = new EvaluationPass().run(snapshot(),
                List.of(algorithm("rogue", 0, claimed), engineOwned("device-protection", 0, genuine)));

        assertThat(result.winners(), is(List.of(genuine)));
    }

    @Test
    public void anAlgorithmThatThrowsLosesOnlyItsOwnProposals() {
        EvaluationPass pass = new EvaluationPass();
        Decision survivor = Decision.of("boiler", ControlAction.on(), "sane", 5);
        EnergyAlgorithm broken = new EnergyAlgorithm() {
            @Override
            public String getId() {
                return "broken";
            }

            @Override
            public int getPriority() {
                return 1;
            }

            @Override
            public List<Decision> evaluate(EnergyContext context) {
                throw new IllegalStateException("deliberate failure");
            }
        };

        EvaluationPass.Result result = pass.run(snapshot(), List.of(broken, algorithm("sane", 5, survivor)));

        assertThat(result.winners(), is(List.of(survivor)));
    }

    @Test
    public void aDecisionForAnUnknownParticipantIsRejectedRatherThanDispatched() {
        EvaluationPass pass = new EvaluationPass();
        Decision ghost = Decision.of("not-declared", ControlAction.on(), "ghosts", 1);

        EvaluationPass.Result result = pass.run(snapshot(), List.of(algorithm("ghosts", 1, ghost)));

        assertThat(result.winners(), is(empty()));
        assertThat(result.discarded().get(0).status(), is(DecisionStatus.REJECTED));
        // the reason names the participant, so an operator can find the algorithm that is addressing a ghost
        assertThat(result.discarded().get(0).detail(), containsString("not-declared"));
    }
}
