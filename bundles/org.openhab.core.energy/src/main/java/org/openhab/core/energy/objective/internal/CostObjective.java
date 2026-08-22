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
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.objective.ObjectiveInput;
import org.openhab.core.energy.objective.ObjectiveInputs;
import org.openhab.core.energy.objective.OptimizationObjective;
import org.openhab.core.energy.window.SlotSeries;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;

/**
 * Lowest cost: rank slots by what a kilowatt-hour drawn in them costs.
 * <p>
 * This is the objective every planning requirement in the corpus assumed before the objective became selectable, and
 * it is the one every production system behind those requirements actually runs. Making it explicit changes nothing
 * about a site that never selects an objective; what it changes is that "cheapest" is now one answer among several
 * rather than the meaning of the word "best".
 * <p>
 * It is also the fall-back the plane degrades to when a selected objective's data plane is absent, which is why it
 * carries no opinion of its own about the instantaneous snapshot: it has nothing to say that is not already in the
 * price series.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(service = OptimizationObjective.class, property = Constants.SERVICE_RANKING + ":Integer=-2")
public class CostObjective implements OptimizationObjective {

    /**
     * The id this objective is selected by, and the id the plane falls back to.
     */
    public static final String ID = "cost";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Set<ObjectiveInput> requiredSeries() {
        return Set.of(ObjectiveInput.CONSUMPTION_PRICE);
    }

    @Override
    public Optional<SlotSeries> rank(ObjectiveInputs inputs) {
        return inputs.consumptionPriceSeries();
    }

    @Override
    public int getServiceRanking() {
        return CORE_DEFAULT_RANKING;
    }
}
