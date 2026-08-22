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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.Collection;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.publish.EnergyItems;
import org.openhab.core.items.Item;
import org.openhab.core.library.items.StringItem;

/**
 * The two Items installing this bundle brings with it.
 * <p>
 * They are provided rather than user-declared, which is the whole difference between "the energy framework has a
 * status Item" and "the user has to remember to create one and name it right". That the framework bundle next door
 * could not have provided them either - a provider hands over a mutable {@code Item}, and an initial state is a
 * write - is exactly why D23 put them here.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EnergyItemProviderTest {

    @Test
    public void theBundleProvidesExactlyTheTwoItemsItDocuments() {
        Collection<Item> items = new EnergyItemProvider().getAll();

        assertThat(items.stream().map(Item::getName).toList(),
                containsInAnyOrder(EnergyItems.ENGINE_STATUS, EnergyItems.CURRENT_LEVEL));
        for (Item item : items) {
            assertThat(item, is(instanceOf(StringItem.class)));
        }
    }

    /**
     * The framework's events are deduplicated, so a publishing component that starts after the engine hears nothing
     * until something changes. An Item reading {@code NULL} in the meantime would look like a fault; this says what
     * is actually true.
     */
    @Test
    public void bothItemsSayThatNothingHasBeenReportedYet() {
        Collection<Item> items = new EnergyItemProvider().getAll();

        for (Item item : items) {
            assertThat(item.getState().toString(), is(EnergyItems.AWAITING_FIRST_CYCLE));
        }
    }

    /**
     * A provider is asked more than once, and a fresh Item each time would lose the state the publisher has been
     * maintaining on it.
     */
    @Test
    public void theSameItemsComeBackEveryTime() {
        EnergyItemProvider provider = new EnergyItemProvider();

        assertThat(provider.getAll(), is(sameInstance(provider.getAll())));
    }
}
