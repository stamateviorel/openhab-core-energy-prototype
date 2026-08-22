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
package org.openhab.core.energy.publish.internal;

import java.util.Collection;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.common.registry.AbstractProvider;
import org.openhab.core.energy.publish.EnergyItems;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemProvider;
import org.openhab.core.library.items.StringItem;
import org.openhab.core.library.types.StringType;
import org.osgi.service.component.annotations.Component;

/**
 * Supplies the two Items this bundle publishes on, so that installing the component is the whole of the setup.
 * <p>
 * They are provided rather than managed: the user does not create them, cannot delete them, and gets them back
 * exactly as they were if the bundle is reinstalled. Removing the bundle removes them, which is the honest
 * behaviour - an energy status Item with no energy framework behind it is a stale number somebody will read.
 * <p>
 * Both are {@link StringItem}s. The status is a sentence, and the level is one of a small closed vocabulary that a
 * rule compares by name; a numeric Item would force the level's ordinal on every reader, and the ordinal is an
 * implementation detail of the framework's own enum.
 * <p>
 * Both are handed over already carrying {@link EnergyItems#AWAITING_FIRST_CYCLE}, set on the Item rather than posted
 * as an event. The framework's events are deduplicated, so a component that starts after the engine hears nothing
 * until something changes; an Item that said {@code NULL} in the meantime would be indistinguishable from a broken
 * one. Setting it here also avoids the race a state event posted at activation would have with the registry picking
 * this provider up.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(immediate = true, service = ItemProvider.class)
public class EnergyItemProvider extends AbstractProvider<Item> implements ItemProvider {

    private final List<Item> items = List.of(awaiting(EnergyItems.ENGINE_STATUS), awaiting(EnergyItems.CURRENT_LEVEL));

    @Override
    public Collection<Item> getAll() {
        return items;
    }

    /**
     * Creates one of the two Items, already saying that nothing has been reported yet.
     *
     * @param name the Item name
     * @return the Item
     */
    private static StringItem awaiting(String name) {
        StringItem item = new StringItem(name);
        item.setState(new StringType(EnergyItems.AWAITING_FIRST_CYCLE));
        return item;
    }
}
