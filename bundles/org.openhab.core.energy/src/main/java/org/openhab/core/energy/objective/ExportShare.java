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
package org.openhab.core.energy.objective;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.window.SlotSeries;

/**
 * How much of a load's energy in one slot would have been exported had the load not run - the quantity the carbon
 * objective's export-credit rule turns on, and the one quantity that rule needs which no requirement in the corpus
 * defines.
 * <p>
 * <strong>This is a seam standing on a hole, and it is meant to look like one.</strong> The export-credit rule says
 * an exported kilowatt-hour earns no carbon credit while the feed-in price is negative. To apply that when ranking
 * future slots, something has to say how much of the load's energy <em>would</em> have been exported in each of them.
 * The corpus defines surplus only as an instantaneous figure read from one cycle snapshot, so the ranking-time answer
 * does not exist yet. The three implementations here are the three honest positions:
 * <ul>
 * <li>{@link None} - nothing is known, the share is zero everywhere, and the credit rule is therefore inert while
 * still being present and testable. This is what an installation with no surplus forecast gets, and the plane reports
 * it rather than letting the objective look like it evaluated a rule it could not evaluate.</li>
 * <li>{@link FromSurplusForecast} - the share is derived from a forecast surplus series. That series is a named,
 * pluggable role, so an installation that has one gets the rule applied at ranking time.</li>
 * <li>{@link Live} - the share is derived from the live surplus of one cycle snapshot. This is the dispatch-time
 * evaluation point, where the figure the corpus <em>does</em> define is the right one.</li>
 * </ul>
 * The two evaluation points are deliberately both present, because the requirement is not evaluable at one of them
 * alone and picking one silently would hide that.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public sealed interface ExportShare {

    /**
     * Returns the share of the load's energy in one slot that would otherwise have been exported.
     *
     * @param slotIndex the slot index in the series being ranked
     * @return the share, clamped to 0..1
     */
    double exportedShareAt(int slotIndex);

    /**
     * Returns whether this share was derived from data at all.
     * <p>
     * A share of zero is a legitimate answer from a forecast; it is also what "nobody told me" looks like. The two
     * are the same number and a very different statement, so they are distinguished here rather than inferred from
     * the value.
     *
     * @return {@code true} if the share comes from data, {@code false} if it is the placeholder for an absent input
     */
    boolean isDerivedFromData();

    /**
     * Returns the share for a site that cannot answer the question.
     *
     * @return a share of zero everywhere, marked as not derived from data
     */
    static ExportShare unknown() {
        return new None();
    }

    /**
     * Nothing is known about how much of the load's energy would have been exported.
     *
     * @author Stamate Viorel - Initial contribution
     */
    record None() implements ExportShare {

        @Override
        public double exportedShareAt(int slotIndex) {
            return 0;
        }

        @Override
        public boolean isDerivedFromData() {
            return false;
        }
    }

    /**
     * The share derived from a forecast surplus series: a load drawing less than the forecast surplus is fed
     * entirely from it, a load drawing more is fed from it in proportion.
     *
     * @param surplusWatts the forecast surplus series, in watts, higher meaning more surplus
     * @param loadWatts the load's own draw in watts
     *
     * @author Stamate Viorel - Initial contribution
     */
    record FromSurplusForecast(SlotSeries surplusWatts, double loadWatts) implements ExportShare {

        /**
         * Validates the inputs.
         *
         * @throws IllegalArgumentException if the load draws nothing
         */
        public FromSurplusForecast {
            if (loadWatts <= 0) {
                throw new IllegalArgumentException("loadWatts must be positive but was " + loadWatts);
            }
        }

        @Override
        public double exportedShareAt(int slotIndex) {
            if (slotIndex < 0 || slotIndex >= surplusWatts.size()) {
                return 0;
            }
            return clamp(surplusWatts.valueAt(slotIndex) / loadWatts);
        }

        @Override
        public boolean isDerivedFromData() {
            return true;
        }
    }

    /**
     * The share derived from the live surplus of one cycle snapshot - the dispatch-time evaluation point, where the
     * figure the corpus defines is available and a forecast is not needed.
     *
     * @param surplusWatts the surplus the snapshot measured, in watts
     * @param loadWatts the load's own draw in watts
     *
     * @author Stamate Viorel - Initial contribution
     */
    record Live(double surplusWatts, double loadWatts) implements ExportShare {

        /**
         * Validates the inputs.
         *
         * @throws IllegalArgumentException if the load draws nothing
         */
        public Live {
            if (loadWatts <= 0) {
                throw new IllegalArgumentException("loadWatts must be positive but was " + loadWatts);
            }
        }

        @Override
        public double exportedShareAt(int slotIndex) {
            return clamp(surplusWatts / loadWatts);
        }

        @Override
        public boolean isDerivedFromData() {
            return true;
        }
    }

    /**
     * Clamps a raw ratio into the 0..1 a share has to be in.
     *
     * @param raw the raw ratio
     * @return the clamped share
     */
    private static double clamp(double raw) {
        if (Double.isNaN(raw) || raw < 0) {
            return 0;
        }
        return Math.min(1, raw);
    }
}
