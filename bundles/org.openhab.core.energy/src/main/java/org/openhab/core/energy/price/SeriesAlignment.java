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

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.price.internal.FinestComponentAlignment;
import org.openhab.core.energy.price.internal.StrictAlignment;
import org.openhab.core.energy.price.internal.UnionBoundaryAlignment;
import org.openhab.core.energy.window.SlotSeries;

/**
 * How components whose slots do not line up are brought onto one geometry before they are summed.
 * <p>
 * <strong>This is a seam because the corpus does not answer it.</strong> {@code price-data} <em>Price component
 * composition</em>'s headline scenario adds an hourly spot series to a grid tariff that changes at two times of day
 * and to a constant fee, and <em>Time resolution</em> then <em>mandates</em> non-uniform and mixed intervals. So
 * components of differing geometry are guaranteed rather than exceptional - and nothing in the corpus says what
 * adding two of them means. Three readings are defensible, all three are implemented, and the choice is
 * configuration rather than a silent decision here:
 * <ul>
 * <li>{@link #unionOfBoundaries()} - refine to every boundary any component has, and read each component across a
 * refined slot at the value in force at its start. This is the default, because it is the only one of the three that
 * loses no information from any component and because holding a value from its own timestamp forward is the same
 * LEFT rule the corpus already names for integration.</li>
 * <li>{@link #finestComponent()} - resample everything onto a grid as fine as the finest component. Regular output,
 * which some consumers want, at the cost of splitting a coarse component into slots it never had.</li>
 * <li>{@link #strict()} - refuse to compose components that are not already aligned. The reading in which the sum of
 * two differently shaped series is simply undefined, and the site is told to fix its inputs.</li>
 * </ul>
 * <p>
 * <strong>A gap is never filled.</strong> Whatever the alignment, a resulting slot survives only where <em>every</em>
 * component has a value: a sum in which one term is unknown is unknown, not the sum of the terms that happen to be
 * known. That keeps gaps meaningful, which is what the level plane's contiguity rule depends on.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface SeriesAlignment {

    /**
     * Returns the stable id of this alignment, which is what a configuration names.
     *
     * @return the alignment id
     */
    String getId();

    /**
     * Brings every component onto one shared set of slot boundaries.
     *
     * @param components the components to align, in the caller's own order
     * @return one series per component, all sharing the same slots, in the same order
     * @throws PriceCompositionException if the components cannot be aligned, or share no time at all
     */
    List<SlotSeries> align(List<SlotSeries> components) throws PriceCompositionException;

    /**
     * Returns the alignment that refines to the union of every component's boundaries, holding each component's value
     * from its own start.
     *
     * @return the alignment
     */
    static SeriesAlignment unionOfBoundaries() {
        return new UnionBoundaryAlignment();
    }

    /**
     * Returns the alignment that resamples every component onto a grid as fine as the finest one.
     *
     * @return the alignment
     */
    static SeriesAlignment finestComponent() {
        return new FinestComponentAlignment();
    }

    /**
     * Returns the alignment that refuses anything not already aligned.
     *
     * @return the alignment
     */
    static SeriesAlignment strict() {
        return new StrictAlignment();
    }

    /**
     * Returns the alignment a configuration names, or the default when it names none.
     *
     * @param id the alignment id, empty or unknown selecting the default
     * @return the alignment
     */
    static SeriesAlignment byId(String id) {
        return switch (id) {
            case FinestComponentAlignment.ID -> finestComponent();
            case StrictAlignment.ID -> strict();
            default -> unionOfBoundaries();
        };
    }
}
