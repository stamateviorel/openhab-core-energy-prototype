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
package org.openhab.core.energy.internal.metadata;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openhab.core.common.registry.Provider;
import org.openhab.core.common.registry.ProviderChangeListener;
import org.openhab.core.config.core.status.ConfigStatusMessage;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.internal.EnergyConfigStatus;
import org.openhab.core.energy.spi.DeclarationOrigin;
import org.openhab.core.energy.spi.ParticipantDeclaration;
import org.openhab.core.items.Metadata;
import org.openhab.core.items.MetadataKey;
import org.openhab.core.items.MetadataRegistry;

/**
 * Tests that item metadata is a live declaration mechanism: what the registry sees follows what the user declares,
 * while the framework runs, and one bad declaration costs only itself.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class MetadataParticipantSourceTest {

    private @Mock @NonNullByDefault({}) MetadataRegistry metadataRegistryMock;

    private final EnergyConfigStatus configStatus = new EnergyConfigStatus();
    private final List<ParticipantDeclaration> added = new ArrayList<>();
    private final List<ParticipantDeclaration> removed = new ArrayList<>();
    private final List<ParticipantDeclaration> updated = new ArrayList<>();

    private static final Map<String, Object> WALLBOX = Map.of("profile", "controllable", "min", "6 A", "max", "32 A",
            "priority", 1);

    private static Metadata energy(String itemName, String value, Map<String, Object> configuration) {
        return new Metadata(new MetadataKey(EnergyMetadataParser.NAMESPACE, itemName), value, configuration);
    }

    private MetadataParticipantSource start(Metadata... existing) {
        when(metadataRegistryMock.stream()).thenReturn(Stream.of(existing));
        MetadataParticipantSource source = new MetadataParticipantSource(metadataRegistryMock, configStatus);
        source.activate();
        source.addProviderChangeListener(new ProviderChangeListener<>() {
            @Override
            public void added(Provider<ParticipantDeclaration> provider, ParticipantDeclaration element) {
                added.add(element);
            }

            @Override
            public void removed(Provider<ParticipantDeclaration> provider, ParticipantDeclaration element) {
                removed.add(element);
            }

            @Override
            public void updated(Provider<ParticipantDeclaration> provider, ParticipantDeclaration oldElement,
                    ParticipantDeclaration element) {
                updated.add(element);
            }
        });
        return source;
    }

    private static List<String> participantIds(MetadataParticipantSource source) {
        return source.getAll().stream().map(ParticipantDeclaration::participantId).sorted().toList();
    }

    /**
     * Scenario: Marking a wallbox consumer - declared on an existing item, discovered without binding-specific code.
     */
    @Test
    public void aWallboxDeclaredInMetadataIsDiscovered() {
        MetadataParticipantSource source = start(energy("Wallbox_Current", "consumer", WALLBOX));

        assertThat(source.getSourceId(), is(MetadataParticipantSource.SOURCE_ID));
        assertThat(source.getOrigin(), is(DeclarationOrigin.EXPLICIT));
        assertThat(participantIds(source), contains("Wallbox_Current"));
        ParticipantDeclaration declaration = source.getAll().iterator().next();
        assertThat(declaration.participant(), instanceOf(EnergyConsumer.class));
        assertThat(declaration.getUID(), is("metadata::Wallbox_Current"));
    }

    /**
     * Scenario: No Thing required - the source reads item metadata and nothing else, so a device that exists only as
     * items participates on the same terms. The verification is structural: the metadata registry is the only
     * collaborator this source ever touches.
     */
    @Test
    public void aDeviceModelledWithoutAThingIsDeclaredJustTheSame() {
        MetadataParticipantSource source = start(energy("Virtual_Heater", "consumer", Map.of("profile", "simple")));

        assertThat(participantIds(source), contains("Virtual_Heater"));
        verify(metadataRegistryMock).stream();
        verify(metadataRegistryMock).addRegistryChangeListener(source);
        verifyNoMoreInteractions(metadataRegistryMock);
    }

    @Test
    public void aDeclarationMadeWhileRunningAppearsWithoutARestart() {
        MetadataParticipantSource source = start();
        assertThat(source.getAll(), is(empty()));

        source.added(energy("Wallbox_Current", "consumer", WALLBOX));

        assertThat(participantIds(source), contains("Wallbox_Current"));
        assertThat(added, hasSize(1));
    }

    @Test
    public void anEditedDeclarationIsUpdatedInPlace() {
        MetadataParticipantSource source = start(
                energy("Boiler_Switch", "consumer", Map.of("profile", "simple", "priority", 2)));

        source.updated(energy("Boiler_Switch", "consumer", Map.of("profile", "simple", "priority", 2)),
                energy("Boiler_Switch", "consumer", Map.of("profile", "simple", "priority", 5)));

        assertThat(participantIds(source), contains("Boiler_Switch"));
        assertThat(updated, hasSize(1));
        EnergyConsumer consumer = (EnergyConsumer) updated.getFirst().participant();
        assertThat(consumer.priority(), is(5));
    }

    @Test
    public void reApplyingAnUnchangedDeclarationIsNotAnUpdate() {
        MetadataParticipantSource source = start(
                energy("Boiler_Switch", "consumer", Map.of("profile", "simple", "priority", 2)));

        source.updated(energy("Boiler_Switch", "consumer", Map.of("profile", "simple", "priority", 2)),
                energy("Boiler_Switch", "consumer", Map.of("profile", "simple", "priority", 2)));

        assertThat(updated, is(empty()));
    }

    @Test
    public void aDeletedDeclarationWithdrawsItsParticipant() {
        MetadataParticipantSource source = start(energy("Wallbox_Current", "consumer", WALLBOX));

        source.removed(energy("Wallbox_Current", "consumer", WALLBOX));

        assertThat(source.getAll(), is(empty()));
        assertThat(removed, hasSize(1));
    }

    @Test
    public void anInvalidDeclarationIsSkippedAndTheValidOnesSurvive() {
        MetadataParticipantSource source = start(energy("Wallbox_Current", "consumer", WALLBOX),
                energy("Mystery_Item", "manager", Map.of()),
                energy("Grid_Power", "provider", Map.of("role", "not-a-role")));

        assertThat(participantIds(source), contains("Wallbox_Current"));
    }

    /**
     * A refused declaration is reported machine-readably, not only to the log: the requirement is that a malformed
     * declaration skips the whole participant <em>and says so</em>, and a warning in a log file nobody was tailing at
     * the time is not a report an operator can act on.
     */
    @Test
    public void aRefusedDeclarationIsReportedAsAConfigurationError() {
        start(energy("Wallbox_Current", "consumer", WALLBOX),
                energy("Boiler_Switch", "consumer", Map.of("profile", "unicycle")));

        List<ConfigStatusMessage> status = List.copyOf(configStatus.getConfigStatus());
        assertThat(status, hasSize(1));
        assertThat(status.getFirst().type, is(ConfigStatusMessage.Type.ERROR));
        assertThat(status.getFirst().parameterName, is("Boiler_Switch"));
    }

    /**
     * And it clears when the declaration is fixed or deleted, so the report says what is wrong now rather than what
     * was ever wrong.
     */
    @Test
    public void theConfigurationErrorClearsWhenTheDeclarationIsCorrected() {
        MetadataParticipantSource source = start(energy("Boiler_Switch", "consumer", Map.of("profile", "unicycle")));
        assertThat(configStatus.getConfigStatus(), hasSize(1));

        source.updated(energy("Boiler_Switch", "consumer", Map.of("profile", "unicycle")),
                energy("Boiler_Switch", "consumer", Map.of("profile", "simple")));
        assertThat(configStatus.getConfigStatus(), is(empty()));

        source.removed(energy("Boiler_Switch", "consumer", Map.of("profile", "simple")));
        assertThat(configStatus.getConfigStatus(), is(empty()));
    }

    /**
     * Requirement: Malformed declarations are reported, never partially accepted - the block half, owner decision
     * D26. A refused declaration does not merely withdraw its participant; it blocks the identity, so that no
     * lower-ranked statement about it can become effective while the text is wrong.
     */
    @Test
    public void aRefusedDeclarationBlocksTheIdentityItClaims() {
        MetadataParticipantSource source = start(energy("Boiler_Switch", "consumer", Map.of("profile", "unicycle")));

        assertThat(source.getBlockedParticipants(), contains("Boiler_Switch"));
        assertThat(participantIds(source), is(empty()));
    }

    /**
     * The identity is the one the declaration <em>claims</em>, which is readable even when the rest of it is not:
     * blocking the item name instead would leave the identity an add-on actually contributed wide open.
     */
    @Test
    public void theBlockedIdentityIsTheOneTheDeclarationNames() {
        MetadataParticipantSource source = start(
                energy("Boiler_Switch", "consumer", Map.of("id", "boiler", "profile", "unicycle")));

        assertThat(source.getBlockedParticipants(), contains("boiler"));
    }

    /**
     * A declaration edited into an unreadable one blocks both the identity it now claims and the one it last
     * produced, because that second one is what a contribution would otherwise inherit from a user who is mid-edit.
     * The failure mode of D26 is "nothing happens", so over-blocking here is the deliberate direction to be wrong in.
     */
    @Test
    public void anEditThatBreaksTheIdKeyBlocksBothIdentities() {
        MetadataParticipantSource source = start(
                energy("Boiler_Switch", "consumer", Map.of("id", "boiler", "profile", "simple")));

        source.updated(energy("Boiler_Switch", "consumer", Map.of("id", "boiler", "profile", "simple")),
                energy("Boiler_Switch", "consumer", Map.of("id", "kettle", "profile", "unicycle")));

        assertThat(source.getBlockedParticipants(), containsInAnyOrder("boiler", "kettle"));
        assertThat(participantIds(source), is(empty()));
    }

    /**
     * Requirement scenario: <em>The block lifts when the declaration is fixed</em> - and equally when it is deleted,
     * because a declaration that is gone is not a declaration in error.
     */
    @Test
    public void theBlockLiftsWhenTheDeclarationIsCorrectedOrDeleted() {
        MetadataParticipantSource source = start(energy("Boiler_Switch", "consumer", Map.of("profile", "unicycle")));
        assertThat(source.getBlockedParticipants(), contains("Boiler_Switch"));

        source.updated(energy("Boiler_Switch", "consumer", Map.of("profile", "unicycle")),
                energy("Boiler_Switch", "consumer", Map.of("profile", "simple")));
        assertThat(source.getBlockedParticipants(), is(empty()));
        assertThat(participantIds(source), contains("Boiler_Switch"));

        source.updated(energy("Boiler_Switch", "consumer", Map.of("profile", "simple")),
                energy("Boiler_Switch", "consumer", Map.of("profile", "unicycle")));
        assertThat(source.getBlockedParticipants(), contains("Boiler_Switch"));

        source.removed(energy("Boiler_Switch", "consumer", Map.of("profile", "unicycle")));
        assertThat(source.getBlockedParticipants(), is(empty()));
    }

    /**
     * The machine-readable report says what the block means, not only that a key could not be read: an operator
     * reading it has to be able to tell that the device is now unmanaged.
     */
    @Test
    public void theReportedErrorSaysTheParticipantIsUnmanaged() {
        start(energy("Boiler_Switch", "consumer", Map.of("profile", "unicycle")));

        List<ConfigStatusMessage> status = List.copyOf(configStatus.getConfigStatus());
        assertThat(status, hasSize(1));
        assertThat(status.getFirst().toString(), allOf(containsString("Boiler_Switch"), containsString("unmanaged"),
                containsString("no contributed declaration takes its place")));
    }

    /**
     * Deactivation is not an error condition: a source that goes away takes its blocks with it, so the ordinary
     * graceful-degradation path is untouched by D26.
     */
    @Test
    public void deactivationLiftsEveryBlock() {
        MetadataParticipantSource source = start(energy("Boiler_Switch", "consumer", Map.of("profile", "unicycle")));
        assertThat(source.getBlockedParticipants(), hasSize(1));

        source.deactivate();

        assertThat(source.getBlockedParticipants(), is(empty()));
    }

    /**
     * A declaration that parses with something redundant in it is kept, and reported as a warning rather than an
     * error - the device is managed, the remark is about the declaration.
     */
    @Test
    public void anAcceptedDeclarationWithARemarkIsReportedAsAWarning() {
        start(energy("Pool_Pump", "consumer", Map.of("profile", "simple", "typo", "1")));

        List<ConfigStatusMessage> status = List.copyOf(configStatus.getConfigStatus());
        assertThat(status, hasSize(1));
        assertThat(status.getFirst().type, is(ConfigStatusMessage.Type.WARNING));
        assertThat(status.getFirst().parameterName, is("Pool_Pump"));
    }

    @Test
    public void aDeclarationThatIsEditedIntoAnInvalidOneWithdrawsItsParticipant() {
        MetadataParticipantSource source = start(energy("Wallbox_Current", "consumer", WALLBOX));

        source.updated(energy("Wallbox_Current", "consumer", WALLBOX),
                energy("Wallbox_Current", "consumer", Map.of("profile", "controllable")));

        assertThat(source.getAll(), is(empty()));
        assertThat(removed, hasSize(1));
    }

    @Test
    public void renamingTheParticipantIdWithdrawsTheOldOne() {
        MetadataParticipantSource source = start(
                energy("Wallbox_Current", "consumer", Map.of("profile", "simple", "id", "car")));

        source.updated(energy("Wallbox_Current", "consumer", Map.of("profile", "simple", "id", "car")),
                energy("Wallbox_Current", "consumer", Map.of("profile", "simple", "id", "car2")));

        assertThat(participantIds(source), contains("car2"));
        assertThat(removed, hasSize(1));
        assertThat(removed.getFirst().participantId(), is("car"));
    }

    /**
     * Scenario: Identity survives a restart - the same declaration read again presents the same identity, which is
     * what lets engine-held state about a participant still refer to it.
     */
    @Test
    public void theSameDeclarationReadAgainPresentsTheSameIdentity() {
        MetadataParticipantSource before = start(energy("Wallbox_Current", "consumer", WALLBOX));
        List<String> firstRun = participantIds(before);
        String firstUid = before.getAll().iterator().next().getUID();
        before.deactivate();

        MetadataParticipantSource after = start(energy("Wallbox_Current", "consumer", WALLBOX));

        assertThat(participantIds(after), is(firstRun));
        assertThat(participantIds(after), contains("Wallbox_Current"));
        assertThat(after.getAll().iterator().next().origin(), is(DeclarationOrigin.EXPLICIT));
        assertThat("and it is still the same statement, not a new one", after.getAll().iterator().next().getUID(),
                is(firstUid));
    }

    /**
     * Scenario: Metadata read before its Item, and Scenario: Unresolved is not malformed. A declaration is a
     * statement about item <em>names</em>, so it is accepted whether or not those items exist yet - the source's
     * only collaborator is the {@link MetadataRegistry}, and it never asks whether a name resolves. Whether a
     * reading can be taken is the engine's question at evaluation time, and a different failure with a different
     * remedy from an unreadable declaration.
     */
    @Test
    public void aDeclarationNamingItemsThatDoNotExistIsStillAccepted() {
        MetadataParticipantSource source = start(
                energy("Not_Created_Yet", "consumer",
                        Map.of("profile", "simple", "measure", "Also_Missing", "ready", "Missing_Too")),
                energy("Wallbox_Current", "consumer", Map.of("profile", "controllable", "min", "6 A")));

        assertThat("the unresolved name is registered, the unreadable declaration is not", participantIds(source),
                contains("Not_Created_Yet"));
        EnergyConsumer accepted = (EnergyConsumer) source.getAll().iterator().next().participant();
        assertThat(accepted.measureItemName(), is("Also_Missing"));
        assertThat(accepted.readyItemName(), is("Missing_Too"));
    }

    @Test
    public void metadataInOtherNamespacesIsIgnored() {
        MetadataParticipantSource source = start();

        source.added(new Metadata(new MetadataKey("expire", "Wallbox_Current"), "5m", Map.of()));
        source.removed(new Metadata(new MetadataKey("expire", "Wallbox_Current"), "5m", Map.of()));

        assertThat(source.getAll(), is(empty()));
        assertThat(added, is(empty()));
    }

    @Test
    public void deactivationWithdrawsEveryDeclarationAndStopsListening() {
        MetadataParticipantSource source = start(energy("Wallbox_Current", "consumer", WALLBOX));

        source.deactivate();

        assertThat(source.getAll(), is(empty()));
        assertThat(removed, hasSize(1));
        verify(metadataRegistryMock).removeRegistryChangeListener(source);
    }
}
