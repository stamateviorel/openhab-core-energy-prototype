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
package org.openhab.core.energy.forecast.store.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.openhab.core.energy.forecast.ForecastRole;
import org.openhab.core.energy.forecast.ForecastSeries;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.persistence.FilterCriteria;
import org.openhab.core.persistence.HistoricItem;
import org.openhab.core.persistence.ModifiablePersistenceService;
import org.openhab.core.persistence.PersistenceService;
import org.openhab.core.persistence.PersistenceServiceRegistry;
import org.openhab.core.types.State;

/**
 * The fakes the storage tests run against: an in-memory persistence service that can modify what it stored, one that
 * cannot, and the small registries around them.
 * <p>
 * They are deliberately literal about the one contract this bundle depends on - "adding data with the same time as an
 * existing record should update the current record value rather than adding a new record" - because that sentence is
 * what makes a layered series possible at all, and a fake that appended instead would let a broken implementation
 * pass.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class StoreFixtures {

    /**
     * Midnight of the synthetic day the storage tests use.
     */
    public static final Instant DAY_START = Instant.parse("2026-01-15T00:00:00Z");

    private StoreFixtures() {
    }

    /**
     * Returns a forecast of hourly values from {@link #DAY_START}.
     *
     * @param role what the series is about
     * @param unit what the values are in
     * @param values the hourly values
     * @return the series
     */
    public static ForecastSeries hourly(ForecastRole role, Unit<?> unit, double... values) {
        return ForecastSeries.of(role, "fixture", unit, DAY_START.minus(Duration.ofHours(1)),
                SlotSeries.hourly(DAY_START, values));
    }

    /**
     * Returns a forecast of hourly values starting at a given hour of the synthetic day.
     *
     * @param role what the series is about
     * @param unit what the values are in
     * @param firstHour the hour of the day the first value applies from
     * @param values the hourly values
     * @return the series
     */
    public static ForecastSeries hourlyFrom(ForecastRole role, Unit<?> unit, int firstHour, double... values) {
        return ForecastSeries.of(role, "fixture", unit, DAY_START.minus(Duration.ofHours(1)),
                SlotSeries.hourly(DAY_START.plus(Duration.ofHours(firstHour)), values));
    }

    /**
     * An in-memory {@link ModifiablePersistenceService}: it keeps one value per item and timestamp, and a second store
     * at the same timestamp replaces the first, exactly as the interface requires.
     *
     * @author Stamate Viorel - Initial contribution
     */
    public static final class InMemoryPersistence implements ModifiablePersistenceService {

        private final Map<String, NavigableMap<Instant, State>> stored = new LinkedHashMap<>();

        @Override
        public String getId() {
            return "inmemory";
        }

        @Override
        public String getLabel(@Nullable Locale locale) {
            return "In Memory";
        }

        @Override
        public void store(Item item) {
            // an explicit zone rather than the machine's: a fixture that reads the host's default makes the same
            // test mean different things on two developers' laptops
            store(item, ZonedDateTime.now(ZoneId.of("UTC")), item.getState());
        }

        @Override
        public void store(Item item, @Nullable String alias) {
            store(item);
        }

        @Override
        public void store(Item item, ZonedDateTime date, State state) {
            NavigableMap<Instant, State> series = Objects
                    .requireNonNull(stored.computeIfAbsent(item.getName(), name -> new TreeMap<>()));
            series.put(date.toInstant(), state);
        }

        @Override
        public void store(Item item, ZonedDateTime date, State state, @Nullable String alias) {
            store(item, date, state);
        }

        @Override
        public boolean remove(FilterCriteria filter) {
            String itemName = filter.getItemName();
            if (itemName == null) {
                throw new IllegalArgumentException("itemName must not be null");
            }
            return stored.remove(itemName) != null;
        }

        @Override
        public Iterable<HistoricItem> query(FilterCriteria filter) {
            String itemName = filter.getItemName();
            NavigableMap<Instant, State> series = itemName == null ? null : stored.get(itemName);
            if (series == null) {
                return List.of();
            }
            List<HistoricItem> answer = new ArrayList<>();
            String name = Objects.requireNonNull(itemName);
            series.forEach((timestamp, state) -> {
                ZonedDateTime begin = filter.getBeginDate();
                ZonedDateTime end = filter.getEndDate();
                if (begin != null && timestamp.isBefore(begin.toInstant())) {
                    return;
                }
                if (end != null && timestamp.isAfter(end.toInstant())) {
                    return;
                }
                answer.add(historic(name, timestamp, state));
            });
            return answer;
        }

        /**
         * Returns how many values are stored for an item, which is what tells an overwrite from an append.
         *
         * @param itemName the item
         * @return the number of stored values
         */
        public int size(String itemName) {
            NavigableMap<Instant, State> series = stored.get(itemName);
            return series == null ? 0 : series.size();
        }

        /**
         * Returns one stored value.
         *
         * @param itemName the item
         * @param timestamp the timestamp
         * @return the state, or {@code null} when nothing is stored at that timestamp
         */
        public @Nullable State valueAt(String itemName, Instant timestamp) {
            NavigableMap<Instant, State> series = stored.get(itemName);
            return series == null ? null : series.get(timestamp);
        }

        private static HistoricItem historic(String itemName, Instant timestamp, State state) {
            return new HistoricItem() {

                @Override
                public ZonedDateTime getTimestamp() {
                    return ZonedDateTime.ofInstant(timestamp, ZoneId.systemDefault());
                }

                @Override
                public State getState() {
                    return state;
                }

                @Override
                public String getName() {
                    return itemName;
                }
            };
        }
    }

    /**
     * A persistence service that can store the present and never the past - the kind a layered prediction series has to
     * refuse.
     *
     * @author Stamate Viorel - Initial contribution
     */
    public static final class AppendOnlyPersistence implements PersistenceService {

        @Override
        public String getId() {
            return "rrd4j";
        }

        @Override
        public String getLabel(@Nullable Locale locale) {
            return "Append Only";
        }

        @Override
        public void store(Item item) {
        }

        @Override
        public void store(Item item, @Nullable String alias) {
        }
    }

    /**
     * A persistence-service registry holding exactly what a test put in it.
     *
     * @author Stamate Viorel - Initial contribution
     */
    public static final class FakeServiceRegistry implements PersistenceServiceRegistry {

        private final @Nullable PersistenceService service;

        /**
         * Creates a registry over one service.
         *
         * @param service the service, or {@code null} for a site with none
         */
        public FakeServiceRegistry(@Nullable PersistenceService service) {
            this.service = service;
        }

        @Override
        public @Nullable PersistenceService getDefault() {
            return service;
        }

        @Override
        public @Nullable PersistenceService get(@Nullable String serviceId) {
            PersistenceService held = service;
            return held != null && held.getId().equals(serviceId) ? held : null;
        }

        @Override
        public @Nullable String getDefaultId() {
            PersistenceService held = service;
            return held == null ? null : held.getId();
        }

        @Override
        public Set<PersistenceService> getAll() {
            PersistenceService held = service;
            return held == null ? Set.of() : Set.of(held);
        }
    }

    /**
     * Returns an item registry holding exactly the named items, and refusing anything else the way the real one does.
     *
     * @param itemNames the items that exist
     * @return the registry
     */
    public static ItemRegistry itemRegistry(String... itemNames) {
        Map<String, Item> items = new LinkedHashMap<>();
        for (String name : itemNames) {
            items.put(name, new NumberItem(name));
        }
        ItemRegistry registry = Mockito.mock(ItemRegistry.class);
        try {
            Mockito.when(registry.getItem(ArgumentMatchers.anyString())).thenAnswer(invocation -> {
                String name = invocation.getArgument(0);
                Item item = items.get(name);
                if (item == null) {
                    throw new ItemNotFoundException(name);
                }
                return item;
            });
        } catch (ItemNotFoundException e) {
            throw new IllegalStateException(e);
        }
        return registry;
    }
}
