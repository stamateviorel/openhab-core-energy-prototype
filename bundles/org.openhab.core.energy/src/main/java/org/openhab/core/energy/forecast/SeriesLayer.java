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
 * Who wrote an entry of a layered prediction series.
 * <p>
 * The corpus names four writers - the baseline generator, the live forecast refresh, the cap writer, and later the
 * learning layer - and needs to tell them apart in exactly one place: when two of them land on the same timestamp.
 * <p>
 * <strong>There is deliberately no rank on this enum.</strong> Ordering the layers <em>is</em> the answer to the
 * corpus's open writer-precedence question, and the constant order of an enum is the easiest place in the world to
 * decide something by accident. The order, where one is used at all, is configuration -
 * {@link LayeredWritePolicy#WRITER_PRECEDENCE} reads it from the site and refuses to write without it.
 * <p>
 * <strong>A layer identity is the framework's own bookkeeping and does not survive in storage.</strong> A persisted
 * value is a number at a timestamp; nothing in persistence, and nothing in a published time series, records who put
 * it there. So a write-time rule can only be evaluated for writes that go through this framework's own layered
 * surface while it is running, and a restart forgets which layer wrote what. That is not an implementation shortcut
 * to be fixed later: it is the fact that makes {@link LayeredWritePolicy#CAP_COMPOSED_AT_READ_TIME} the only option
 * that keeps working across a restart, and it is why the alternatives are configuration rather than a decision made
 * here.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum SeriesLayer {

    /**
     * The long-range, coarse pre-fill - the corpus's photovoltaic year derived from daily sunshine duration.
     */
    BASELINE("baseline"),

    /**
     * A live forecast run, refining the baseline for the days it covers.
     */
    FORECAST("forecast"),

    /**
     * A known limit written onto the entries it applies to - an inverter's built-in generation limit, or a
     * §14a dimming limit openHAB can neither read nor control.
     */
    CAP("cap"),

    /**
     * A value corrected from observed history. No writer produces it yet; the learning layer is a later wave, and the
     * layer is named here because a precedence order that omits it would have to be re-decided the moment it lands.
     */
    LEARNED("learned");

    private final String id;

    SeriesLayer(String id) {
        this.id = id;
    }

    /**
     * Returns the stable configuration id of this layer.
     *
     * @return the layer id
     */
    public String id() {
        return id;
    }

    /**
     * Resolves a layer from its configuration id.
     *
     * @param text the id, case-insensitive, surrounding whitespace ignored
     * @return the layer, or empty if no layer carries that id
     */
    public static Optional<SeriesLayer> fromId(String text) {
        String wanted = text.trim().toLowerCase(Locale.ROOT);
        for (SeriesLayer layer : values()) {
            if (layer.id.equals(wanted)) {
                return Optional.of(layer);
            }
        }
        return Optional.empty();
    }
}
