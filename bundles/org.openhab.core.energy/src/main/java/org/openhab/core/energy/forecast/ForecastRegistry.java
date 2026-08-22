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
package org.openhab.core.energy.forecast;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The single place anything asks for a forecast.
 * <p>
 * A caller names a {@link ForecastRole} and gets a series, or nothing. It never names a source, never learns which
 * add-on produced the answer, and needs no change when the site swaps one forecast service for another - which is
 * _Source-agnostic consumption_ in one sentence.
 * <p>
 * <strong>What the registry adds over the sources themselves</strong> is the resolution and the reporting: which
 * source answers for a role (the site's own preference first, then {@code service.ranking}, then the source id as a
 * final tie-break so the outcome never depends on registration order), what happens when that source disappears
 * mid-day (the next one answers, and the degradation is reported), and how old a run may be before the site is told
 * it is planning on something stale.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface ForecastRegistry {

    /**
     * Returns the forecast currently in force for a role.
     *
     * @param role what the caller needs a forecast of
     * @return the series, or empty when no registered source has one
     */
    Optional<ForecastSeries> getSeries(ForecastRole role);

    /**
     * Returns the ids of every source registered for a role, best first.
     * <p>
     * This is the diagnostic view - "what could answer this, and in which order" - as opposed to
     * {@link #getSeries(ForecastRole)}, which is the answer itself.
     *
     * @param role the role
     * @return the source ids, in resolution order, empty when nothing is registered for the role
     */
    List<String> getSourceIds(ForecastRole role);

    /**
     * Returns the id of the source the last answer for a role came from.
     *
     * @param role the role
     * @return the source id, or empty when the role has not been answered
     */
    Optional<String> getAnsweringSourceId(ForecastRole role);

    /**
     * Returns what the plane currently has to report about a role.
     *
     * @param role the role
     * @return the conditions, empty when there is nothing to say
     */
    Set<ForecastPlaneCondition> getConditions(ForecastRole role);

    /**
     * Returns everything the plane currently has to report, across all roles.
     *
     * @return the conditions, empty when there is nothing to say
     */
    Set<ForecastPlaneCondition> getConditions();
}
