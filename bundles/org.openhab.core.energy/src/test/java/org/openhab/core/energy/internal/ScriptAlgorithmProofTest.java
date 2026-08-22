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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
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
import org.openhab.core.library.types.OnOffType;

/**
 * <strong>THE SCRIPT-ALGORITHM PROOF</strong> for {@code define-engine-contract}'s <em>Replaceable algorithm,
 * scripts first-class</em> requirement: an algorithm can be supplied to the engine without being compiled into this
 * bundle, and it is then subject to exactly the same guardrails as the built-in one.
 * <p>
 * "Not compiled into the bundle" is proved twice, in increasing strength:
 * <ol>
 * <li>a <strong>lambda</strong> handed to {@code registerAlgorithm(id, priority, algorithm)} - the entry point a
 * JSR-223 rule calls;</li>
 * <li>a <strong>{@link Proxy}</strong> built at runtime from nothing but the interface and an
 * {@link InvocationHandler} that dispatches by method name. No class implementing {@link EnergyAlgorithm} exists
 * anywhere at compile time in this case; the implementing class is generated while the test runs. This is exactly
 * the mechanism openHAB's own GraalJS integration uses to bind a script object to a Java interface, so if a
 * {@code Proxy} can be an algorithm, a JavaScript object can be one.</li>
 * </ol>
 * A structural test backs both up by asserting that {@link EnergyAlgorithm} stays implementable from outside: one
 * abstract method, no OSGi type in its signature, and everything it mentions is in an exported package.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ScriptAlgorithmProofTest {

    private final MutableClock clock = new MutableClock(T0);
    private final MapItemStateReader reader = new MapItemStateReader();
    private final RecordingSink sink = new RecordingSink();
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

    private final EnergyProvider grid = EnergyProvider.of("grid", "Grid_Power", ProviderRole.GRID);

    private EnergyEngine engine(Map<String, Object> configuration, EnergyParticipant... participants) {
        EnergyEngine engine = new EnergyEngine(scheduler, clock, reader, withRecordingSink(configuration));
        engine.setParticipantSnapshotSource(new FixedParticipants(participants));
        engine.addActuationSink(sink);
        return engine;
    }

    /**
     * Builds an {@link EnergyAlgorithm} whose implementing class does not exist until this method runs - the stand-in
     * for a script-language object bound to a Java interface.
     *
     * @param id the id the algorithm reports
     * @param priority the priority the algorithm reports
     * @param body what {@code evaluate} does
     * @return the algorithm
     */
    private static EnergyAlgorithm scriptedAlgorithm(String id, int priority, EnergyAlgorithm body) {
        InvocationHandler handler = new InvocationHandler() {

            @Override
            public @Nullable Object invoke(@Nullable Object proxy, @Nullable Method method,
                    @Nullable Object @Nullable [] arguments) throws Throwable {
                String name = method == null ? "" : method.getName();
                Object[] args = arguments == null ? new Object[0] : arguments;
                return switch (name) {
                    case "evaluate" -> body.evaluate((EnergyContext) args[0]);
                    case "getId" -> id;
                    case "getPriority" -> priority;
                    case "toString" -> "scripted:" + id;
                    case "hashCode" -> id.hashCode();
                    // two proxies are the same algorithm exactly when they share this handler, which is the
                    // identity semantics the engine's collections need without comparing references by hand
                    case "equals" -> args[0] != null && Proxy.isProxyClass(args[0].getClass())
                            && Proxy.getInvocationHandler(args[0]).equals(this);
                    default -> throw new UnsupportedOperationException(name);
                };
            }
        };
        return (EnergyAlgorithm) Proxy.newProxyInstance(EnergyAlgorithm.class.getClassLoader(),
                new Class<?>[] { EnergyAlgorithm.class }, handler);
    }

    @Test
    public void aScriptSuppliesAnAlgorithmAsALambdaAndTheEngineRunsIt() {
        EnergyConsumer boiler = simple("boiler", 2, 2000);
        reader.putWatts("Grid_Power", 5000);
        EnergyEngine engine = engine(Map.of("shadow", "false"), grid, boiler);

        engine.registerAlgorithm("my-rule", 5, context -> List.of(new Decision("boiler", ControlAction.on(), "my-rule",
                5, DecisionKind.OPTIMIZATION, "the script wanted it on")));

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.applied(), hasSize(1));
        assertThat(outcome.applied().get(0).algorithmId(), is("my-rule"));
        assertThat(sink.dispatched(), hasSize(1));
    }

    @Test
    public void anAlgorithmClassThatDoesNotExistAtCompileTimeIsAcceptedAndEvaluated() {
        EnergyConsumer boiler = simple("boiler", 2, 2000);
        reader.putWatts("Grid_Power", 5000);
        EnergyEngine engine = engine(Map.of("shadow", "false"), grid, boiler);

        EnergyAlgorithm scripted = scriptedAlgorithm("js-rule", 3,
                context -> List.of(new Decision("boiler", ControlAction.on(), "js-rule", 3, DecisionKind.OPTIMIZATION,
                        "a runtime-generated algorithm proposed it")));

        // the class implementing the interface was generated while this test ran
        assertThat(Proxy.isProxyClass(scripted.getClass()), is(true));
        assertThat(scripted.getClass().getName(), not(startsWith("org.openhab.core.energy")));

        engine.registerAlgorithm(scripted);
        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.applied(), hasSize(1));
        assertThat(outcome.applied().get(0).algorithmId(), is("js-rule"));
        assertThat(engine.algorithms(), hasSize(1));
    }

    @Test
    public void aScriptedAlgorithmTakesTheSameOsgiWhiteboardPathAnAddOnTakes() {
        EnergyConsumer boiler = simple("boiler", 2, 2000);
        reader.putWatts("Grid_Power", 5000);
        EnergyEngine engine = engine(Map.of("shadow", "false"), grid, boiler);

        // addAlgorithm is the DS bind method; an add-on's service and a runtime-generated object are
        // indistinguishable to it
        engine.addAlgorithm(scriptedAlgorithm("addon-rule", 3, context -> List.of(new Decision("boiler",
                ControlAction.on(), "addon-rule", 3, DecisionKind.OPTIMIZATION, "contributed as a service"))));

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.applied(), hasSize(1));
        assertThat(outcome.applied().get(0).algorithmId(), is("addon-rule"));
    }

    @Test
    public void aScriptedAlgorithmIsSubjectToTheElectricalLimitFloorLikeAnyOther() {
        EnergyConsumer wallbox = wallbox("wallbox", 1, 6, 32);
        reader.putWatts("Grid_Power", 0);
        EnergyEngine engine = engine(Map.of("shadow", "false", "budget", "3000"), grid, wallbox);

        engine.registerAlgorithm(scriptedAlgorithm("greedy-script", 1,
                context -> List.of(new Decision("wallbox", ControlAction.amperes(32), "greedy-script", 1,
                        DecisionKind.OPTIMIZATION, "a script asking for more than the site has"))));

        CycleOutcome outcome = engine.runCycleNow();

        // 3000 W / 230 V = 13.04 A: the script asked for 32 A and the floor trimmed it, exactly as it would have
        // trimmed the built-in algorithm
        DecisionOutcome trimmed = outcome.forParticipant("wallbox").orElseThrow();
        assertThat(trimmed.trimmed(), is(true));
        assertThat(trimmed.decision().action(), is(not(ControlAction.amperes(32))));
    }

    /**
     * The guardrails are not opt-out, and the one field a script could have used to opt out of them is the
     * {@code kind} it puts on its own decision. A script calling its decision a device protection is read at the
     * level-gate rung instead, so the user's gate is still consulted - which is what makes "subject to exactly the
     * same guardrails" true rather than aspirational. Every other test in this class uses
     * {@code DecisionKind.OPTIMIZATION}, so without this one the escalation path was the untested half of the class's
     * own claim.
     */
    @Test
    public void aScriptCannotLiftALevelGateByCallingItsDecisionAProtection() {
        EnergyConsumer pump = gated("pump", 1, 500, LevelGate.atLeast(EnergyLevel.ENCOURAGED));
        reader.putWatts("Grid_Power", 0);
        reader.put("pump_Switch", OnOffType.OFF);
        EnergyEngine engine = engine(Map.of("shadow", false), grid, pump);
        engine.setCurrentLevelFunction(new FixedLevel(EnergyLevel.NORMAL));

        engine.registerAlgorithm(
                scriptedAlgorithm("js-rule", 0, context -> List.of(new Decision("pump", ControlAction.on(), "js-rule",
                        0, DecisionKind.DEVICE_PROTECTION, "a script asserting a protection it does not own"))));

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.applied(), is(empty()));
        assertThat(outcome.withStatus(DecisionStatus.WITHHELD), hasSize(1));
        assertThat(sink.dispatched(), is(empty()));
    }

    @Test
    public void aScriptedAlgorithmIsShadowedAndStoppedLikeAnyOther() {
        EnergyConsumer boiler = simple("boiler", 2, 2000);
        reader.putWatts("Grid_Power", 5000);
        EnergyEngine engine = engine(Map.of(), grid, boiler);
        engine.registerAlgorithm(scriptedAlgorithm("js-rule", 3, context -> List
                .of(new Decision("boiler", ControlAction.on(), "js-rule", 3, DecisionKind.OPTIMIZATION, "wanted on"))));

        // shadow is the default, so a brand-new script cannot write on its first cycle either
        assertThat(engine.runCycleNow().shadowed(), hasSize(1));
        assertThat(sink.dispatched(), is(empty()));

        engine.setShadow(false);
        engine.setStopped(true);
        // the stop halts everything, so the scripted algorithm is not even invoked
        assertThat(engine.runCycleNow().outcomes(), is(empty()));
        assertThat(sink.dispatched(), is(empty()));

        engine.setStopped(false);
        assertThat(engine.runCycleNow().applied(), hasSize(1));
    }

    @Test
    public void aScriptedAlgorithmThatThrowsLosesOnlyItsOwnProposals() {
        EnergyConsumer boiler = simple("boiler", 2, 2000);
        reader.putWatts("Grid_Power", 5000);
        EnergyEngine engine = engine(Map.of("shadow", "false"), grid, boiler);

        engine.registerAlgorithm(scriptedAlgorithm("broken-script", 1, context -> {
            throw new IllegalStateException("a typo in a rule");
        }));
        engine.registerAlgorithm(scriptedAlgorithm("good-script", 2, context -> List.of(new Decision("boiler",
                ControlAction.on(), "good-script", 2, DecisionKind.OPTIMIZATION, "still works"))));

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.applied(), hasSize(1));
        assertThat(outcome.applied().get(0).algorithmId(), is("good-script"));
    }

    @Test
    public void aReloadedScriptCanReplaceAndWithdrawItsOwnAlgorithm() {
        EnergyConsumer boiler = simple("boiler", 2, 2000);
        reader.putWatts("Grid_Power", 5000);
        EnergyEngine engine = engine(Map.of("shadow", "false"), grid, boiler);

        engine.registerAlgorithm("my-rule", 5, context -> List.of(
                new Decision("boiler", ControlAction.on(), "my-rule", 5, DecisionKind.OPTIMIZATION, "version one")));
        engine.registerAlgorithm("my-rule", 5, context -> List.of(
                new Decision("boiler", ControlAction.off(), "my-rule", 5, DecisionKind.OPTIMIZATION, "version two")));

        assertThat(engine.algorithms(), hasSize(1));
        assertThat(engine.runCycleNow().applied().get(0).action(), is(ControlAction.off()));

        assertThat(engine.unregisterAlgorithm("my-rule"), is(true));
        assertThat(engine.algorithms(), is(empty()));
        assertThat(engine.runCycleNow().outcomes(), is(empty()));
    }

    @Test
    public void theAlgorithmInterfaceStaysImplementableFromOutsideTheBundle() {
        List<Method> abstractMethods = new ArrayList<>();
        for (Method method : EnergyAlgorithm.class.getDeclaredMethods()) {
            if (Modifier.isAbstract(method.getModifiers())) {
                abstractMethods.add(method);
            }
        }

        // one abstract method: implementable by a lambda, and therefore by a script function
        assertThat(abstractMethods, hasSize(1));
        assertThat(abstractMethods.get(0).getName(), is("evaluate"));
        assertThat(EnergyAlgorithm.class.isAnnotationPresent(FunctionalInterface.class), is(true));

        // nothing in the signature is an OSGi type or lives in a non-exported package
        List<Class<?>> mentioned = new ArrayList<>(List.of(abstractMethods.get(0).getReturnType()));
        mentioned.addAll(List.of(abstractMethods.get(0).getParameterTypes()));
        for (Class<?> type : mentioned) {
            assertThat(type.getName(), not(containsString("org.osgi")));
            assertThat(type.getName(), not(containsString(".internal.")));
        }
    }
}
