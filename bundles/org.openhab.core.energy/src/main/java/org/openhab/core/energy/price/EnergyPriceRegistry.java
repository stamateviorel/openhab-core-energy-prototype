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
import java.util.Optional;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * One source per price role, and the effective price they compose into.
 * <p>
 * This is the ranked-aggregator shape core already uses in {@code StateDescriptionServiceImpl} and that wave 1 used
 * for participant declarations, applied to price series: several sources may publish the same role, the site may name
 * which one it wants, and where it has not, the highest {@code service.ranking} wins. Registration order never
 * decides anything.
 * <p>
 * What it deliberately does <em>not</em> do is invent. A role nobody publishes has no series, an unconfigured
 * composition has no effective price, and both come back as a {@link PricePlaneCondition} rather than as a number.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface EnergyPriceRegistry {

    /**
     * Returns the source in force for a role.
     *
     * @param role the role
     * @return the selected source, or empty when nothing publishes the role
     */
    Optional<EnergyPriceSource> sourceFor(PriceRole role);

    /**
     * Returns the series in force for a role.
     *
     * @param role the role
     * @return the series, or empty when nothing publishes the role or the source currently has nothing
     */
    Optional<EnergyPriceSeries> seriesFor(PriceRole role);

    /**
     * Returns the components the site has configured, in its own order, each carrying whatever series its role
     * currently resolves to.
     *
     * @return the components, empty when the site has configured no composition
     */
    List<PriceComponent> components();

    /**
     * Returns the effective consumption price: the configured components, summed.
     *
     * @return the effective series
     * @throws PriceCompositionException if the composition is unconfigured, unsupplied or cannot be summed, carrying
     *             the condition a status page renders
     */
    EnergyPriceSeries effectiveConsumptionPrice() throws PriceCompositionException;

    /**
     * Returns the feed-in price, which is a role rather than a composition.
     *
     * @return the feed-in series, or empty when nothing publishes one
     */
    Optional<EnergyPriceSeries> feedInPrice();

    /**
     * Returns what the price plane currently cannot do, for whoever renders the site's status.
     *
     * @return the conditions in force, empty when the plane is answering from data
     */
    Set<PricePlaneCondition> conditions();
}
