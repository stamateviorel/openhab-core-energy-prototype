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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.EnergyParticipant;

/**
 * The programmatic declaration mechanism: describe a participant in code instead of in item metadata.
 * <p>
 * This is the second of the two candidate mechanisms kept alive behind {@link EnergyParticipantSource}. It is a
 * plain OSGi service rather than an interface to implement, because the corpus expects contributions from two very
 * different kinds of caller: add-ons, which could equally well register their own {@link EnergyParticipantSource},
 * and user scripts, which cannot register services conveniently but can look one up and call it. Both arrive in the
 * registry as ordinary declarations - "the engine treats it exactly like an add-on-contributed one".
 * <p>
 * Every call names a {@code contributorId}. Contributions are owned by their contributor: only the contributor that
 * declared a participant may change or withdraw it, and {@link #withdrawAll(String)} lets a script that is being
 * reloaded, or an add-on that is shutting down, take exactly its own contributions out again without touching
 * anyone else's.
 * <p>
 * A binding that wants full control over its own lifecycle - so that its participants disappear the moment the
 * bundle stops, without any explicit withdrawal - should instead extend {@link AbstractEnergyParticipantSource} and
 * register itself as an {@link EnergyParticipantSource} service.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface EnergyParticipantContributor {

    /**
     * Contributes a participant, replacing this contributor's previous contribution of the same participant id.
     * <p>
     * A participant id another contributor already describes is <strong>not</strong> refused: a second statement
     * about one participant is a further statement about it, never a second participant and never an error. Both
     * statements are kept, and the one that takes effect is chosen deterministically rather than by who called
     * first - see the implementation for the rule - so a contributor whose statement is not the effective one now
     * becomes effective by itself if the other withdraws.
     *
     * @param contributorId identifies the contributing script or add-on
     * @param participant the participant to contribute
     * @return {@code true} if this contributor's statement is the effective one, {@code false} if another
     *         contributor's statement takes precedence - in which case this one is still recorded
     * @throws IllegalArgumentException if the contributor id is blank, which is programmer error rather than a
     *             runtime condition
     */
    boolean declare(String contributorId, EnergyParticipant participant);

    /**
     * Withdraws this contributor's statement about a participant.
     * <p>
     * Where another contributor also describes it, that statement stays and takes effect; the participant only
     * leaves the site when the last statement about it is withdrawn.
     *
     * @param contributorId identifies the contributing script or add-on
     * @param participantId the id of the participant to withdraw
     * @return {@code true} if a statement of this contributor was withdrawn, {@code false} if it had made none
     */
    boolean withdraw(String contributorId, String participantId);

    /**
     * Withdraws every participant of a contributor.
     *
     * @param contributorId identifies the contributing script or add-on
     * @return the number of withdrawn participants
     */
    int withdrawAll(String contributorId);

    /**
     * Returns the participants currently contributed by a contributor.
     *
     * @param contributorId identifies the contributing script or add-on
     * @return the contributed participants, empty if the contributor has none
     */
    Collection<EnergyParticipant> getContributions(String contributorId);
}
