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
package org.openhab.core.energy;

import java.time.Duration;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.QuantityType;

/**
 * Anything that takes part in energy management: an {@link EnergyProvider} or an {@link EnergyConsumer}.
 * <p>
 * A participant is always attached to an existing Item, never to a Thing or a channel. That is the point of the
 * requirement: a "logical device" wired through HTTP, a rule or any binding participates on exactly the same terms as
 * a device with a Thing, and a device modelled only as Items needs no new hardware abstraction to join.
 * <p>
 * The type is sealed to the two roles so that an engine can switch over participants exhaustively. It carries no
 * behaviour and no Item access: resolving {@link #itemName()} to a state, and deciding <em>how</em> a participant was
 * declared, are both outside the model.
 * <p>
 * <strong>Identity is the name of the Item carrying the declaration</strong>, overridable by an explicit id in the
 * declaration itself. That is what makes two statements about one device - a user's item metadata and an add-on's
 * contribution - recognisable as describing the same participant, so that a second declaration of an identity
 * already present is a further statement about that participant rather than a second participant or an error.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public sealed interface EnergyParticipant permits EnergyProvider, EnergyConsumer {

    /**
     * The priority a participant carries when its declaration names none.
     * <p>
     * It is deliberately far above the small numbers sites actually type, so that a declared priority always beats an
     * undeclared one.
     */
    int DEFAULT_PRIORITY = 100;

    /**
     * Returns the stable identifier of this participant, unique across all participants of a site.
     *
     * @return the participant id
     */
    String id();

    /**
     * Returns the name of the Item this participant is attached to: the power reading for a provider, the control
     * Item for a consumer.
     *
     * @return the Item name
     */
    String itemName();

    /**
     * Returns the priority of this participant on the one site-wide scale, where <strong>a numerically lower value is
     * the better priority</strong> - served first when available power cannot serve everyone, and winning when two
     * decisions conflict.
     * <p>
     * Consumers and controllable providers share the scale and the {@link #DEFAULT_PRIORITY default}, so that
     * "is this battery's charging power reclaimable by that consumer?" is decidable from the cycle's own inputs by
     * comparing two numbers.
     *
     * @return the priority, lower is better
     */
    int priority();

    /**
     * Returns the actuation sink this participant's decisions are written through, overriding the site-wide one.
     * <p>
     * The component that turns decisions into device writes is chosen by <strong>naming it</strong> - site-wide in
     * configuration, per participant here - and never by service ranking, registration order or any other race
     * between installed components. This is the one deliberate exception to the precedence chain that resolves
     * every other statement about a participant: getting a ranked selection wrong moves hardware.
     *
     * @return the sink id, or {@code null} when this participant uses the site-wide sink
     */
    @Nullable
    String sinkId();

    /**
     * Returns how long a command to this participant may go unacknowledged before it lapses and the engine resumes
     * control of it, overriding the engine's own default for this participant alone.
     *
     * @return the window, or {@code null} when this participant declares none
     */
    @Nullable
    Duration ackWindow();

    /**
     * Returns how far a reported value may sit from the commanded one and still acknowledge it.
     * <p>
     * The band is an <strong>absolute quantity in the control Item's own dimension</strong> and never a fraction of
     * the commanded value: 0.01 A means the same thing at 6 A as it does at 32 A, which is what lets a device that
     * settles a milliamp low be told from one that ignored the command outright.
     *
     * @return the band, or {@code null} when this participant requires an exact match
     */
    @Nullable
    QuantityType<?> ackTolerance();

    /**
     * Returns how old a reading from this participant may be before the engine treats it as stale.
     * <p>
     * It is optional, and its absence does not mean a reading can never be stale: an unreadable state and the
     * {@code UNDEF} and {@code NULL} states count as stale whether or not an age is declared. The age exists so that
     * a reading which stopped moving without ever going undefined can be told from a fresh one, without the
     * framework inventing an ageing mechanism of its own.
     *
     * @return the maximum age, or {@code null} when this participant declares none
     */
    @Nullable
    Duration maxReadingAge();
}
