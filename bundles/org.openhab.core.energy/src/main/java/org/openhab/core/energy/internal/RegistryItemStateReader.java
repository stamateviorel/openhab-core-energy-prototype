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

import java.time.Instant;
import java.time.ZonedDateTime;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.items.GroupItem;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.persistence.PersistenceItemConfiguration;
import org.openhab.core.persistence.config.PersistenceAllConfig;
import org.openhab.core.persistence.config.PersistenceConfig;
import org.openhab.core.persistence.config.PersistenceGroupConfig;
import org.openhab.core.persistence.config.PersistenceGroupExcludeConfig;
import org.openhab.core.persistence.config.PersistenceItemConfig;
import org.openhab.core.persistence.config.PersistenceItemExcludeConfig;
import org.openhab.core.persistence.registry.PersistenceServiceConfiguration;
import org.openhab.core.persistence.registry.PersistenceServiceConfigurationRegistry;
import org.openhab.core.persistence.strategy.PersistenceStrategy;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * Reads Item states from the {@link ItemRegistry}, and the site's persistence configuration from the
 * {@link PersistenceServiceConfigurationRegistry}.
 * <p>
 * A missing Item and an undefined state are both reported as "no state" rather than as an error: participants are
 * declared on Items that may not exist yet, and the snapshot builder turns the absence into a stale-measurement
 * flag where it matters for safety.
 * <p>
 * <strong>Both registries are read-only here and nothing else in this bundle touches either.</strong> The
 * persistence registry is consulted for exactly one question - {@link #retentionOf(String)}, whether some service
 * restores an Item on startup - which is what makes an absent last state change reportable as either a
 * misconfiguration or the ordinary just-restarted case (owner decision D28,
 * {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}). No history is queried and no persistence service is called: the
 * question is answered from configuration alone, so it costs a walk over a handful of item configurations rather
 * than a database round trip per cycle.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class RegistryItemStateReader implements ItemStateReader {

    private final ItemRegistry itemRegistry;
    private final PersistenceServiceConfigurationRegistry persistenceConfigurations;

    /**
     * Creates the reader.
     *
     * @param itemRegistry the registry to read states from
     * @param persistenceConfigurations the registry holding what each persistence service is configured to keep
     */
    public RegistryItemStateReader(ItemRegistry itemRegistry,
            PersistenceServiceConfigurationRegistry persistenceConfigurations) {
        this.itemRegistry = itemRegistry;
        this.persistenceConfigurations = persistenceConfigurations;
    }

    @Override
    public @Nullable State readState(String itemName) {
        Item item = item(itemName);
        if (item == null) {
            return null;
        }
        State state = item.getState();
        return state instanceof UnDefType ? null : state;
    }

    @Override
    public @Nullable Instant lastUpdate(String itemName) {
        Item item = item(itemName);
        if (item == null) {
            return null;
        }
        ZonedDateTime lastUpdate = item.getLastStateUpdate();
        return lastUpdate == null ? null : lastUpdate.toInstant();
    }

    @Override
    public @Nullable Instant lastChange(String itemName) {
        Item item = item(itemName);
        if (item == null) {
            return null;
        }
        ZonedDateTime lastChange = item.getLastStateChange();
        return lastChange == null ? null : lastChange.toInstant();
    }

    /**
     * Answers whether any configured persistence service restores this Item on startup.
     * <p>
     * {@code restoreOnStartup} is the strategy that matters and not merely {@code everyChange}, because it is the one
     * core acts on to put the last state change back after a restart: an Item whose changes are stored but never
     * restored still comes up with no history, and a protection measured from it still starts over.
     * <p>
     * An Item that does not exist yet answers {@link HistoryRetention#UNKNOWN} rather than {@code NOT_KEPT}: a
     * declaration may name an Item that has not been created, and a group-based persistence rule cannot be evaluated
     * against an Item that is not in the registry, so saying "not kept" would report a fault that may not be one.
     *
     * @param itemName the Item name
     * @return whether that Item's history is being kept
     */
    @Override
    public HistoryRetention retentionOf(String itemName) {
        Item item = item(itemName);
        if (item == null) {
            return HistoryRetention.UNKNOWN;
        }
        for (PersistenceServiceConfiguration configuration : persistenceConfigurations.getAll()) {
            for (PersistenceItemConfiguration itemConfiguration : configuration.getConfigs()) {
                if (itemConfiguration.strategies().contains(PersistenceStrategy.Globals.RESTORE)
                        && appliesTo(itemConfiguration, item)) {
                    return HistoryRetention.KEPT;
                }
            }
        }
        return HistoryRetention.NOT_KEPT;
    }

    /**
     * Tells whether one persistence item configuration covers an Item, following core's own matching rules: an
     * explicit exclusion wins over everything, a catch-all or a name match or membership of a named group includes.
     * <p>
     * This mirrors {@code PersistenceManagerImpl}'s private {@code appliesToItem}, which is not public API. The
     * duplication is deliberate and named here rather than hidden: reading the configuration the same way the
     * component acting on it does is the whole point, and a divergence would make the engine report a persistence
     * state the site does not actually have.
     *
     * @param itemConfiguration one item configuration of one persistence service
     * @param item the Item to test
     * @return {@code true} if the configuration covers that Item
     */
    private boolean appliesTo(PersistenceItemConfiguration itemConfiguration, Item item) {
        boolean applies = false;
        for (PersistenceConfig entry : itemConfiguration.items()) {
            switch (entry) {
                case PersistenceAllConfig all -> applies = true;
                case PersistenceItemConfig named -> applies |= item.getName().equals(named.getItem());
                case PersistenceItemExcludeConfig excluded -> {
                    if (item.getName().equals(excluded.getItem())) {
                        return false;
                    }
                }
                case PersistenceGroupConfig group -> applies |= isMemberOf(item, group.getGroup());
                case PersistenceGroupExcludeConfig excluded -> {
                    if (isMemberOf(item, excluded.getGroup())) {
                        return false;
                    }
                }
                default -> {
                    // an entry kind this bundle does not know about neither includes nor excludes
                }
            }
        }
        return applies;
    }

    private boolean isMemberOf(Item item, String groupName) {
        Item group = item(groupName);
        return group instanceof GroupItem members && members.getAllStateMembers().contains(item);
    }

    private @Nullable Item item(String itemName) {
        try {
            return itemRegistry.getItem(itemName);
        } catch (ItemNotFoundException e) {
            return null;
        }
    }
}
