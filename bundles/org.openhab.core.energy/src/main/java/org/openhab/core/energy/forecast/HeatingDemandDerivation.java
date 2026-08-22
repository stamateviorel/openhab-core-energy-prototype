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
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.window.SeriesSense;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;

/**
 * Heating need derived from an outdoor-temperature forecast, optionally reduced by passive solar gain and optionally
 * pulled forward ahead of a steep drop - _Derived-demand forecasts_ as a pure, testable function, which is what the
 * change's own prototype task 3.2 asks for.
 * <p>
 * <strong>What it computes.</strong> The corpus states the relation and not its constants: heating need is
 * "approximately linear" in outdoor temperature, passive solar gain offsets it and is "useful even without
 * photovoltaics on site", and demand rises ahead of a steep drop so the engine can pre-heat in the cheaper, warmer
 * hours. So, per slot, with everything the site declared and nothing it did not:
 * <ol>
 * <li>{@code need = heatLoss × max(0, baseTemperature − outdoorTemperature) × slotHours}, in kilowatt-hours - the
 * degree-hours of the slot against the building's own coefficient;</li>
 * <li>{@code need −= solarGainFactor × solarEnergyInSlot}, floored at zero, where a solar series is available. The
 * solar series may be a photovoltaic production forecast or an irradiance one: the factor carries whatever aperture
 * and efficiency turn the one into the other, which is what makes the proxy usable on a site with no array;</li>
 * <li>where the coldest slot inside the pre-heating horizon is at least the declared drop colder, a share of the
 * extra need it brings is pulled into the current slot, added or moved according to {@link PreheatModel}.</li>
 * </ol>
 * <p>
 * <strong>The result is in energy, per period.</strong> _Derived-demand forecasts_ is explicit that a demand series is
 * "in energy units (kWh per period, not a count of hours)", and the whole series is positive, meaning consumption -
 * the single sign convention, with the demand and the photovoltaic forecast both positive and meaning opposite
 * things, which is _A demand forecast is not sign-ambiguous_.
 * <p>
 * <strong>Two geometries, one rule.</strong> The temperature series decides the output's slots. A solar series with
 * different boundaries is read by holding each of its values from its own timestamp - the same left-hold every other
 * calculation in the framework uses - so nothing is interpolated and neither series has to be resampled onto the
 * other. Aligning by refining onto the union of both boundary sets is the alternative, and it is the same open
 * question the price plane meets when it composes two components of different geometry.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class HeatingDemandDerivation {

    private HeatingDemandDerivation() {
    }

    /**
     * Derives a heating-demand series.
     *
     * @param temperature the outdoor-temperature forecast
     * @param solar the solar forecast standing in for passive gain, or {@code null} when there is none
     * @param parameters what the site declared about its building
     * @param sourceId the id the derived series is published under
     * @return the derived series, or the reasons there is none
     */
    public static DerivedDemand derive(ForecastSeries temperature, @Nullable ForecastSeries solar,
            HeatingDemandParameters parameters, String sourceId) {
        Set<ForecastPlaneCondition> conditions = EnumSet.noneOf(ForecastPlaneCondition.class);
        if (temperature.role() != ForecastRole.TEMPERATURE || !temperature.unit().isCompatible(SIUnits.CELSIUS)) {
            conditions.add(ForecastPlaneCondition.UNIT_MISMATCH);
            return DerivedDemand.none(conditions);
        }
        if (!parameters.isDerivationConfigured()) {
            conditions.add(ForecastPlaneCondition.DEMAND_DERIVATION_UNCONFIGURED);
            return DerivedDemand.none(conditions);
        }
        double heatLoss = Objects.requireNonNull(parameters.heatLossKilowattPerKelvin());
        double base = Objects.requireNonNull(parameters.baseTemperatureCelsius());

        List<Double> celsius = new ArrayList<>(temperature.size());
        double[] need = new double[temperature.size()];
        for (int i = 0; i < temperature.size(); i++) {
            Slot slot = temperature.slotAt(i);
            double outdoor = inCelsius(slot.value(), temperature.unit());
            celsius.add(outdoor);
            need[i] = heatLoss * Math.max(0, base - outdoor) * hours(slot.duration());
        }

        applySolarGain(temperature, solar, parameters, need, conditions);
        applyPreheat(temperature, parameters, celsius, need, conditions);

        List<Slot> slots = new ArrayList<>(need.length);
        for (int i = 0; i < need.length; i++) {
            Slot slot = temperature.slotAt(i);
            slots.add(new Slot(slot.start(), slot.end(), Math.max(0, need[i])));
        }
        Instant generatedAt = solar == null ? temperature.generatedAt()
                : latest(temperature.generatedAt(), solar.generatedAt());
        ForecastSeries derived = new ForecastSeries(ForecastRole.HEATING_DEMAND, sourceId, Units.KILOWATT_HOUR,
                generatedAt, new SlotSeries(slots, SeriesSense.LOWER_IS_BETTER));
        return new DerivedDemand(Optional.of(derived), conditions);
    }

    private static void applySolarGain(ForecastSeries temperature, @Nullable ForecastSeries solar,
            HeatingDemandParameters parameters, double[] need, Set<ForecastPlaneCondition> conditions) {
        if (solar == null) {
            return;
        }
        if (!parameters.isSolarGainConfigured()) {
            conditions.add(ForecastPlaneCondition.SOLAR_GAIN_UNCONFIGURED);
            return;
        }
        if (!solar.unit().isCompatible(Units.WATT) && !solar.unit().isCompatible(Units.KILOWATT_HOUR)
                && !solar.unit().isCompatible(Units.IRRADIANCE)) {
            conditions.add(ForecastPlaneCondition.UNIT_MISMATCH);
            return;
        }
        double factor = Objects.requireNonNull(parameters.solarGainFactor());
        for (int i = 0; i < need.length; i++) {
            Slot slot = temperature.slotAt(i);
            double gain = factor * solarEnergy(solar, slot.start(), slot.duration());
            need[i] = Math.max(0, need[i] - gain);
        }
    }

    private static void applyPreheat(ForecastSeries temperature, HeatingDemandParameters parameters,
            List<Double> celsius, double[] need, Set<ForecastPlaneCondition> conditions) {
        if (!parameters.isPreheatConfigured()) {
            conditions.add(ForecastPlaneCondition.PREHEAT_UNCONFIGURED);
            return;
        }
        Duration horizon = Objects.requireNonNull(parameters.preheatHorizon());
        double drop = Objects.requireNonNull(parameters.preheatDropKelvin());
        double share = Objects.requireNonNull(parameters.preheatShare());
        PreheatModel model = Objects.requireNonNull(parameters.preheatModel());

        double[] pulled = new double[need.length];
        for (int i = 0; i < need.length; i++) {
            Instant limit = temperature.slotAt(i).start().plus(horizon);
            int coldest = -1;
            for (int j = i + 1; j < need.length && temperature.slotAt(j).start().isBefore(limit); j++) {
                if (coldest < 0 || celsius.get(j) < celsius.get(coldest)) {
                    coldest = j;
                }
            }
            if (coldest < 0 || celsius.get(i) - celsius.get(coldest) < drop) {
                continue;
            }
            double extra = share * Math.max(0, need[coldest] - need[i]);
            if (extra <= 0) {
                continue;
            }
            pulled[i] += extra;
            if (model == PreheatModel.REDISTRIBUTED) {
                pulled[coldest] -= extra;
            }
        }
        for (int i = 0; i < need.length; i++) {
            need[i] = Math.max(0, need[i] + pulled[i]);
        }
    }

    /**
     * Returns how much energy a solar series carries over an interval of another series' geometry.
     *
     * @param solar the solar series
     * @param from the start of the interval
     * @param width how long the interval lasts
     * @return the energy in kilowatt-hours, or zero where the solar series does not cover the interval's start
     */
    private static double solarEnergy(ForecastSeries solar, Instant from, Duration width) {
        OptionalInt covering = solar.values().indexAt(from);
        if (covering.isEmpty()) {
            return 0;
        }
        int index = covering.getAsInt();
        Slot slot = solar.slotAt(index);
        double perHour;
        if (solar.unit().isCompatible(Units.KILOWATT_HOUR)) {
            perHour = solar.energyKilowattHoursAt(index) / hours(slot.duration());
        } else if (solar.unit().isCompatible(Units.WATT)) {
            perHour = solar.energyKilowattHoursAt(index) / hours(slot.duration());
        } else {
            // an irradiance series stands in for production; the factor carries the aperture and the efficiency
            perHour = slot.value() / 1000;
        }
        return perHour * hours(width);
    }

    private static double inCelsius(double value, Unit<?> unit) {
        if (SIUnits.CELSIUS.equals(unit)) {
            return value;
        }
        @Nullable
        QuantityType<?> converted = new QuantityType<>(value, unit).toUnit(SIUnits.CELSIUS);
        if (converted == null) {
            throw new IllegalArgumentException("a value in " + unit + " is not a temperature");
        }
        return converted.doubleValue();
    }

    private static Instant latest(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private static double hours(Duration duration) {
        return duration.toNanos() / (double) Duration.ofHours(1).toNanos();
    }
}
