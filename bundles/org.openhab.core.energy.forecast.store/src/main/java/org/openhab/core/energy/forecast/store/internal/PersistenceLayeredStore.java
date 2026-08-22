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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.ConfigurableService;
import org.openhab.core.energy.forecast.ForecastPlaneCondition;
import org.openhab.core.energy.forecast.ForecastRole;
import org.openhab.core.energy.forecast.ForecastSeries;
import org.openhab.core.energy.forecast.ForecastTimeSeries;
import org.openhab.core.energy.forecast.LayeredEntry;
import org.openhab.core.energy.forecast.LayeredSeriesResolver;
import org.openhab.core.energy.forecast.LayeredWritePlan;
import org.openhab.core.energy.forecast.LayeredWritePolicy;
import org.openhab.core.energy.forecast.SeriesLayer;
import org.openhab.core.energy.forecast.store.LayeredPredictionStore;
import org.openhab.core.energy.forecast.store.LayeredWriteReport;
import org.openhab.core.energy.window.SeriesSense;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.persistence.FilterCriteria;
import org.openhab.core.persistence.HistoricItem;
import org.openhab.core.persistence.ModifiablePersistenceService;
import org.openhab.core.persistence.PersistenceService;
import org.openhab.core.persistence.PersistenceServiceRegistry;
import org.openhab.core.types.State;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The layered prediction series, on top of a {@link ModifiablePersistenceService}.
 * <p>
 * <strong>THIS CLASS WRITES ITEM HISTORY, ON PURPOSE.</strong> It is the half of _Layered prediction series_ that the
 * framework bundle is forbidden to do: {@code org.openhab.core.energy} may never write an Item and may not so much as
 * name {@code .store(}, {@code .query(} or {@code FilterCriteria}, and five tests hold it to that. What is decided
 * there is <em>what</em> a write should produce - {@link LayeredSeriesResolver} - and what happens here is only the
 * carrying out.
 * <p>
 * The bound on what it writes is worth stating as plainly as the publishing bundle states its own:
 * <ul>
 * <li>it writes <strong>only Items a site named</strong> in this component's own configuration, so it can never
 * scribble on something nobody pointed it at;</li>
 * <li>it writes <strong>history</strong>, through the persistence service - never a command, never a state update, so
 * nothing it does moves a device or changes what an Item currently reads;</li>
 * <li>it writes <strong>only what a caller handed it</strong>, one entry per timestamp, and reports every entry it
 * refused.</li>
 * </ul>
 * <p>
 * <strong>Why a modifiable service is required rather than nice to have.</strong> The requirement is a series
 * "updatable at any time over any of its entries - past, present or future". Appending is not that, and core's own
 * persistence manager already filters on {@code instanceof ModifiablePersistenceService} before storing any time
 * series at all, so a non-modifiable service would not even receive the future half. A site pointed at one is
 * therefore refused and told, rather than being given a surface that quietly only works forwards.
 * <p>
 * <strong>The layer ledger is in memory and does not survive a restart.</strong> Persistence stores a number at a
 * timestamp and nothing about who wrote it. The collision rules that need to know which layer holds an entry can
 * therefore only see writes that came through this surface since the framework started; after a restart, an entry's
 * layer is unknown and it takes part in no collision. That is a property of the storage model rather than of this
 * implementation, and it is the sharpest thing this prototype has to say about the corpus's open writer-precedence
 * question.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
// both types are published: contributors bind the interface, and the registrar in this bundle needs this component's
// own configuration, which is deliberately not on the interface. Publishing only the interface left the registrar's
// mandatory reference unsatisfiable and made the Karaf feature fail to resolve - a defect no unit test could see,
// because it is a property of the generated component descriptors rather than of any code path.
@Component(immediate = true, service = { LayeredPredictionStore.class,
        PersistenceLayeredStore.class }, configurationPid = PersistenceLayeredStore.CONFIGURATION_PID, configurationPolicy = ConfigurationPolicy.OPTIONAL, property = Constants.SERVICE_PID
                + "=" + PersistenceLayeredStore.CONFIGURATION_PID)
@ConfigurableService(category = "system", label = "Energy Management Forecast Storage", description_uri = PersistenceLayeredStore.CONFIG_URI)
public class PersistenceLayeredStore implements LayeredPredictionStore {

    /**
     * The configuration pid of the forecast storage component.
     */
    public static final String CONFIGURATION_PID = "org.openhab.core.energy.forecast.store";

    /**
     * The configuration description uri of the forecast storage component.
     */
    public static final String CONFIG_URI = "system:energy-forecast-store";

    private static final int PAGE_SIZE = 100_000;

    private final Logger logger = LoggerFactory.getLogger(PersistenceLayeredStore.class);

    private final ItemRegistry itemRegistry;
    private final PersistenceServiceRegistry persistenceServiceRegistry;
    private final Map<String, NavigableMap<Instant, LayeredEntry>> ledger = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastWriteAt = new ConcurrentHashMap<>();
    private final Clock clock;

    private volatile StoreConfiguration configuration = StoreConfiguration.defaults();

    /**
     * Creates the store.
     *
     * @param itemRegistry the registry the named Items are resolved through
     * @param persistenceServiceRegistry the registry the configured persistence service is taken from
     * @param properties the component properties
     */
    @Activate
    public PersistenceLayeredStore(final @Reference ItemRegistry itemRegistry,
            final @Reference PersistenceServiceRegistry persistenceServiceRegistry, Map<String, Object> properties) {
        this(itemRegistry, persistenceServiceRegistry, Clock.systemUTC(), properties);
    }

    /**
     * Creates the store with a clock of the caller's choosing, which is what makes a run's age testable.
     *
     * @param itemRegistry the registry the named Items are resolved through
     * @param persistenceServiceRegistry the registry the configured persistence service is taken from
     * @param clock the clock a write is timed by
     * @param properties the component properties
     */
    public PersistenceLayeredStore(ItemRegistry itemRegistry, PersistenceServiceRegistry persistenceServiceRegistry,
            Clock clock, Map<String, Object> properties) {
        this.itemRegistry = itemRegistry;
        this.persistenceServiceRegistry = persistenceServiceRegistry;
        this.clock = clock;
        modified(properties);
    }

    /**
     * Re-reads the configuration.
     *
     * @param properties the component properties
     */
    @Modified
    public void modified(Map<String, Object> properties) {
        configuration = StoreConfiguration.fromProperties(properties,
                rejected -> logger.warn("Energy forecast storage configuration: {}", rejected));
    }

    /**
     * Returns the site's configuration, which the components sharing this pid read through the store rather than
     * reading the pid twice.
     *
     * @return the configuration
     */
    StoreConfiguration configuration() {
        return configuration;
    }

    @Override
    public LayeredWriteReport apply(String itemName, SeriesLayer layer, ForecastSeries series) {
        Set<ForecastPlaneCondition> conditions = EnumSet.noneOf(ForecastPlaneCondition.class);
        @Nullable
        ModifiablePersistenceService service = modifiableService(conditions);
        @Nullable
        Item item = item(itemName, conditions);
        if (service == null || item == null) {
            return LayeredWriteReport.refused(conditions);
        }
        LayeredWritePolicy policy = configuration.writePolicy();
        List<LayeredEntry> incoming = entriesOf(series, layer);
        LayeredWritePlan plan = LayeredSeriesResolver.resolve(rememberedEntries(itemName), incoming, policy,
                configuration.layerPrecedence());
        conditions.addAll(plan.conditions());

        int written = write(service, item, itemName, series.unit(), plan.entries());

        int constrained = 0;
        if (!plan.constraints().isEmpty()) {
            Optional<String> capItem = configuration.capItemOf(itemName);
            if (capItem.isEmpty()) {
                conditions.add(ForecastPlaneCondition.CAP_SERIES_UNCONFIGURED);
                logger.warn(
                        "Caps are kept out of the prediction on this site, but no constraint series is configured for '{}', so {} cap entries were dropped",
                        itemName, plan.constraints().size());
            } else {
                @Nullable
                Item constraintItem = item(capItem.get(), conditions);
                if (constraintItem != null) {
                    constrained = write(service, constraintItem, capItem.get(), series.unit(), plan.constraints());
                }
            }
        }
        if (!plan.collisions().isEmpty()) {
            logger.warn("A {} write met {} entries another layer had written on '{}'; the site's policy is {}",
                    layer.id(), plan.collisions().size(), itemName,
                    policy == null ? "unset, so the newest write wins and the older value is gone" : policy.id());
        }
        return new LayeredWriteReport(written, constrained, plan.refused().size(), plan.collisions(), conditions);
    }

    @Override
    public Optional<ForecastSeries> read(String itemName, ForecastRole role, Instant from, @Nullable Instant to) {
        Set<ForecastPlaneCondition> conditions = EnumSet.noneOf(ForecastPlaneCondition.class);
        @Nullable
        ModifiablePersistenceService service = modifiableService(conditions);
        if (service == null) {
            return Optional.empty();
        }
        Optional<SlotSeries> prediction = query(service, itemName, role, from, to);
        if (prediction.isEmpty()) {
            return Optional.empty();
        }
        SlotSeries values = prediction.get();
        if (configuration.writePolicy() == LayeredWritePolicy.CAP_COMPOSED_AT_READ_TIME) {
            Optional<String> capItem = configuration.capItemOf(itemName);
            if (capItem.isPresent()) {
                Optional<SlotSeries> cap = query(service, capItem.get(), role, from, to);
                if (cap.isPresent()) {
                    values = LayeredSeriesResolver.composeCap(values, cap.get());
                }
            }
        }
        return Optional.of(new ForecastSeries(role, itemName, role.canonicalUnit(), newestRun(itemName), values));
    }

    /**
     * Returns the entries this framework remembers writing to a series, which is all the collision rules can see.
     *
     * @param itemName the Item carrying the series
     * @return the remembered entries
     */
    private List<LayeredEntry> rememberedEntries(String itemName) {
        NavigableMap<Instant, LayeredEntry> remembered = ledger.get(itemName);
        return remembered == null ? List.of() : List.copyOf(remembered.values());
    }

    private int write(ModifiablePersistenceService service, Item item, String itemName, Unit<?> unit,
            List<LayeredEntry> entries) {
        NavigableMap<Instant, LayeredEntry> remembered = Objects
                .requireNonNull(ledger.computeIfAbsent(itemName, name -> new ConcurrentSkipListMap<>()));
        for (LayeredEntry entry : entries) {
            State state = new QuantityType<>(entry.value(), unit);
            service.store(item, ZonedDateTime.ofInstant(entry.timestamp(), ZoneId.systemDefault()), state);
            remembered.put(entry.timestamp(), entry);
        }
        if (!entries.isEmpty()) {
            lastWriteAt.put(itemName, clock.instant());
        }
        return entries.size();
    }

    private Optional<SlotSeries> query(ModifiablePersistenceService service, String itemName, ForecastRole role,
            Instant from, @Nullable Instant to) {
        FilterCriteria filter = new FilterCriteria().setItemName(itemName)
                .setBeginDate(ZonedDateTime.ofInstant(from, ZoneId.systemDefault()))
                .setOrdering(FilterCriteria.Ordering.ASCENDING).setPageSize(PAGE_SIZE);
        if (to != null) {
            filter.setEndDate(ZonedDateTime.ofInstant(to, ZoneId.systemDefault()));
        }
        List<HistoricItem> history = new ArrayList<>();
        service.query(filter).forEach(history::add);
        if (history.isEmpty()) {
            return Optional.empty();
        }
        Unit<?> unit = role.canonicalUnit();
        SeriesSense sense = role.defaultSense();
        List<Slot> slots = new ArrayList<>(history.size());
        for (int i = 0; i < history.size(); i++) {
            Instant start = history.get(i).getInstant();
            Instant end = i + 1 < history.size() ? history.get(i + 1).getInstant() : end(start, slots);
            @Nullable
            Double value = ForecastTimeSeries.valueOf(history.get(i).getState(), unit);
            if (value == null) {
                logger.debug("Ignoring a stored value of '{}' at {} that carries no number in {}", itemName, start,
                        unit);
                continue;
            }
            if (end.isAfter(start)) {
                slots.add(new Slot(start, end, value));
            }
        }
        return slots.isEmpty() ? Optional.empty() : Optional.of(new SlotSeries(slots, sense));
    }

    /**
     * Returns the end of the last stored entry, which storage does not carry: the width of the entry before it, or an
     * hour where there is no entry before it.
     *
     * @param start the last entry's own timestamp
     * @param sofar the slots built so far
     * @return the end of the last slot
     */
    private static Instant end(Instant start, List<Slot> sofar) {
        Duration width = sofar.isEmpty() ? Duration.ofHours(1) : sofar.getLast().duration();
        return start.plus(width);
    }

    /**
     * Returns when this framework last wrote to a series, which is the closest thing storage offers to the moment a
     * run was made: a stored entry carries when its value <em>applies</em> and never when it was computed. A series
     * this framework has not written since it started is dated from the epoch, so it reads as old rather than as
     * fresh.
     *
     * @param itemName the Item carrying the series
     * @return when the series was last written through this surface
     */
    private Instant newestRun(String itemName) {
        return lastWriteAt.getOrDefault(itemName, Instant.EPOCH);
    }

    /**
     * Returns a forecast's values as layered entries, one per slot start.
     *
     * @param series the forecast
     * @param layer the writer
     * @return the entries
     */
    private static List<LayeredEntry> entriesOf(ForecastSeries series, SeriesLayer layer) {
        List<LayeredEntry> entries = new ArrayList<>(series.size());
        for (Slot slot : series.values().slots()) {
            entries.add(new LayeredEntry(slot.start(), slot.value(), layer));
        }
        return List.copyOf(entries);
    }

    private @Nullable ModifiablePersistenceService modifiableService(Set<ForecastPlaneCondition> conditions) {
        @Nullable
        String serviceId = configuration.persistenceServiceId();
        @Nullable
        PersistenceService service = serviceId == null ? persistenceServiceRegistry.getDefault()
                : persistenceServiceRegistry.get(serviceId);
        if (service instanceof ModifiablePersistenceService modifiable) {
            return modifiable;
        }
        conditions.add(ForecastPlaneCondition.PERSISTENCE_NOT_MODIFIABLE);
        logger.warn(
                "A layered prediction series needs a persistence service that can modify what it stored; '{}' cannot, so nothing was written. Services that can: influxdb, inmemory, jdbc, mongodb",
                service == null ? serviceId : service.getId());
        return null;
    }

    private @Nullable Item item(String itemName, Set<ForecastPlaneCondition> conditions) {
        try {
            return itemRegistry.getItem(itemName);
        } catch (ItemNotFoundException e) {
            conditions.add(ForecastPlaneCondition.ITEM_UNRESOLVED);
            logger.warn("The prediction series is configured on '{}', which is not an Item", itemName);
            return null;
        }
    }
}
