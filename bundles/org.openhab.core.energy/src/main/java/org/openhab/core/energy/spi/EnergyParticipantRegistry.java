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
package org.openhab.core.energy.spi;

import java.util.Collection;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.common.registry.Registry;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.energy.EnergyProvider;

/**
 * The single place the engine asks which energy participants exist.
 * <p>
 * The registry aggregates every {@link EnergyParticipantSource} that is currently registered, so which declaration
 * mechanism produced a participant never reaches the engine. Sources come and go while the framework runs and the
 * registry follows them: a participant appears as soon as its source declares it and disappears with its source.
 * <p>
 * Two levels are visible here, and the difference matters:
 * <ul>
 * <li>the <strong>declarations</strong>, reached through the inherited {@link Registry} methods - every statement of
 * every source, including the ones that lose to a more authoritative source. This is the level to look at when
 * asking "why is this device configured the way it is";</li>
 * <li>the <strong>effective participants</strong>, reached through {@link #getParticipants()} and friends - one
 * participant per id, after disabled sources have been dropped and conflicts resolved. This is the level the engine
 * plans on.</li>
 * </ul>
 * Resolution is deterministic and configurable: the configured source precedence decides first, then the
 * {@link DeclarationOrigin#rank()} (an explicit declaration beating a contributed one), then the source id as a
 * final tie-break so the outcome never depends on registration or iteration order. When the winning source
 * disappears, the runner-up simply becomes effective - a contributor going away degrades the site to its remaining
 * declarations rather than to nothing.
 * <p>
 * There is no managed provider: declarations are made by sources, not stored by the registry, so the inherited
 * {@link Registry#add}, {@link Registry#update} and {@link Registry#remove} methods throw
 * {@link IllegalStateException}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface EnergyParticipantRegistry extends Registry<ParticipantDeclaration, String> {

    /**
     * Returns the effective participants, one per participant id.
     *
     * @return the effective participants
     */
    Collection<EnergyParticipant> getParticipants();

    /**
     * Returns the effective participant with the given id.
     *
     * @param participantId the participant id
     * @return the participant, or {@code null} if no enabled source declares it
     */
    @Nullable
    EnergyParticipant getParticipant(String participantId);

    /**
     * Returns the effective providers.
     *
     * @return the effective providers
     */
    Collection<EnergyProvider> getProviders();

    /**
     * Returns the effective consumers in {@link EnergyConsumer#PRIORITY_ORDER}.
     * <p>
     * The order is total, so two evaluation cycles with the same declarations always allocate in the same order.
     *
     * @return the effective consumers, best priority first
     */
    List<EnergyConsumer> getConsumers();

    /**
     * Returns the declaration a participant is currently taken from.
     *
     * @param participantId the participant id
     * @return the winning declaration, or {@code null} if no enabled source declares that participant
     */
    @Nullable
    ParticipantDeclaration getEffectiveDeclaration(String participantId);

    /**
     * Returns every declaration of a participant, from the most to the least authoritative.
     * <p>
     * Declarations of disabled sources are included: this is the diagnostic view, showing what a site has stated
     * about a participant and which statement is currently winning.
     *
     * @param participantId the participant id
     * @return the declarations, most authoritative first, empty if the participant is not declared at all
     */
    List<ParticipantDeclaration> getDeclarations(String participantId);
}
