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
 * The selectable optimization objective: what "best" means when a load is placed, expressed as a ranking over a
 * series rather than as the constant assumption that best means cheapest.
 * <p>
 * <strong>An objective is a pure function.</strong> {@link org.openhab.core.energy.objective.OptimizationObjective}
 * turns the series available this planning run into one ranking series, and the existing selection strategies place
 * loads on that ranking exactly as they already place them on a price series. Nothing in this package reads a clock,
 * an Item or a persistence service, and nothing in it writes anything: the ranking is a value, and whoever wants it
 * published publishes it in a bundle that is allowed to.
 * <p>
 * <strong>Sense, not price.</strong> Every series an objective ranks is a
 * {@link org.openhab.core.energy.window.SlotSeries}, which carries a
 * {@link org.openhab.core.energy.window.SeriesSense} saying whether a low value or a high value is the good one. One
 * calculation therefore serves a price (low wins), a CO<sub>2</sub> intensity (low wins), a renewable share (high
 * wins) and a surplus forecast (high wins) - which is what lets the carbon objective use the shared window
 * calculation "exactly as the cost objective uses the price series" without a second copy of the arithmetic, and it
 * is the answer this package gives to the corpus's objective-neutral naming pass
 * ({@code define-optimization-objectives} design.md §3).
 * <p>
 * <strong>Carbon data arrives on the same terms a price does.</strong>
 * {@link org.openhab.core.energy.objective.CarbonSeriesSource} is a whiteboard SPI answering with a
 * {@link org.openhab.core.energy.objective.CarbonSeries} - the same slot geometry every other series in the framework
 * uses, plus the source, the unit and the generation time. Core ships no carbon source of its own: market- and
 * region-specific data belongs to add-ons.
 * <p>
 * Null-safety follows the openhab-core convention: {@code @NonNullByDefault} sits on every type in this package
 * rather than on the package itself.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy.objective;
