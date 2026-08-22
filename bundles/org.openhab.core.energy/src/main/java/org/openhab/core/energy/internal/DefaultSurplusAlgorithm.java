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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.DecisionKind;
import org.openhab.core.energy.EnergyAlgorithm;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.LevelGate;
import org.openhab.core.energy.ParticipantState;
import org.openhab.core.energy.SimpleProfile;
import org.openhab.core.library.types.QuantityType;
import org.osgi.service.component.annotations.Component;

/**
 * The built-in algorithm, kept deliberately trivial: hand the live surplus to Simple consumers in priority order,
 * best priority first, and switch off what the surplus no longer covers.
 * <p>
 * It exists to prove the spine, not to be the optimizer the corpus eventually wants. It looks at one figure -
 * current surplus, which is grid export plus the battery charging the claiming consumer outranks - and it only
 * understands the Simple class. Anything requiring a plan (cheapest hours, deadlines,
 * load curves, batch scheduling) belongs to capabilities that are not built yet, and anything requiring a device's
 * protection timers belongs to the participant model's protection set, which no engine-owned code here evaluates.
 * <p>
 * Its two non-obvious behaviours are both about not fighting itself:
 * <ul>
 * <li>a device that already reports the state it would be commanded to gets no decision at all, so the log shows
 * changes rather than a heartbeat;</li>
 * <li>a running device's own draw is added back to the available surplus before its threshold is tested -
 * otherwise the very act of switching it on would immediately make it look unaffordable. Whether that add-back is
 * correct is one of the sub-questions the surplus decision left open, so it stays this algorithm's own reading
 * rather than something {@link EnergyContext#surplusWatts()} bakes in.</li>
 * </ul>
 * A consumer its owner marked hands-off is never proposed anything, and one whose readiness interlock is open is
 * skipped without a word - both are normal outcomes, not errors. Neither is this algorithm's own discipline: both
 * prohibitions are engine-owned and would be enforced against it anyway. Checking them here only keeps the log free
 * of decisions that were never going to be dispatched.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(service = EnergyAlgorithm.class)
public class DefaultSurplusAlgorithm implements EnergyAlgorithm {

    /**
     * The id of this algorithm, as it appears in decisions and log lines.
     */
    public static final String ID = "default-surplus";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public List<Decision> evaluate(EnergyContext context) {
        if (context.surplusWatts().isEmpty()) {
            return List.of();
        }
        // What the better-priority consumers of this cycle have already taken. Each consumer's own pool is asked for
        // separately, because battery charging is only surplus to a consumer that outranks the battery: a
        // worse-priority load sees a smaller pool than a better-priority one, and subtracting a shared running total
        // from each is what keeps the two facts consistent.
        double consumed = 0;
        List<Decision> decisions = new ArrayList<>();
        for (ParticipantState state : context.consumers()) {
            Optional<EnergyConsumer> maybeConsumer = state.consumer();
            if (maybeConsumer.isEmpty()) {
                continue;
            }
            EnergyConsumer consumer = maybeConsumer.get();
            if (!(consumer.profile() instanceof SimpleProfile profile)) {
                continue;
            }
            if (EngineProhibitions.isHandsOff(state)) {
                continue;
            }
            LevelGate gate = profile.levelGate();
            boolean running = state.isReportedOn();
            if (!gate.permits(context.level())) {
                if (running) {
                    decisions.add(new Decision(consumer.id(), ControlAction.off(), ID, consumer.priority(),
                            DecisionKind.LEVEL_GATE, "site level " + context.level() + " is below the declared gate"));
                }
                continue;
            }
            QuantityType<Power> onThreshold = profile.onThreshold();
            if (onThreshold == null) {
                continue;
            }
            OptionalDouble threshold = EngineUnits.watts(onThreshold);
            if (threshold.isEmpty()) {
                continue;
            }
            double pool = context.surplusWattsFor(consumer.priority()).orElse(0);
            double ownDraw = running ? ownDrawWatts(state, threshold.getAsDouble()) : 0;
            double available = pool - consumed + ownDraw;
            if (available >= threshold.getAsDouble()) {
                if (!running && !state.ready()) {
                    continue;
                }
                consumed += threshold.getAsDouble() - ownDraw;
                if (!running) {
                    decisions.add(new Decision(consumer.id(), ControlAction.on(), ID, consumer.priority(),
                            DecisionKind.OPTIMIZATION, "surplus covers its threshold of " + onThreshold));
                }
            } else if (running) {
                consumed -= ownDraw;
                decisions.add(new Decision(consumer.id(), ControlAction.off(), ID, consumer.priority(),
                        DecisionKind.OPTIMIZATION, "surplus no longer covers its threshold of " + onThreshold));
            }
        }
        return decisions;
    }

    /**
     * Returns what a running consumer is drawing, falling back to its declared threshold when it is unmetered.
     *
     * @param state the participant state
     * @param threshold the declared on-threshold in watts
     * @return the draw in watts
     */
    private double ownDrawWatts(ParticipantState state, double threshold) {
        return state.hasMeasurement() ? state.measuredWatts() : threshold;
    }
}
