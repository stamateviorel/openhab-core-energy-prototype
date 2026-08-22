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
/**
 * The implementations behind the level classifier's seams: three level derivations and the surplus escalation
 * policies. The two window strategies and the shared window costing moved to
 * {@link org.openhab.core.energy.window.internal} when the price plane became a second caller.
 * <p>
 * The two derivations are a pair because the corpus frames both readings and has not chosen between them. The
 * escalation policies are no longer a pair either: {@code GradedSurplusEscalation} is the
 * one shape core ships, and {@code UnconfiguredSurplusEscalation} is not a rival policy but what a site that has
 * declared no threshold gets, existing as a type of its own only so that it can report itself as unconfigured.
 * <p>
 * Reach all of them through the factory methods on {@code LevelDerivation} and {@code SurplusEscalationPolicy}
 * rather than directly - this package is not exported.
 * <p>
 * Null-safety follows the openhab-core convention: {@code @NonNullByDefault} sits on every type in this package
 * rather than on the package itself.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy.level.internal;
