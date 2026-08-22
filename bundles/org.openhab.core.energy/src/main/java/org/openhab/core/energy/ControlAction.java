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
package org.openhab.core.energy;

import javax.measure.quantity.ElectricCurrent;
import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * What an algorithm wants done to a participant: one of the control surfaces the four
 * {@link PowerProfile} classes expose, plus an explicit "leave it as it is".
 * <p>
 * The variants map onto the profile classes: {@link SetPower} and {@link SetCurrent} onto
 * {@link ControllableProfile} (which is power-bounded or current-bounded - see its own JavaDoc),
 * {@link Switch} onto {@link SimpleProfile} and onto the start of a {@link BatchProfile} program,
 * {@link SetMode} onto {@link ModeControllableProfile}, and {@link Hold} onto "the engine deliberately proposes no
 * change this cycle". A controllable provider (a battery) is steered with {@link SetPower} as well, its sign
 * following the provider clamp convention.
 * <p>
 * The static factories take plain {@code double}s and {@code String}s so that a script-contributed algorithm can
 * build actions without touching the unit-of-measurement API.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public sealed interface ControlAction permits ControlAction.SetPower, ControlAction.SetCurrent, ControlAction.Switch, ControlAction.SetMode, ControlAction.Hold {

    /**
     * Returns a short human-readable rendering of the action, used in the engine's log lines.
     *
     * @return the description, never {@code null}
     */
    String describe();

    /**
     * A continuous power setpoint - the archetype being a battery or a power-bounded consumer.
     *
     * @param power the requested power
     *
     * @author Stamate Viorel - Initial contribution
     */
    record SetPower(QuantityType<Power> power) implements ControlAction {

        /**
         * Validates the action.
         *
         * @throws IllegalArgumentException if the quantity is not a power
         */
        public SetPower {
            ModelChecks.requireCompatible(power, Units.WATT, "power");
        }

        @Override
        public String describe() {
            return "power=" + power;
        }
    }

    /**
     * A continuous current setpoint - the archetype being a wallbox declared with "min 6 A, max 32 A".
     *
     * @param current the requested current, per phase
     *
     * @author Stamate Viorel - Initial contribution
     */
    record SetCurrent(QuantityType<ElectricCurrent> current) implements ControlAction {

        /**
         * Validates the action.
         *
         * @throws IllegalArgumentException if the quantity is not an electric current
         */
        public SetCurrent {
            ModelChecks.requireCompatible(current, Units.AMPERE, "current");
        }

        @Override
        public String describe() {
            return "current=" + current;
        }
    }

    /**
     * An ON/OFF command.
     *
     * @param on {@code true} to switch the participant on, {@code false} to switch it off
     *
     * @author Stamate Viorel - Initial contribution
     */
    record Switch(boolean on) implements ControlAction {

        @Override
        public String describe() {
            return on ? "ON" : "OFF";
        }
    }

    /**
     * A discrete mode selection out of the ordered mode list of a {@link ModeControllableProfile}.
     *
     * @param mode the mode to select
     *
     * @author Stamate Viorel - Initial contribution
     */
    record SetMode(String mode) implements ControlAction {

        /**
         * Validates the action.
         *
         * @throws IllegalArgumentException if the mode is blank
         */
        public SetMode {
            mode = ModelChecks.requireText(mode, "mode");
        }

        @Override
        public String describe() {
            return "mode=" + mode;
        }
    }

    /**
     * An explicit "no change this cycle". A hold never increases the site load, so the electrical-limit floor never
     * trims or defers it; it only accounts for what the participant already draws.
     *
     * @author Stamate Viorel - Initial contribution
     */
    record Hold() implements ControlAction {

        @Override
        public String describe() {
            return "hold";
        }
    }

    /**
     * Creates a power setpoint.
     *
     * @param watts the requested power in watts, negative for a charging battery
     * @return the action
     */
    static ControlAction watts(double watts) {
        return new SetPower(new QuantityType<>(watts, Units.WATT));
    }

    /**
     * Creates a current setpoint.
     *
     * @param amperes the requested per-phase current in amperes
     * @return the action
     */
    static ControlAction amperes(double amperes) {
        return new SetCurrent(new QuantityType<>(amperes, Units.AMPERE));
    }

    /**
     * Creates the ON command.
     *
     * @return the action
     */
    static ControlAction on() {
        return new Switch(true);
    }

    /**
     * Creates the OFF command.
     *
     * @return the action
     */
    static ControlAction off() {
        return new Switch(false);
    }

    /**
     * Creates a mode selection.
     *
     * @param mode the mode to select
     * @return the action
     */
    static ControlAction mode(String mode) {
        return new SetMode(mode);
    }

    /**
     * Creates the "no change" action.
     *
     * @return the action
     */
    static ControlAction hold() {
        return new Hold();
    }
}
