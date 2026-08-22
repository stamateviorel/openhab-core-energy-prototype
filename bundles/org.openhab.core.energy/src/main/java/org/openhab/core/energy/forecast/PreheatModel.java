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
package org.openhab.core.energy.forecast;

import java.util.Locale;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * What pre-heating ahead of a temperature drop does to the energy the rest of the series asks for.
 * <p>
 * <strong>The corpus states the effect and not the bookkeeping.</strong> _Derived-demand forecasts_ requires that
 * "the heating-need series rises ahead of the drop so the engine pre-heats in the cheaper, warmer hours". It does not
 * say whether the energy pulled forward is <em>added</em> to the total - a building charged above its comfort point
 * genuinely loses more - or <em>moved</em> from the hours it was pre-heating for. The two produce different totals
 * and different plans, and no source in the corpus or in either cited production system states which. So both ship,
 * the site chooses, and neither is a default.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum PreheatModel {

    /**
     * The pre-heated energy is added to the earlier hours and the later hours keep asking for what they asked for.
     * <p>
     * Physically the honest half of it: heat stored early is heat partly lost early. What it does not carry is
     * <em>how much</em> is lost, which is a building constant nobody in this corpus supplies, so this model overstates
     * the total by exactly the amount the later hours no longer need.
     */
    ADDITIVE("additive"),

    /**
     * The pre-heated energy is taken from the hours it was pre-heating for, so the day's total is unchanged.
     * <p>
     * The bookkeeping a cost comparison wants - two plans for the same energy - and it silently assumes storing heat
     * for a few hours is free.
     */
    REDISTRIBUTED("redistributed");

    private final String id;

    PreheatModel(String id) {
        this.id = id;
    }

    /**
     * Returns the stable configuration id of this model.
     *
     * @return the model id
     */
    public String id() {
        return id;
    }

    /**
     * Resolves a model from its configuration id.
     *
     * @param text the id, case-insensitive, surrounding whitespace ignored
     * @return the model, or empty if no model carries that id
     */
    public static Optional<PreheatModel> fromId(String text) {
        String wanted = text.trim().toLowerCase(Locale.ROOT);
        for (PreheatModel model : values()) {
            if (model.id.equals(wanted)) {
                return Optional.of(model);
            }
        }
        return Optional.empty();
    }
}
