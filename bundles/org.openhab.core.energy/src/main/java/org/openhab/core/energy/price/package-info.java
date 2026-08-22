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
 * <strong>The price data plane</strong>: energy prices as future-timestamped series, the composition of an effective
 * consumer price out of components from different sources, the generic configurable grid-price provider's arithmetic,
 * and feed-in pricing.
 * <p>
 * This is the founding need of openhab-core issue #3478 - "future energy prices from many sources behind one
 * interface, with calculations implemented once". The calculations themselves are not here: they are
 * {@link org.openhab.core.energy.window}, shared with every other plane. What is here is the <em>meaning</em> of the
 * numbers those calculations rank: which currency, per what unit of energy, in which market's time zone, and in which
 * direction the kilowatt-hour is flowing.
 * <p>
 * <strong>Everything in this package is pure.</strong> No Item is read or written, no persistence service is called,
 * no HTTP request is made, no clock is consulted. A price series arrives as a value, through
 * {@link org.openhab.core.energy.price.EnergyPriceSource}, from whoever fetched it - a binding, a script, or the
 * Item-backed reader in the companion {@code org.openhab.core.energy.series} bundle. That split is the same one
 * decision D23 drew for the write side: <em>the value is computed where nothing can be written, and the touching of
 * Items happens where that is the point</em>. It is what keeps this bundle structurally incapable of writing to an
 * Item, which is proven by {@code ShadowModeDemonstrationTest}'s five structural witnesses.
 * <p>
 * <strong>Prices are signed and never clamped.</strong> {@code Number:EnergyPrice} is a signed quantity, a negative
 * spot price is routine in the markets this plane serves, and a fixed component can be more than cancelled by it
 * (owner decision D17, row EL-13). Nothing here assumes a price is positive, and a day whose every price is below
 * zero ranks exactly as any other day does.
 * <p>
 * Null-safety follows the openhab-core convention: {@code @NonNullByDefault} sits on every type in this package
 * rather than on the package itself.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy.price;
