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
 * <strong>The shared window calculations, implemented once.</strong> This is the openhab-core issue #3478 founding
 * requirement in code: "calculations implemented once", reachable from the engine, from rules and from scripts, so
 * that two conforming callers cost the same dishwasher identically.
 * <p>
 * What lives here is the slot geometry ({@link org.openhab.core.energy.window.SlotSeries},
 * {@link org.openhab.core.energy.window.Slot}), the request and answer shapes
 * ({@link org.openhab.core.energy.window.WindowRequest}, {@link org.openhab.core.energy.window.WindowSelection},
 * {@link org.openhab.core.energy.window.SlotSelection}), the one costing function
 * ({@link org.openhab.core.energy.window.WindowCost} with its {@link org.openhab.core.energy.window.CostWeights}) and
 * the selection strategies ({@link org.openhab.core.energy.window.SelectionStrategy}).
 * <p>
 * <strong>Why it is a package of its own, and why it is in this bundle.</strong> It was
 * {@code org.openhab.core.energy.level} while the level plane was the only caller, and {@code define-price-providers}
 * task 2.3 asks which bundle should ship it now that the price plane calls it too. The answer is: this one. The level
 * plane calls it on every derivation and {@code define-participant-model} puts the level plane in core, so moving the
 * calculation into a price bundle would invert the dependency and leave the level plane unusable unless a price
 * bundle were installed. It is pure arithmetic, and the bundle it sits in is the one that provably never writes.
 * <p>
 * <strong>Nothing here is about prices.</strong> A {@link org.openhab.core.energy.window.Slot} carries a bare
 * {@code double} whose meaning, unit and polarity belong to the typed series that wraps it -
 * {@code org.openhab.core.energy.price.EnergyPriceSeries} for a price, and the forecast and carbon series for the
 * rest. The one thing this package does need to know is which direction is better, and that arrives as
 * {@link org.openhab.core.energy.window.SeriesSense} on the series.
 * <p>
 * Nothing here touches OSGi, the clock, an Item or a logger. Time is always an argument, never a lookup.
 * <p>
 * Null-safety follows the openhab-core convention: {@code @NonNullByDefault} sits on every type in this package
 * rather than on the package itself.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy.window;
