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

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.price.PriceCompositionException;
import org.openhab.core.energy.price.PricePlaneCondition;
import org.openhab.core.energy.price.SeriesAlignment;
import org.openhab.core.energy.window.SlotSeries;

/**
 * The refusing alignment: components must already share their slots exactly, or nothing is composed.
 * <p>
 * The third reading the corpus leaves open, and the only one that never produces a slot no source published. A site
 * whose components genuinely arrive on one geometry - a single provider publishing spot, tariff and fee together -
 * gets a guarantee from it: if the shapes ever diverge, the site hears about it instead of silently getting a
 * refined or resampled series.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class StrictAlignment implements SeriesAlignment {

    /**
     * The id a configuration names this alignment by.
     */
    public static final String ID = "strict";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public List<SlotSeries> align(List<SlotSeries> components) throws PriceCompositionException {
        SlotSeries reference = components.getFirst();
        for (SlotSeries component : components) {
            if (component.size() != reference.size()) {
                throw new PriceCompositionException(PricePlaneCondition.UNALIGNABLE,
                        "the price components must already share their slots: one has " + reference.size()
                                + " slots and another has " + component.size());
            }
            for (int slot = 0; slot < reference.size(); slot++) {
                if (!component.slotAt(slot).start().equals(reference.slotAt(slot).start())
                        || !component.slotAt(slot).end().equals(reference.slotAt(slot).end())) {
                    throw new PriceCompositionException(PricePlaneCondition.UNALIGNABLE,
                            "the price components must already share their slots: slot " + slot + " runs "
                                    + reference.slotAt(slot).start() + " to " + reference.slotAt(slot).end()
                                    + " in one component and " + component.slotAt(slot).start() + " to "
                                    + component.slotAt(slot).end() + " in another");
                }
            }
        }
        return List.copyOf(components);
    }

    @Override
    public String toString() {
        return "StrictAlignment";
    }
}
