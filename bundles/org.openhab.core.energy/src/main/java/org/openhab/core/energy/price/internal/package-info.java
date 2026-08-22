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
 * The implementations behind the price plane's seams: the three series alignments, and the registry that resolves one
 * source per price role.
 * <p>
 * The three alignments are a genuine trio rather than one implementation and two rivals. Nothing in the corpus says
 * what adding two series of differing geometry means, and all three readings are defensible, so all three are
 * implemented and the choice is configuration - which is this corpus' own rule for an undecided question.
 * <p>
 * Reach them through the factory methods on {@code SeriesAlignment} rather than directly; this package is not
 * exported.
 * <p>
 * Null-safety follows the openhab-core convention: {@code @NonNullByDefault} sits on every type in this package
 * rather than on the package itself.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy.price.internal;
