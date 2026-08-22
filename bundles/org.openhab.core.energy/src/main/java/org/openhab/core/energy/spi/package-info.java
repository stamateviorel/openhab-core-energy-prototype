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
 * The declaration plane: how participants get into the system, and who wins when two sources disagree.
 * <p>
 * This package exists because the question it answers is <strong>open</strong>. The corpus lists candidate
 * declaration mechanisms - item metadata, a description-provider SPI for add-ons, Thing/channel annotation,
 * dedicated EMS add-on types - and leaves the choice to the maintainers. Everything here is arranged so that the
 * choice can still be made:
 * <ul>
 * <li>{@link org.openhab.core.energy.spi.EnergyParticipantSource} is the mechanism-neutral seam; both candidate
 * mechanisms are implementations of it, and a third would be a third implementation;</li>
 * <li>{@link org.openhab.core.energy.spi.ParticipantDeclaration} keeps <em>who said what</em> attached to the
 * statement, so nothing downstream has to guess where a participant came from;</li>
 * <li>{@link org.openhab.core.energy.spi.EnergyParticipantRegistry} aggregates the sources and resolves conflicts
 * by configured precedence - the authority question is a configuration value, not a line of code.</li>
 * </ul>
 * If the maintainers settle on a single mechanism, the losing implementation is deleted and this package shrinks to
 * the registry; nothing above it changes. That is the point of the arrangement.
 * <p>
 * The package deliberately carries no {@code @NonNullByDefault} package annotation: openhab-core annotates every
 * type instead, and adding the package default makes the compiler report the type-level annotations as redundant.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy.spi;
