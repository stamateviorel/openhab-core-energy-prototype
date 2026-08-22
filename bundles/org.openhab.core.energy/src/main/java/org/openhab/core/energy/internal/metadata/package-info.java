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
 * Declaration mechanism (a): participants described in {@code energy:} Item metadata.
 * <p>
 * This is one of the two candidate mechanisms the corpus frames and does not choose between, and it is deliberately
 * split in two so that the parsing can be judged separately from the plumbing:
 * {@link org.openhab.core.energy.internal.metadata.EnergyMetadataParser} is a pure function from a metadata value
 * to a participant, and {@link org.openhab.core.energy.internal.metadata.MetadataParticipantSource} is the OSGi
 * component that watches the {@code MetadataRegistry} and publishes the results as declarations.
 * <p>
 * If the maintainers settle on the description-provider SPI instead, this package is deleted and nothing above
 * {@link org.openhab.core.energy.spi.EnergyParticipantSource} changes.
 * <p>
 * The key vocabulary implemented here has <strong>no specification behind it</strong>: the corpus names the
 * {@code energy:} namespace but defines no key, no value grammar and no unit convention. Every key is a prototype
 * invention pending a normative schema, which is recorded as an ambiguity rather than presented as a contract.
 * <p>
 * Null-safety follows the openhab-core convention: {@code @NonNullByDefault} sits on every type in this package
 * rather than on the package itself.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy.internal.metadata;
