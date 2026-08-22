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
 * The Item and persistence edge of the energy data planes: the half of the framework that touches openHAB's own data
 * surfaces, kept out of the engine bundle so that the engine can go on being structurally incapable of it.
 * <p>
 * Everything here is internal. What the rest of the framework sees is an
 * {@code org.openhab.core.energy.price.EnergyPriceSource} registered as an OSGi service; how it got its numbers is
 * this bundle's business alone.
 * <p>
 * Null-safety follows the openhab-core convention: {@code @NonNullByDefault} sits on every type in this package
 * rather than on the package itself.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy.series.internal;
