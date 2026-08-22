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

import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.window.SeriesSense;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;

/**
 * What a forecast series is about - the key a source registers under, and the key a site selects a preferred source
 * by.
 * <p>
 * <strong>The role is global, not per participant.</strong> _Solar production forecast_ speaks of "plant-level"
 * forecasts and _Source-agnostic consumption_ selects sources "by user configuration", and nothing in the corpus says
 * whether the selection key is one role for the whole site or one per photovoltaic array. A site with an east and a
 * west array makes the two readings differ. One global role is what the corpus's own wording supports and what every
 * scenario in it needs; a per-participant override is left as a seam - a source id may be named per role today, and
 * naming one per participant is an added map, not a changed model. Reported rather than decided.
 * <p>
 * A role says what the data <em>is</em>. Whether more of it is better travels on the series as its
 * {@link SeriesSense}, because the same role carries both directions in practice: a cloud-cover forecast is better
 * when it is low for a photovoltaic plant and better when it is high for a cooling load, and a derived demand is
 * neither.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum ForecastRole {

    /**
     * Expected photovoltaic production, positive while producing, as
     * {@link org.openhab.core.energy.SignConvention the one site convention} requires.
     * <p>
     * An irradiance series is accepted under the same role, because the corpus uses a solar forecast "as a proxy,
     * useful even without photovoltaics on site" - a site with no array still has passive solar gain, and the
     * quantity it needs is the same shape.
     */
    SOLAR_PRODUCTION("solar-production", SeriesSense.HIGHER_IS_BETTER),

    /**
     * Expected outdoor temperature - the input the corpus's proven derived-demand case is built on.
     */
    TEMPERATURE("temperature", SeriesSense.LOWER_IS_BETTER),

    /**
     * Expected cloud cover, as a fraction or a percentage of the sky.
     */
    CLOUD_COVER("cloud-cover", SeriesSense.LOWER_IS_BETTER),

    /**
     * Expected wind speed.
     */
    WIND("wind", SeriesSense.HIGHER_IS_BETTER),

    /**
     * Expected heating energy need per period, in energy units - the series _Derived-demand forecasts_ asks for, and
     * the one series in this enum that is normally computed rather than fetched.
     */
    HEATING_DEMAND("heating-demand", SeriesSense.LOWER_IS_BETTER);

    private final String id;
    private final SeriesSense defaultSense;

    ForecastRole(String id, SeriesSense defaultSense) {
        this.id = id;
        this.defaultSense = defaultSense;
    }

    /**
     * Returns the stable configuration id of this role.
     *
     * @return the role id, as it is written in configuration and in log lines
     */
    public String id() {
        return id;
    }

    /**
     * Returns the sense a series of this role takes when its source states none.
     * <p>
     * It is a fallback rather than a fact: a source that knows better says so on the series. The fallbacks here are
     * the ones the corpus's own scenarios need - most production is better, less consumption is better - and nothing
     * in the framework reads this for a series whose source stated a sense.
     *
     * @return the default sense
     */
    public SeriesSense defaultSense() {
        return defaultSense;
    }

    /**
     * Tells whether the given unit is one this role is normally expressed in.
     * <p>
     * This is a <em>check</em>, not a conversion: a source publishing watts under {@link #TEMPERATURE} is a
     * configuration error worth reporting, and no arithmetic in this plane silently reinterprets a unit.
     *
     * @param unit the unit a source published
     * @return {@code true} if the unit is compatible with the quantity this role carries
     */
    public boolean accepts(Unit<?> unit) {
        return switch (this) {
            case SOLAR_PRODUCTION -> unit.isCompatible(Units.WATT) || unit.isCompatible(Units.KILOWATT_HOUR)
                    || unit.isCompatible(Units.IRRADIANCE);
            case TEMPERATURE -> unit.isCompatible(SIUnits.CELSIUS);
            case CLOUD_COVER -> unit.isCompatible(Units.PERCENT) || unit.isCompatible(Units.ONE);
            case WIND -> unit.isCompatible(Units.METRE_PER_SECOND);
            case HEATING_DEMAND -> unit.isCompatible(Units.KILOWATT_HOUR);
        };
    }

    /**
     * Returns the unit a series of this role is read in when the source that published it carried none.
     * <p>
     * It is a reading convention rather than a claim about the quantity: a stored plain number has to be taken as
     * being in <em>some</em> unit, and taking it as being in the role's own canonical one is the only reading that
     * does not silently rescale a source's numbers.
     *
     * @return the canonical unit of this role
     */
    public Unit<?> canonicalUnit() {
        return switch (this) {
            case SOLAR_PRODUCTION -> Units.WATT;
            case TEMPERATURE -> SIUnits.CELSIUS;
            case CLOUD_COVER -> Units.PERCENT;
            case WIND -> Units.METRE_PER_SECOND;
            case HEATING_DEMAND -> Units.KILOWATT_HOUR;
        };
    }

    /**
     * Resolves a role from its configuration id.
     *
     * @param text the id, case-insensitive, surrounding whitespace ignored
     * @return the role, or empty if no role carries that id
     */
    public static Optional<ForecastRole> fromId(String text) {
        String wanted = text.trim().toLowerCase(Locale.ROOT);
        for (ForecastRole role : values()) {
            if (role.id.equals(wanted)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
