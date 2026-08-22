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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.common.registry.Identifiable;
import org.openhab.core.energy.EnergyParticipant;

/**
 * One statement, by one source, that a given {@link EnergyParticipant} exists.
 * <p>
 * A declaration is <em>not</em> the participant: several sources may declare the same participant id, and the
 * {@link EnergyParticipantRegistry} keeps all of those statements side by side and resolves which one is effective.
 * That is what makes "explicit declaration wins" expressible without any source having to know about the others.
 * <p>
 * The registry key is therefore the pair {@code sourceId + participantId} and not the participant id alone -
 * see {@link #getUID()}. Callers that want the participant id use {@link #participantId()}.
 *
 * The declaration carries the declaring source's {@code service.ranking} rather than leaving the registry to look
 * it up, so that resolving an identity's statements is a pure function of the statements themselves. Core's
 * {@link org.openhab.core.common.registry.AbstractRegistry} hands a registry the provider object without its
 * {@code ServiceReference}, so the ranking has to travel with the statement; carrying it here also means the
 * outcome cannot depend on which providers happen to be bound at the moment the comparison runs.
 *
 * @param sourceId the id of the {@link EnergyParticipantSource} that made this statement
 * @param participant the declared participant
 * @param origin whether the statement is a user's own or a contribution made on their behalf
 * @param serviceRanking the {@code service.ranking} of the declaring source, breaking ties between two contributed
 *            statements about one participant
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record ParticipantDeclaration(String sourceId, EnergyParticipant participant, DeclarationOrigin origin,
        int serviceRanking) implements Identifiable<String> {

    /**
     * Separates the source id from the participant id in {@link #getUID()}.
     */
    public static final String UID_SEPARATOR = "::";

    /**
     * Validates the declaration.
     *
     * @throws IllegalArgumentException if the source id is blank
     */
    public ParticipantDeclaration {
        String trimmed = sourceId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        sourceId = trimmed;
    }

    /**
     * Creates a declaration by a source that registers at the default {@code service.ranking} of zero.
     *
     * @param sourceId the id of the source that made this statement
     * @param participant the declared participant
     * @param origin whether the statement is a user's own or a contribution made on their behalf
     * @throws IllegalArgumentException if the source id is blank
     */
    public ParticipantDeclaration(String sourceId, EnergyParticipant participant, DeclarationOrigin origin) {
        this(sourceId, participant, origin, 0);
    }

    /**
     * Builds the registry key of a declaration without having to construct one.
     *
     * @param sourceId the id of the declaring source
     * @param participantId the declared participant id
     * @return the registry key
     */
    public static String uid(String sourceId, String participantId) {
        return sourceId + UID_SEPARATOR + participantId;
    }

    @Override
    public String getUID() {
        return uid(sourceId, participant.id());
    }

    /**
     * Returns the id of the declared participant.
     * <p>
     * This is the id the engine reasons about; it is shared by every declaration of the same participant, whichever
     * source made them.
     *
     * @return the participant id
     */
    public String participantId() {
        return participant.id();
    }
}
