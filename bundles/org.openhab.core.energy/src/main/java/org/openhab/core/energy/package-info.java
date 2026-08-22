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
 * The energy management participant model: the pure data types that describe which existing Items take part in energy
 * management and how they may be steered.
 * <p>
 * Everything in this package is immutable, free of OSGi, I/O and Item access, and therefore trivially unit-testable.
 * How a participant gets <em>declared</em> (Item metadata, a description-provider SPI, an add-on type, ...) is
 * deliberately not modelled here: that is an open maintainer decision, so the model stays mechanism-neutral.
 *
 * Null-safety follows the openhab-core convention: {@code @NonNullByDefault} sits on every type in this package
 * rather than on the package itself. Declaring it here as well is legal but makes the compiler flag every class as a
 * redundant nullness default, and openhab-core carries no {@code package-info.java} with that annotation anywhere in
 * the tree.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy;
