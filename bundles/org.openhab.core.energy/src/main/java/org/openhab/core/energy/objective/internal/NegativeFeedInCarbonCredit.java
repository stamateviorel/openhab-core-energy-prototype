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
package org.openhab.core.energy.objective.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.objective.ExportCarbonCredit;

/**
 * <strong>THIS CLASS IS ONE DECISION, AND IT IS THE WEAKEST DECISION IN THE SPECIFICATION IT COMES FROM.</strong>
 * <p>
 * The rule: an exported kilowatt-hour earns the generation it displaces as a carbon credit <em>only while the
 * effective feed-in price is zero or above</em>. At a negative feed-in price it earns nothing. The reasoning is that
 * a negative price is the market refusing the power, so the realistic outcome is curtailment and the displacement the
 * credit stands for never happens.
 * <p>
 * <strong>The honest flag, which travels with the rule wherever it is repeated: no thread comment and no production
 * system states this.</strong> Every other requirement in the corpus this implements rests on a comment in the
 * originating issue or on a system somebody actually runs. This one rests on reasoning alone. The decision pack that
 * dispositioned the rest of the corpus declined to recommend it for exactly that reason and handed it to the owner,
 * and the owner's own record calls it the most overturnable decision in the set. Treat a maintainer who disagrees
 * with it as probably right.
 * <p>
 * <strong>How to overturn it, in full.</strong> Everything the rule does is the one comparison in
 * {@link #creditFactorAt(double)}. Three ways out, in increasing order of effort:
 * <ol>
 * <li>Configure {@code exportCarbonCredit = always} on the objective plane. {@link UnconditionalExportCredit} is the
 * naive reading - credit every export regardless of price - and is exactly what any implementation does if this rule
 * is deleted. It is shipped alongside this one so that overturning the decision needs no code at all.</li>
 * <li>Change the shipped default in {@code OH-INF/config/energy.xml} and in
 * {@code ObjectivePlaneConfiguration.DEFAULT_EXPORT_CREDIT}. Two lines.</li>
 * <li>Delete this class. Nothing else in the framework moves: no other objective, no selection strategy, no window
 * cost and no level derivation refers to it, and the carbon objective falls back to ranking on the plain carbon
 * series - which is also what it does today on any site that cannot say how much of a load's energy would have been
 * exported.</li>
 * </ol>
 * <p>
 * A third position exists in the corpus and is deliberately not shipped: crediting the export at a
 * curtailment-discounted rate, which is the defensible middle. It needs a discount rate and nobody has one, so
 * shipping it would mean inventing the number the whole rule is about. It would be one more implementation of
 * {@link ExportCarbonCredit}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class NegativeFeedInCarbonCredit implements ExportCarbonCredit {

    /**
     * The id this rule is selected by.
     */
    public static final String ID = "withdraw-on-negative-price";

    @Override
    public double creditFactorAt(double effectiveFeedInPrice) {
        // The whole of owner decision D20. One comparison; see this class's JavaDoc before changing it.
        return effectiveFeedInPrice < 0 ? 0 : 1;
    }

    @Override
    public String getId() {
        return ID;
    }
}
