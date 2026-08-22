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
package org.openhab.core.energy.objective;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Something the objective plane could not do as asked, and has to say so about rather than doing quietly.
 * <p>
 * The same discipline the level plane already follows: none of these is an error and none of them stops planning, and
 * every one of them exists because a requirement asks for an absence to be <em>visible</em>. They are machine
 * readable on purpose - a UI needs something to render as "degraded" and a test needs something to assert, and a log
 * line is neither.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum ObjectiveCondition {

    /**
     * No objective is configured, so the cost objective is in force.
     * <p>
     * This is the behaviour every planning requirement in the corpus already assumed before the objective became
     * selectable, so leaving the parameter unset changes nothing about how a site behaves. It is reported anyway,
     * because "you are optimizing for money" should be a statement a site can read rather than an assumption it has
     * to know.
     */
    OBJECTIVE_UNCONFIGURED,

    /**
     * The configured objective id is not registered by anything installed.
     */
    OBJECTIVE_UNKNOWN,

    /**
     * The selected objective's data plane is not installed, or is installed and currently has nothing: the carbon
     * objective with no carbon source, the self-consumption objective with no surplus forecast.
     */
    DATA_PLANE_ABSENT,

    /**
     * The selection was degraded to the cost objective, which is what the fall-back policy does with an objective
     * that cannot rank.
     */
    DEGRADED_TO_COST,

    /**
     * The selection was refused, which is what the refusing policy does with an objective that cannot rank. Nothing
     * ranks, so no plan is derived, and the site runs on its level plane's own answer for an absent plan - normal
     * everywhere, plus whatever surplus escalation is configured.
     */
    SELECTION_REFUSED,

    /**
     * The export-credit rule of the carbon objective could not be evaluated over future slots, because nothing said
     * how much of a load's energy would have been exported in them.
     * <p>
     * The rule is still in force at the dispatch-time evaluation point; it is inert over the ranking, which means the
     * ranking is the plain carbon series. Reported because a rule that is present but inert is otherwise
     * indistinguishable from a rule that is doing something.
     */
    EXPORT_SHARE_UNKNOWN,

    /**
     * The carbon series is not a zero-anchored intensity - a renewable share, typically - so the export-credit rule
     * cannot be applied to it: crediting proportionally needs a scale on which zero means "no emissions", and
     * converting a share back to an intensity needs an upper bound nothing states.
     * <p>
     * Ranking is unaffected; only the credit term is dropped.
     */
    CARBON_SERIES_NOT_INTENSITY,

    /**
     * A second objective tried to register under an id that was already taken, and was refused. The incumbent keeps
     * the id.
     */
    DUPLICATE_OBJECTIVE_REFUSED
}
