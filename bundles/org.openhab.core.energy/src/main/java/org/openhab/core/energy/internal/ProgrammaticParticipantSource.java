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
package org.openhab.core.energy.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.energy.spi.AbstractEnergyParticipantSource;
import org.openhab.core.energy.spi.DeclarationOrigin;
import org.openhab.core.energy.spi.EnergyParticipantContributor;
import org.openhab.core.energy.spi.EnergyParticipantSource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Declaration mechanism (b): participants described programmatically, by an add-on or a user script.
 * <p>
 * One source carries all such contributions, each tagged with the {@code contributorId} that made it. That keeps
 * "unregistered when the contributor goes away" a local operation - {@link #withdrawAll(String)} takes out exactly
 * one contributor's participants and leaves every other contributor untouched - without every script having to
 * register an OSGi service of its own.
 * <p>
 * <strong>Two contributors describing one participant is not an error and neither statement is dropped.</strong>
 * Both are kept side by side and the effective one is chosen by <strong>contributor id ascending</strong>, so the
 * outcome is a function of what was said and not of who said it first: registering the two in the opposite order
 * after a restart produces the same participant, and a contributor that leaves and comes back restores the outcome
 * it had before. When the effective contributor withdraws, the next statement about that participant becomes
 * effective by itself rather than the participant disappearing.
 * <p>
 * The contributor id is a <strong>stand-in for the {@code service.ranking}</strong> the precedence chain names, and
 * it is one deliberately: every contribution made through this service shares this source's single ranking, and a
 * user script has no ranking of its own to register at. A contributor that needs a real one should extend
 * {@link AbstractEnergyParticipantSource} and register as its own {@link EnergyParticipantSource}, at which point
 * the registry's chain applies to it in full. Ascending is the same direction {@code EnergyConsumer}'s priority
 * tie-break takes, so the corpus has one habit rather than two.
 * <p>
 * Nothing here holds a lock while it notifies the registry. The statements are held in the atomic maps of a
 * {@link ConcurrentHashMap}, so a listener that calls straight back into this source cannot deadlock against it.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(immediate = true, service = { EnergyParticipantSource.class, EnergyParticipantContributor.class })
public class ProgrammaticParticipantSource extends AbstractEnergyParticipantSource
        implements EnergyParticipantContributor {

    /**
     * The id under which this source is enabled and ordered in the registry configuration.
     */
    public static final String SOURCE_ID = "programmatic";

    private final Logger logger = LoggerFactory.getLogger(ProgrammaticParticipantSource.class);

    /**
     * Every statement made about a participant, by participant id and then by contributor id ascending, so that the
     * first entry of the inner map is the effective one.
     */
    private final Map<String, NavigableMap<String, EnergyParticipant>> statementsByParticipantId = new ConcurrentHashMap<>();

    /**
     * Creates the source.
     */
    public ProgrammaticParticipantSource() {
        super(SOURCE_ID, DeclarationOrigin.CONTRIBUTED);
    }

    /**
     * Withdraws every contribution, so that stopping the framework does not leave participants behind.
     */
    @Deactivate
    protected void deactivate() {
        withdrawAll();
        statementsByParticipantId.clear();
    }

    @Override
    public boolean declare(String contributorId, EnergyParticipant participant) {
        String contributor = requireContributorId(contributorId);
        statementsByParticipantId.computeIfAbsent(participant.id(), id -> new ConcurrentSkipListMap<>())
                .put(contributor, participant);
        logger.debug("Contributor '{}' declares energy participant '{}'", contributor, participant.id());
        return publishEffective(participant.id()).filter(contributor::equals).isPresent();
    }

    @Override
    public boolean withdraw(String contributorId, String participantId) {
        String contributor = requireContributorId(contributorId);
        NavigableMap<String, EnergyParticipant> statements = statementsByParticipantId.get(participantId);
        if (statements == null || statements.remove(contributor) == null) {
            return false;
        }
        if (statements.isEmpty()) {
            statementsByParticipantId.remove(participantId);
            return withdraw(participantId);
        }
        publishEffective(participantId);
        return true;
    }

    @Override
    public int withdrawAll(String contributorId) {
        String contributor = requireContributorId(contributorId);
        int count = 0;
        for (String participantId : List.copyOf(statementsByParticipantId.keySet())) {
            if (withdraw(contributor, participantId)) {
                count++;
            }
        }
        logger.debug("Contributor '{}' withdrew {} energy participants", contributor, count);
        return count;
    }

    @Override
    public Collection<EnergyParticipant> getContributions(String contributorId) {
        String contributor = requireContributorId(contributorId);
        Collection<EnergyParticipant> contributions = new ArrayList<>();
        for (NavigableMap<String, EnergyParticipant> statements : statementsByParticipantId.values()) {
            EnergyParticipant contributed = statements.get(contributor);
            if (contributed != null) {
                contributions.add(contributed);
            }
        }
        return contributions;
    }

    /**
     * Returns the contributor whose statement about a participant is the effective one.
     *
     * @param participantId the participant id
     * @return the contributor id, or {@code null} if this source carries no statement about that participant
     */
    public @Nullable String getContributor(String participantId) {
        NavigableMap<String, EnergyParticipant> statements = statementsByParticipantId.get(participantId);
        return statements == null || statements.isEmpty() ? null : statements.firstKey();
    }

    /**
     * Returns every contributor that has made a statement about a participant, effective one first.
     *
     * @param participantId the participant id
     * @return the contributor ids, ascending, empty if this source carries no statement about that participant
     */
    public List<String> getContributors(String participantId) {
        NavigableMap<String, EnergyParticipant> statements = statementsByParticipantId.get(participantId);
        return statements == null ? List.of() : List.copyOf(statements.keySet());
    }

    /**
     * Publishes the effective statement about a participant to the registry.
     *
     * @param participantId the participant id
     * @return the contributor whose statement is effective, or {@link Optional#empty()} if there is none left
     */
    private Optional<String> publishEffective(String participantId) {
        NavigableMap<String, EnergyParticipant> statements = statementsByParticipantId.get(participantId);
        if (statements == null || statements.isEmpty()) {
            return Optional.empty();
        }
        Map.Entry<String, EnergyParticipant> effective = statements.firstEntry();
        declare(effective.getValue());
        return Optional.of(effective.getKey());
    }

    private static String requireContributorId(String contributorId) {
        String trimmed = contributorId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("contributorId must not be blank");
        }
        return trimmed;
    }
}
