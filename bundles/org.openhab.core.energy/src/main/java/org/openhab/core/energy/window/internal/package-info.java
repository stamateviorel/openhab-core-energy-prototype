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
 * The implementations behind the shared window calculation's seams: the two selection strategies and the one costing
 * function.
 * <p>
 * The two strategies are not rivals - they answer different requests (a scattered set of slots against one
 * uninterrupted stretch) and, since shortfall was fixed, degrade identically. Each of them serves both directions of
 * {@link org.openhab.core.energy.window.SeriesSense} and both ends of a ranking, so "the cheapest three hours" and
 * "the three most expensive hours" are the same code with one flag.
 * <p>
 * There is exactly one costing function, deliberately. Contiguity, start granularity and shortfall are then the same
 * wherever a window is chosen.
 * <p>
 * Reach all of them through the factory methods on {@code SelectionStrategy} and {@code WindowCost} rather than
 * directly - this package is not exported.
 * <p>
 * Null-safety follows the openhab-core convention: {@code @NonNullByDefault} sits on every type in this package
 * rather than on the package itself.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy.window.internal;
