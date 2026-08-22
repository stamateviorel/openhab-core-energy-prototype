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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.price.PriceCompositionException;
import org.openhab.core.energy.price.SeriesAlignment;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;

/**
 * The default alignment: refine to every boundary any component has, and read each component at the value in force at
 * the start of the refined slot.
 * <p>
 * Adding an hourly spot series to a day/night grid tariff that steps at 07:00 and 22:00 gives a series whose slots
 * are the hours, because the tariff's steps already fall on hour boundaries. Adding a quarter-hourly spot series to
 * the same tariff gives quarter hours. Adding an hourly spot series to a tariff stepping at 06:30 gives a day whose
 * 06:00 hour is cut in two - which is the honest answer, because the effective price really does change there.
 * <p>
 * Nothing is invented and nothing is averaged away: every input value appears in the output over exactly the time it
 * was in force.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class UnionBoundaryAlignment implements SeriesAlignment {

    /**
     * The id a configuration names this alignment by.
     */
    public static final String ID = "union";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public List<SlotSeries> align(List<SlotSeries> components) throws PriceCompositionException {
        Instant[] span = AlignmentSupport.overlap(components);
        TreeSet<Instant> boundaries = new TreeSet<>();
        boundaries.add(span[0]);
        boundaries.add(span[1]);
        for (SlotSeries component : components) {
            for (Slot slot : component.slots()) {
                addIfInside(boundaries, span, slot.start());
                addIfInside(boundaries, span, slot.end());
            }
        }
        return AlignmentSupport.project(components, new ArrayList<>(boundaries));
    }

    private static void addIfInside(TreeSet<Instant> boundaries, Instant[] span, Instant candidate) {
        if (!candidate.isBefore(span[0]) && !candidate.isAfter(span[1])) {
            boundaries.add(candidate);
        }
    }

    @Override
    public String toString() {
        return "UnionBoundaryAlignment";
    }
}
