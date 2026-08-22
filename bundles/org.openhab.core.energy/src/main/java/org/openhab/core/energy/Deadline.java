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
import java.time.LocalTime;
import java.time.ZonedDateTime;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The moment by which a {@link Demand} must be met.
 * <p>
 * The requirement's own examples read two different ways - "4 kWh ready by 07:00" is a recurring daily deadline, while
 * a one-off "ready by this evening" is an absolute instant. Rather than picking one, both readings are modelled as
 * variants of this sealed type, so a declaration mechanism can express either and the engine handles both through
 * {@link #resolve(ZonedDateTime)}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public sealed interface Deadline permits Deadline.At, Deadline.Daily {

    /**
     * Resolves this deadline to the next absolute instant at or after the given reference moment.
     *
     * @param reference the moment to resolve against, carrying the site's time zone
     * @return the absolute deadline instant
     */
    Instant resolve(ZonedDateTime reference);

    /**
     * A one-off deadline at a fixed absolute instant.
     *
     * @param instant the absolute deadline
     *
     * @author Stamate Viorel - Initial contribution
     */
    record At(Instant instant) implements Deadline {

        @Override
        public Instant resolve(ZonedDateTime reference) {
            return instant;
        }
    }

    /**
     * A deadline that recurs every day at the same local time. Windows wrap past midnight: if the local time has
     * already passed on the reference day, the deadline is the same local time on the following day.
     *
     * @param localTime the daily local deadline time
     *
     * @author Stamate Viorel - Initial contribution
     */
    record Daily(LocalTime localTime) implements Deadline {

        @Override
        public Instant resolve(ZonedDateTime reference) {
            ZonedDateTime today = reference.with(localTime);
            return (today.isBefore(reference) ? today.plusDays(1) : today).toInstant();
        }
    }

    /**
     * Creates a one-off deadline at the given instant.
     *
     * @param instant the absolute deadline
     * @return the deadline
     */
    static Deadline at(Instant instant) {
        return new At(instant);
    }

    /**
     * Creates a deadline that recurs every day at the given local time.
     *
     * @param localTime the daily local deadline time
     * @return the deadline
     */
    static Deadline daily(LocalTime localTime) {
        return new Daily(localTime);
    }
}
