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

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.objective.CarbonSeries;
import org.openhab.core.energy.objective.ExportCarbonCredit;
import org.openhab.core.energy.objective.ExportShare;
import org.openhab.core.energy.objective.ObjectiveCondition;
import org.openhab.core.energy.objective.ObjectiveInputs;
import org.openhab.core.energy.window.SlotSeries;

/**
 * The <em>Carbon credit for exported energy</em> requirement - the one requirement in its corpus with no thread
 * source and no production system behind it, which is why this file asserts its <em>reversibility</em> as carefully
 * as it asserts its behaviour.
 * <p>
 * Four things are pinned:
 * <ol>
 * <li>the rule itself, on both sides of zero, which is the two scenarios;</li>
 * <li>that it alters nothing else - a slot whose feed-in price is zero or above scores exactly its own carbon value,
 * so the ranking of a day with no negative hour is untouched;</li>
 * <li>that the preserved alternative is shipped and selectable, so overturning the decision is a configuration
 * change;</li>
 * <li>that the rule is inert, and says it is inert, on a site that cannot answer how much of a load's energy would
 * have been exported - which is every site the corpus describes, because no requirement defines that quantity.</li>
 * </ol>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ExportCarbonCreditTest {

    private static final double LOAD_WATTS = 4000;

    /**
     * Scenario "Negative feed-in withdraws the credit": in an hour whose effective feed-in price is negative,
     * exporting earns no credit, so consuming locally wins on carbon as it already does on cost.
     * <p>
     * The assertion is on the score rather than only on the factor, because "wins" is a statement about the ranking:
     * the afternoon slots, where the feed-in price is negative and the surplus is real, score strictly better than
     * their own carbon value, and nothing else on the day moves.
     */
    @Test
    public void aNegativeFeedInPriceWithdrawsTheCreditAndLocalConsumptionWins() {
        ObjectiveInputs inputs = ObjectiveFixtures.fullyEquippedSite().withExportShareFor(LOAD_WATTS);
        CarbonSeries carbon = ObjectiveFixtures.carbonIntensity();

        SlotSeries scored = new CarbonObjective().rank(inputs).orElseThrow();

        assertThat("the feed-in price of the afternoon really is negative", ObjectiveFixtures.feedInPrices().valueAt(2),
                lessThan(0.0));
        assertThat("so running the load locally costs less carbon than the hour's own intensity says",
                scored.valueAt(2), lessThan(carbon.valueAt(2)));
        assertThat("and the same holds for the second negative hour", scored.valueAt(3), lessThan(carbon.valueAt(3)));
    }

    /**
     * Scenario "The rule bites only on negative prices": where the feed-in price is zero or above the credit applies
     * unchanged, so the score is the carbon series itself.
     */
    @Test
    public void aNonNegativeFeedInPriceLeavesTheCreditAndTheScoreUntouched() {
        ObjectiveInputs inputs = ObjectiveFixtures.fullyEquippedSite().withExportShareFor(LOAD_WATTS);
        CarbonSeries carbon = ObjectiveFixtures.carbonIntensity();

        SlotSeries scored = new CarbonObjective().rank(inputs).orElseThrow();

        for (int slot : new int[] { 0, 1, 4, 5 }) {
            assertThat("slot " + slot + " has a feed-in price of zero or above",
                    ObjectiveFixtures.feedInPrices().valueAt(slot), greaterThanOrEqualTo(0.0));
            assertThat("so its score is its own carbon value, unchanged", scored.valueAt(slot),
                    is(carbon.valueAt(slot)));
        }
    }

    /**
     * The rule itself, isolated: one comparison, one class, and this is the assertion a maintainer overturning the
     * decision would delete.
     */
    @Test
    public void theRuleIsOneComparisonAgainstZero() {
        ExportCarbonCredit rule = new NegativeFeedInCarbonCredit();

        assertThat(rule.creditFactorAt(-0.01), is(0.0));
        assertThat("zero is above the line, as the requirement words it", rule.creditFactorAt(0), is(1.0));
        assertThat(rule.creditFactorAt(4), is(1.0));
        assertThat(rule.getId(), is(NegativeFeedInCarbonCredit.ID));
    }

    /**
     * The preserved alternative is shipped, not merely documented: selecting it is one configuration value, and with
     * it the carbon objective ranks on the plain carbon series again.
     */
    @Test
    public void theAlternativeIsSelectableAndUndoesTheRuleEntirely() {
        Map<String, Object> crediting = Map.of("exportCarbonCredit", UnconditionalExportCredit.ID);
        ObjectiveInputs inputs = ObjectiveFixtures.fullyEquippedSite().withExportShareFor(LOAD_WATTS);

        CarbonObjective naive = new CarbonObjective(crediting);
        SlotSeries scored = naive.rank(inputs).orElseThrow();

        assertThat(naive.getExportCarbonCredit().getId(), is(UnconditionalExportCredit.ID));
        for (int slot = 0; slot < scored.size(); slot++) {
            assertThat("with the credit always applying, the score is the carbon series itself", scored.valueAt(slot),
                    is(ObjectiveFixtures.carbonIntensity().valueAt(slot)));
        }
    }

    /**
     * The share of the load's energy that would have been exported is what the rule scales by, and it comes from a
     * forecast surplus - the quantity <strong>no requirement in the corpus defines</strong>. A site without one gets
     * the rule inert, and is told so rather than left to believe it was applied.
     */
    @Test
    public void withoutAForecastSurplusTheRuleIsInertAndSaysSo() {
        ObjectiveInputs unknown = ObjectiveFixtures.fullyEquippedSite();

        CarbonObjective objective = new CarbonObjective();
        SlotSeries scored = objective.rank(unknown).orElseThrow();

        assertThat(unknown.exportShare().isDerivedFromData(), is(false));
        for (int slot = 0; slot < scored.size(); slot++) {
            assertThat(scored.valueAt(slot), is(ObjectiveFixtures.carbonIntensity().valueAt(slot)));
        }
        assertThat(objective.conditionsFor(unknown), contains(ObjectiveCondition.EXPORT_SHARE_UNKNOWN));
    }

    /**
     * The share is proportional, not binary: a load bigger than the forecast surplus is only partly fed from it, so
     * only that part of its energy loses a credit.
     */
    @Test
    public void theCreditIsWithdrawnInProportionToWhatWouldHaveBeenExported() {
        SlotSeries surplus = ObjectiveFixtures.surplusForecast();
        ExportShare half = new ExportShare.FromSurplusForecast(surplus, 8000);
        ExportShare whole = new ExportShare.FromSurplusForecast(surplus, 2000);

        assertThat("4 kW of surplus feeds half of an 8 kW load", half.exportedShareAt(2), is(0.5));
        assertThat("and all of a 2 kW one, never more than all of it", whole.exportedShareAt(2), is(1.0));
        assertThat("a night hour has no surplus to lose", whole.exportedShareAt(0), is(0.0));

        ObjectiveInputs inputs = ObjectiveFixtures.fullyEquippedSite();
        double partial = new CarbonObjective().rank(inputs.withExportShare(half)).orElseThrow().valueAt(2);
        double full = new CarbonObjective().rank(inputs.withExportShare(whole)).orElseThrow().valueAt(2);

        assertThat("the more of the load the surplus would have covered, the more the withdrawal bites", full,
                lessThan(partial));
        assertThat(partial, lessThan(ObjectiveFixtures.carbonIntensity().valueAt(2)));
    }

    /**
     * The rule's second evaluation point is dispatch time, on the live figures. It is implemented and reachable -
     * and it cannot be reached from the engine's own cycle snapshot, because that snapshot carries no price of any
     * kind. That is a gap in the snapshot rather than in the rule, and this test pins both halves of it.
     */
    @Test
    public void theRuleHasADispatchTimeEvaluationPointTheSnapshotCannotFeed() {
        CarbonObjective objective = new CarbonObjective();

        assertThat("the rule answers for right now, given a price", objective.dispatchCreditFactor(-1), is(0.0));
        assertThat(objective.dispatchCreditFactor(2), is(1.0));

        assertThat("but nothing on the cycle snapshot carries a feed-in price to give it",
                java.util.Arrays.stream(org.openhab.core.energy.EnergyContext.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList(),
                not(hasItem(containsString("rice"))));

        ExportShare live = new ExportShare.Live(3000, 4000);
        assertThat("the live share is computable the moment something does supply one", live.exportedShareAt(0),
                is(0.75));
        assertThat(live.isDerivedFromData(), is(true));
    }

    /**
     * A renewable share ranks perfectly well and cannot carry the credit term, because the term scales a value by a
     * fraction and a share is not a scale on which that means anything. The term is dropped and the condition is
     * reported; the ranking is untouched.
     */
    @Test
    public void onAShareSeriesTheCreditTermIsDroppedAndReported() {
        ObjectiveInputs inputs = ObjectiveFixtures.fullyEquippedSite().withCarbon(ObjectiveFixtures.renewableShare())
                .withExportShareFor(LOAD_WATTS);

        CarbonObjective objective = new CarbonObjective();
        SlotSeries scored = objective.rank(inputs).orElseThrow();

        for (int slot = 0; slot < scored.size(); slot++) {
            assertThat(scored.valueAt(slot), is(ObjectiveFixtures.renewableShare().valueAt(slot)));
        }
        assertThat(objective.conditionsFor(inputs), contains(ObjectiveCondition.CARBON_SERIES_NOT_INTENSITY));
    }
}
