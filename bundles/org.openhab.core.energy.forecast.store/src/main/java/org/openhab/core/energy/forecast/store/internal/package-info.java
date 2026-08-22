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
 * The components of the forecast storage bundle: the layered prediction store over a modifiable persistence service,
 * the Item-backed sources it registers, and the derived-demand component.
 * <p>
 * Nothing outside this bundle may depend on these types; the surface is
 * {@link org.openhab.core.energy.forecast.store.LayeredPredictionStore}.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy.forecast.store.internal;
