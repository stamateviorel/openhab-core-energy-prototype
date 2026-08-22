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

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.common.registry.RegistryChangeListener;
import org.openhab.core.energy.internal.EnergyConfigStatus;
import org.openhab.core.energy.internal.metadata.EnergyMetadataParser.ParsedDeclaration;
import org.openhab.core.energy.spi.AbstractEnergyParticipantSource;
import org.openhab.core.energy.spi.DeclarationOrigin;
import org.openhab.core.energy.spi.EnergyParticipantSource;
import org.openhab.core.items.Metadata;
import org.openhab.core.items.MetadataPredicates;
import org.openhab.core.items.MetadataRegistry;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Declaration mechanism (a): participants declared by the user in the {@code energy} item-metadata namespace.
 * <p>
 * The source watches the {@link MetadataRegistry}, so a declaration that is added, edited or deleted takes effect
 * immediately - whether it arrived from a {@code .items} file, the UI or the REST API - without a restart.
 * <p>
 * It deliberately never consults the {@link org.openhab.core.items.ItemRegistry} or anything Thing-shaped. A
 * declaration is a statement about <em>item names</em>, which is what lets a device that exists only as items - no
 * Thing, no binding, wired through HTTP or a rule - participate on exactly the same terms as any other. Whether the
 * named items exist and can be read is the engine's problem at evaluation time, not this source's problem at
 * declaration time.
 * <p>
 * A declaration that cannot be parsed is skipped whole and leaves the other declarations alone: one typo must not
 * cost a site its whole energy configuration, and half a declaration must never be accepted. It is reported twice
 * over - as a log warning, and to {@link EnergyConfigStatus}, which is what makes "the report is machine-readable"
 * true of something other than a log line a reader has to be watching at the right moment.
 * <p>
 * <strong>Skipping it also blocks the participant.</strong> Source: owner decision <strong>D26</strong>
 * (2026-08-03, {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}). Withdrawing a malformed explicit declaration used
 * to promote whatever an add-on had contributed for the same identity, so a keystroke transferred control of a live
 * device to terms its owner never chose - visible as a configuration error, invisible in the device's behaviour.
 * The source now calls {@link #block(String)} instead, and the participant is unmanaged until the declaration is
 * fixed. Two identities are blocked where they differ: the one this declaration <em>claims</em> now, and the one it
 * last successfully produced. Over-blocking the second is deliberate - the failure mode of D26 is "nothing
 * happens", and a device whose user is mid-edit is exactly the device an add-on should not quietly inherit. Every
 * block this item holds lifts the moment the declaration parses again.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(immediate = true, service = EnergyParticipantSource.class)
public class MetadataParticipantSource extends AbstractEnergyParticipantSource
        implements RegistryChangeListener<Metadata> {

    /**
     * The id under which this source is enabled and ordered in the registry configuration.
     */
    public static final String SOURCE_ID = "metadata";

    private final Logger logger = LoggerFactory.getLogger(MetadataParticipantSource.class);

    private final MetadataRegistry metadataRegistry;
    private final EnergyConfigStatus configStatus;

    /**
     * Maps an item name to the participant id its declaration produced, so that a declaration whose {@code id} key
     * changed, or which was deleted, withdraws the right participant.
     */
    private final Map<String, String> participantIdsByItemName = new ConcurrentHashMap<>();

    /**
     * Maps an item name to the identities its unreadable declaration is blocking, so that correcting the
     * declaration - or deleting it - lifts exactly those blocks and no others. Owner decision D26.
     */
    private final Map<String, Set<String>> blockedIdsByItemName = new ConcurrentHashMap<>();

    /**
     * Creates the source.
     *
     * @param metadataRegistry the registry holding the {@code energy} declarations
     * @param configStatus where a refused or incomplete declaration is reported so a user can find it
     */
    @Activate
    public MetadataParticipantSource(final @Reference MetadataRegistry metadataRegistry,
            final @Reference EnergyConfigStatus configStatus) {
        super(SOURCE_ID, DeclarationOrigin.EXPLICIT);
        this.metadataRegistry = metadataRegistry;
        this.configStatus = configStatus;
    }

    /**
     * Reads every declaration that already exists and starts following further changes.
     */
    @Activate
    protected void activate() {
        metadataRegistry.stream().filter(MetadataPredicates.hasNamespace(EnergyMetadataParser.NAMESPACE))
                .forEach(this::apply);
        metadataRegistry.addRegistryChangeListener(this);
    }

    /**
     * Stops following changes and withdraws every declaration of this source.
     */
    @Deactivate
    protected void deactivate() {
        metadataRegistry.removeRegistryChangeListener(this);
        withdrawAll();
        participantIdsByItemName.keySet().forEach(configStatus::declarationWithdrawn);
        participantIdsByItemName.clear();
        blockedIdsByItemName.keySet().forEach(configStatus::declarationWithdrawn);
        blockedIdsByItemName.clear();
    }

    @Override
    public void added(Metadata element) {
        apply(element);
    }

    @Override
    public void updated(Metadata oldElement, Metadata element) {
        apply(element);
    }

    @Override
    public void removed(Metadata element) {
        if (EnergyMetadataParser.NAMESPACE.equals(element.getUID().getNamespace())) {
            withdrawItem(element.getUID().getItemName());
        }
    }

    private void apply(Metadata metadata) {
        if (!EnergyMetadataParser.NAMESPACE.equals(metadata.getUID().getNamespace())) {
            return;
        }
        String itemName = metadata.getUID().getItemName();
        ParsedDeclaration parsed;
        try {
            parsed = EnergyMetadataParser.parse(itemName, metadata.getValue(), metadata.getConfiguration());
        } catch (EnergyMetadataParseException e) {
            Set<String> blockedIds = blockIdentitiesOf(itemName, metadata);
            logger.warn("Skipping the energy declaration on item '{}': {}. {} stays unmanaged until it is fixed",
                    itemName, e.getMessage(), blockedIds);
            configStatus.declarationRejected(itemName, e.getMessage() + " - " + blockedIds
                    + " stays unmanaged until this is fixed, and no contributed " + "declaration takes its place");
            return;
        }
        parsed.warnings().forEach(warning -> logger.warn("Energy declaration on item '{}': {}", itemName, warning));
        configStatus.declarationAccepted(itemName, parsed.warnings());

        liftBlocks(itemName);
        String participantId = parsed.participant().id();
        String previousId = participantIdsByItemName.put(itemName, participantId);
        if (previousId != null && !previousId.equals(participantId)) {
            withdraw(previousId);
        }
        declare(parsed.participant());
        logger.debug("Item '{}' declares energy participant '{}'", itemName, participantId);
    }

    /**
     * Blocks every identity a refused declaration is about, and returns them.
     * <p>
     * The identity a declaration claims is readable even when the rest of it is not - it is one optional key with the
     * item name as its fallback. Where the item last produced a <em>different</em> id, that one is blocked as well,
     * because it is the identity a contribution would otherwise inherit from a user who is mid-edit.
     *
     * @param itemName the item carrying the refused declaration
     * @param metadata the declaration as it stands
     * @return the blocked identities
     */
    private Set<String> blockIdentitiesOf(String itemName, Metadata metadata) {
        Set<String> identities = new TreeSet<>();
        identities.add(EnergyMetadataParser.participantId(itemName, metadata.getConfiguration()));
        String previousId = participantIdsByItemName.remove(itemName);
        if (previousId != null) {
            identities.add(previousId);
        }
        Set<String> lifted = blockedIdsByItemName.getOrDefault(itemName, Set.of());
        lifted.stream().filter(id -> !identities.contains(id)).forEach(this::unblock);
        blockedIdsByItemName.put(itemName, identities);
        identities.forEach(this::block);
        return identities;
    }

    /**
     * Lifts every block this item holds, which is what correcting or deleting its declaration means.
     *
     * @param itemName the item whose declaration is readable again, or gone
     */
    private void liftBlocks(String itemName) {
        blockedIdsByItemName.getOrDefault(itemName, Set.of()).forEach(this::unblock);
        blockedIdsByItemName.remove(itemName);
    }

    private void withdrawItem(String itemName) {
        configStatus.declarationWithdrawn(itemName);
        liftBlocks(itemName);
        String participantId = participantIdsByItemName.remove(itemName);
        if (participantId != null) {
            withdraw(participantId);
        }
    }
}
