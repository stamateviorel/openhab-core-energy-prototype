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
package org.openhab.core.energy.level.internal;

import java.time.LocalDate;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.level.LevelDerivation;
import org.openhab.core.energy.level.PlannedLevelSchedule;
import org.openhab.core.energy.level.SeasonalParameters;
import org.openhab.core.energy.window.SlotSeries;

/**
 * Picks a derivation by the date of the series and delegates to it - the <em>Seasonal window defaults</em>
 * requirement, where "the configured winter hour-counts replace the summer ones without user action".
 * <p>
 * The season is decided by the local date of the <strong>first slot's start</strong> in the configured zone. A market
 * day nearly always straddles two local dates (the fixture day starts at 23:00 UTC the evening before), and the
 * corpus never says which date names a delivery day, so this class applies one derivation to the whole series rather
 * than switching part-way through it - switching mid-series would make the level counts of the two halves ambiguous.
 * The alternative reading, classifying each slot by its own date, is a spec decision and is deliberately not taken
 * here.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class SeasonalLevelDerivation implements LevelDerivation {

    private final SeasonalParameters parameters;

    /**
     * Creates the derivation.
     *
     * @param parameters the seasons, their derivations and the fallback outside them
     */
    public SeasonalLevelDerivation(SeasonalParameters parameters) {
        this.parameters = parameters;
    }

    @Override
    public PlannedLevelSchedule derive(SlotSeries series) {
        LocalDate date = series.start().atZone(parameters.zone()).toLocalDate();
        return parameters.derivationFor(date).derive(series);
    }

    /**
     * Returns the configured seasons.
     *
     * @return the seasonal parameters this derivation applies
     */
    public SeasonalParameters parameters() {
        return parameters;
    }

    @Override
    public String toString() {
        return "SeasonalLevelDerivation[" + parameters.seasons().size() + " seasons in " + parameters.zone() + "]";
    }
}
