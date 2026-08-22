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

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.function.Predicate;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * The immutable snapshot every decision of one cycle is judged against.
 * <p>
 * The engine builds exactly one of these per cycle and hands the same instance to every algorithm, so two consumers
 * evaluated in the same cycle are judged against the same surplus figure rather than against values read at
 * different moments. Nothing here is read lazily and nothing here can change after construction; algorithms are
 * pure functions of this object.
 * <p>
 * <strong>The content of the snapshot is an open question</strong>, not a settled contract:
 * {@code define-engine-contract/design.md} §4 asks for "a defined minimum: live powers, prices, forecasts, levels,
 * per-participant state" and says the core version should be decided together with the participant-model
 * mechanism. This record covers the wave-1 half of that list - timestamp, live powers, level, per-participant
 * state and the electrical limits. Prices and forecasts belong to capabilities that are not built yet
 * ({@code price-data}, {@code forecast-data}) and are deliberately absent rather than stubbed.
 *
 * @param timestamp the moment the snapshot was taken; every decision of the cycle is stamped with it
 * @param level the current site energy level
 * @param gridPower the grid reading, positive = export, or {@code null} when no grid provider is declared or its
 *            reading is unavailable
 * @param pvPower the PV production reading, or {@code null} when unavailable
 * @param batteryPower the battery reading, or {@code null} when unavailable
 * @param uncontrolledLoad the site draw the engine does not steer, against which the electrical-limit floor
 *            measures its headroom, or {@code null} when unknown (treated as zero)
 * @param uncontrolledPhaseLoad the same figure per phase, empty when unknown
 * @param participants the per-participant state, keyed by participant id
 * @param limits the declared electrical limits
 * @param measurementsStale whether a measurement feeding the electrical-limit floor is missing or stale, in which
 *            case the engine degrades to a safe state instead of optimizing on stale safety data
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record EnergyContext(Instant timestamp, EnergyLevel level, @Nullable QuantityType<Power> gridPower,
        @Nullable QuantityType<Power> pvPower, @Nullable QuantityType<Power> batteryPower,
        @Nullable QuantityType<Power> uncontrolledLoad, Map<Integer, QuantityType<Power>> uncontrolledPhaseLoad,
        Map<String, ParticipantState> participants, ElectricalLimits limits, boolean measurementsStale) {

    /**
     * Validates the snapshot and takes immutable, key-ordered copies of its maps.
     *
     * @throws IllegalArgumentException if a power reading is not a power
     */
    public EnergyContext {
        requirePower(gridPower, "gridPower");
        requirePower(pvPower, "pvPower");
        requirePower(batteryPower, "batteryPower");
        requirePower(uncontrolledLoad, "uncontrolledLoad");
        Map<Integer, QuantityType<Power>> phaseCopy = new TreeMap<>();
        for (Map.Entry<Integer, QuantityType<Power>> entry : uncontrolledPhaseLoad.entrySet()) {
            ModelChecks.requireCompatible(entry.getValue(), Units.WATT, "uncontrolledPhaseLoad");
            phaseCopy.put(entry.getKey(), entry.getValue());
        }
        uncontrolledPhaseLoad = Collections.unmodifiableMap(phaseCopy);
        participants = Collections.unmodifiableMap(new TreeMap<>(participants));
    }

    private static void requirePower(@Nullable QuantityType<Power> value, String name) {
        if (value != null) {
            ModelChecks.requireCompatible(value, Units.WATT, name);
        }
    }

    /**
     * Starts building a snapshot.
     *
     * @param timestamp the moment the snapshot is taken
     * @param level the current site energy level
     * @return a builder
     */
    public static Builder builder(Instant timestamp, EnergyLevel level) {
        return new Builder(timestamp, level);
    }

    /**
     * Returns the state of one participant.
     *
     * @param participantId the participant id
     * @return the state, or empty when the participant is not part of this cycle
     */
    public Optional<ParticipantState> participant(String participantId) {
        return Optional.ofNullable(participants.get(participantId));
    }

    /**
     * Returns the consumers of this cycle in the canonical allocation order: better priority first, ties broken on
     * the participant id.
     *
     * @return the consumer states, never {@code null}
     */
    public List<ParticipantState> consumers() {
        Comparator<ParticipantState> order = Comparator
                .comparingInt((ParticipantState state) -> ((EnergyConsumer) state.participant()).priority())
                .thenComparing(ParticipantState::id);
        return participants.values().stream().filter(state -> state.participant() instanceof EnergyConsumer)
                .sorted(order).toList();
    }

    /**
     * Returns the providers of this cycle, ordered by participant id.
     *
     * @return the provider states, never {@code null}
     */
    public List<ParticipantState> providers() {
        return participants.values().stream().filter(state -> state.participant() instanceof EnergyProvider).toList();
    }

    /**
     * Returns the site surplus in watts: grid export plus every watt of battery charging the engine could reclaim.
     * <p>
     * This is the site-wide figure, the one the level plane's escalation thresholds apply to. It counts <em>all</em>
     * battery charging, because a battery is only ever outranked from above: any charge is reclaimable by some
     * consumer whose priority number is better than the battery's. What a <em>particular</em> consumer may claim is
     * narrower and is answered by {@link #surplusWattsFor(int)}.
     * <p>
     * Both terms are read under the one central sign convention (grid + = export, battery + = charging), so a device
     * that reports the opposite sign has already been normalised at the edge and is not special-cased here.
     * <p>
     * <strong>The composition is {@code max(0, grid + reclaimable)}</strong>: the <em>signed</em> grid reading plus
     * the reclaimable charge, netted against any import before the clamp at zero. That amends the sum of two
     * non-negative terms the decision was first worded as. A site importing 1 kW while 3 kW goes into the battery has
     * 2 kW to hand out, not 3 kW - stopping the battery frees only what is left after the import is covered - and a
     * consumer started on the larger figure would put the site straight back into import, which is the opposite of
     * what a surplus figure is for. The clamp keeps the result a magnitude, so the thresholds it feeds stay
     * comparable; a deficit is read from the grid figure that carries it. A discharging battery still contributes no
     * reclaimable charge, because there is nothing there to reclaim.
     * <p>
     * Source: owner decision D27 ({@code openhab-ems-spec/docs/OWNER_DECISIONS.md}), amending D10 as literally
     * worded. The alternatives are preserved there: the literal sum, and publishing an export figure and a
     * reclaimable figure separately.
     * <p>
     * Two sub-questions the owner left open with the decision are deliberately not answered here: whether the figure
     * should be instantaneous, averaged or forecast - it is instantaneous, because that is all one cycle's snapshot
     * holds - and whether an already-running managed consumer's own draw counts towards it, which each algorithm
     * still decides for itself.
     *
     * @return the surplus in watts, or empty when no grid reading is available
     */
    public OptionalDouble surplusWatts() {
        return surplusWatts(reclaimableBatteryWatts(state -> true));
    }

    /**
     * Returns the surplus in watts one consumer may claim: grid export plus the battery charging it outranks.
     * <p>
     * Battery charging is a decision the engine itself made rather than a fixed load, so it is available to a
     * consumer with a better priority - a strictly lower priority number (a tie does not outrank). A battery
     * charging at 3 kW is therefore surplus to a solar-first EV on priority 40 and is not surplus to a trickle load
     * on priority 140, which is what stops the battery absorbing everything while a better-priority consumer idles.
     * <p>
     * The composition is the same {@code max(0, grid + reclaimable)} {@link #surplusWatts()} uses, over the narrower
     * reclaimable term: a consumer that may not reclaim the battery sees an importing site as having nothing to hand
     * out, exactly as it would with no battery at all.
     *
     * @param consumerPriority the claiming consumer's priority, lower being better
     * @return the surplus that consumer may claim in watts, or empty when no grid reading is available
     */
    public OptionalDouble surplusWattsFor(int consumerPriority) {
        return surplusWatts(reclaimableBatteryWatts(provider -> consumerPriority < provider.priority()));
    }

    private OptionalDouble surplusWatts(double reclaimableBatteryWatts) {
        QuantityType<Power> grid = gridPower;
        if (grid == null) {
            return OptionalDouble.empty();
        }
        // D27: net the reclaimable charge against any import first, then clamp - not two non-negative terms added
        double signedGrid = ModelChecks.toDouble(grid, Units.WATT, "gridPower");
        return OptionalDouble.of(Math.max(0, signedGrid + reclaimableBatteryWatts));
    }

    private double reclaimableBatteryWatts(Predicate<EnergyProvider> reclaimable) {
        double total = 0;
        for (ParticipantState state : participants.values()) {
            EnergyParticipant participant = state.participant();
            if (participant instanceof EnergyProvider provider && provider.role() == ProviderRole.BATTERY
                    && state.hasMeasurement() && reclaimable.test(provider)) {
                total += Math.max(0, state.measuredWatts());
            }
        }
        return total;
    }

    /**
     * Returns the draw the engine does not steer, in watts.
     *
     * @return the uncontrolled load in watts, zero when unknown
     */
    public double uncontrolledLoadWatts() {
        QuantityType<Power> load = uncontrolledLoad;
        return load == null ? 0 : ModelChecks.toDouble(load, Units.WATT, "uncontrolledLoad");
    }

    /**
     * Returns the draw the engine does not steer on one phase, in watts.
     *
     * @param phase the phase number, starting at 1
     * @return the uncontrolled load on that phase in watts, zero when unknown
     */
    public double uncontrolledPhaseWatts(int phase) {
        QuantityType<Power> load = uncontrolledPhaseLoad.get(phase);
        return load == null ? 0 : ModelChecks.toDouble(load, Units.WATT, "uncontrolledPhaseLoad");
    }

    /**
     * A fluent builder for {@link EnergyContext}, kept simple enough to be driven from a script or a test.
     *
     * @author Stamate Viorel - Initial contribution
     */
    public static final class Builder {

        private final Instant timestamp;
        private final EnergyLevel level;
        private final Map<String, ParticipantState> participants = new TreeMap<>();
        private final Map<Integer, QuantityType<Power>> uncontrolledPhaseLoad = new TreeMap<>();
        private @Nullable QuantityType<Power> gridPower;
        private @Nullable QuantityType<Power> pvPower;
        private @Nullable QuantityType<Power> batteryPower;
        private @Nullable QuantityType<Power> uncontrolledLoad;
        private ElectricalLimits limits = ElectricalLimits.unlimited();
        private boolean measurementsStale;

        private Builder(Instant timestamp, EnergyLevel level) {
            this.timestamp = timestamp;
            this.level = level;
        }

        /**
         * Sets the grid reading.
         *
         * @param watts the reading in watts, positive = export
         * @return this builder
         */
        public Builder gridWatts(double watts) {
            gridPower = new QuantityType<>(watts, Units.WATT);
            return this;
        }

        /**
         * Sets the PV production reading.
         *
         * @param watts the reading in watts
         * @return this builder
         */
        public Builder pvWatts(double watts) {
            pvPower = new QuantityType<>(watts, Units.WATT);
            return this;
        }

        /**
         * Sets the battery reading.
         *
         * @param watts the reading in watts
         * @return this builder
         */
        public Builder batteryWatts(double watts) {
            batteryPower = new QuantityType<>(watts, Units.WATT);
            return this;
        }

        /**
         * Sets the draw the engine does not steer.
         *
         * @param watts the load in watts
         * @return this builder
         */
        public Builder uncontrolledWatts(double watts) {
            uncontrolledLoad = new QuantityType<>(watts, Units.WATT);
            return this;
        }

        /**
         * Sets the draw the engine does not steer on one phase.
         *
         * @param phase the phase number, starting at 1
         * @param watts the load in watts
         * @return this builder
         */
        public Builder uncontrolledPhaseWatts(int phase, double watts) {
            uncontrolledPhaseLoad.put(phase, new QuantityType<>(watts, Units.WATT));
            return this;
        }

        /**
         * Adds the state of one participant.
         *
         * @param state the participant state
         * @return this builder
         */
        public Builder participant(ParticipantState state) {
            participants.put(state.id(), state);
            return this;
        }

        /**
         * Sets the declared electrical limits.
         *
         * @param electricalLimits the limits
         * @return this builder
         */
        public Builder limits(ElectricalLimits electricalLimits) {
            limits = electricalLimits;
            return this;
        }

        /**
         * Marks the safety-relevant measurements of this snapshot as missing or stale.
         *
         * @param stale {@code true} if the engine must not optimize on this data
         * @return this builder
         */
        public Builder measurementsStale(boolean stale) {
            measurementsStale = stale;
            return this;
        }

        /**
         * Builds the snapshot.
         *
         * @return the immutable snapshot
         */
        public EnergyContext build() {
            return new EnergyContext(timestamp, level, gridPower, pvPower, batteryPower, uncontrolledLoad,
                    uncontrolledPhaseLoad, participants, limits, measurementsStale);
        }
    }
}
