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

import java.util.List;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The whiteboard a contributed objective arrives through, reachable from a script exactly as the algorithm registry
 * is.
 * <p>
 * <strong>Identity is first-registration-wins, and a duplicate is refused with a warning.</strong> That is the
 * decided rule for objectives, and it is the opposite of what the algorithm registry next door does - which replaces
 * silently, so that a reloaded script can replace its own registration. Both cannot be right for a user who
 * contributes a script algorithm and a script objective in the same file, and this divergence is reported rather than
 * smoothed over: the honest fix is to unify the two on first-wins now that the framework's script-unload hook makes
 * last-wins unnecessary, and unifying them is a change to the algorithm registry, not to this one.
 * <p>
 * <strong>Deliberately narrow.</strong> Registering, withdrawing and reading are all this offers. Which objective is
 * <em>selected</em> is not on it: that is a configuration value, so no contributed code can switch the site's
 * objective behind the operator's back.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface ObjectiveRegistry {

    /**
     * Registers an objective.
     * <p>
     * A registration under an id that is already taken is refused and the incumbent keeps the id.
     *
     * @param objective the objective
     * @return {@code true} if it was registered, {@code false} if the id was taken
     */
    boolean registerObjective(OptimizationObjective objective);

    /**
     * Withdraws a previously registered objective.
     *
     * @param id the id it was registered under
     * @return {@code true} if an objective was withdrawn
     */
    boolean unregisterObjective(String id);

    /**
     * Returns every registered objective, ordered by {@code service.ranking} descending and then by id, so that the
     * order a user is offered them in does not depend on the order they happened to start in.
     *
     * @return the objectives, contributed and built-in alike
     */
    List<OptimizationObjective> objectives();

    /**
     * Returns one registered objective.
     *
     * @param id the objective id
     * @return the objective, or empty when nothing is registered under that id
     */
    Optional<OptimizationObjective> objective(String id);
}
