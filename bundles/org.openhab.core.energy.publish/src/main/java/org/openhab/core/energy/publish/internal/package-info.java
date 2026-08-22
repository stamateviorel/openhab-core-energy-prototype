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
 * The publishing component's machinery: a provider that supplies the two Items, and a subscriber that keeps them up
 * to date from the framework's events.
 * <p>
 * <strong>Unlike {@code org.openhab.core.energy.internal}, code in this package may - and does - write Items.</strong>
 * It writes exactly two, both of which it provides itself, and it writes them by posting a state update on the event
 * bus rather than by reaching into an Item. It commands nothing, so no device moves because of anything here: the
 * distinction between reporting a state and steering a device is the same one that lets the framework next door
 * publish events while remaining provably unable to touch hardware.
 * <p>
 * Null-safety follows the openhab-core convention: {@code @NonNullByDefault} sits on every type in this package
 * rather than on the package itself.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy.publish.internal;
