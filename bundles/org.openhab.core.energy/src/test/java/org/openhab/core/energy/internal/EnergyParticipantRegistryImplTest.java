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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.core.common.registry.RegistryChangeListener;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.energy.EnergyProvider;
import org.openhab.core.energy.ProviderRole;
import org.openhab.core.energy.SimpleProfile;
import org.openhab.core.energy.spi.AbstractEnergyParticipantSource;
import org.openhab.core.energy.spi.DeclarationOrigin;
import org.openhab.core.energy.spi.ParticipantDeclaration;

/**
 * Tests the aggregation seam: several declaration mechanisms coexisting, a configurable answer to which of them is
 * authoritative, and a site that keeps working when one of them goes away.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EnergyParticipantRegistryImplTest {

    private static final EnergyConsumer HEATING = EnergyConsumer.of("heating", "Heating_Switch", SimpleProfile.plain(),
            1);
    private static final EnergyConsumer BOILER = EnergyConsumer.of("boiler", "Boiler_Switch", SimpleProfile.plain(), 2);
    private static final EnergyConsumer HEATING_AS_CONTRIBUTED = EnergyConsumer.of("heating", "Heating_Switch",
            SimpleProfile.plain(), 9);
    private static final EnergyProvider GRID = EnergyProvider.of("grid", "Grid_Power", ProviderRole.GRID);

    private @NonNullByDefault({}) EnergyParticipantRegistryImpl registry;
    private @NonNullByDefault({}) TestSource explicitSource;
    private @NonNullByDefault({}) TestSource contributedSource;

    private final List<ParticipantDeclaration> added = new ArrayList<>();
    private final List<ParticipantDeclaration> removed = new ArrayList<>();

    /**
     * Stands in for any source a maintainer might pick: it is the mechanism-neutral SPI and nothing else.
     */
    private static final class TestSource extends AbstractEnergyParticipantSource {

        TestSource(String sourceId, DeclarationOrigin origin) {
            super(sourceId, origin);
        }

        TestSource(String sourceId, DeclarationOrigin origin, int serviceRanking) {
            super(sourceId, origin, serviceRanking);
        }

        @Override
        public ParticipantDeclaration declare(EnergyParticipant participant) {
            return super.declare(participant);
        }

        @Override
        public boolean withdraw(String participantId) {
            return super.withdraw(participantId);
        }

        @Override
        public boolean block(String participantId) {
            return super.block(participantId);
        }

        @Override
        public boolean unblock(String participantId) {
            return super.unblock(participantId);
        }
    }

    private static Map<String, @Nullable Object> configuration(String key, Object value) {
        Map<String, @Nullable Object> configuration = new HashMap<>();
        configuration.put(key, value);
        return configuration;
    }

    private static List<String> idsOf(Iterable<? extends EnergyParticipant> participants) {
        List<String> ids = new ArrayList<>();
        participants.forEach(participant -> ids.add(participant.id()));
        return ids;
    }

    @BeforeEach
    public void setup() {
        registry = new EnergyParticipantRegistryImpl(Map.of());
        registry.addRegistryChangeListener(new RegistryChangeListener<>() {
            @Override
            public void added(ParticipantDeclaration element) {
                added.add(element);
            }

            @Override
            public void removed(ParticipantDeclaration element) {
                removed.add(element);
            }

            @Override
            public void updated(ParticipantDeclaration oldElement, ParticipantDeclaration element) {
            }
        });
        explicitSource = new TestSource("metadata", DeclarationOrigin.EXPLICIT);
        contributedSource = new TestSource("binding", DeclarationOrigin.CONTRIBUTED);
    }

    /**
     * Requirement: Runtime contribution - a source that appears while the framework runs brings its participants
     * with it, with no core change and no restart.
     */
    @Test
    public void participantsOfSeveralSourcesCoexist() {
        explicitSource.declare(HEATING);
        contributedSource.declare(GRID);
        registry.addProvider(explicitSource);
        registry.addProvider(contributedSource);

        assertThat(idsOf(registry.getParticipants()), containsInAnyOrder("heating", "grid"));
        assertThat(idsOf(registry.getProviders()), contains("grid"));
        assertThat(idsOf(registry.getConsumers()), contains("heating"));
    }

    /**
     * Scenario: Script-contributed consumer - a declaration made after the source is already registered is visible
     * immediately, exactly like one that was there from the start.
     */
    @Test
    public void aDeclarationMadeAfterRegistrationIsVisibleImmediately() {
        registry.addProvider(contributedSource);
        assertThat(registry.getParticipants(), is(empty()));

        contributedSource.declare(BOILER);

        assertThat(idsOf(registry.getParticipants()), contains("boiler"));
        assertThat(added, hasSize(1));
    }

    /**
     * Requirement: Deterministic resolution between contributors - explicit metadata beats a contributed
     * declaration, which is kept rather than dropped.
     */
    @Test
    public void anExplicitDeclarationWinsOverAContributedOne() {
        explicitSource.declare(HEATING);
        contributedSource.declare(HEATING_AS_CONTRIBUTED);
        registry.addProvider(explicitSource);
        registry.addProvider(contributedSource);

        assertThat(registry.getAll(), hasSize(2));
        assertThat(idsOf(registry.getParticipants()), contains("heating"));
        assertThat(registry.getParticipant("heating"), is(HEATING));
        assertThat(Objects.requireNonNull(registry.getEffectiveDeclaration("heating")).sourceId(), is("metadata"));
        assertThat(registry.getDeclarations("heating").stream().map(ParticipantDeclaration::sourceId).toList(),
                contains("metadata", "binding"));
    }

    /**
     * Scenario: A second declaration is a further statement, not a second participant - the site keeps exactly one
     * participant, the metadata wins wherever the two disagree, and no error is raised.
     */
    @Test
    public void aSecondDeclarationOfOneIdentityIsAFurtherStatementAndNeverAnError() {
        contributedSource.declare(HEATING_AS_CONTRIBUTED);
        registry.addProvider(contributedSource);
        assertThat(registry.getParticipant("heating"), is(HEATING_AS_CONTRIBUTED));

        explicitSource.declare(HEATING);
        registry.addProvider(explicitSource);

        assertThat("one participant, two statements", idsOf(registry.getParticipants()), contains("heating"));
        assertThat(registry.getDeclarations("heating"), hasSize(2));
        assertThat(registry.getParticipant("heating"), is(HEATING));
        assertThat("neither statement was rejected", removed, is(empty()));
    }

    /**
     * Scenario: An add-on and a user script declare the same participant - two contributed statements break their
     * tie on {@code service.ranking}, so registering them in either order produces the same participant.
     */
    @Test
    public void tiesBetweenContributedDeclarationsBreakOnServiceRanking() {
        TestSource lowRanked = new TestSource("aaa-addon", DeclarationOrigin.CONTRIBUTED, 5);
        TestSource highRanked = new TestSource("zzz-script", DeclarationOrigin.CONTRIBUTED, 50);
        lowRanked.declare(HEATING_AS_CONTRIBUTED);
        highRanked.declare(HEATING);

        registry.addProvider(lowRanked);
        registry.addProvider(highRanked);
        assertThat(Objects.requireNonNull(registry.getEffectiveDeclaration("heating")).sourceId(), is("zzz-script"));

        // the same two sources, registered the other way round on a restart
        EnergyParticipantRegistryImpl restarted = new EnergyParticipantRegistryImpl(Map.of());
        restarted.addProvider(highRanked);
        restarted.addProvider(lowRanked);

        assertThat("registration order is not load-bearing",
                Objects.requireNonNull(restarted.getEffectiveDeclaration("heating")).sourceId(), is("zzz-script"));
    }

    /**
     * The ranking never lets a contributed declaration outrank explicit metadata: origin is the first term of the
     * chain, and the chain is fixed rather than configured.
     */
    @Test
    public void serviceRankingNeverLiftsAContributionOverExplicitMetadata() {
        TestSource loudContributor = new TestSource("binding", DeclarationOrigin.CONTRIBUTED, 1000);
        loudContributor.declare(HEATING_AS_CONTRIBUTED);
        explicitSource.declare(HEATING);
        registry.addProvider(loudContributor);
        registry.addProvider(explicitSource);

        assertThat(Objects.requireNonNull(registry.getEffectiveDeclaration("heating")).sourceId(), is("metadata"));
        assertThat(registry.getParticipant("heating"), is(HEATING));
    }

    /**
     * Scenario: A contributor leaves and comes back - the outcome is the one it was before that contributor left.
     */
    @Test
    public void aContributorThatLeavesAndComesBackRestoresTheSameOutcome() {
        explicitSource.declare(HEATING);
        contributedSource.declare(HEATING_AS_CONTRIBUTED);
        registry.addProvider(explicitSource);
        registry.addProvider(contributedSource);
        assertThat(registry.getParticipant("heating"), is(HEATING));

        registry.removeProvider(contributedSource);
        assertThat(registry.getParticipant("heating"), is(HEATING));

        registry.addProvider(contributedSource);
        assertThat(registry.getParticipant("heating"), is(HEATING));
    }

    /**
     * Requirement: Multiple contributors, user selection - a source the user did not select changes nothing.
     */
    @Test
    public void onlySelectedSourcesAreUsed() {
        explicitSource.declare(HEATING);
        contributedSource.declare(GRID);
        registry.addProvider(explicitSource);
        registry.addProvider(contributedSource);

        registry.modified(configuration(EnergyParticipantRegistryImpl.CONFIG_SOURCES, "metadata"));

        assertThat(idsOf(registry.getParticipants()), contains("heating"));
        assertThat(registry.getParticipant("grid"), is(nullValue()));
        assertThat(registry.getAll(), hasSize(2));
    }

    /**
     * Requirement: Graceful degradation on contributor loss - losing the authoritative source falls back to the
     * declaration that was being shadowed rather than losing the participant.
     */
    @Test
    public void theShadowedDeclarationTakesOverWhenItsSourceDisappears() {
        explicitSource.declare(HEATING);
        contributedSource.declare(HEATING_AS_CONTRIBUTED);
        registry.addProvider(explicitSource);
        registry.addProvider(contributedSource);

        registry.removeProvider(explicitSource);

        assertThat(registry.getParticipant("heating"), is(HEATING_AS_CONTRIBUTED));
        assertThat(Objects.requireNonNull(registry.getEffectiveDeclaration("heating")).sourceId(), is("binding"));
    }

    /**
     * Requirement: Malformed declarations are reported, never partially accepted - scenario <em>A typo does not hand
     * the device to somebody else</em>.
     * <p>
     * Source: owner decision D26. Before it, withdrawing the malformed explicit declaration promoted the contributed
     * one and the device carried on being steered on terms its owner never chose. A block is therefore
     * <em>not</em> a withdrawal: the identity leaves the resolved view entirely, while both statements stay in the
     * registry so the block can lift by itself.
     */
    @Test
    public void aMalformedExplicitDeclarationBlocksTheParticipantInsteadOfPromotingTheContributedOne() {
        explicitSource.declare(HEATING);
        contributedSource.declare(HEATING_AS_CONTRIBUTED);
        registry.addProvider(explicitSource);
        registry.addProvider(contributedSource);

        explicitSource.block("heating");

        assertThat("the device is unmanaged, not managed by somebody else", registry.getParticipant("heating"),
                is(nullValue()));
        assertThat(idsOf(registry.getParticipants()), is(empty()));
        assertThat("both statements are still on file", registry.getDeclarations("heating"), hasSize(1));
    }

    /**
     * Requirement scenario: <em>The block lifts when the declaration is fixed</em> - the contributed statement
     * resumes its ranked place beneath the corrected one, with nothing to acknowledge and nothing to restart.
     */
    @Test
    public void theBlockLiftsWhenTheDeclarationIsFixed() {
        contributedSource.declare(HEATING_AS_CONTRIBUTED);
        registry.addProvider(explicitSource);
        registry.addProvider(contributedSource);
        explicitSource.block("heating");
        assertThat(registry.getParticipant("heating"), is(nullValue()));

        explicitSource.unblock("heating");
        explicitSource.declare(HEATING);

        assertThat(registry.getParticipant("heating"), is(HEATING));
        assertThat(registry.getDeclarations("heating").stream().map(ParticipantDeclaration::sourceId).toList(),
                contains("metadata", "binding"));
    }

    /**
     * A block reaches only statements that are not strictly more authoritative than it. An add-on that cannot read
     * its own declaration therefore cannot disable a site's explicit one - which is the failure mode that would make
     * D26 worse than the behaviour it replaces.
     */
    @Test
    public void aContributedBlockCannotDisableAnExplicitDeclaration() {
        explicitSource.declare(HEATING);
        registry.addProvider(explicitSource);
        registry.addProvider(contributedSource);

        contributedSource.block("heating");

        assertThat(registry.getParticipant("heating"), is(HEATING));
    }

    /**
     * Two equally authoritative statements, one of them unreadable, resolve to "nothing happens". The alphabetical
     * source-id clause that makes the precedence chain total decides which of two <em>readable</em> statements is
     * effective; it has no business deciding whether a device is steered at all.
     */
    @Test
    public void anEquallyAuthoritativeBlockStopsTheParticipant() {
        TestSource other = new TestSource("aaa-addon", DeclarationOrigin.CONTRIBUTED);
        contributedSource.declare(HEATING_AS_CONTRIBUTED);
        registry.addProvider(contributedSource);
        registry.addProvider(other);

        other.block("heating");

        assertThat(registry.getParticipant("heating"), is(nullValue()));
    }

    /**
     * A source that goes away takes its blocks with it, because a block is a statement it is making. Losing a
     * contributor is the <em>graceful degradation</em> case and stays exactly as it was.
     */
    @Test
    public void aBlockDisappearsWithTheSourceThatHeldIt() {
        explicitSource.declare(HEATING);
        contributedSource.declare(HEATING_AS_CONTRIBUTED);
        registry.addProvider(explicitSource);
        registry.addProvider(contributedSource);
        explicitSource.block("heating");

        registry.removeProvider(explicitSource);

        assertThat(registry.getParticipant("heating"), is(HEATING_AS_CONTRIBUTED));
    }

    /**
     * Requirement: Runtime contribution - unregistering a contributor takes exactly its participants out.
     */
    @Test
    public void unregisteringASourceRemovesItsParticipants() {
        explicitSource.declare(HEATING);
        contributedSource.declare(GRID);
        registry.addProvider(explicitSource);
        registry.addProvider(contributedSource);

        registry.removeProvider(contributedSource);

        assertThat(idsOf(registry.getParticipants()), contains("heating"));
        assertThat(removed, hasSize(1));
        assertThat(removed.getFirst().participantId(), is("grid"));
    }

    @Test
    public void withdrawingOneDeclarationLeavesTheRest() {
        explicitSource.declare(HEATING);
        explicitSource.declare(BOILER);
        registry.addProvider(explicitSource);

        explicitSource.withdraw("boiler");

        assertThat(idsOf(registry.getParticipants()), contains("heating"));
    }

    /**
     * Scenario: Two consumers, limited surplus - the allocation order does not depend on incidental iteration order.
     */
    @Test
    public void consumersComeBackInPriorityOrder() {
        explicitSource.declare(BOILER);
        explicitSource.declare(HEATING);
        contributedSource.declare(GRID);
        registry.addProvider(explicitSource);
        registry.addProvider(contributedSource);

        assertThat(idsOf(registry.getConsumers()), contains("heating", "boiler"));
    }

    @Test
    public void anUndeclaredParticipantIsSimplyAbsent() {
        registry.addProvider(explicitSource);

        assertThat(registry.getParticipant("nothing"), is(nullValue()));
        assertThat(registry.getEffectiveDeclaration("nothing"), is(nullValue()));
        assertThat(registry.getDeclarations("nothing"), is(empty()));
    }

    /**
     * Declarations are made by sources, so the registry has no managed provider to write to.
     */
    @Test
    public void theRegistryIsNotWritable() {
        assertThrows(IllegalStateException.class,
                () -> registry.add(new ParticipantDeclaration("manual", HEATING, DeclarationOrigin.EXPLICIT)));
        assertThrows(IllegalStateException.class, () -> registry.remove("manual::heating"));
    }
}
