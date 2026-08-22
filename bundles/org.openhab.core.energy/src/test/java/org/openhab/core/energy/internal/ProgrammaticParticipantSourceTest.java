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

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.energy.EnergyProvider;
import org.openhab.core.energy.ProviderRole;
import org.openhab.core.energy.SimpleProfile;
import org.openhab.core.energy.spi.DeclarationOrigin;
import org.openhab.core.energy.spi.ParticipantDeclaration;

/**
 * Tests the programmatic declaration mechanism: a script or add-on contributing participants at runtime, and taking
 * exactly its own contributions back when it goes away.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ProgrammaticParticipantSourceTest {

    private static final String SCRIPT = "user-script";
    private static final String BINDING = "some-binding";

    private static final EnergyConsumer POOL_PUMP = EnergyConsumer.of("pool", "Pool_Pump", SimpleProfile.plain(), 3);
    private static final EnergyConsumer POOL_PUMP_REVISED = EnergyConsumer.of("pool", "Pool_Pump",
            SimpleProfile.plain(), 4);
    private static final EnergyProvider GRID = EnergyProvider.of("grid", "Grid_Power", ProviderRole.GRID);

    private @NonNullByDefault({}) ProgrammaticParticipantSource source;

    private static List<String> participantIds(ProgrammaticParticipantSource source) {
        return source.getAll().stream().map(ParticipantDeclaration::participantId).sorted().toList();
    }

    @BeforeEach
    public void setup() {
        source = new ProgrammaticParticipantSource();
    }

    /**
     * Scenario: Script-contributed consumer - it arrives as an ordinary declaration, indistinguishable from an
     * add-on's except for its origin.
     */
    @Test
    public void aScriptContributedConsumerBecomesAnOrdinaryDeclaration() {
        source.declare(SCRIPT, POOL_PUMP);

        assertThat(source.getSourceId(), is(ProgrammaticParticipantSource.SOURCE_ID));
        assertThat(participantIds(source), contains("pool"));
        ParticipantDeclaration declaration = source.getAll().iterator().next();
        assertThat(declaration.origin(), is(DeclarationOrigin.CONTRIBUTED));
        assertThat(declaration.participant(), is(POOL_PUMP));
        assertThat(source.getContributor("pool"), is(SCRIPT));
    }

    @Test
    public void severalContributorsCoexistInOneSource() {
        source.declare(SCRIPT, POOL_PUMP);
        source.declare(BINDING, GRID);

        assertThat(participantIds(source), contains("grid", "pool"));
        assertThat(source.getContributions(SCRIPT), contains(POOL_PUMP));
        assertThat(source.getContributions(BINDING), contains(GRID));
    }

    @Test
    public void aContributorMayReviseItsOwnContribution() {
        source.declare(SCRIPT, POOL_PUMP);
        source.declare(SCRIPT, POOL_PUMP_REVISED);

        assertThat(source.getAll(), hasSize(1));
        assertThat(source.getContributions(SCRIPT), contains(POOL_PUMP_REVISED));
    }

    /**
     * Scenario: A duplicate declaration is not an error - a second contributor describing one participant is a
     * further statement about it. Both statements are kept, and the effective one is chosen by contributor id
     * ascending rather than by who called first.
     */
    @Test
    public void aSecondContributorsStatementIsKeptRatherThanRefused() {
        assertThat("'some-binding' sorts before 'user-script', so the first caller does not simply keep it",
                source.declare(SCRIPT, POOL_PUMP), is(true));

        assertThat(source.declare(BINDING, POOL_PUMP_REVISED), is(true));
        assertThat("one participant, two statements", participantIds(source), contains("pool"));
        assertThat(source.getContributors("pool"), contains(BINDING, SCRIPT));
        assertThat("neither statement was dropped", source.getContributions(SCRIPT), contains(POOL_PUMP));
        assertThat(source.getContributions(BINDING), contains(POOL_PUMP_REVISED));
        assertThat(source.getContributor("pool"), is(BINDING));
    }

    /**
     * The same two statements, made in the opposite order, produce the same participant - which is what "the
     * outcome does not depend on the order in which the contributors registered" means when both of them arrive
     * through one service.
     */
    @Test
    public void theOrderTheTwoStatementsArriveInIsNotLoadBearing() {
        source.declare(BINDING, POOL_PUMP_REVISED);
        source.declare(SCRIPT, POOL_PUMP);
        assertThat(source.getContributor("pool"), is(BINDING));

        ProgrammaticParticipantSource otherOrder = new ProgrammaticParticipantSource();
        otherOrder.declare(SCRIPT, POOL_PUMP);
        otherOrder.declare(BINDING, POOL_PUMP_REVISED);

        assertThat(otherOrder.getContributor("pool"), is(BINDING));
    }

    /**
     * A contributor whose statement is shadowed becomes effective by itself when the other withdraws - the
     * participant does not leave the site while any statement about it stands.
     */
    @Test
    public void aShadowedStatementTakesOverWhenTheEffectiveContributorWithdraws() {
        source.declare(SCRIPT, POOL_PUMP);
        source.declare(BINDING, POOL_PUMP_REVISED);
        assertThat(source.getContributor("pool"), is(BINDING));

        assertThat(source.withdraw(BINDING, "pool"), is(true));

        assertThat("the participant is still declared", participantIds(source), contains("pool"));
        assertThat(source.getContributor("pool"), is(SCRIPT));
        ParticipantDeclaration declaration = source.getAll().iterator().next();
        assertThat(declaration.participant(), is(POOL_PUMP));
    }

    @Test
    public void aContributorCannotWithdrawAnotherContributorsParticipant() {
        source.declare(SCRIPT, POOL_PUMP);

        assertThat("a contributor that made no statement withdraws nothing", source.withdraw(BINDING, "pool"),
                is(false));
        assertThat(participantIds(source), contains("pool"));
        assertThat(source.getContributor("pool"), is(SCRIPT));
        assertThat(source.withdraw(SCRIPT, "pool"), is(true));
        assertThat(source.getAll(), is(empty()));
    }

    /**
     * Requirement: Runtime contribution - "unregistered when the contributor goes away", and only that
     * contributor's participants.
     */
    @Test
    public void withdrawingAContributorRemovesExactlyItsParticipants() {
        source.declare(SCRIPT, POOL_PUMP);
        source.declare(BINDING, GRID);

        assertThat(source.withdrawAll(SCRIPT), is(1));

        assertThat(participantIds(source), contains("grid"));
        assertThat(source.getContributions(SCRIPT), is(empty()));
    }

    /**
     * The two halves together: a contribution reaches the registry, and withdrawing it removes it there too.
     */
    @Test
    public void contributionAndWithdrawalReachTheRegistry() {
        EnergyParticipantRegistryImpl registry = new EnergyParticipantRegistryImpl(Map.of());
        registry.addProvider(source);

        source.declare(SCRIPT, POOL_PUMP);
        assertThat(registry.getParticipant("pool"), is(POOL_PUMP));

        source.withdrawAll(SCRIPT);
        assertThat(registry.getParticipant("pool"), is(nullValue()));
    }

    @Test
    public void deactivationWithdrawsEveryContribution() {
        source.declare(SCRIPT, POOL_PUMP);
        source.declare(BINDING, GRID);

        source.deactivate();

        assertThat(source.getAll(), is(empty()));
        assertThat(source.getContributor("pool"), is(nullValue()));
        assertThat(source.getContributors("pool"), is(empty()));
    }

    /**
     * Scenario: Consumer that declares no priority - the programmatic path places it at the same 100 the metadata
     * path does, so which mechanism declared a participant never changes where it sorts.
     */
    @Test
    public void aContributedDeclarationWithoutAPriorityGetsTheSameDefault() {
        source.declare(SCRIPT, EnergyConsumer.of("Sauna_Switch", SimpleProfile.plain()));

        EnergyParticipant contributed = source.getContributions(SCRIPT).iterator().next();
        assertThat(contributed.priority(), is(EnergyParticipant.DEFAULT_PRIORITY));
        assertThat("identity is the Item name on this path too", contributed.id(), is("Sauna_Switch"));
    }

    @Test
    public void aBlankContributorIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> source.declare(" ", POOL_PUMP));
        assertThrows(IllegalArgumentException.class, () -> source.withdrawAll(""));
    }
}
