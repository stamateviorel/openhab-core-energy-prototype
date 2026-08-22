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

import java.time.Duration;
import java.util.Optional;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * An ON/OFF consumer - a DHW boiler, an immersion-heater stage, a fridge, a dumb resistive load.
 * <p>
 * It carries the full protection set, the complete <code>{min,max} &times; {on,off}</code> matrix:
 * <ul>
 * <li>{@code minOn} - the shortest run the engine must allow once started; it doubles as the catch-up time after a
 * forced restart, which is <em>any</em> OFF&rarr;ON transition the engine did not command, recognised as such
 * without the engine having to know why the device started.</li>
 * <li>{@code maxOn} - the longest uninterrupted run the engine may allow.</li>
 * <li>{@code minOff} - the cooldown after switching off, before the engine may switch on again.</li>
 * <li>{@code maxOff} - the duty-cycle guarantee: once exceeded the engine switches the device back on regardless of
 * price or surplus.</li>
 * </ul>
 * All four protection times are measured from the steered Item's own last state change; the engine keeps no timers
 * of its own, which is what lets a compressor's cooldown survive a restart wherever that Item is persisted.
 * <p>
 * The two power figures are distinct and both optional:
 * <ul>
 * <li>{@code onThreshold} is a <em>switching</em> figure - the surplus above which the engine may switch the device
 * on - and is typically set with margin;</li>
 * <li>{@code ratedPower} is the <em>booking</em> figure the electrical-limit floor and budget-constrained scheduling
 * charge against this consumer. It is optional on purpose, so that declaring it never becomes a condition of an
 * existing declaration being read at all. When it is absent the on-threshold is used instead and the participant
 * carries a declaration gap that the engine reports - see {@link #powerFigure()} and {@link #ratingIsInferred()}.
 * A gap is never a rejection.</li>
 * </ul>
 * The {@code levelGate} is mandatory but defaults to {@link LevelGate#always()} through {@link #plain()}. "Leave
 * this device alone" is <strong>not</strong> a gate setting: it is {@link EnergyConsumer#handsOff()}, which every
 * profile class carries.
 *
 * @param onThreshold the surplus above which the engine may switch the device on, or {@code null} if not declared
 * @param ratedPower the power this consumer is booked at, or {@code null} if not declared
 * @param minOn the minimum ON runtime, or {@code null} if not declared
 * @param maxOn the maximum ON runtime, or {@code null} if not declared
 * @param minOff the minimum OFF time (cooldown), or {@code null} if not declared
 * @param maxOff the maximum OFF time (duty-cycle guarantee), or {@code null} if not declared
 * @param levelGate the site level from which the engine may run the device, never {@code null}
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record SimpleProfile(@Nullable QuantityType<Power> onThreshold, @Nullable QuantityType<Power> ratedPower,
        @Nullable Duration minOn, @Nullable Duration maxOn, @Nullable Duration minOff, @Nullable Duration maxOff,
        LevelGate levelGate) implements PowerProfile {

    /**
     * Validates the profile.
     *
     * @throws IllegalArgumentException if a power is not a power, a duration is negative, or a minimum exceeds its
     *             matching maximum
     */
    public SimpleProfile {
        QuantityType<Power> threshold = onThreshold;
        if (threshold != null) {
            ModelChecks.requireCompatible(threshold, Units.WATT, "onThreshold");
            ModelChecks.requireNotNegative(threshold, "onThreshold");
        }
        QuantityType<Power> rated = ratedPower;
        if (rated != null) {
            ModelChecks.requireCompatible(rated, Units.WATT, "ratedPower");
            ModelChecks.requireNotNegative(rated, "ratedPower");
        }
        ModelChecks.requireNotNegative(minOn, "minOn");
        ModelChecks.requireNotNegative(maxOn, "maxOn");
        ModelChecks.requireNotNegative(minOff, "minOff");
        ModelChecks.requireNotNegative(maxOff, "maxOff");
        ModelChecks.requireOrdered(minOn, maxOn, "minOn", "maxOn");
        ModelChecks.requireOrdered(minOff, maxOff, "minOff", "maxOff");
    }

    /**
     * Creates an unprotected profile that the engine may run at any site level.
     *
     * @return a profile with no power figures, no protection times and the "always" gate
     */
    public static SimpleProfile plain() {
        return new SimpleProfile(null, null, null, null, null, null, LevelGate.always());
    }

    /**
     * Creates an unprotected profile with the given level gate.
     *
     * @param levelGate the site level from which the engine may run the device
     * @return a profile with no power figures and no protection times
     */
    public static SimpleProfile withGate(LevelGate levelGate) {
        return new SimpleProfile(null, null, null, null, null, null, levelGate);
    }

    /**
     * Creates an unprotected profile with a surplus on-threshold, in watts.
     *
     * @param thresholdWatts the surplus above which the engine may switch the device on
     * @return the profile
     */
    public static SimpleProfile switchingAt(double thresholdWatts) {
        return new SimpleProfile(new QuantityType<>(thresholdWatts, Units.WATT), null, null, null, null, null,
                LevelGate.always());
    }

    /**
     * Returns the power figure this consumer is booked at: its declared rated power, or the on-threshold when no
     * rated power is declared.
     *
     * @return the figure to book, or {@link Optional#empty()} when the declaration carries neither
     */
    public Optional<QuantityType<Power>> powerFigure() {
        QuantityType<Power> rated = ratedPower;
        return rated != null ? Optional.of(rated) : Optional.ofNullable(onThreshold);
    }

    /**
     * Tests whether the booked figure is inferred from the on-threshold rather than declared.
     * <p>
     * This is the declaration gap the engine reports for a Simple consumer without a {@code ratedPower}: the figure
     * it books was chosen for switching behaviour, not for allocation. It is a condition to report, never a reason
     * to reject the declaration.
     *
     * @return {@code true} if no rated power is declared
     */
    public boolean ratingIsInferred() {
        return ratedPower == null;
    }

    /**
     * Tests whether this profile declares a protection whose elapsed time is measured from device state history.
     *
     * @return {@code true} if a minimum or maximum ON or OFF time is declared
     */
    public boolean declaresProtections() {
        return minOn != null || maxOn != null || minOff != null || maxOff != null;
    }

    @Override
    public Kind kind() {
        return Kind.SIMPLE;
    }
}
