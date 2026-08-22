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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Where an admitted decision goes once every guardrail has passed.
 * <p>
 * <strong>This prototype ships exactly one implementation, and it writes nothing.</strong> The shadow-only rule of
 * the prototype track is enforced structurally: the only sink in the bundle logs the decision. The interface exists
 * so that a real sink - one that resolves the participant's Item and sends a command - can be dropped in later
 * without touching the evaluation pass.
 * <p>
 * Whether actuation adapters are themselves a contributable extension point is an open question
 * ({@code define-extension-points/design.md} §3), as is whether the acknowledgement window belongs here or in the
 * engine ({@code define-engine-contract/design.md} §3). {@link #tracksAcknowledgements()} is the seam for the
 * second one: a sink that answers {@code true} tells the engine to stop policing repeats itself, because the
 * adapter behind the sink already does.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface ActuationSink {

    /**
     * Returns the stable id of this sink, used in log lines.
     *
     * @return the sink id
     */
    String getId();

    /**
     * Carries out one decision.
     *
     * @param decision the decision to carry out, already conflict-resolved, limit-checked and gate-approved
     * @param context the snapshot the decision was made against
     */
    void dispatch(Decision decision, EnergyContext context);

    /**
     * Tells whether this sink handles command acknowledgement itself.
     *
     * @return {@code true} if the sink suppresses repeats of an unacknowledged command on its own, so the engine
     *         does not have to
     */
    default boolean tracksAcknowledgements() {
        return false;
    }
}
