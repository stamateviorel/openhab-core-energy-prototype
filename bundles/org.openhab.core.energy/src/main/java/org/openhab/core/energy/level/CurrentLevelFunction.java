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
package org.openhab.core.energy.level;

import java.time.Instant;
import java.util.OptionalDouble;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.EnergyLevel;

/**
 * The level plane as the engine uses it: <strong>a pure function the engine calls, never a source it reads</strong>.
 * <p>
 * This is the whole coupling between the level classifier and the engine spine, and its direction is fixed by the
 * corpus. The engine takes one snapshot per cycle, and it computes the level <em>of that snapshot</em> by calling
 * this function with the snapshot's own instant and the surplus that snapshot measured. Both arguments come from the
 * cycle; nothing here has a clock, a plan lookup of its own, or an Item to consult. That is what makes "one
 * consistent snapshot per cycle" true: the level and the readings it was derived from cannot disagree, because there
 * is only one reading and only one moment.
 * <p>
 * <strong>Publication runs the other way.</strong> The current level as an Item state, and the planned schedule as a
 * future-timestamped {@code TimeSeries}, are <em>outputs</em> of the computation this function performs - reports of
 * what the engine decided. They are never the path by which the engine obtains a level, so no implementation may
 * satisfy a call by reading back a published Item. This bundle cannot publish either of them at all: it is
 * structurally incapable of writing to an Item, and a companion component has to own that surface.
 * <p>
 * The alternative the corpus preserves - the level published as an Item plus a planned series, with the engine
 * reading it back - is a materially different architecture: the classifier and the engine would couple through the
 * item registry, this seam would disappear, and shadow mode would need its own answer for where the level comes
 * from. It is preserved, not implemented.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@FunctionalInterface
public interface CurrentLevelFunction {

    /**
     * Returns the site energy level for one cycle, from that cycle's own moment and surplus.
     *
     * @param moment the instant the cycle's snapshot was taken at - never "now", always the snapshot's own timestamp
     * @param surplusWatts the surplus that snapshot measured, in watts, or empty when the cycle has no usable
     *            reading to derive one from
     * @return the level in force for that cycle, never {@code null}
     */
    EnergyLevel levelAt(Instant moment, OptionalDouble surplusWatts);
}
