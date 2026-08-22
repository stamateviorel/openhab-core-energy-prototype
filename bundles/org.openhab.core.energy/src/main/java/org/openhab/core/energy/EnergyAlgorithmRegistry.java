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
package org.openhab.core.energy;

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * How something outside the engine's own bundle contributes an {@link EnergyAlgorithm} - and takes it away again.
 * <p>
 * An add-on does not need this: it publishes an {@link EnergyAlgorithm} as an OSGi service and the engine binds it
 * through the whiteboard. A <em>script</em> does, because a JSR-223 rule has no bundle of its own to register a
 * service from, and because {@code define-engine-contract}'s <em>Replaceable algorithm</em> requirement puts scripts
 * on the same footing as add-ons. This interface is the reachable half of that promise: it is exported, the engine
 * is registered under it, and a script can therefore look it up and hand over a lambda.
 * <p>
 * <strong>Deliberately narrow.</strong> Registration and de-registration are all this offers. Shadow mode and the
 * master stop are not on it, so no contributed code can release either - the only way out of shadow is a
 * configuration edit by the operator.
 * <p>
 * Two things the corpus does not settle, both reported rather than decided:
 * <ul>
 * <li>Ownership. {@link #registerAlgorithm(String, int, EnergyAlgorithm)} replaces silently under an id that is
 * already taken, because a reloaded script must be able to replace its own previous registration - but that also
 * lets one script take over another's id unnoticed. The declaration plane defines contributor ownership carefully;
 * the algorithm plane has no equivalent statement.</li>
 * <li>Lifecycle. Nothing in openHAB reliably tells a script it is being unloaded, so withdrawing a registration is
 * possible here, not automatic. An add-on gets that for free from OSGi.</li>
 * </ul>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface EnergyAlgorithmRegistry {

    /**
     * Registers an algorithm that carries its own identity.
     *
     * @param algorithm the algorithm; its {@link EnergyAlgorithm#getId()} and
     *            {@link EnergyAlgorithm#getPriority()} are used
     */
    void registerAlgorithm(EnergyAlgorithm algorithm);

    /**
     * Registers an algorithm under an explicit identity - the path a script lambda takes, since a lambda has no
     * meaningful class name to derive an id from.
     *
     * @param id the id to register the algorithm under
     * @param priority the priority its decisions carry, lower is stronger
     * @param algorithm the algorithm
     */
    void registerAlgorithm(String id, int priority, EnergyAlgorithm algorithm);

    /**
     * Removes a previously registered algorithm.
     *
     * @param id the id it was registered under
     * @return {@code true} if an algorithm was removed
     */
    boolean unregisterAlgorithm(String id);

    /**
     * Returns the algorithms of the next cycle, in the order they will be evaluated in.
     *
     * @return the algorithms, contributed and registered alike
     */
    List<EnergyAlgorithm> algorithms();
}
