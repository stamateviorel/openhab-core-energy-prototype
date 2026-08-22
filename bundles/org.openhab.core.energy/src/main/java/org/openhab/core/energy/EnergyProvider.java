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
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * A source of energy on the site: the grid connection, PV production, or a battery.
 * <p>
 * {@link #itemName()} is the provider's live power reading, in the one site convention {@link SignConvention} fixes -
 * grid positive = export, PV positive = producing, battery positive = charging. A device that counts the other way
 * round is normalised at this boundary by {@link #invert()}, so that nothing above the declaration knows it
 * disagreed.
 * <p>
 * A provider is <em>controllable</em> when it declares a {@code controlItemName}: the engine may then write a
 * setpoint, which takes the same sign as the provider's own reading - a positive battery setpoint commands charging.
 * A controllable provider <strong>must</strong> declare a {@code [minPower, maxPower]} clamp; a controllable
 * declaration without one is malformed, because accepting it would mean an unbounded setpoint write to an inverter.
 * The clamp is signed and may be stated as a power or as a current, exactly like a Controllable consumer's bounds.
 * <p>
 * {@code priority} is on the same scale consumers use, lower being better, defaulting to
 * {@link EnergyParticipant#DEFAULT_PRIORITY}: it is what makes "is this battery's charging power reclaimable for
 * that consumer?" decidable by comparing two declared numbers.
 * <p>
 * {@code phaseItemNames} optionally names a reading Item per phase index. Without it, per-phase enforcement can only
 * see the load the engine itself dispatched; with it, the uncontrolled load on each phase becomes visible.
 * <p>
 * {@code ackWindow}, {@code ackTolerance} and {@code maxReadingAge} mean here exactly what they mean on
 * {@link EnergyConsumer}: they override the engine's acknowledgement and staleness defaults for this participant
 * alone, the tolerance band being an absolute quantity in the control Item's own dimension rather than a fraction of
 * the commanded value. A provider carrying a safety-relevant reading is the case the age exists for - a grid clamp
 * that stops updating is the input the whole electrical-limit floor rests on.
 *
 * @param id the stable participant id, defaulting to the name of the Item carrying the declaration
 * @param itemName the Item holding the live power reading
 * @param role the role this provider plays
 * @param controlItemName the Item accepting a setpoint, or {@code null} if the provider is not controllable
 * @param minPower the lowest setpoint the provider accepts, or {@code null} if the provider is not controllable
 * @param maxPower the highest setpoint the provider accepts, or {@code null} if the provider is not controllable
 * @param socItemName the Item holding the state of charge, or {@code null} if the provider has no storage
 * @param priority the priority of this provider on the consumer scale, lower is better
 * @param invert whether the device counts the opposite way round and has to be normalised at the edge
 * @param phaseItemNames the reading Item per phase index, empty when the provider reads only an aggregate
 * @param sinkId the actuation sink this provider's setpoints are written through, or {@code null} for the
 *            site-wide one
 * @param ackWindow how long a setpoint sent to this provider may go unacknowledged before it lapses, or
 *            {@code null} to use the engine's default
 * @param ackTolerance how far the reported value may sit from the commanded one and still acknowledge it - an
 *            absolute quantity in the control Item's own dimension - or {@code null} to require an exact match
 * @param maxReadingAge how old this provider's reading may be before it counts as stale, or {@code null} when it
 *            declares no age
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record EnergyProvider(String id, String itemName, ProviderRole role, @Nullable String controlItemName,
        @Nullable QuantityType<?> minPower, @Nullable QuantityType<?> maxPower, @Nullable String socItemName,
        int priority, boolean invert, Map<Integer, String> phaseItemNames, @Nullable String sinkId,
        @Nullable Duration ackWindow, @Nullable QuantityType<?> ackTolerance,
        @Nullable Duration maxReadingAge) implements EnergyParticipant {

    /**
     * The priority a provider declaring none is placed at, shared with {@link EnergyConsumer#DEFAULT_PRIORITY}.
     */
    public static final int DEFAULT_PRIORITY = EnergyParticipant.DEFAULT_PRIORITY;

    /**
     * Validates the provider and takes an immutable, ordered copy of the per-phase reading Items.
     *
     * @throws IllegalArgumentException if an identifier or Item name is blank, a controllable provider declares no
     *             complete clamp, the clamp bounds are not both powers or both currents, the minimum exceeds the
     *             maximum, or a phase index is not 1, 2 or 3
     */
    public EnergyProvider {
        id = ModelChecks.requireText(id, "id");
        itemName = ModelChecks.requireText(itemName, "itemName");
        String control = controlItemName;
        if (control != null) {
            controlItemName = ModelChecks.requireText(control, "controlItemName");
        }
        String soc = socItemName;
        if (soc != null) {
            socItemName = ModelChecks.requireText(soc, "socItemName");
        }
        String sink = sinkId;
        if (sink != null) {
            sinkId = ModelChecks.requireText(sink, "sinkId");
        }
        ackWindow = ModelChecks.requirePositiveOrNull(ackWindow, "ackWindow");
        ackTolerance = ModelChecks.requireNonNegativeOrNull(ackTolerance, "ackTolerance");
        maxReadingAge = ModelChecks.requirePositiveOrNull(maxReadingAge, "maxReadingAge");
        QuantityType<?> minimum = minPower;
        QuantityType<?> maximum = maxPower;
        if (control != null && (minimum == null || maximum == null)) {
            throw new IllegalArgumentException(
                    "a controllable provider must declare both minPower and maxPower, as powers or as currents");
        }
        if (minimum != null && maximum != null) {
            requireSameDimension(minimum, maximum);
            ModelChecks.requireOrdered(minimum, maximum, "minPower", "maxPower");
        } else if (minimum != null) {
            requireDimension(minimum, "minPower");
        } else if (maximum != null) {
            requireDimension(maximum, "maxPower");
        }
        Map<Integer, String> readings = new TreeMap<>();
        phaseItemNames.forEach((phase, item) -> readings.put(ModelChecks.requirePhase(phase),
                ModelChecks.requireText(item, "phaseItemName")));
        phaseItemNames = Collections.unmodifiableMap(readings);
    }

    /**
     * Creates an uncontrollable provider identified by the Item carrying its declaration - the usual shape for a grid
     * meter or a PV production reading.
     *
     * @param itemName the Item holding the live power reading, which is also the participant id
     * @param role the role this provider plays
     * @return the provider
     */
    public static EnergyProvider of(String itemName, ProviderRole role) {
        return of(itemName, itemName, role);
    }

    /**
     * Creates an uncontrollable provider - the usual shape for a grid meter or a PV production reading.
     *
     * @param id the stable participant id
     * @param itemName the Item holding the live power reading
     * @param role the role this provider plays
     * @return the provider
     */
    public static EnergyProvider of(String id, String itemName, ProviderRole role) {
        return new EnergyProvider(id, itemName, role, null, null, null, null, DEFAULT_PRIORITY, false, Map.of(), null,
                null, null, null);
    }

    /**
     * Creates a controllable provider with a watt clamp - the usual shape for a battery.
     *
     * @param id the stable participant id
     * @param itemName the Item holding the live power reading
     * @param role the role this provider plays
     * @param controlItemName the Item accepting a power setpoint
     * @param minWatts the lowest setpoint, in watts, negative for a battery that may discharge
     * @param maxWatts the highest setpoint, in watts, positive for a battery that may charge
     * @param socItemName the Item holding the state of charge, or {@code null}
     * @return the provider
     */
    public static EnergyProvider controllable(String id, String itemName, ProviderRole role, String controlItemName,
            double minWatts, double maxWatts, @Nullable String socItemName) {
        return new EnergyProvider(id, itemName, role, controlItemName, new QuantityType<>(minWatts, Units.WATT),
                new QuantityType<>(maxWatts, Units.WATT), socItemName, DEFAULT_PRIORITY, false, Map.of(), null, null,
                null, null);
    }

    /**
     * Returns a copy carrying a priority on the consumer scale.
     *
     * @param declaredPriority the priority, lower is better
     * @return the copy
     */
    public EnergyProvider withPriority(int declaredPriority) {
        return new EnergyProvider(id, itemName, role, controlItemName, minPower, maxPower, socItemName,
                declaredPriority, invert, phaseItemNames, sinkId, ackWindow, ackTolerance, maxReadingAge);
    }

    /**
     * Returns a copy whose readings and setpoints are normalised at the edge, for a device that counts the opposite
     * way round.
     *
     * @return the copy
     */
    public EnergyProvider inverted() {
        return new EnergyProvider(id, itemName, role, controlItemName, minPower, maxPower, socItemName, priority, true,
                phaseItemNames, sinkId, ackWindow, ackTolerance, maxReadingAge);
    }

    /**
     * Returns a copy declaring a reading Item per phase.
     *
     * @param readings the reading Item per phase index
     * @return the copy
     * @throws IllegalArgumentException if a phase index is not 1, 2 or 3
     */
    public EnergyProvider withPhaseItems(Map<Integer, String> readings) {
        return new EnergyProvider(id, itemName, role, controlItemName, minPower, maxPower, socItemName, priority,
                invert, readings, sinkId, ackWindow, ackTolerance, maxReadingAge);
    }

    /**
     * Returns a copy whose setpoints are written through the named actuation sink rather than the site-wide one.
     *
     * @param declaredSinkId the sink id
     * @return the copy
     */
    public EnergyProvider withSink(String declaredSinkId) {
        return new EnergyProvider(id, itemName, role, controlItemName, minPower, maxPower, socItemName, priority,
                invert, phaseItemNames, declaredSinkId, ackWindow, ackTolerance, maxReadingAge);
    }

    /**
     * Returns a copy whose setpoints lapse after the declared window rather than after the engine's default.
     *
     * @param declaredWindow how long a setpoint may go unacknowledged
     * @return the copy
     * @throws IllegalArgumentException if the window is not positive
     */
    public EnergyProvider withAckWindow(Duration declaredWindow) {
        return new EnergyProvider(id, itemName, role, controlItemName, minPower, maxPower, socItemName, priority,
                invert, phaseItemNames, sinkId, declaredWindow, ackTolerance, maxReadingAge);
    }

    /**
     * Returns a copy that accepts a reported value within the declared band as an acknowledgement.
     *
     * @param declaredTolerance the band, an absolute quantity in the control Item's own dimension
     * @return the copy
     * @throws IllegalArgumentException if the band is negative
     */
    public EnergyProvider withAckTolerance(QuantityType<?> declaredTolerance) {
        return new EnergyProvider(id, itemName, role, controlItemName, minPower, maxPower, socItemName, priority,
                invert, phaseItemNames, sinkId, ackWindow, declaredTolerance, maxReadingAge);
    }

    /**
     * Returns a copy whose reading counts as stale once it is older than the declared age.
     *
     * @param declaredAge the maximum age of a reading
     * @return the copy
     * @throws IllegalArgumentException if the age is not positive
     */
    public EnergyProvider withMaxReadingAge(Duration declaredAge) {
        return new EnergyProvider(id, itemName, role, controlItemName, minPower, maxPower, socItemName, priority,
                invert, phaseItemNames, sinkId, ackWindow, ackTolerance, declaredAge);
    }

    /**
     * Normalises a raw reading or setpoint of this device onto the site sign convention.
     *
     * @param rawWatts the value as the device reports or accepts it
     * @return the value in the site convention
     */
    public double normalise(double rawWatts) {
        return SignConvention.normalise(rawWatts, invert);
    }

    /**
     * Tests whether the engine may write a setpoint to this provider.
     *
     * @return {@code true} if a control Item is declared
     */
    public boolean isControllable() {
        return controlItemName != null;
    }

    /**
     * Tests whether this provider reports a state of charge.
     *
     * @return {@code true} if a state-of-charge Item is declared
     */
    public boolean hasStateOfCharge() {
        return socItemName != null;
    }

    /**
     * Tests whether the clamp is expressed as powers.
     *
     * @return {@code true} if a clamp is declared in a power unit
     */
    public boolean isPowerClamped() {
        QuantityType<?> minimum = minPower;
        return minimum != null && minimum.getUnit().isCompatible(Units.WATT);
    }

    /**
     * Tests whether the clamp is expressed as electric currents.
     *
     * @return {@code true} if a clamp is declared in a current unit
     */
    public boolean isCurrentClamped() {
        QuantityType<?> minimum = minPower;
        return minimum != null && minimum.getUnit().isCompatible(Units.AMPERE);
    }

    /**
     * Tests whether this provider declares a reading Item per phase.
     *
     * @return {@code true} if at least one per-phase reading Item is declared
     */
    public boolean hasPerPhaseReadings() {
        return !phaseItemNames.isEmpty();
    }

    /**
     * Returns the reading Item of one phase.
     *
     * @param phase the phase index
     * @return the Item name, or {@link Optional#empty()} if the provider declares none for that phase
     */
    public Optional<String> phaseItemName(int phase) {
        return Optional.ofNullable(phaseItemNames.get(phase));
    }

    private static void requireDimension(QuantityType<?> bound, String name) {
        if (!bound.getUnit().isCompatible(Units.WATT) && !bound.getUnit().isCompatible(Units.AMPERE)) {
            throw new IllegalArgumentException(name + " must be a power or a current but was " + bound);
        }
    }

    private static void requireSameDimension(QuantityType<?> minimum, QuantityType<?> maximum) {
        boolean power = minimum.getUnit().isCompatible(Units.WATT) && maximum.getUnit().isCompatible(Units.WATT);
        boolean current = minimum.getUnit().isCompatible(Units.AMPERE) && maximum.getUnit().isCompatible(Units.AMPERE);
        if (!power && !current) {
            throw new IllegalArgumentException("minPower and maxPower must both be powers or both be currents but were "
                    + minimum + " and " + maximum);
        }
    }
}
