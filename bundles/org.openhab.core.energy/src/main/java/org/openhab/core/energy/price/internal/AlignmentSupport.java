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
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.price.PriceCompositionException;
import org.openhab.core.energy.price.PricePlaneCondition;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;

/**
 * What the two producing alignments share: the overlap they may work in, and the projection of a component onto a set
 * of boundaries.
 * <p>
 * Both steps are the same for the union alignment and the resampling one; only the boundaries differ. Keeping them
 * here means the rules a reviewer cares about - a value is held from its own start, a slot survives only where every
 * component has one - are written once and cannot drift apart between two implementations.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
final class AlignmentSupport {

    private AlignmentSupport() {
    }

    /**
     * Returns the span every component covers, which is the only span a sum can be defined over.
     *
     * @param components the components
     * @return the shared span, start inclusive and end exclusive
     * @throws PriceCompositionException if the components share no time
     */
    static Instant[] overlap(List<SlotSeries> components) throws PriceCompositionException {
        Instant from = components.getFirst().start();
        Instant to = components.getFirst().end();
        for (SlotSeries component : components) {
            from = component.start().isAfter(from) ? component.start() : from;
            to = component.end().isBefore(to) ? component.end() : to;
        }
        if (!to.isAfter(from)) {
            throw new PriceCompositionException(PricePlaneCondition.NO_COMMON_TIME,
                    "the price components share no time at all: the latest component starts at " + from
                            + " and the earliest one ends at " + to);
        }
        return new Instant[] { from, to };
    }

    /**
     * Projects every component onto the given boundaries, dropping the slots any component does not cover.
     *
     * @param components the components
     * @param boundaries the shared boundaries, ascending, at least two
     * @return one series per component, all sharing the surviving slots
     * @throws PriceCompositionException if no slot survives
     */
    static List<SlotSeries> project(List<SlotSeries> components, List<Instant> boundaries)
            throws PriceCompositionException {
        List<List<Slot>> projected = new ArrayList<>(components.size());
        for (int component = 0; component < components.size(); component++) {
            projected.add(new ArrayList<>(boundaries.size()));
        }
        for (int boundary = 0; boundary + 1 < boundaries.size(); boundary++) {
            Instant start = boundaries.get(boundary);
            Instant end = boundaries.get(boundary + 1);
            List<Double> values = new ArrayList<>(components.size());
            boolean covered = true;
            for (SlotSeries component : components) {
                Optional<Double> held = heldAt(component, start);
                if (held.isEmpty()) {
                    covered = false;
                    break;
                }
                values.add(held.get());
            }
            if (covered) {
                for (int component = 0; component < components.size(); component++) {
                    projected.get(component).add(new Slot(start, end, values.get(component)));
                }
            }
        }
        if (projected.getFirst().isEmpty()) {
            throw new PriceCompositionException(PricePlaneCondition.NO_COMMON_TIME,
                    "no slot is covered by every price component, so no effective price can be composed");
        }
        List<SlotSeries> aligned = new ArrayList<>(components.size());
        for (int component = 0; component < components.size(); component++) {
            aligned.add(new SlotSeries(projected.get(component), components.get(component).sense()));
        }
        return aligned;
    }

    /**
     * Returns the value a component holds at an instant.
     * <p>
     * A component's value is held from its own slot start until that slot ends, which is the LEFT rule the corpus
     * names for integration applied to reading. Inside a gap there is no value, and that is an answer rather than a
     * zero.
     *
     * @param component the component
     * @param instant the instant
     * @return the value in force, or empty inside a gap or outside the component
     */
    static Optional<Double> heldAt(SlotSeries component, Instant instant) {
        for (Slot slot : component.slots()) {
            if (slot.covers(instant)) {
                return Optional.of(slot.value());
            }
        }
        return Optional.empty();
    }
}
