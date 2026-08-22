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
package org.openhab.core.energy.spi;

import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The one contract every place a time series comes from satisfies, whatever the series carries.
 * <p>
 * <strong>Why this exists at all.</strong> The price, forecast and carbon planes were built against the same shape
 * independently, and each arrived at the same four members under a different name. Three identical interfaces in one
 * framework is the modelling inconsistency openHAB reviewers weight first, and it is worse than cosmetic here: the
 * precedence rule that decides <em>which</em> source answers has to be the same rule in all three planes, and three
 * separate declarations of the same shape is exactly how three copies of that rule drift apart. This interface is
 * the shape stated once; {@link SourceRanking} is the rule stated once.
 * <p>
 * <strong>What deliberately did not get unified.</strong> The series type stays per plane, and so does the role
 * enum. Those two are not accidental duplication:
 * <ul>
 * <li>the typed series differ in what they must carry - a price has a currency, a market zone and a delivery day; a
 * forecast has a run time and a physical unit - and collapsing them would mean a single type with a
 * mostly-empty field set, which is the modelling defect this change is trying to avoid, not a fix for it;</li>
 * <li>the role enums are not the same kind of key. {@code PriceRole} names a <em>component of a sum</em> (spot, grid
 * tariff, taxes) whose members are added together to make one effective price. {@code ForecastRole} names a
 * <em>distinct physical quantity</em> (solar production, temperature, wind) whose members are never added at all. A
 * single role enum over both would be a list whose halves obey different arithmetic.</li>
 * </ul>
 * So what is shared is the part that genuinely is shared: an identity, a precedence number, and an answer that may
 * be absent.
 * <p>
 * <strong>Absence is normal.</strong> {@link #getSeries()} returns an empty optional whenever the source has nothing
 * to say - before its first fetch, after a failure, outside the hours its market publishes. A source is not expected
 * to unregister itself to signal that, because disappearing loses the precedence it was chosen by; it stays
 * registered and answers empty, and the plane above it decides whether to fall through to the next candidate or
 * report the gap.
 * <p>
 * Implementations are registered as OSGi services and may appear and disappear at any time.
 *
 * @param <S> the typed series this source produces
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface EnergySeriesSource<S> {

    /**
     * The ranking core's own sources register at, so that anything a site installs outranks them without having to
     * know what number to beat.
     * <p>
     * The value follows the precedent {@code DefaultStateDescriptionFragmentProvider} set in core.
     */
    int CORE_DEFAULT_RANKING = -2;

    /**
     * Returns the stable identity of this source, which is what a site names when it selects one explicitly and what
     * a report names when it says which source answered.
     *
     * @return the source id, never blank
     */
    String getSourceId();

    /**
     * Returns the precedence of this source among the candidates for the same role.
     * <p>
     * Higher wins. Core-shipped sources use {@link #CORE_DEFAULT_RANKING} so that an add-on registering at the
     * default of {@code 0} takes precedence without configuration.
     *
     * @return the service ranking
     */
    default int getServiceRanking() {
        return 0;
    }

    /**
     * Returns the series this source currently offers, or empty when it has nothing to offer.
     *
     * @return the series, or empty
     */
    Optional<S> getSeries();
}
