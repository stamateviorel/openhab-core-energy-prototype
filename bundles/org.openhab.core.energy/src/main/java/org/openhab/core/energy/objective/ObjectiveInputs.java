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

import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.price.EnergyPriceSeries;
import org.openhab.core.energy.window.SlotSeries;

/**
 * Everything an objective is allowed to rank on: the series resolved for this planning run, and the export share the
 * carbon objective's credit term turns on.
 * <p>
 * It is the planning-side twin of the engine's cycle snapshot, and it exists for the same reason: two objectives
 * compared in one run have to be compared against the same data rather than against whatever each of them happened
 * to fetch. Nothing here is fetched lazily and nothing here can change after construction, so an objective is a pure
 * function of this object.
 * <p>
 * The three market and forecast series arrive as plain {@link SlotSeries} - numbers, geometry and sense - because
 * that is the whole of what a ranking function needs from them, and it keeps this record independent of how the
 * price and forecast planes choose to type their own series. Carbon arrives as a {@link CarbonSeries} because the
 * export-credit rule needs to know whether it is an intensity or a share, which is a question about the unit.
 *
 * @param consumptionPrice what a kilowatt-hour costs per slot, or {@code null} when nothing publishes it
 * @param feedInPrice what a kilowatt-hour earns per slot, or {@code null} when nothing publishes it
 * @param carbon the carbon content per slot, or {@code null} when nothing publishes it
 * @param surplusForecast the expected surplus in watts per slot, or {@code null} when nothing forecasts it
 * @param exportShare how much of a load's energy would otherwise have been exported, per slot
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record ObjectiveInputs(@Nullable SlotSeries consumptionPrice, @Nullable SlotSeries feedInPrice,
        @Nullable CarbonSeries carbon, @Nullable SlotSeries surplusForecast, ExportShare exportShare) {

    /**
     * Returns inputs with nothing in them - what an installation that has installed no source has.
     *
     * @return the empty inputs
     */
    public static ObjectiveInputs empty() {
        return new ObjectiveInputs(null, null, null, null, ExportShare.unknown());
    }

    /**
     * Returns inputs carrying a consumption price alone, which is what a site with a price feed and nothing else has.
     *
     * @param prices the consumption price series
     * @return the inputs
     */
    public static ObjectiveInputs ofPrices(SlotSeries prices) {
        return new ObjectiveInputs(prices, null, null, null, ExportShare.unknown());
    }

    /**
     * Returns inputs carrying the price plane's own answers.
     * <p>
     * This is where the two planes meet, and it is deliberately one line: the price plane composes, converts and
     * aligns its components and answers with a series; an objective ranks numbers and does not care how they were
     * arrived at. Only the numbers and their sense cross the boundary, which is why an objective needs no dependency
     * on currencies, tariff calendars or market zones.
     *
     * @param consumption the effective consumption price series
     * @param feedIn the effective feed-in price series, or {@code null} when the site has none
     * @return the inputs
     */
    public static ObjectiveInputs fromPrices(EnergyPriceSeries consumption, @Nullable EnergyPriceSeries feedIn) {
        return new ObjectiveInputs(consumption.values(), feedIn == null ? null : feedIn.values(), null, null,
                ExportShare.unknown());
    }

    /**
     * Returns whether one input is available.
     *
     * @param input the input
     * @return {@code true} if a series is resolved for it
     */
    public boolean has(ObjectiveInput input) {
        return switch (input) {
            case CONSUMPTION_PRICE -> consumptionPrice != null;
            case FEED_IN_PRICE -> feedInPrice != null;
            case CARBON -> carbon != null;
            case SURPLUS_FORECAST -> surplusForecast != null;
        };
    }

    /**
     * Returns the consumption price series.
     *
     * @return the series, or empty when none is available
     */
    public Optional<SlotSeries> consumptionPriceSeries() {
        return Optional.ofNullable(consumptionPrice);
    }

    /**
     * Returns the feed-in price series.
     *
     * @return the series, or empty when none is available
     */
    public Optional<SlotSeries> feedInPriceSeries() {
        return Optional.ofNullable(feedInPrice);
    }

    /**
     * Returns the carbon series.
     *
     * @return the series, or empty when none is available
     */
    public Optional<CarbonSeries> carbonSeries() {
        return Optional.ofNullable(carbon);
    }

    /**
     * Returns the forecast surplus series.
     *
     * @return the series, or empty when none is available
     */
    public Optional<SlotSeries> surplusForecastSeries() {
        return Optional.ofNullable(surplusForecast);
    }

    /**
     * Returns these inputs with a consumption price series added.
     *
     * @param prices the series
     * @return the extended inputs
     */
    public ObjectiveInputs withConsumptionPrice(SlotSeries prices) {
        return new ObjectiveInputs(prices, feedInPrice, carbon, surplusForecast, exportShare);
    }

    /**
     * Returns these inputs with a feed-in price series added.
     *
     * @param prices the series
     * @return the extended inputs
     */
    public ObjectiveInputs withFeedInPrice(SlotSeries prices) {
        return new ObjectiveInputs(consumptionPrice, prices, carbon, surplusForecast, exportShare);
    }

    /**
     * Returns these inputs with a carbon series added.
     *
     * @param series the series
     * @return the extended inputs
     */
    public ObjectiveInputs withCarbon(CarbonSeries series) {
        return new ObjectiveInputs(consumptionPrice, feedInPrice, series, surplusForecast, exportShare);
    }

    /**
     * Returns these inputs with a forecast surplus series added.
     *
     * @param watts the series, in watts
     * @return the extended inputs
     */
    public ObjectiveInputs withSurplusForecast(SlotSeries watts) {
        return new ObjectiveInputs(consumptionPrice, feedInPrice, carbon, watts, exportShare);
    }

    /**
     * Returns these inputs with a different export share.
     *
     * @param share the export share
     * @return the inputs carrying that share
     */
    public ObjectiveInputs withExportShare(ExportShare share) {
        return new ObjectiveInputs(consumptionPrice, feedInPrice, carbon, surplusForecast, share);
    }

    /**
     * Returns these inputs with the export share derived from the forecast surplus for one load.
     * <p>
     * This is the ranking-time evaluation point of the export-credit rule, and it is available only to a site that
     * forecasts its surplus. Without a forecast the share stays unknown and the rule is inert over a plan, which the
     * objective reports rather than hides.
     *
     * @param loadWatts the load's own draw in watts
     * @return the inputs carrying the derived share, unchanged when nothing forecasts the surplus
     */
    public ObjectiveInputs withExportShareFor(double loadWatts) {
        SlotSeries forecast = surplusForecast;
        return forecast == null ? this : withExportShare(new ExportShare.FromSurplusForecast(forecast, loadWatts));
    }
}
