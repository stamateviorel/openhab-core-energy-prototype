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

import java.util.OptionalDouble;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.BatchProfile;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.ControllableProfile;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.energy.ModeControllableProfile;
import org.openhab.core.energy.ParticipantState;
import org.openhab.core.energy.PowerProfile;
import org.openhab.core.energy.SignConvention;
import org.openhab.core.energy.SimpleProfile;
import org.openhab.core.library.types.QuantityType;

/**
 * Answers the one question the electrical-limit floor needs: how much site load would this action cause?
 * <p>
 * What each profile class contributes is fixed, one rule per class:
 * <ul>
 * <li><strong>Sign.</strong> One site convention, stated once in {@link SignConvention} and never re-derived here:
 * a consumer's power is load, a battery's positive direction is charging and therefore <em>is</em> load, and PV and
 * grid are supply and therefore negative load. A setpoint takes the same sign as the reading it steers.</li>
 * <li><strong>Current to power.</strong> {@code amperes x nominalVoltage x phaseCount}. The nominal voltage is
 * engine configuration; the phase count comes from the participant's phases and falls back to one.</li>
 * <li><strong>Controllable</strong> books its declared maximum, <strong>Batch</strong> its rated power scaled by its
 * curve - the curve's <em>peak</em> when the load is being admitted, because that is the worst moment the site has
 * to carry - and <strong>Simple</strong> its declared {@code ratedPower}, falling back to the on-threshold when it
 * declares none. That fallback is a declaration gap the model reports
 * ({@link SimpleProfile#ratingIsInferred()}); it is never a reason to reject the declaration, and it is no longer
 * this class's private assumption.</li>
 * <li><strong>A mode carries no figure and is exempt.</strong> {@link ModeControllableProfile} is an ordered list of
 * names, and an SG-ready mode 3 draws whatever the heat pump wants, so a declared number would be fiction. Such a
 * decision has no derivable demand and the floor admits it, books nothing and reports the gap - which is what the
 * prototype's {@code unknownDemandPolicy} parameter used to offer as a choice.</li>
 * </ul>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class PowerEstimator {

    private final double nominalVoltage;

    /**
     * Creates the estimator.
     *
     * @param nominalVoltage the nominal phase voltage used to convert currents to powers
     */
    public PowerEstimator(double nominalVoltage) {
        this.nominalVoltage = nominalVoltage;
    }

    /**
     * Returns the number of phases the participant draws on, falling back to one when nothing is declared.
     *
     * @param state the participant state
     * @return the phase count, at least one
     */
    public int phaseCount(ParticipantState state) {
        return Math.max(1, state.phases().size());
    }

    /**
     * Estimates the site load an action would cause.
     *
     * @param state the participant the action addresses
     * @param action the action
     * @return the load in watts, or empty when the demand cannot be derived from the declaration
     */
    public OptionalDouble loadWatts(ParticipantState state, ControlAction action) {
        double sign = sign(state.participant());
        return switch (action) {
            case ControlAction.Hold hold -> OptionalDouble.of(currentLoadWatts(state));
            case ControlAction.Switch onOff -> onOff.on() ? ratedLoadWatts(state) : OptionalDouble.of(0);
            case ControlAction.SetPower power -> scale(EngineUnits.watts(power.power()), sign);
            case ControlAction.SetCurrent current -> state.participant() instanceof EnergyConsumer
                    ? scale(EngineUnits.amperes(current.current()), nominalVoltage * phaseCount(state))
                    : OptionalDouble.empty();
            case ControlAction.SetMode mode -> OptionalDouble.empty();
        };
    }

    /**
     * Returns the load the participant is drawing right now, as far as the snapshot knows.
     *
     * @param state the participant state
     * @return the measured load in watts, zero when the participant declares no measurement
     */
    public double currentLoadWatts(ParticipantState state) {
        return state.hasMeasurement() ? sign(state.participant()) * state.measuredWatts() : 0;
    }

    /**
     * Returns the smallest site load the participant may be steered to while it keeps running - the hard floor of a
     * {@link ControllableProfile}, below which the device has to be stopped instead of throttled.
     *
     * @param state the participant state
     * @return the minimum load in watts, or empty when the participant declares no continuous minimum
     */
    public OptionalDouble minimumLoadWatts(ParticipantState state) {
        if (!(state.participant() instanceof EnergyConsumer consumer)) {
            return OptionalDouble.empty();
        }
        if (!(consumer.profile() instanceof ControllableProfile controllable)) {
            return OptionalDouble.empty();
        }
        if (controllable.isPowerBased()) {
            return EngineUnits.watts(controllable.min());
        }
        return scale(EngineUnits.amperes(controllable.min()), nominalVoltage * phaseCount(state));
    }

    /**
     * Returns the largest site load the participant may be steered to.
     *
     * @param state the participant state
     * @return the maximum load in watts, or empty when the participant declares no rating
     */
    public OptionalDouble ratedLoadWatts(ParticipantState state) {
        if (!(state.participant() instanceof EnergyConsumer consumer)) {
            return OptionalDouble.empty();
        }
        PowerProfile profile = consumer.profile();
        return switch (profile) {
            case SimpleProfile simple -> {
                QuantityType<Power> figure = simple.powerFigure().orElse(null);
                yield figure == null ? OptionalDouble.empty() : EngineUnits.watts(figure);
            }
            case BatchProfile batch -> EngineUnits.watts(batch.admissionPower());
            case ControllableProfile controllable -> controllable.isPowerBased() ? EngineUnits.watts(controllable.max())
                    : scale(EngineUnits.amperes(controllable.max()), nominalVoltage * phaseCount(state));
            case ModeControllableProfile modes -> OptionalDouble.empty();
        };
    }

    /**
     * Returns the per-phase share of a total load, assuming an even split across the declared phases.
     *
     * @param totalWatts the total load
     * @param state the participant state
     * @return the load on each declared phase
     */
    public double perPhaseWatts(double totalWatts, ParticipantState state) {
        return totalWatts / phaseCount(state);
    }

    /**
     * Returns the nominal phase voltage used for current-to-power conversions.
     *
     * @return the voltage in volts
     */
    public double nominalVoltage() {
        return nominalVoltage;
    }

    /**
     * Returns the coefficient turning a participant's own power into site load, under the one site sign convention.
     *
     * @param participant the participant
     * @return {@code +1} where the participant's positive direction is load, {@code -1} where it is supply
     */
    private static double sign(EnergyParticipant participant) {
        return SignConvention.loadWatts(participant, 1);
    }

    private static OptionalDouble scale(OptionalDouble value, double factor) {
        return value.isPresent() ? OptionalDouble.of(value.getAsDouble() * factor) : OptionalDouble.empty();
    }
}
