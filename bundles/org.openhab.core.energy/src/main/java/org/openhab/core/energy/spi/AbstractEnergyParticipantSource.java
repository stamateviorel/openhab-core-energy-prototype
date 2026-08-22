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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.common.registry.AbstractProvider;
import org.openhab.core.energy.EnergyParticipant;

/**
 * Base class for {@link EnergyParticipantSource} implementations.
 * <p>
 * It keeps the source's declarations in a map keyed by participant id and turns {@link #declare(EnergyParticipant)}
 * and {@link #withdraw(String)} into the provider events the registry listens to, so an implementation only has to
 * decide <em>what</em> it declares - not how to notify anyone about it. Re-declaring an identical participant is a
 * no-op, which keeps a source that rescans its input from flooding the registry with pointless updates.
 * <p>
 * It also keeps the set of identities this source has a declaration for and cannot read - see {@link #block(String)}
 * and owner decision D26 - which the registry reads through {@link #getBlockedParticipants()}. A source that reads
 * typed objects rather than text never blocks anything and never has to know the mechanism exists.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public abstract class AbstractEnergyParticipantSource extends AbstractProvider<ParticipantDeclaration>
        implements EnergyParticipantSource {

    private final String sourceId;
    private final DeclarationOrigin origin;
    private final int serviceRanking;
    private final Map<String, ParticipantDeclaration> declarations = new ConcurrentHashMap<>();

    /**
     * The identities this source has a declaration for that it cannot read. Owner decision D26: a malformed
     * declaration blocks its participant instead of quietly ceding it to the next statement down the chain.
     */
    private final Set<String> blocked = ConcurrentHashMap.newKeySet();

    /**
     * Creates a source registering at the OSGi default {@code service.ranking} of zero.
     *
     * @param sourceId the stable id of the source
     * @param origin the origin every declaration of this source carries
     * @throws IllegalArgumentException if the source id is blank
     */
    protected AbstractEnergyParticipantSource(String sourceId, DeclarationOrigin origin) {
        this(sourceId, origin, 0);
    }

    /**
     * Creates a source.
     *
     * @param sourceId the stable id of the source
     * @param origin the origin every declaration of this source carries
     * @param serviceRanking the {@code service.ranking} this source registers at, which breaks ties between two
     *            contributed statements about one participant
     * @throws IllegalArgumentException if the source id is blank
     */
    protected AbstractEnergyParticipantSource(String sourceId, DeclarationOrigin origin, int serviceRanking) {
        String trimmed = sourceId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        this.sourceId = trimmed;
        this.origin = origin;
        this.serviceRanking = serviceRanking;
    }

    @Override
    public String getSourceId() {
        return sourceId;
    }

    @Override
    public int getServiceRanking() {
        return serviceRanking;
    }

    @Override
    public DeclarationOrigin getOrigin() {
        return origin;
    }

    @Override
    public Set<String> getBlockedParticipants() {
        return Set.copyOf(blocked);
    }

    @Override
    public Collection<ParticipantDeclaration> getAll() {
        return List.copyOf(declarations.values());
    }

    /**
     * Returns this source's declaration of a participant.
     *
     * @param participantId the participant id
     * @return the declaration, or {@code null} if this source does not declare that participant
     */
    public @Nullable ParticipantDeclaration getDeclaration(String participantId) {
        return declarations.get(participantId);
    }

    /**
     * Declares a participant, replacing this source's previous declaration of the same participant id.
     *
     * @param participant the participant to declare
     * @return the resulting declaration
     */
    protected ParticipantDeclaration declare(EnergyParticipant participant) {
        ParticipantDeclaration declaration = new ParticipantDeclaration(sourceId, participant, origin, serviceRanking);
        ParticipantDeclaration previous = declarations.put(participant.id(), declaration);
        if (previous == null) {
            notifyListenersAboutAddedElement(declaration);
        } else if (!previous.equals(declaration)) {
            notifyListenersAboutUpdatedElement(previous, declaration);
        }
        return declaration;
    }

    /**
     * Withdraws this source's declaration of a participant.
     *
     * @param participantId the participant id
     * @return {@code true} if a declaration was withdrawn, {@code false} if this source did not declare it
     */
    protected boolean withdraw(String participantId) {
        ParticipantDeclaration removed = declarations.remove(participantId);
        if (removed == null) {
            return false;
        }
        notifyListenersAboutRemovedElement(removed);
        return true;
    }

    /**
     * Blocks a participant this source has a declaration for but cannot read, withdrawing whatever this source last
     * declared about it.
     * <p>
     * Blocking is not the same as going quiet. A withdrawn declaration lets the next statement down the precedence
     * chain become effective; a block leaves the participant out of the resolved view altogether, so that a typo in
     * the authoritative declaration degrades to "nothing happens" rather than handing the device to a contribution
     * its owner never chose. Source: owner decision D26 (2026-08-03,
     * {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}).
     *
     * @param participantId the identity the unreadable declaration claims
     * @return {@code true} if this source was not already blocking it
     */
    protected boolean block(String participantId) {
        withdraw(participantId);
        return blocked.add(participantId);
    }

    /**
     * Lifts a block, which is what correcting a declaration means.
     *
     * @param participantId the identity to stop blocking
     * @return {@code true} if this source was blocking it
     */
    protected boolean unblock(String participantId) {
        return blocked.remove(participantId);
    }

    /**
     * Withdraws every declaration of this source and lifts every block it holds.
     *
     * @return the number of withdrawn declarations
     */
    protected int withdrawAll() {
        blocked.clear();
        int count = 0;
        for (String participantId : List.copyOf(declarations.keySet())) {
            if (withdraw(participantId)) {
                count++;
            }
        }
        return count;
    }
}
