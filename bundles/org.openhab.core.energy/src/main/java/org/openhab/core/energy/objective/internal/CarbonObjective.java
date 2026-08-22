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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.objective.CarbonSeries;
import org.openhab.core.energy.objective.ExportCarbonCredit;
import org.openhab.core.energy.objective.ExportShare;
import org.openhab.core.energy.objective.ObjectiveCondition;
import org.openhab.core.energy.objective.ObjectiveInput;
import org.openhab.core.energy.objective.ObjectiveInputs;
import org.openhab.core.energy.objective.OptimizationObjective;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;

/**
 * Lowest carbon: rank slots by the carbon content of the electricity they would be run on.
 * <p>
 * <strong>The ranking is the carbon series, used exactly as the cost objective uses the price series.</strong> A
 * source may publish a CO&#8322; intensity, where a low number is the good one, or a renewable share, where a high
 * one is - the requirement accepts both, so the polarity travels on the series and the shared calculation is told
 * which end is good rather than assuming.
 * <p>
 * <strong>One term is added on top of the series, and it is a decision rather than arithmetic.</strong> Running a
 * deferrable load in a slot consumes energy that would partly have been exported. Exported energy displaces grid
 * generation and so earns a carbon credit which running the load forgoes, and that credit is what makes a carbon
 * objective indifferent between exporting and self-consuming. The shipped rule withdraws the credit while the feed-in
 * price is negative, because a negative price is the market refusing the power, so the displacement never happens.
 * Per unit of energy in slot <em>t</em>, with <em>c</em> the carbon value, <em>s</em> the share that would have been
 * exported and <em>k</em> the credit factor:
 *
 * <pre>
 * score(t) = c(t) &times; (1 &minus; s(t)) + k(f(t)) &times; c(t) &times; s(t)
 *          = c(t) &times; (1 &minus; s(t) &times; (1 &minus; k(f(t))))
 * </pre>
 *
 * With the credit applying (<em>k</em> = 1) the term multiplies out and the score is the carbon series itself, which
 * is why the requirement can say the rule alters nothing but the negative-price case. With the credit withdrawn
 * (<em>k</em> = 0) the score falls to <em>c</em> &times; (1 &minus; <em>s</em>), so local consumption wins in that
 * slot on carbon as it already does on cost.
 * <p>
 * <strong>Three things about that term are honest limitations rather than implementation detail, and each is
 * reported through {@link #conditionsFor(ObjectiveInputs)} rather than hidden:</strong>
 * <ul>
 * <li><em>s</em> is not defined anywhere in the corpus for a future slot. Without a forecast surplus the share is
 * zero, the term multiplies out, and the objective ranks on the plain carbon series - the rule is present and inert,
 * and says so.</li>
 * <li>The term needs a scale on which zero means no emissions, because it scales the value by a fraction. A renewable
 * share is not such a scale, and converting one back to an intensity needs an upper bound nothing states, so on a
 * share series the term is dropped and the condition is reported.</li>
 * <li>The rule's other evaluation point is dispatch time, where the live figures are the right ones. It is reachable
 * through {@link #dispatchCreditFactor(double)} and is <em>not</em> reachable from the engine's cycle snapshot, which
 * carries no price of any kind. That gap is the framework's, not this objective's.</li>
 * </ul>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(service = OptimizationObjective.class, configurationPid = ObjectivePlaneConfiguration.CONFIGURATION_PID, configurationPolicy = ConfigurationPolicy.OPTIONAL, property = Constants.SERVICE_RANKING
        + ":Integer=-2")
public class CarbonObjective implements OptimizationObjective {

    /**
     * The id this objective is selected by.
     */
    public static final String ID = "carbon";

    private volatile ExportCarbonCredit exportCredit;

    /**
     * Creates the objective with the shipped export-credit rule.
     */
    public CarbonObjective() {
        this(new NegativeFeedInCarbonCredit());
    }

    /**
     * Creates the objective with an explicit export-credit rule.
     *
     * @param credit the rule
     */
    public CarbonObjective(ExportCarbonCredit credit) {
        exportCredit = credit;
    }

    /**
     * Creates the objective as OSGi activates it, reading the objective plane's own configuration so that the
     * export-credit rule is selected in one place for the whole plane.
     *
     * @param properties the component configuration
     */
    @Activate
    public CarbonObjective(Map<String, Object> properties) {
        exportCredit = ObjectivePlaneConfiguration.fromProperties(properties).createExportCarbonCredit();
    }

    /**
     * Re-reads the configuration.
     *
     * @param properties the new component configuration
     */
    @Modified
    public void modified(Map<String, Object> properties) {
        exportCredit = ObjectivePlaneConfiguration.fromProperties(properties).createExportCarbonCredit();
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Set<ObjectiveInput> requiredSeries() {
        return Set.of(ObjectiveInput.CARBON);
    }

    @Override
    public Optional<SlotSeries> rank(ObjectiveInputs inputs) {
        Optional<CarbonSeries> carbon = inputs.carbonSeries();
        if (carbon.isEmpty()) {
            return Optional.empty();
        }
        CarbonSeries series = carbon.get();
        Optional<SlotSeries> feedIn = inputs.feedInPriceSeries();
        if (feedIn.isEmpty() || !series.isEmissionIntensity() || !inputs.exportShare().isDerivedFromData()) {
            return Optional.of(series.values());
        }
        return Optional.of(withExportCredit(series, feedIn.get(), inputs.exportShare()));
    }

    @Override
    public Set<ObjectiveCondition> conditionsFor(ObjectiveInputs inputs) {
        Optional<CarbonSeries> carbon = inputs.carbonSeries();
        if (carbon.isEmpty()) {
            return Set.of();
        }
        Set<ObjectiveCondition> conditions = EnumSet.noneOf(ObjectiveCondition.class);
        if (!carbon.get().isEmissionIntensity()) {
            conditions.add(ObjectiveCondition.CARBON_SERIES_NOT_INTENSITY);
        } else if (!inputs.exportShare().isDerivedFromData()) {
            conditions.add(ObjectiveCondition.EXPORT_SHARE_UNKNOWN);
        }
        return Set.copyOf(conditions);
    }

    /**
     * Returns the export-credit factor for one moment, from the feed-in price in force at that moment.
     * <p>
     * This is the rule's dispatch-time evaluation point: the answer for "right now" rather than for a slot of a plan.
     * It takes the price as an argument because the engine's cycle snapshot does not carry one - prices are a later
     * capability there, and the snapshot's own contract says so - which means the objective cannot reach this point
     * on its own and a caller has to supply the figure. That is a gap in the snapshot, and it is exactly why the rule
     * cannot simply be evaluated once at dispatch and left out of the ranking.
     *
     * @param feedInPriceNow the effective feed-in price in force now
     * @return the credit factor, {@code 1} for the full credit and {@code 0} for none
     */
    public double dispatchCreditFactor(double feedInPriceNow) {
        return exportCredit.creditFactorAt(feedInPriceNow);
    }

    /**
     * Returns the export-credit rule in force.
     *
     * @return the rule
     */
    public ExportCarbonCredit getExportCarbonCredit() {
        return exportCredit;
    }

    @Override
    public int getServiceRanking() {
        return CORE_DEFAULT_RANKING;
    }

    private SlotSeries withExportCredit(CarbonSeries carbon, SlotSeries feedIn, ExportShare share) {
        List<Slot> scored = new ArrayList<>(carbon.size());
        for (int index = 0; index < carbon.size(); index++) {
            Slot slot = carbon.slotAt(index);
            double credited = creditFactorAt(feedIn, slot);
            double exported = share.exportedShareAt(index);
            scored.add(new Slot(slot.start(), slot.end(), slot.value() * (1 - exported * (1 - credited))));
        }
        return new SlotSeries(scored, carbon.sense());
    }

    /**
     * Returns the credit factor for one carbon slot, read off the feed-in series by time rather than by index: the
     * two series are separately published and need not share a slot geometry.
     *
     * @param feedIn the feed-in price series
     * @param slot the carbon slot being scored
     * @return the credit factor, the full credit where the feed-in series does not reach
     */
    private double creditFactorAt(SlotSeries feedIn, Slot slot) {
        OptionalInt index = feedIn.indexAt(slot.start());
        return index.isPresent() ? exportCredit.creditFactorAt(feedIn.valueAt(index.getAsInt())) : 1;
    }
}
