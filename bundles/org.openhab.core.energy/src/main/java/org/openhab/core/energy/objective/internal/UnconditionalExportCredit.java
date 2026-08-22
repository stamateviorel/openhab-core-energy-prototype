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
 * The preserved alternative to {@link NegativeFeedInCarbonCredit}: an exported kilowatt-hour earns the generation it
 * displaces whatever the feed-in price is.
 * <p>
 * This is the naive reading, and it is deliberately shipped rather than merely documented. It is what any
 * implementation does if the export-credit requirement is deleted, so having it selectable makes overturning that
 * requirement a configuration change instead of a code change - which matters, because the requirement is the one in
 * its corpus with no thread source and no production system behind it.
 * <p>
 * Under this rule the carbon objective ranks a slot on its carbon series alone: the credit term multiplies out to
 * one everywhere, so the exported share of a load's energy stops mattering and the objective's answer no longer
 * depends on a forecast surplus.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class UnconditionalExportCredit implements ExportCarbonCredit {

    /**
     * The id this rule is selected by.
     */
    public static final String ID = "always";

    @Override
    public double creditFactorAt(double effectiveFeedInPrice) {
        return 1;
    }

    @Override
    public String getId() {
        return ID;
    }
}
