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

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;

/**
 * The arithmetic of a layered prediction series: which entries a write should end up producing, and what the
 * effective series is once a constraint series is composed onto it.
 * <p>
 * Both halves are pure functions over timestamps and numbers. Neither touches an Item, a persistence service or an
 * event, which is what lets the whole of the layered-prediction <em>decision</em> live in the framework bundle while
 * only the write itself moves out.
 * <p>
 * <strong>The collision this class exists for is not resolved here.</strong> See {@link LayeredWritePolicy}: the site
 * chooses, and where it has chosen nothing the requirement's own words are applied and the erasure is reported.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class LayeredSeriesResolver {

    private LayeredSeriesResolver() {
    }

    /**
     * Works out what one write to a layered prediction series should do.
     *
     * @param stored the entries the framework believes the series currently holds, with the layer that wrote each
     * @param incoming the entries being written now; they may mix layers, though a single writer normally does not
     * @param policy how a collision between two layers resolves, or {@code null} where the site has chosen nothing
     * @param precedence the rank of each layer, higher winning, used only by
     *            {@link LayeredWritePolicy#WRITER_PRECEDENCE} and empty unless the site declared one
     * @return the plan: what to write, what to keep apart, what was refused, and what to report
     */
    public static LayeredWritePlan resolve(List<LayeredEntry> stored, List<LayeredEntry> incoming,
            @Nullable LayeredWritePolicy policy, Map<SeriesLayer, Integer> precedence) {
        Map<Instant, LayeredEntry> existing = new LinkedHashMap<>();
        for (LayeredEntry entry : stored) {
            existing.put(entry.timestamp(), entry);
        }
        Set<ForecastPlaneCondition> conditions = EnumSet.noneOf(ForecastPlaneCondition.class);
        if (policy == null) {
            conditions.add(ForecastPlaneCondition.WRITE_POLICY_UNCONFIGURED);
        }
        LayeredWritePolicy effective = policy == null ? LayeredWritePolicy.FRESH_OVERWRITES_OLD : policy;
        return switch (effective) {
            case FRESH_OVERWRITES_OLD -> freshWins(existing, incoming, conditions);
            case REAPPLY_CAPS_AFTER_REFRESH -> reapplyCaps(existing, incoming, conditions);
            case WRITER_PRECEDENCE -> byPrecedence(existing, incoming, precedence, conditions);
            case CAP_COMPOSED_AT_READ_TIME -> capsApart(incoming, conditions);
        };
    }

    /**
     * Composes a constraint series onto a prediction: where a cap covers a slot, the effective value is the smaller
     * of the two.
     * <p>
     * <strong>The two series need not share a geometry, and how they are aligned is a decision the corpus does not
     * make.</strong> The rule here is the one the rest of the framework already uses for a value that applies over an
     * interval: a prediction slot is capped by whatever cap value covers its <em>start</em>, held from that cap
     * entry's own timestamp. A prediction slot that starts inside a gap in the cap series is not capped at all. This
     * keeps the prediction's own boundaries, so a caller's slot indices keep meaning what they meant, and it never
     * invents a boundary neither series has. Refining to the union of both boundary sets is the alternative, and it
     * is the same choice the price plane faces when it adds two components of different geometry - which is why it is
     * reported rather than settled twice, differently.
     *
     * @param prediction the predicted series
     * @param cap the constraint series
     * @return the effective series, on the prediction's own slot boundaries
     */
    public static SlotSeries composeCap(SlotSeries prediction, SlotSeries cap) {
        List<Slot> composed = new ArrayList<>(prediction.size());
        for (Slot slot : prediction.slots()) {
            OptionalInt covering = cap.indexAt(slot.start());
            double value = covering.isPresent() ? Math.min(slot.value(), cap.valueAt(covering.getAsInt()))
                    : slot.value();
            composed.add(new Slot(slot.start(), slot.end(), value));
        }
        return new SlotSeries(composed, prediction.sense());
    }

    private static LayeredWritePlan freshWins(Map<Instant, LayeredEntry> existing, List<LayeredEntry> incoming,
            Set<ForecastPlaneCondition> conditions) {
        List<Instant> collisions = new ArrayList<>();
        for (LayeredEntry entry : incoming) {
            @Nullable
            LayeredEntry held = existing.get(entry.timestamp());
            if (overwritesACap(held, entry)) {
                collisions.add(entry.timestamp());
            }
        }
        if (!collisions.isEmpty()) {
            conditions.add(ForecastPlaneCondition.CAP_OVERWRITTEN_BY_REFRESH);
        }
        return new LayeredWritePlan(sorted(incoming), List.of(), List.of(), collisions, conditions);
    }

    private static LayeredWritePlan reapplyCaps(Map<Instant, LayeredEntry> existing, List<LayeredEntry> incoming,
            Set<ForecastPlaneCondition> conditions) {
        List<Instant> collisions = new ArrayList<>();
        List<LayeredEntry> entries = new ArrayList<>(sorted(incoming));
        List<LayeredEntry> reapplied = new ArrayList<>();
        for (LayeredEntry entry : incoming) {
            @Nullable
            LayeredEntry held = existing.get(entry.timestamp());
            if (overwritesACap(held, entry) && held != null) {
                collisions.add(entry.timestamp());
                reapplied.add(held);
            }
        }
        if (!collisions.isEmpty()) {
            conditions.add(ForecastPlaneCondition.CAP_OVERWRITTEN_BY_REFRESH);
        }
        // the cap goes back on last, which is what "re-apply after any refresh" means
        entries.addAll(reapplied);
        return new LayeredWritePlan(entries, List.of(), List.of(), collisions, conditions);
    }

    private static LayeredWritePlan byPrecedence(Map<Instant, LayeredEntry> existing, List<LayeredEntry> incoming,
            Map<SeriesLayer, Integer> precedence, Set<ForecastPlaneCondition> conditions) {
        if (precedence.isEmpty()) {
            conditions.add(ForecastPlaneCondition.LAYER_PRECEDENCE_UNCONFIGURED);
            return new LayeredWritePlan(List.of(), List.of(), sorted(incoming), List.of(), conditions);
        }
        List<LayeredEntry> entries = new ArrayList<>();
        List<LayeredEntry> refused = new ArrayList<>();
        List<Instant> collisions = new ArrayList<>();
        for (LayeredEntry entry : incoming) {
            @Nullable
            LayeredEntry held = existing.get(entry.timestamp());
            if (held == null || held.layer() == entry.layer()) {
                entries.add(entry);
                continue;
            }
            collisions.add(entry.timestamp());
            if (rankOf(entry.layer(), precedence) >= rankOf(held.layer(), precedence)) {
                entries.add(entry);
            } else {
                refused.add(entry);
            }
        }
        if (!refused.isEmpty()) {
            conditions.add(ForecastPlaneCondition.WRITE_REFUSED_BY_PRECEDENCE);
        }
        return new LayeredWritePlan(sorted(entries), List.of(), sorted(refused), collisions, conditions);
    }

    private static LayeredWritePlan capsApart(List<LayeredEntry> incoming, Set<ForecastPlaneCondition> conditions) {
        List<LayeredEntry> entries = new ArrayList<>();
        List<LayeredEntry> constraints = new ArrayList<>();
        for (LayeredEntry entry : incoming) {
            if (entry.layer() == SeriesLayer.CAP) {
                constraints.add(entry);
            } else {
                entries.add(entry);
            }
        }
        return new LayeredWritePlan(sorted(entries), sorted(constraints), List.of(), List.of(), conditions);
    }

    private static boolean overwritesACap(@Nullable LayeredEntry held, LayeredEntry incoming) {
        return held != null && held.layer() == SeriesLayer.CAP && incoming.layer() != SeriesLayer.CAP;
    }

    private static int rankOf(SeriesLayer layer, Map<SeriesLayer, Integer> precedence) {
        @Nullable
        Integer rank = precedence.get(layer);
        return rank == null ? Integer.MIN_VALUE : rank;
    }

    private static List<LayeredEntry> sorted(List<LayeredEntry> entries) {
        List<LayeredEntry> copy = new ArrayList<>(entries);
        copy.sort((left, right) -> left.timestamp().compareTo(right.timestamp()));
        return copy;
    }
}
