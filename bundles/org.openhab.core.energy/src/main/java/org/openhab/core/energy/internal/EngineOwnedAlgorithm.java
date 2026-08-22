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
package org.openhab.core.energy.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.DecisionKind;

/**
 * Marks the algorithms whose {@link DecisionKind} the engine believes, because it wrote them.
 * <p>
 * <strong>Why a marker and not an id.</strong> The prohibitions are engine-owned and the list of them is closed, so a
 * contributed algorithm may not promote a reading of its own into one. A {@code DecisionKind} is self-declared - the
 * {@code Decision} constructor is public API and nothing corroborates the field - so an algorithm that simply calls
 * its proposal a {@code DEVICE_PROTECTION} would otherwise outrank the user's level gate, walk through the
 * stale-measurement freeze, and beat the engine's own protection for the same participant. Trusting an <em>id</em>
 * instead would be no better: two algorithms may carry the same id, and nothing stops a contribution using the
 * engine's.
 * <p>
 * The type is what makes the claim unforgeable. This interface lives in a package the bundle does not export
 * ({@code Private-Package}), so no code outside this bundle can implement it, whether it arrives as an OSGi service,
 * as a script lambda or through {@code EnergyAlgorithmRegistry}. What a contributed algorithm claims is normalised in
 * {@link EvaluationPass}; see {@link ConstraintLadder} for the ladder the rungs belong to.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
interface EngineOwnedAlgorithm {
}
