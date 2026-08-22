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

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.ConfigParser;

/**
 * What a site has to say about its own building before a heating demand can be derived from a temperature forecast.
 * <p>
 * <strong>Not one of these has a shipped default, and that is the decided pattern rather than an omission.</strong>
 * The corpus decided thirty parameters and their defaults (D17), and not one of them is in this plane - Part B has no
 * row for prices, forecasts or objectives at all. The precedent for what to do about that is D22, which shipped the
 * surplus escalation with no number: the shape exists, it does nothing until the site supplies the numbers, and the
 * absence is <em>reported</em> rather than silently inert. A heat-loss coefficient invented here would be a building
 * physics claim about somebody else's house.
 *
 * @param heatLossKilowattPerKelvin how much heating power the building needs per kelvin of difference to the outside
 * @param baseTemperatureCelsius the outdoor temperature below which the building needs heat at all
 * @param solarGainFactor how much heating need one unit of the solar series displaces, in kilowatt-hours of need per
 *            kilowatt-hour the solar series carries; it absorbs both the aperture and the efficiency, which is what
 *            lets an irradiance series stand in for a photovoltaic one on a site with no array
 * @param preheatHorizon how far ahead a temperature drop is looked for
 * @param preheatDropKelvin how steep the drop has to be before demand is pulled forward
 * @param preheatShare how much of the coming extra need is pulled forward, as a fraction between 0 and 1
 * @param preheatModel whether pulled-forward energy is added to the total or moved within it
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record HeatingDemandParameters(@Nullable Double heatLossKilowattPerKelvin,
        @Nullable Double baseTemperatureCelsius, @Nullable Double solarGainFactor, @Nullable Duration preheatHorizon,
        @Nullable Double preheatDropKelvin, @Nullable Double preheatShare, @Nullable PreheatModel preheatModel) {

    /**
     * The heat-loss coefficient parameter, in kW/K.
     */
    public static final String CONFIG_HEAT_LOSS = "heatLoss";

    /**
     * The base-temperature parameter, in °C.
     */
    public static final String CONFIG_BASE_TEMPERATURE = "baseTemperature";

    /**
     * The solar-gain factor parameter.
     */
    public static final String CONFIG_SOLAR_GAIN = "solarGain";

    /**
     * The pre-heating horizon parameter, in hours.
     */
    public static final String CONFIG_PREHEAT_HORIZON = "preheatHorizon";

    /**
     * The pre-heating drop-threshold parameter, in kelvin.
     */
    public static final String CONFIG_PREHEAT_DROP = "preheatDrop";

    /**
     * The pre-heating share parameter, between 0 and 1.
     */
    public static final String CONFIG_PREHEAT_SHARE = "preheatShare";

    /**
     * The pre-heating model parameter.
     */
    public static final String CONFIG_PREHEAT_MODEL = "preheatModel";

    /**
     * Every parameter this record reads, so a configuration description and the code can be held to each other.
     */
    public static final Set<String> CONFIG_KEYS = Set.of(CONFIG_HEAT_LOSS, CONFIG_BASE_TEMPERATURE, CONFIG_SOLAR_GAIN,
            CONFIG_PREHEAT_HORIZON, CONFIG_PREHEAT_DROP, CONFIG_PREHEAT_SHARE, CONFIG_PREHEAT_MODEL);

    /**
     * Returns the parameters of a site that has declared nothing, which derive nothing and say so.
     *
     * @return the unconfigured parameters
     */
    public static HeatingDemandParameters unconfigured() {
        return new HeatingDemandParameters(null, null, null, null, null, null, null);
    }

    /**
     * Returns parameters carrying only what the linear heat-demand relation needs.
     *
     * @param heatLossKilowattPerKelvin the building's heat loss per kelvin
     * @param baseTemperatureCelsius the temperature below which it needs heat
     * @return the parameters, with no solar gain and no pre-heating
     */
    public static HeatingDemandParameters of(double heatLossKilowattPerKelvin, double baseTemperatureCelsius) {
        return new HeatingDemandParameters(heatLossKilowattPerKelvin, baseTemperatureCelsius, null, null, null, null,
                null);
    }

    /**
     * Returns these parameters with a solar-gain factor.
     *
     * @param factor how much heating need one kilowatt-hour of the solar series displaces
     * @return the parameters
     */
    public HeatingDemandParameters withSolarGain(double factor) {
        return new HeatingDemandParameters(heatLossKilowattPerKelvin, baseTemperatureCelsius, factor, preheatHorizon,
                preheatDropKelvin, preheatShare, preheatModel);
    }

    /**
     * Returns these parameters with pre-heating declared.
     *
     * @param horizon how far ahead a drop is looked for
     * @param dropKelvin how steep the drop has to be
     * @param share how much of the coming extra need is pulled forward
     * @param model whether the pulled-forward energy is added or moved
     * @return the parameters
     */
    public HeatingDemandParameters withPreheat(Duration horizon, double dropKelvin, double share, PreheatModel model) {
        return new HeatingDemandParameters(heatLossKilowattPerKelvin, baseTemperatureCelsius, solarGainFactor, horizon,
                dropKelvin, share, model);
    }

    /**
     * Tells whether the linear heat-demand relation can be evaluated at all.
     *
     * @return {@code true} if both the heat-loss coefficient and the base temperature are declared
     */
    public boolean isDerivationConfigured() {
        return heatLossKilowattPerKelvin != null && baseTemperatureCelsius != null;
    }

    /**
     * Tells whether a solar forecast may reduce the derived demand.
     *
     * @return {@code true} if a solar-gain factor is declared
     */
    public boolean isSolarGainConfigured() {
        return solarGainFactor != null;
    }

    /**
     * Tells whether demand may be pulled forward ahead of a drop.
     *
     * @return {@code true} if the horizon, the drop threshold, the share and the model are all declared
     */
    public boolean isPreheatConfigured() {
        return preheatHorizon != null && preheatDropKelvin != null && preheatShare != null && preheatModel != null;
    }

    /**
     * Reads the parameters out of OSGi component properties.
     * <p>
     * Nothing is rejected outright: an unreadable value is reported and left undeclared, so one typo cannot stop a
     * whole derivation from coming up.
     *
     * @param properties the component properties
     * @param rejected receives one description per value that could not be used
     * @return the parameters
     */
    public static HeatingDemandParameters fromProperties(Map<String, Object> properties, Consumer<String> rejected) {
        @Nullable
        Double heatLoss = positive(properties, CONFIG_HEAT_LOSS, rejected);
        @Nullable
        Double base = number(properties, CONFIG_BASE_TEMPERATURE, rejected);
        @Nullable
        Double solarGain = positive(properties, CONFIG_SOLAR_GAIN, rejected);
        @Nullable
        Double drop = positive(properties, CONFIG_PREHEAT_DROP, rejected);
        @Nullable
        Double share = fraction(properties, CONFIG_PREHEAT_SHARE, rejected);
        @Nullable
        Double horizonHours = positive(properties, CONFIG_PREHEAT_HORIZON, rejected);
        @Nullable
        Duration horizon = horizonHours == null ? null
                : Duration.ofSeconds(Math.round(horizonHours * Duration.ofHours(1).toSeconds()));
        @Nullable
        PreheatModel model = null;
        @Nullable
        Object declaredModel = properties.get(CONFIG_PREHEAT_MODEL);
        if (declaredModel != null && !String.valueOf(declaredModel).isBlank()) {
            model = PreheatModel.fromId(String.valueOf(declaredModel)).orElse(null);
            if (model == null) {
                rejected.accept("unknown pre-heating model '" + declaredModel + "'; not pre-heating at all");
            }
        }
        return new HeatingDemandParameters(heatLoss, base, solarGain, horizon, drop, share, model);
    }

    private static @Nullable Double number(Map<String, Object> properties, String key, Consumer<String> rejected) {
        @Nullable
        Object value = properties.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        @Nullable
        Double parsed = ConfigParser.valueAs(value, Double.class);
        if (parsed == null || !Double.isFinite(parsed)) {
            rejected.accept("'" + key + "' (" + value + ") is not a number; leaving it undeclared");
            return null;
        }
        return parsed;
    }

    private static @Nullable Double positive(Map<String, Object> properties, String key, Consumer<String> rejected) {
        @Nullable
        Double parsed = number(properties, key, rejected);
        if (parsed != null && parsed <= 0) {
            rejected.accept("'" + key + "' (" + parsed + ") must be positive; leaving it undeclared");
            return null;
        }
        return parsed;
    }

    private static @Nullable Double fraction(Map<String, Object> properties, String key, Consumer<String> rejected) {
        @Nullable
        Double parsed = number(properties, key, rejected);
        if (parsed != null && (parsed < 0 || parsed > 1)) {
            rejected.accept("'" + key + "' (" + parsed + ") must be between 0 and 1; leaving it undeclared");
            return null;
        }
        return parsed;
    }
}
