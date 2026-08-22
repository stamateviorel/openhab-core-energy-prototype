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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.objective.ObjectiveCondition;
import org.openhab.core.energy.objective.ObjectiveInput;
import org.openhab.core.energy.objective.ObjectiveInputs;
import org.openhab.core.energy.objective.ObjectiveResolution;
import org.openhab.core.energy.objective.OptimizationObjective;
import org.openhab.core.energy.price.EnergyPriceSeries;
import org.openhab.core.energy.price.PriceDirection;
import org.openhab.core.energy.window.SlotSelection;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.energy.window.WindowRequest;
import org.openhab.core.energy.window.internal.RankedSlotsSelection;
import org.openhab.core.library.unit.CurrencyUnits;
import org.openhab.core.library.unit.Units;

/**
 * The <em>Selectable objective</em> requirement, one test per scenario.
 * <p>
 * All four scenarios are about <strong>placement</strong>, and they are asserted as placement: the same deferrable
 * load, the same day, the same selection strategy, and only the objective changed. That is the point of the
 * requirement - the metric is a user choice - and it is also what makes the test honest, because an assertion on a
 * ranking alone would pass for an objective that ranks correctly and places nowhere.
 * <p>
 * The two battery scenarios are answered at the other evaluation point, on the cycle snapshot, because that is where
 * the figure they turn on exists. Both were already correct in the framework before this capability existed; what is
 * new is that an objective is the thing that asks for them.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class SelectableObjectiveTest {

    private final RankedSlotsSelection best = new RankedSlotsSelection(false);

    /**
     * Scenario "Self-consumption over cheap grid": with a live photovoltaic surplus during a cheap grid hour, a
     * deferrable load goes into the surplus rather than into the cheap hour.
     * <p>
     * The two objectives disagree on this day by construction - the night is the cheapest grid and the afternoon is
     * the only surplus - so the assertion is that the choice of objective is what decides, not the data.
     */
    @Test
    public void theSelfConsumptionObjectivePlacesTheLoadInTheSurplusRatherThanInTheCheapHour() {
        ObjectiveInputs inputs = ObjectiveFixtures.fullyEquippedSite();

        SlotSeries byCost = new CostObjective().rank(inputs).orElseThrow();
        SlotSeries bySelfConsumption = new SelfConsumptionObjective().rank(inputs).orElseThrow();

        List<Integer> cheapHours = best.select(byCost, 1, SlotSelection.empty()).indices();
        List<Integer> surplusHours = best.select(bySelfConsumption, 1, SlotSelection.empty()).indices();

        assertThat("the cheap grid hour is the night", cheapHours, contains(1));
        assertThat("the surplus is the afternoon", surplusHours, contains(2));
        assertThat("and the two objectives therefore place the same load in different hours", surplusHours,
                is(not(cheapHours)));
    }

    /**
     * Scenario "Battery charging is reclaimable surplus": nothing exported, 3 kW going into the battery, and a load
     * that outranks the battery is placed now against that charge rather than waiting for export to appear.
     */
    @Test
    public void aChargingBatteryIsSurplusToAConsumerThatOutranksIt() {
        EnergyContext site = ObjectiveFixtures.siteWith(0, 3000, 100);

        SelfConsumptionObjective objective = new SelfConsumptionObjective();

        assertThat("the load outranks the battery, so the charge is available to it",
                objective.availableWattsFor(site, 40).getAsDouble(), is(3000.0));
        assertThat("a load the battery outranks gets nothing", objective.availableWattsFor(site, 140).getAsDouble(),
                is(0.0));
    }

    /**
     * Scenario "Reclaimable charge under an importing meter": importing 1 kW while 3 kW charges the battery leaves
     * 2 kW for the load, because placing it must not deepen the import the objective exists to avoid.
     */
    @Test
    public void anImportIsNettedOffTheReclaimableChargeBeforeItIsOffered() {
        EnergyContext site = ObjectiveFixtures.siteWith(-1000, 3000, 100);

        assertThat(new SelfConsumptionObjective().availableWattsFor(site, 40).getAsDouble(), is(2000.0));
    }

    /**
     * Scenario "Carbon over price": a very renewable afternoon that is slightly pricier than the night takes the
     * load under the carbon objective.
     */
    @Test
    public void theCarbonObjectivePlacesTheLoadInTheGreenerAfternoon() {
        ObjectiveInputs inputs = ObjectiveFixtures.fullyEquippedSite();

        SlotSeries byCarbon = new CarbonObjective().rank(inputs).orElseThrow();
        SlotSeries byCost = new CostObjective().rank(inputs).orElseThrow();

        List<Integer> greenest = best.select(byCarbon, 1, SlotSelection.empty()).indices();

        assertThat("the greenest hours are the renewable afternoon", greenest, contains(2));
        assertThat("which is dearer than the cheapest hour, so the choice really is the objective's", byCost.valueAt(2),
                greaterThan(byCost.valueAt(1)));
    }

    /**
     * The two planes meet on numbers and a sense, and on nothing else: the price plane's own answer becomes an
     * objective's input in one call, and the objective needs to know nothing about currencies, tariff calendars or
     * market zones to rank it.
     */
    @Test
    public void thePricePlanesOwnAnswerIsAnObjectiveInput() {
        EnergyPriceSeries consumption = EnergyPriceSeries.of(ObjectiveFixtures.DAY, java.time.Duration.ofHours(4),
                CurrencyUnits.BASE_CURRENCY, Units.KILOWATT_HOUR, ZoneId.of("CET"), PriceDirection.CONSUMPTION, 8, 7,
                12, 14, 9, 20);
        EnergyPriceSeries feedIn = EnergyPriceSeries.of(ObjectiveFixtures.DAY, java.time.Duration.ofHours(4),
                CurrencyUnits.BASE_CURRENCY, Units.KILOWATT_HOUR, ZoneId.of("CET"), PriceDirection.FEED_IN, 3, 3, -2,
                -2, 1, 4);

        ObjectiveInputs inputs = ObjectiveInputs.fromPrices(consumption, feedIn);

        assertThat(inputs.has(ObjectiveInput.CONSUMPTION_PRICE), is(true));
        assertThat(inputs.has(ObjectiveInput.FEED_IN_PRICE), is(true));
        assertThat("and the cost objective ranks it as it ranks any other price series",
                best.select(new CostObjective().rank(inputs).orElseThrow(), 1, SlotSelection.empty()).indices(),
                contains(1));
    }

    /**
     * The requirement's own words are that the objective is <em>selected</em>, so the selection has to be what
     * decides - not which objective happens to be installed.
     */
    @Test
    public void theConfiguredObjectiveIsTheOneThatRanks() {
        ObjectiveInputs inputs = ObjectiveFixtures.fullyEquippedSite();

        ObjectivePlane onCost = ObjectiveFixtures.planeWithBuiltIns(Map.of("objective", CostObjective.ID));
        ObjectivePlane onCarbon = ObjectiveFixtures.planeWithBuiltIns(Map.of("objective", CarbonObjective.ID));

        assertThat(onCost.resolve(inputs).effectiveId().orElseThrow(), is(CostObjective.ID));
        assertThat(onCarbon.resolve(inputs).effectiveId().orElseThrow(), is(CarbonObjective.ID));
        assertThat("and each ranking really is its own objective's",
                best.select(onCarbon.resolve(inputs).ranking().orElseThrow(), 1, SlotSelection.empty()).indices(),
                is(not(best.select(onCost.resolve(inputs).ranking().orElseThrow(), 1, SlotSelection.empty())
                        .indices())));
    }

    /**
     * A site that selects nothing behaves exactly as the framework did before the objective became selectable, and
     * is told so rather than left to assume it.
     */
    @Test
    public void anUnconfiguredSiteOptimizesForCostAndIsToldThatItDoes() {
        ObjectiveResolution resolution = ObjectiveFixtures.planeWithBuiltIns(Map.of())
                .resolve(ObjectiveFixtures.fullyEquippedSite());

        assertThat(resolution.effectiveId().orElseThrow(), is(CostObjective.ID));
        assertThat(resolution.reports(ObjectiveCondition.OBJECTIVE_UNCONFIGURED), is(true));
        assertThat("which is a note, not a degradation", resolution.reports(ObjectiveCondition.DEGRADED_TO_COST),
                is(false));
    }

    /**
     * The same window request, asked of two objectives, comes back in the same shape - which is what "the ranking
     * uses that series exactly as the cost objective uses the price series" has to mean for a caller.
     */
    @Test
    public void aWindowRequestTakesTheSameShapeUnderEveryObjective() {
        ObjectiveInputs inputs = ObjectiveFixtures.fullyEquippedSite();
        WindowRequest request = WindowRequest.ofSlots(2);

        for (OptimizationObjective objective : List.of(new CostObjective(), new SelfConsumptionObjective(),
                new CarbonObjective())) {
            SlotSeries ranking = objective.rank(inputs).orElseThrow();
            assertThat(objective.getId(), best.select(ranking, request).size(), is(2));
            assertThat(objective.getId(), best.select(ranking, request).isComplete(), is(true));
        }
    }
}
