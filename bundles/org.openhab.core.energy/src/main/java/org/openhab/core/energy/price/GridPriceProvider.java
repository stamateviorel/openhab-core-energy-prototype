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
package org.openhab.core.energy.price;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The generic configurable grid-price provider's arithmetic: a raw series in, an effective series out, through an
 * ordered pipeline of {@link PriceAdjustment}s.
 * <p>
 * This is Kai's own {@code GridEnergyProvider} suggestion - "the system SHALL ship one generic, configurable
 * grid-price provider ... so common cases need no custom binding". It exists so that a site whose supplier publishes
 * raw ENTSO-E prices does not need anybody to write a binding for its country's VAT rate.
 * <p>
 * <strong>Where the Item is.</strong> The requirement says the provider "reads a future price series from an Item".
 * Reading it is a persistence query, which this bundle may not make and is structurally unable to make; so the half
 * that reads lives in the companion {@code org.openhab.core.energy.series} bundle and hands the raw series here as a
 * value. What is here is everything that is arithmetic - which is everything that is worth unit-testing, and all of
 * the behaviour a reviewer would want to check against a bill.
 * <p>
 * <strong>Core ships this and it is still not privileged.</strong> The component that wraps it registers at
 * {@code service.ranking = -2}, the number {@code DefaultStateDescriptionFragmentProvider} already uses for a
 * core-shipped default, so any add-on that publishes a better series for the same role wins without the user
 * configuring anything.
 *
 * @param id the provider's stable id
 * @param adjustments the pipeline, in the order the site's own bill applies it
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record GridPriceProvider(String id, List<PriceAdjustment> adjustments) {

    /**
     * Validates the provider and takes a defensive immutable copy.
     *
     * @throws IllegalArgumentException if the id is blank
     */
    public GridPriceProvider {
        if (id.isBlank()) {
            throw new IllegalArgumentException("a grid price provider needs an id");
        }
        adjustments = List.copyOf(adjustments);
    }

    /**
     * Creates a provider from a pipeline.
     *
     * @param id the provider's stable id
     * @param adjustments the pipeline, in order
     * @return the provider
     */
    public static GridPriceProvider of(String id, PriceAdjustment... adjustments) {
        return new GridPriceProvider(id, List.of(adjustments));
    }

    /**
     * Runs the pipeline.
     * <p>
     * An empty pipeline is a legitimate configuration and returns the raw series unchanged: a site whose source
     * already publishes a consumer price wants the provider for the series' identity, not for its arithmetic.
     *
     * @param raw the series as the source published it
     * @return the effective series
     * @throws PriceCompositionException if a step cannot be carried out, with the reason a user has to act on
     */
    public EnergyPriceSeries apply(EnergyPriceSeries raw) throws PriceCompositionException {
        EnergyPriceSeries effective = raw;
        for (PriceAdjustment adjustment : adjustments) {
            effective = adjustment.apply(effective);
        }
        return effective;
    }

    /**
     * Returns the pipeline written out in order, which is what a status page shows a user who wants to know where
     * their effective price came from.
     *
     * @return the description, for example {@code entsoe: per kWh, VAT 24.0%, +0.0279/kWh}
     */
    public String describe() {
        StringBuilder description = new StringBuilder(id).append(": ");
        for (int step = 0; step < adjustments.size(); step++) {
            description.append(step == 0 ? "" : ", ").append(adjustments.get(step).describe());
        }
        return adjustments.isEmpty() ? description.append("no adjustments").toString() : description.toString();
    }
}
