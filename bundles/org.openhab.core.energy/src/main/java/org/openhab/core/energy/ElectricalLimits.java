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

import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * The site's declared electrical limits: a total power budget and, where declared, a per-phase budget.
 * <p>
 * These are the runtime <em>floor</em> of the engine - the limits that outrank every optimization decision no
 * matter which algorithm produced it. Cost-optimal planning under a budget is a different concern and lives in the
 * (not yet built) {@code grid-constraints} capability.
 * <p>
 * Both the total and the per-phase budgets are expressed as powers even though an installation's per-phase limit is
 * usually stamped in amperes: {@link #ofAmperesPerPhase} converts at a declared nominal voltage, so the conversion
 * happens once, at configuration time, instead of on every cycle. A phase with no declared budget is unconstrained.
 *
 * @param totalBudget the maximum total site draw the engine may plan for, or {@code null} for unconstrained
 * @param phaseBudgets the maximum draw per phase number, empty for unconstrained
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record ElectricalLimits(@Nullable QuantityType<Power> totalBudget,
        Map<Integer, QuantityType<Power>> phaseBudgets) {

    private static final ElectricalLimits UNLIMITED = new ElectricalLimits(null, Map.of());

    /**
     * Validates the limits and takes an immutable, key-ordered copy of the phase budgets.
     *
     * @throws IllegalArgumentException if a budget is not a power, is negative, or a phase number is below one
     */
    public ElectricalLimits {
        QuantityType<Power> total = totalBudget;
        if (total != null) {
            ModelChecks.requireCompatible(total, Units.WATT, "totalBudget");
            ModelChecks.requireNotNegative(total, "totalBudget");
        }
        Map<Integer, QuantityType<Power>> copy = new TreeMap<>();
        for (Map.Entry<Integer, QuantityType<Power>> entry : phaseBudgets.entrySet()) {
            Integer phase = entry.getKey();
            if (phase < 1) {
                throw new IllegalArgumentException("phase numbers start at 1 but was " + phase);
            }
            QuantityType<Power> budget = entry.getValue();
            ModelChecks.requireCompatible(budget, Units.WATT, "phaseBudget");
            ModelChecks.requireNotNegative(budget, "phaseBudget");
            copy.put(phase, budget);
        }
        phaseBudgets = Map.copyOf(copy);
    }

    /**
     * Returns the limits of a site that declares none.
     *
     * @return unconstrained limits
     */
    public static ElectricalLimits unlimited() {
        return UNLIMITED;
    }

    /**
     * Creates limits with a total budget only.
     *
     * @param watts the total budget in watts
     * @return the limits
     */
    public static ElectricalLimits ofWatts(double watts) {
        return new ElectricalLimits(new QuantityType<>(watts, Units.WATT), Map.of());
    }

    /**
     * Creates limits from a per-phase current rating, the usual way an installation is stamped.
     *
     * @param amperesPerPhase the per-phase current limit in amperes
     * @param nominalVolts the nominal phase voltage used to convert to power
     * @param phaseCount the number of phases, each getting the same budget
     * @return limits whose total budget is the sum of the per-phase budgets
     */
    public static ElectricalLimits ofAmperesPerPhase(double amperesPerPhase, double nominalVolts, int phaseCount) {
        if (phaseCount < 1) {
            throw new IllegalArgumentException("phaseCount must be at least 1 but was " + phaseCount);
        }
        double perPhaseWatts = amperesPerPhase * nominalVolts;
        Map<Integer, QuantityType<Power>> budgets = new TreeMap<>();
        for (int phase = 1; phase <= phaseCount; phase++) {
            budgets.put(phase, new QuantityType<>(perPhaseWatts, Units.WATT));
        }
        return new ElectricalLimits(new QuantityType<>(perPhaseWatts * phaseCount, Units.WATT), budgets);
    }

    /**
     * Returns a copy of these limits with one phase budget added or replaced.
     *
     * @param phase the phase number, starting at 1
     * @param watts the budget for that phase in watts
     * @return the copy
     */
    public ElectricalLimits withPhaseWatts(int phase, double watts) {
        Map<Integer, QuantityType<Power>> budgets = new TreeMap<>(phaseBudgets);
        budgets.put(phase, new QuantityType<>(watts, Units.WATT));
        return new ElectricalLimits(totalBudget, budgets);
    }

    /**
     * Tests whether any limit at all is declared.
     *
     * @return {@code true} if neither a total nor a per-phase budget is declared
     */
    public boolean isUnlimited() {
        return totalBudget == null && phaseBudgets.isEmpty();
    }

    /**
     * Returns the total budget in watts.
     *
     * @return the budget, or empty if unconstrained
     */
    public OptionalDouble totalBudgetWatts() {
        QuantityType<Power> total = totalBudget;
        return total == null ? OptionalDouble.empty()
                : OptionalDouble.of(ModelChecks.toDouble(total, Units.WATT, "totalBudget"));
    }

    /**
     * Returns the budget of one phase in watts.
     *
     * @param phase the phase number, starting at 1
     * @return the budget, or empty if that phase is unconstrained
     */
    public OptionalDouble phaseBudgetWatts(int phase) {
        QuantityType<Power> budget = phaseBudgets.get(phase);
        return budget == null ? OptionalDouble.empty()
                : OptionalDouble.of(ModelChecks.toDouble(budget, Units.WATT, "phaseBudget"));
    }
}
