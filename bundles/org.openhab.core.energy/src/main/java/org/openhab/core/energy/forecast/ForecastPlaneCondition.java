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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Something the forecast plane cannot do, or did in a way somebody has to know about, rather than doing it quietly.
 * <p>
 * This is the level plane's {@code LevelPlaneCondition} pattern applied to the data plane, for the same reason: a
 * requirement that is live but inert until a site configures it has to be readable as <em>unconfigured</em> rather
 * than looking like a working feature that never fires. None of these is an error and none stops the plane; they are
 * machine-readable so that a status surface can render them and a test can assert them.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum ForecastPlaneCondition {

    /**
     * No source is registered for a role a consumer asked for, or every source for it answered empty.
     * <p>
     * This is the reportable half of _Graceful degradation on contributor loss_: planning continues on whatever
     * remains, and this says the answer came from a gap rather than from data.
     */
    SOURCE_UNAVAILABLE,

    /**
     * The selected source's newest run is older than the age the site declared it may be.
     * <p>
     * The series is still answered - "keep planning on the baseline instead of losing the series" - and this is what
     * makes "we are planning on something old" visible.
     */
    SOURCE_STALE,

    /**
     * The site has declared no maximum age for a forecast run, so no run is ever counted as stale.
     * <p>
     * _Forecast source fails_ speaks of a provider "unavailable for any duration" and gives no age behind it, and no
     * decided parameter in the corpus supplies one. Inventing a number would decide, silently, how long a site is
     * happy to plan on yesterday's weather. So the shape ships, the number does not, and this condition is what keeps
     * that honest.
     */
    STALENESS_UNCONFIGURED,

    /**
     * A site named a preferred source for a role and no source with that id is registered.
     * <p>
     * The plane falls back to ranking, which is what an uninstalled add-on should degrade to, and reports this so the
     * fallback is not mistaken for the configuration having taken effect.
     */
    PREFERRED_SOURCE_ABSENT,

    /**
     * A source published a series in a unit its role does not carry - watts under a temperature role, say.
     * <p>
     * The series is refused rather than reinterpreted: no arithmetic in this plane silently converts a quantity into
     * one of a different kind.
     */
    UNIT_MISMATCH,

    /**
     * A refresh overwrote entries that a cap had been written onto.
     * <p>
     * <strong>This is the corpus's own unresolved collision, made visible.</strong> _Layered prediction series_ says
     * fresh values replace old ones for the same timestamps, and in the next scenario says the capped entries hold
     * the capped value; a refresh arriving after a cap therefore erases it. The framework does not decide which of
     * the two wins - {@link LayeredWritePolicy} makes that a configuration choice - and where the site has chosen
     * nothing, the requirement's own literal words are followed and this condition is raised, so an erased cap is
     * loud rather than silent.
     */
    CAP_OVERWRITTEN_BY_REFRESH,

    /**
     * The site has not chosen how a cap and a refresh resolve when they land on the same entry.
     * <p>
     * Reported for as long as it holds, alongside {@link #CAP_OVERWRITTEN_BY_REFRESH} whenever the collision actually
     * happens.
     */
    WRITE_POLICY_UNCONFIGURED,

    /**
     * The writer-precedence policy is selected but the site has declared no order for the layers, so the write was
     * refused rather than applied in an order nobody chose.
     */
    LAYER_PRECEDENCE_UNCONFIGURED,

    /**
     * A write was refused because a layer of higher precedence already holds the entry.
     * <p>
     * Not a fault - it is the writer-precedence policy doing exactly what it was selected for - but it is the
     * difference between a cap that held and a forecast that was ignored, so it is reported rather than assumed.
     */
    WRITE_REFUSED_BY_PRECEDENCE,

    /**
     * A layered series was asked for on a persistence service that cannot modify what it has stored.
     * <p>
     * Overwriting past and present entries is the whole of _Layered prediction series_, and core persists nothing to
     * a service that does not implement the modifiable interface, so the surface refuses rather than pretending.
     */
    PERSISTENCE_NOT_MODIFIABLE,

    /**
     * The site chose to keep caps out of the prediction and named no series to keep them in, so the cap was not
     * written anywhere.
     */
    CAP_SERIES_UNCONFIGURED,

    /**
     * A prediction series names an Item that does not exist, so nothing can be written to or read from it.
     */
    ITEM_UNRESOLVED,

    /**
     * The site has declared no heat-loss coefficient or base temperature, so no heating demand is derived.
     * <p>
     * The relation between outdoor temperature and heating need is "approximately linear" in the corpus and the
     * constant of that line is a property of the building, which no source in this corpus supplies. So the derivation
     * ships and stays inert, exactly as the surplus escalation does, and this is what makes inert readable.
     */
    DEMAND_DERIVATION_UNCONFIGURED,

    /**
     * The site has declared no solar-gain factor, so a solar forecast does not reduce the derived heating demand.
     */
    SOLAR_GAIN_UNCONFIGURED,

    /**
     * The site has declared no pre-heating horizon, drop threshold or share, so demand is not pulled forward ahead of
     * a temperature drop.
     */
    PREHEAT_UNCONFIGURED
}
