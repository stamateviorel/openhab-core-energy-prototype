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
package org.openhab.core.energy.level;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * What had to be said about the attempt to turn the data planes into a level plan.
 * <p>
 * <strong>Why this is not {@link LevelPlaneCondition}.</strong> That enum answers "what is true about the plan at
 * this moment" - there is no plan, the escalation is unconfigured. This one answers "what happened when the plan was
 * last derived", which is a different question with a different audience: the first is read by whatever is deciding
 * right now, the second by a person looking at a settings page and asking why the levels are not what they expected.
 * Merging them would produce one enum whose members are true at different times.
 * <p>
 * Every member here is a <em>reported</em> state rather than an exception. Nothing in this vocabulary stops a site
 * running; each one names a reason the plan is less informed than it could be, so that a degradation is visible
 * instead of silent. That is the same discipline the other three planes follow.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum PlanDerivationCondition {

    /**
     * There was no series to derive a plan from, so the previous plan - possibly none at all - still stands.
     * <p>
     * This is the ordinary state of a fresh installation: core ships no price source, so until a site installs one
     * there is nothing to cut level bands out of. It is reported rather than logged as an error for exactly that
     * reason.
     */
    NO_SERIES_TO_DERIVE_FROM,

    /**
     * The price plane has sources but could not compose an effective price out of them - mismatched currencies, a
     * market-zone disagreement, or no configured composition at all.
     * <p>
     * The price plane's own condition says which; this one says only that the level plan is the thing that suffered
     * for it, which is what makes the connection visible from the level side.
     */
    PRICE_COMPOSITION_FAILED,

    /**
     * The objective that ranked the series was not the one the site asked for.
     * <p>
     * The objective plane decides what to do about that and reports why; this records that the plan in force was
     * derived through the fallback rather than through the selection, because a plan derived from a different
     * objective is a different plan and a user comparing it against their configuration deserves to know.
     */
    OBJECTIVE_DEGRADED,

    /**
     * A surplus forecast was assembled from a solar production forecast with no demand forecast to net off against
     * it, so it describes production rather than genuine surplus.
     * <p>
     * <strong>This one is a warning about a quantity the corpus does not define.</strong> The self-consumption
     * objective needs to know how much surplus each future slot will have; no requirement anywhere says how that is
     * computed, and there is no total-demand forecast role to subtract. Netting solar against the one demand role
     * that does exist - heating - is the closest honest approximation, and when even that is missing the series is
     * production alone. A site optimising on it is optimising on an upper bound, and this says so.
     */
    SURPLUS_FORECAST_IS_PRODUCTION_ONLY,

    /**
     * A demand forecast existed but its slots do not line up with the solar forecast's, so it was not netted off.
     * <p>
     * Nothing is resampled here on purpose. How series of differing geometry are brought together is an open
     * question the price plane already had to face for composition, and answering it a second time, differently,
     * inside the coordinator is precisely how a framework ends up with two incompatible notions of alignment.
     */
    SURPLUS_FORECAST_UNALIGNED,

    /**
     * A surplus forecast could have been assembled but was not used, because nothing predicts the house's own demand
     * and it would therefore have been the production forecast under another name.
     * <p>
     * Owner decision D39: the feature turns itself on where the figure means something and stays off where it would
     * over-promise. A site that wants the upper bound anyway sets {@code surplusForecast} explicitly.
     */
    SURPLUS_FORECAST_WITHHELD,

    /**
     * No refresh interval is configured, so the plan is re-derived only when something observable changes - a
     * configuration edit, or a source appearing or disappearing - and not when an already-installed source quietly
     * publishes new values.
     * <p>
     * <strong>This is the honest name for a real gap.</strong> The source SPI is pull-only: a day-ahead source that
     * fetches tomorrow's prices at midday has no way to announce it, and nothing here can notice. Until the SPI
     * grows a notification, a site that wants tomorrow's prices picked up on the day they arrive has to say how
     * often to look.
     */
    REFRESH_INTERVAL_UNCONFIGURED
}
