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
package org.openhab.core.energy.price.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.price.PriceCompositionException;
import org.openhab.core.energy.price.SeriesAlignment;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;

/**
 * The resampling alignment: put every component onto a regular grid as fine as the finest slot any of them has.
 * <p>
 * One of the three readings of {@code price-data} <em>Price component composition</em> that the corpus leaves open.
 * It answers with a uniform series, which some consumers prefer and some charts need, and it pays for that by
 * splitting a coarse component into slots it never published - a week of hourly far-term prices becomes a week of
 * quarter hours because tomorrow happens to be quarter-hourly. No value is changed; only the number of slots carrying
 * it is.
 * <p>
 * A grid finer than the span is refused implicitly: the finest slot of a real series is at least one slot wide, so
 * the number of steps is bounded by the span divided by that width.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class FinestComponentAlignment implements SeriesAlignment {

    /**
     * The id a configuration names this alignment by.
     */
    public static final String ID = "finest";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public List<SlotSeries> align(List<SlotSeries> components) throws PriceCompositionException {
        Instant[] span = AlignmentSupport.overlap(components);
        Duration step = finestSlot(components);
        List<Instant> boundaries = new ArrayList<>();
        Instant cursor = span[0];
        while (cursor.isBefore(span[1])) {
            boundaries.add(cursor);
            cursor = cursor.plus(step);
        }
        boundaries.add(span[1]);
        return AlignmentSupport.project(components, boundaries);
    }

    private static Duration finestSlot(List<SlotSeries> components) {
        Duration finest = components.getFirst().slotAt(0).duration();
        for (SlotSeries component : components) {
            for (Slot slot : component.slots()) {
                finest = slot.duration().compareTo(finest) < 0 ? slot.duration() : finest;
            }
        }
        return finest;
    }

    @Override
    public String toString() {
        return "FinestComponentAlignment";
    }
}
