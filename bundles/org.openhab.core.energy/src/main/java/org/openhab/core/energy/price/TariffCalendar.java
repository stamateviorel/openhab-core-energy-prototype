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
package org.openhab.core.energy.price;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;

/**
 * A conditional tariff as a calendar - the "winter Mon-Sat 07-22 = higher tariff" half of {@code price-data}
 * <em>Generic grid-price provider</em>.
 * <p>
 * <strong>The zone is the site's, and it is carried here rather than inferred.</strong> This is the other half of
 * {@code price-data} <em>Delivery-day identity and the market zone</em>: a delivery day follows the market's zone,
 * carried on the price series, while a season boundary follows the site's, carried here - "and neither is inferred
 * from the other". A Belgian site buying on a Finnish market has both, and they differ.
 * <p>
 * <strong>First match wins.</strong> Periods are consulted in the order the site wrote them and the first one that
 * applies decides; anything no period covers gets {@link #defaultAmount()}. Overlapping periods are therefore
 * resolved by the user's own ordering rather than by a rule they cannot see, which is the same principle the
 * adjustment pipeline uses for its order of operations.
 * <p>
 * <strong>Rendered as a series, not applied per slot.</strong> {@link #toSeries} produces a series whose boundaries
 * are the moments the tariff actually changes, and the composition then aligns it against the spot series like any
 * other component. Evaluating the tariff at each price slot's start instead would be wrong for a coarse slot: a
 * week-out slot spanning several days would be charged the tariff in force at its first instant.
 *
 * @param zone the site's zone, in which seasons, weekdays and times of day are read
 * @param periods the conditions, in the order they are consulted
 * @param defaultAmount what applies where no period does
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record TariffCalendar(ZoneId zone, List<TariffPeriod> periods, double defaultAmount) {

    /**
     * The longest span a calendar will enumerate. A tariff series is asked for over a price series' own span, which
     * is a day or a week in every case the corpus describes; a request for centuries is a bug in the caller, and
     * answering it would build millions of slots.
     */
    private static final Duration MAXIMUM_SPAN = Duration.ofDays(400);

    /**
     * Validates the calendar and takes a defensive immutable copy.
     *
     * @throws IllegalArgumentException if the default amount is not a finite number
     */
    public TariffCalendar {
        if (!Double.isFinite(defaultAmount)) {
            throw new IllegalArgumentException("the default tariff amount must be a finite number");
        }
        periods = List.copyOf(periods);
    }

    /**
     * Returns the amount in force at a moment.
     *
     * @param moment the moment
     * @return the first applying period's amount, or {@link #defaultAmount()}
     */
    public double amountAt(Instant moment) {
        ZonedDateTime local = moment.atZone(zone);
        for (TariffPeriod period : periods) {
            if (period.applies(local)) {
                return period.amount();
            }
        }
        return defaultAmount;
    }

    /**
     * Renders this calendar as a series over a span, with a boundary wherever the amount changes.
     * <p>
     * Adjacent stretches carrying the same amount are merged, so a calendar whose periods never fire over the span
     * comes back as a single slot rather than as one slot per day - which is what makes a constant fee expressible as
     * a calendar with no periods at all.
     *
     * @param from the start of the span, inclusive
     * @param to the end of the span, exclusive
     * @return the tariff as a series
     * @throws PriceCompositionException if the span is empty or longer than a calendar will enumerate
     */
    public SlotSeries toSeries(Instant from, Instant to) throws PriceCompositionException {
        if (!to.isAfter(from)) {
            throw new PriceCompositionException(PricePlaneCondition.NO_COMMON_TIME,
                    "a tariff calendar cannot be rendered over an empty span, from " + from + " to " + to);
        }
        if (Duration.between(from, to).compareTo(MAXIMUM_SPAN) > 0) {
            throw new PriceCompositionException(PricePlaneCondition.TARIFF_SPAN_TOO_LONG,
                    "a tariff calendar is rendered over a price series' own span; " + Duration.between(from, to)
                            + " is longer than the " + MAXIMUM_SPAN + " this will enumerate");
        }
        List<Instant> changes = new ArrayList<>(candidateBoundaries(from, to));
        List<Slot> slots = new ArrayList<>(changes.size());
        for (int index = 0; index + 1 < changes.size(); index++) {
            Instant start = changes.get(index);
            double amount = amountAt(start);
            if (!slots.isEmpty() && slots.getLast().value() == amount) {
                Slot previous = slots.removeLast();
                slots.add(new Slot(previous.start(), changes.get(index + 1), amount));
            } else {
                slots.add(new Slot(start, changes.get(index + 1), amount));
            }
        }
        return new SlotSeries(slots);
    }

    /**
     * Returns every instant inside the span at which the amount could change: each local midnight, which is where a
     * month or a weekday turns over, and each period's own start and end on each local date.
     *
     * @param from the start of the span, inclusive
     * @param to the end of the span, exclusive
     * @return the candidate boundaries, ascending, always including both ends of the span
     */
    private TreeSet<Instant> candidateBoundaries(Instant from, Instant to) {
        TreeSet<Instant> boundaries = new TreeSet<>();
        boundaries.add(from);
        boundaries.add(to);
        LocalDate date = from.atZone(zone).toLocalDate().minusDays(1);
        LocalDate last = to.atZone(zone).toLocalDate().plusDays(1);
        while (!date.isAfter(last)) {
            addIfInside(boundaries, date.atStartOfDay(zone).toInstant(), from, to);
            for (TariffPeriod period : periods) {
                addIfInside(boundaries, date.atTime(period.from()).atZone(zone).toInstant(), from, to);
                addIfInside(boundaries, date.atTime(period.to()).atZone(zone).toInstant(), from, to);
            }
            date = date.plusDays(1);
        }
        return boundaries;
    }

    private static void addIfInside(TreeSet<Instant> boundaries, Instant candidate, Instant from, Instant to) {
        if (!candidate.isBefore(from) && candidate.isBefore(to)) {
            boundaries.add(candidate);
        }
    }
}
