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
import org.openhab.core.energy.objective.AbsentDataPlanePolicy;
import org.openhab.core.energy.objective.ObjectiveCondition;
import org.openhab.core.energy.objective.ObjectiveInput;
import org.openhab.core.energy.objective.ObjectiveInputs;
import org.openhab.core.energy.objective.ObjectiveResolution;
import org.openhab.core.energy.objective.OptimizationObjective;

/**
 * <strong>The open question, kept open.</strong> Selecting the carbon objective on a site with no carbon source is
 * undefined in the corpus: the design file frames three options and records no answer. All three are implemented,
 * the choice is one configuration value, and this file asserts that they really are three different behaviours
 * rather than three names for one.
 * <p>
 * What the shipped default follows is not a verdict on that question. It is the disposition already on record
 * elsewhere - fall back to cost and report the degraded source - which is also the shape the extension surface's
 * graceful-degradation requirement mandates for the harder case of a source vanishing after selection. Answering the
 * question the other way is a change to one {@code <default>} in one configuration description.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class AbsentDataPlaneTest {

    /**
     * The site of every one of these tests: prices, and no carbon source at all.
     *
     * @return the inputs
     */
    private static ObjectiveInputs siteWithoutCarbon() {
        return ObjectiveInputs.ofPrices(ObjectiveFixtures.consumptionPrices());
    }

    /**
     * Option one, and the shipped default: the site keeps planning, on cost, and is told that its choice is not
     * taking effect.
     */
    @Test
    public void fallingBackToCostKeepsPlanningAndReportsTheDegradation() {
        ObjectivePlane plane = ObjectiveFixtures.planeWithBuiltIns(Map.of("objective", CarbonObjective.ID));

        ObjectiveResolution resolution = plane.resolve(siteWithoutCarbon());

        assertThat(plane.absentDataPlanePolicy(), is(AbsentDataPlanePolicy.FALL_BACK_TO_COST));
        assertThat(resolution.requestedId(), is(CarbonObjective.ID));
        assertThat(resolution.effectiveId().orElseThrow(), is(CostObjective.ID));
        assertThat(resolution.isAsRequested(), is(false));
        assertThat(resolution.ranking().isPresent(), is(true));
        assertThat(resolution.conditions(),
                hasItems(ObjectiveCondition.DATA_PLANE_ABSENT, ObjectiveCondition.DEGRADED_TO_COST));
    }

    /**
     * Option two: an objective whose data is missing is not offered, so it cannot be chosen by mistake - but one
     * that was chosen before its source was uninstalled still has to do something, and does the same as option one.
     */
    @Test
    public void hidingAnUnavailableObjectiveChangesWhatIsOfferedAndNotWhatIsPlanned() {
        ObjectivePlane plane = ObjectiveFixtures
                .planeWithBuiltIns(Map.of("objective", CarbonObjective.ID, "absentDataPlane", "hide"));

        ObjectiveInputs inputs = siteWithoutCarbon();

        assertThat(plane.offeredObjectives(inputs).stream().map(OptimizationObjective::getId).toList(),
                contains(CostObjective.ID));
        assertThat("the carbon and self-consumption objectives are installed but not offered",
                plane.objectives().stream().map(OptimizationObjective::getId).toList(),
                hasItems(CarbonObjective.ID, SelfConsumptionObjective.ID));
        assertThat("a selection made before the source went away still plans",
                plane.resolve(inputs).effectiveId().orElseThrow(), is(CostObjective.ID));
    }

    /**
     * Option three: rank nothing rather than rank on a metric the user did not choose. The most conservative
     * reading, and the one whose cost is visible - a site whose carbon feed is down stops planning.
     */
    @Test
    public void refusingTheSelectionPlansNothingAtAll() {
        ObjectivePlane plane = ObjectiveFixtures
                .planeWithBuiltIns(Map.of("objective", CarbonObjective.ID, "absentDataPlane", "refuse"));

        ObjectiveResolution resolution = plane.resolve(siteWithoutCarbon());

        assertThat(resolution.effective().isPresent(), is(false));
        assertThat(resolution.ranking().isPresent(), is(false));
        assertThat(resolution.reports(ObjectiveCondition.SELECTION_REFUSED), is(true));
        assertThat("and it is not silently reported as a cost site",
                resolution.reports(ObjectiveCondition.DEGRADED_TO_COST), is(false));
    }

    /**
     * The three really are three: the same site and the same selection produce three different answers.
     */
    @Test
    public void theThreeOptionsAreThreeDifferentBehaviours() {
        ObjectiveInputs inputs = siteWithoutCarbon();

        ObjectiveResolution fallBack = ObjectiveFixtures.planeWithBuiltIns(Map.of("objective", CarbonObjective.ID))
                .resolve(inputs);
        ObjectivePlane hiding = ObjectiveFixtures
                .planeWithBuiltIns(Map.of("objective", CarbonObjective.ID, "absentDataPlane", "hide"));
        ObjectiveResolution refused = ObjectiveFixtures
                .planeWithBuiltIns(Map.of("objective", CarbonObjective.ID, "absentDataPlane", "refuse"))
                .resolve(inputs);

        assertThat(fallBack.ranking().isPresent(), is(true));
        assertThat(refused.ranking().isPresent(), is(false));
        assertThat(hiding.offeredObjectives(inputs), hasSize(1));
        assertThat(
                ObjectiveFixtures.planeWithBuiltIns(Map.of("objective", CarbonObjective.ID)).offeredObjectives(inputs),
                hasSize(3));
    }

    /**
     * The second clause of the same behaviour: when even the fall-back has nothing to rank on, nothing ranks, and
     * the level plane's own answer for an absent plan takes over - normal everywhere, plus whatever surplus
     * escalation is configured. Nothing here invents a plan out of no data.
     */
    @Test
    public void withNoDataAtAllNothingRanksAndTheLevelPlaneAnswersForAnAbsentPlan() {
        ObjectiveResolution resolution = ObjectiveFixtures.planeWithBuiltIns(Map.of("objective", CarbonObjective.ID))
                .resolve(ObjectiveInputs.empty());

        assertThat(resolution.effective().isPresent(), is(false));
        assertThat(resolution.ranking().isPresent(), is(false));
        assertThat(resolution.reports(ObjectiveCondition.DATA_PLANE_ABSENT), is(true));
    }

    /**
     * An objective nobody registered is a different fault from one whose data is missing, and is reported as one.
     */
    @Test
    public void anObjectiveNobodyInstalledIsReportedAsUnknownRatherThanAsMissingData() {
        ObjectiveResolution resolution = ObjectiveFixtures.planeWithBuiltIns(Map.of("objective", "no-such-objective"))
                .resolve(ObjectiveFixtures.fullyEquippedSite());

        assertThat(resolution.reports(ObjectiveCondition.OBJECTIVE_UNKNOWN), is(true));
        assertThat(resolution.reports(ObjectiveCondition.DATA_PLANE_ABSENT), is(false));
        assertThat(resolution.effectiveId().orElseThrow(), is(CostObjective.ID));
    }

    /**
     * What is missing is answerable by name, so a report can say which series to install rather than only that one
     * is absent.
     */
    @Test
    public void whatIsMissingIsAnswerableByName() {
        assertThat(ObjectivePlane.missingInputs(new CarbonObjective(), siteWithoutCarbon()),
                contains(ObjectiveInput.CARBON));
        assertThat(ObjectivePlane.missingInputs(new SelfConsumptionObjective(), siteWithoutCarbon()),
                contains(ObjectiveInput.SURPLUS_FORECAST));
        assertThat(ObjectivePlane.missingInputs(new CostObjective(), siteWithoutCarbon()), is(empty()));
    }

    /**
     * A configuration nobody can read falls back rather than stopping the plane from coming up, which is the same
     * discipline the engine and the level plane already follow.
     */
    @Test
    public void anUnreadablePolicyFallsBackInsteadOfFailing() {
        ObjectivePlane plane = ObjectiveFixtures.planeWithBuiltIns(Map.of("absentDataPlane", "whatever"));

        assertThat(plane.absentDataPlanePolicy(), is(AbsentDataPlanePolicy.FALL_BACK_TO_COST));
    }
}
