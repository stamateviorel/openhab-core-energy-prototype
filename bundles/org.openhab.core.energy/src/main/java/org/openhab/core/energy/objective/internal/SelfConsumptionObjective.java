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

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.objective.ObjectiveInput;
import org.openhab.core.energy.objective.ObjectiveInputs;
import org.openhab.core.energy.objective.OptimizationObjective;
import org.openhab.core.energy.window.SlotSeries;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;

/**
 * Maximum self-consumption: put the load where the site's own generation is, rather than where the grid is cheap.
 * <p>
 * <strong>This objective answers at both evaluation points, and only one of them rests on something the corpus
 * defines.</strong>
 * <ul>
 * <li>{@link #availableWattsFor(EnergyContext, int)} is the instantaneous answer, and it is the corpus's own
 * definition of surplus, unchanged: grid export plus the battery charge this consumer outranks, netted against any
 * import and never negative. It is read straight off the cycle snapshot, so the two scenarios about a charging
 * battery - place the load now against 3 kW of reclaimable charge, and offer it only 2 kW when the site is importing
 * 1 kW - are answered by the framework's existing figure rather than by anything recomputed here.</li>
 * <li>{@link #rank(ObjectiveInputs)} is the planning answer, and it needs a <em>forecast</em> surplus: placing a
 * deferrable load "into the surplus" is a statement about future slots, and the defined surplus figure is a fact
 * about the present. No requirement in the corpus defines a forecast surplus series. It is therefore a named input
 * that an installation either has or does not, and an installation that does not gets an objective that reports its
 * data plane absent rather than one that quietly ranks on nothing.</li>
 * </ul>
 * The gap between the two is the single most consequential ambiguity found while building this capability, and it is
 * left visible on purpose.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(service = OptimizationObjective.class, property = Constants.SERVICE_RANKING + ":Integer=-2")
public class SelfConsumptionObjective implements OptimizationObjective {

    /**
     * The id this objective is selected by.
     */
    public static final String ID = "self-consumption";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Set<ObjectiveInput> requiredSeries() {
        return Set.of(ObjectiveInput.SURPLUS_FORECAST);
    }

    @Override
    public Optional<SlotSeries> rank(ObjectiveInputs inputs) {
        return inputs.surplusForecastSeries();
    }

    @Override
    public OptionalDouble availableWattsFor(EnergyContext context, int consumerPriority) {
        return context.surplusWattsFor(consumerPriority);
    }

    @Override
    public int getServiceRanking() {
        return CORE_DEFAULT_RANKING;
    }
}
