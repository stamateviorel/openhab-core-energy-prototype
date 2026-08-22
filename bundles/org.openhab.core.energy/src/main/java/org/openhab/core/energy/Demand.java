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

import java.time.LocalTime;

import javax.measure.quantity.Energy;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;

/**
 * Future demand declared by a consumer: an energy amount to be delivered by a deadline.
 * <p>
 * {@code consecutive} carries the "must not be interrupted" property from the requirement's second scenario: when it
 * is set, a scheduler must place the demand in contiguous slots rather than in the cheapest scattered ones.
 * <p>
 * A Batch consumer's load curve is <em>not</em> modelled here but on {@link BatchProfile}, because the shape belongs
 * to the program rather than to the demand. See {@code CONTRACT.md} for that divergence from the requirement text.
 *
 * @param energy the amount of energy to deliver, in any unit compatible with watt-hours
 * @param deadline the moment by which the energy must be delivered
 * @param consecutive {@code true} if the demand must be served in contiguous slots
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record Demand(QuantityType<Energy> energy, Deadline deadline, boolean consecutive) {

    /**
     * Validates the demand.
     *
     * @throws IllegalArgumentException if the energy amount is not expressed in an energy unit, or is negative
     */
    public Demand {
        ModelChecks.requireCompatible(energy, Units.WATT_HOUR, "energy");
        ModelChecks.requireNotNegative(energy, "energy");
    }

    /**
     * Creates an interruptible demand.
     *
     * @param energy the amount of energy to deliver
     * @param deadline the moment by which the energy must be delivered
     * @return the demand
     */
    public static Demand of(QuantityType<Energy> energy, Deadline deadline) {
        return new Demand(energy, deadline, false);
    }

    /**
     * Creates an interruptible demand expressed in kilowatt-hours with a recurring daily deadline.
     *
     * @param kilowattHours the amount of energy to deliver, in kWh
     * @param dailyDeadline the daily local deadline time
     * @return the demand
     */
    public static Demand kilowattHoursBy(double kilowattHours, LocalTime dailyDeadline) {
        return new Demand(new QuantityType<>(kilowattHours, Units.KILOWATT_HOUR), Deadline.daily(dailyDeadline), false);
    }

    /**
     * Returns the declared energy amount in kilowatt-hours.
     *
     * @return the energy amount in kWh
     */
    public double kilowattHours() {
        return ModelChecks.toDouble(energy, Units.KILOWATT_HOUR, "energy");
    }
}
