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

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.EnergyProvider;
import org.openhab.core.energy.ParticipantState;
import org.openhab.core.energy.ProviderRole;
import org.openhab.core.energy.objective.CarbonSeries;
import org.openhab.core.energy.objective.CarbonSeriesSource;
import org.openhab.core.energy.objective.ExportShare;
import org.openhab.core.energy.objective.ObjectiveInputs;
import org.openhab.core.energy.window.SeriesSense;
import org.openhab.core.energy.window.SlotSeries;

/**
 * The synthetic day the objective scenarios are argued on, and the small builders they share.
 * <p>
 * One day, four series, all on the same hourly geometry so that a placement difference between two objectives is a
 * statement about the objectives rather than about their slot widths:
 * <ul>
 * <li>the grid is cheapest at night and dearest in the afternoon;</li>
 * <li>the sun, and with it the site's surplus, is there in the afternoon and nowhere else;</li>
 * <li>the grid is dirtiest at night - a wind-poor, coal-heavy night - and cleanest in the afternoon;</li>
 * <li>the feed-in price goes negative in the afternoon, when everybody's array is exporting at once.</li>
 * </ul>
 * That shape is the one the corpus's own scenarios describe: a renewable afternoon that is "slightly pricier than the
 * night", and a negative feed-in hour that the export-credit rule turns on.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class ObjectiveFixtures {

    /**
     * Midnight UTC of the synthetic day.
     */
    public static final Instant DAY = Instant.parse("2026-06-01T00:00:00Z");

    private ObjectiveFixtures() {
    }

    /**
     * Returns the consumption price of the synthetic day, in cents per kilowatt-hour: cheap at night, dear in the
     * afternoon, with the afternoon only slightly dearer than the night.
     *
     * @return the price series, six four-hour slots
     */
    public static SlotSeries consumptionPrices() {
        return SlotSeries.uniform(DAY, java.time.Duration.ofHours(4), 8, 7, 12, 14, 9, 20);
    }

    /**
     * Returns the feed-in price of the synthetic day, in cents per kilowatt-hour, negative through the sunny
     * afternoon when everybody's array is exporting at once.
     *
     * @return the feed-in series, six four-hour slots
     */
    public static SlotSeries feedInPrices() {
        return SlotSeries.uniform(DAY, java.time.Duration.ofHours(4), 3, 3, -2, -2, 1, 4);
    }

    /**
     * Returns the forecast surplus of the synthetic day, in watts: nothing at night, a strong afternoon.
     *
     * @return the surplus series, six four-hour slots, higher being better
     */
    public static SlotSeries surplusForecast() {
        return SlotSeries.uniform(DAY, java.time.Duration.ofHours(4), 0, 0, 4000, 3000, 0, 0)
                .withSense(SeriesSense.HIGHER_IS_BETTER);
    }

    /**
     * Returns the grid carbon intensity of the synthetic day, in grams per kilowatt-hour: a coal-heavy night, a
     * renewable afternoon.
     *
     * @return the carbon series
     */
    public static CarbonSeries carbonIntensity() {
        return CarbonSeries.intensity("test-carbon", DAY,
                SlotSeries.uniform(DAY, java.time.Duration.ofHours(4), 500, 480, 120, 140, 300, 450));
    }

    /**
     * Returns the same day expressed as a renewable share in percent, which is the other form the requirement
     * accepts and the one the export-credit rule cannot be applied to.
     *
     * @return the carbon series, higher being better
     */
    public static CarbonSeries renewableShare() {
        return CarbonSeries.renewableShare("test-share", DAY,
                SlotSeries.uniform(DAY, java.time.Duration.ofHours(4), 10, 15, 80, 75, 40, 20));
    }

    /**
     * Returns the inputs a fully equipped site has: prices, feed-in prices, carbon and a surplus forecast.
     *
     * @return the inputs, with the export share still unknown
     */
    public static ObjectiveInputs fullyEquippedSite() {
        return new ObjectiveInputs(consumptionPrices(), feedInPrices(), carbonIntensity(), surplusForecast(),
                ExportShare.unknown());
    }

    /**
     * Returns an objective plane with the three built-ins registered, configured as given.
     *
     * @param configuration the component configuration
     * @return the plane
     */
    public static ObjectivePlane planeWithBuiltIns(Map<String, Object> configuration) {
        ObjectivePlane plane = new ObjectivePlane(configuration);
        plane.registerObjective(new CostObjective());
        plane.registerObjective(new SelfConsumptionObjective());
        plane.registerObjective(new CarbonObjective(configuration));
        return plane;
    }

    /**
     * Returns a cycle snapshot of a site with the given grid reading and battery charge.
     *
     * @param gridWatts the grid reading, positive being export
     * @param batteryWatts the battery reading, positive being charging
     * @param batteryPriority the battery's priority on the shared scale
     * @return the snapshot
     */
    public static EnergyContext siteWith(double gridWatts, double batteryWatts, int batteryPriority) {
        EnergyProvider battery = EnergyProvider.of("battery", "Battery_Power", ProviderRole.BATTERY)
                .withPriority(batteryPriority);
        return EnergyContext.builder(DAY, EnergyLevel.NORMAL).gridWatts(gridWatts).batteryWatts(batteryWatts)
                .participant(ParticipantState.of(battery).withMeasuredWatts(batteryWatts)).build();
    }

    /**
     * A carbon source a test can hand a series to, standing in for a contributed add-on.
     *
     * @author Stamate Viorel - Initial contribution
     */
    public static final class TestCarbonSource implements CarbonSeriesSource {

        private final String sourceId;
        private final int ranking;
        private @Nullable CarbonSeries series;

        /**
         * Creates a source that publishes nothing yet.
         *
         * @param sourceId the source id
         * @param ranking the service ranking it registers at
         */
        public TestCarbonSource(String sourceId, int ranking) {
            this.sourceId = sourceId;
            this.ranking = ranking;
        }

        /**
         * Publishes a series.
         *
         * @param published the series, or {@code null} to go quiet
         * @return this source
         */
        public TestCarbonSource publishing(@Nullable CarbonSeries published) {
            series = published;
            return this;
        }

        @Override
        public String getSourceId() {
            return sourceId;
        }

        @Override
        public Optional<CarbonSeries> getSeries() {
            return Optional.ofNullable(series);
        }

        @Override
        public int getServiceRanking() {
            return ranking;
        }
    }
}
