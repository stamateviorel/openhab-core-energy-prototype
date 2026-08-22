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
 * The engine spine: the core-owned runtime that turns algorithm proposals into (shadow) actuation.
 * <p>
 * One cycle is one snapshot: gather the algorithms, evaluate them all against the same
 * {@link org.openhab.core.energy.EnergyContext}, resolve conflicts deterministically, enforce the electrical-limit
 * floor, then gate on the master stop, on shadow mode and on the acknowledgement window before anything reaches an
 * {@link org.openhab.core.energy.ActuationSink}. Every guardrail applies to every algorithm's output alike.
 * <p>
 * Three of the open questions in {@code define-engine-contract/design.md} are represented here as seams with more
 * than one implementation - precedence (§5), the relationship between the master stop and shadow mode (§2), and
 * where acknowledgement handling lives (§3). None of them is resolved; the engine selects between the framed
 * options by configuration and says which one it picked in its log.
 * <p>
 * Null-safety follows the openhab-core convention: {@code @NonNullByDefault} sits on every type in this package
 * rather than on the package itself.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy.internal;
