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
package org.openhab.core.energy.series.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import javax.measure.Unit;
import javax.measure.quantity.Energy;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.price.EnergyPriceSeries;
import org.openhab.core.energy.price.EnergyPriceUnits;
import org.openhab.core.energy.price.PriceDirection;
import org.openhab.core.energy.window.Slot;
import org.openhab.core.energy.window.SlotSeries;
import org.openhab.core.library.dimension.Currency;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.persistence.FilterCriteria;
import org.openhab.core.persistence.HistoricItem;
import org.openhab.core.persistence.PersistenceService;
import org.openhab.core.persistence.PersistenceServiceRegistry;
import org.openhab.core.persistence.QueryablePersistenceService;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the future price series an Item carries out of persistence.
 * <p>
 * <strong>This is the class the engine bundle may not contain.</strong> It calls
 * {@link QueryablePersistenceService#query}, and {@code .query(}, {@code FilterCriteria}, {@code HistoricItem} and
 * {@code PersistenceService} are all forbidden tokens there, pinned by
 * {@code ShadowModeDemonstrationTest#theOnlyPersistenceAccessInTheBundleIsReadingTheConfiguration}. That is not an
 * obstacle worked around: it is owner decision D23's split applied to the input side. The value is computed where
 * nothing can be written, and the touching of openHAB's own data surfaces happens where that is the point.
 *
 * <h2>Where the future comes from</h2>
 * openHAB 4.1 made future-timestamped series native: a binding publishes a {@code TimeSeries}, the {@code forecast}
 * persistence strategy stores its entries under their own future timestamps, and a query with a begin date in the
 * future returns them. So nothing here has to invent a transport - the capability the issue's step 1 asked for is
 * already in the framework, and this is a query against it.
 *
 * <h2>Slot geometry</h2>
 * Persistence stores instants, not intervals, so a slot's end is the next entry's start - the same reconstruction the
 * acceptance fixtures need, and the same one their own README describes. The <strong>last</strong> slot has no
 * successor to end it, so its width is taken from the entry before it. That is an inference and it is the only one
 * here; a series of one entry has no width to infer and is refused rather than guessed at.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ItemPriceSeriesReader {

    private final Logger logger = LoggerFactory.getLogger(ItemPriceSeriesReader.class);
    private final PersistenceServiceRegistry persistenceServices;

    /**
     * Creates the reader.
     *
     * @param persistenceServices the persistence services a site has installed
     */
    public ItemPriceSeriesReader(PersistenceServiceRegistry persistenceServices) {
        this.persistenceServices = persistenceServices;
    }

    /**
     * Reads whatever future prices an Item currently carries.
     *
     * @param itemName the Item the source publishes its prices to
     * @param serviceId the persistence service to read from, or {@code null} for the site's default
     * @param horizon how far ahead to read
     * @param currency the currency the numbers are in, used only when the stored states are plain numbers
     * @param energyUnit the unit of energy the prices are per
     * @param marketZone the market's time zone, which names the delivery day
     * @param direction whether these prices value an imported or an exported kilowatt-hour
     * @param now the moment to read forward from, supplied by the caller so that nothing here consults a clock
     * @return the series, or empty when the service cannot be queried or has fewer than two future entries
     */
    public Optional<EnergyPriceSeries> read(String itemName, @Nullable String serviceId, Duration horizon,
            Unit<Currency> currency, Unit<Energy> energyUnit, ZoneId marketZone, PriceDirection direction,
            Instant now) {
        Optional<QueryablePersistenceService> service = queryable(serviceId);
        if (service.isEmpty()) {
            return Optional.empty();
        }
        FilterCriteria criteria = new FilterCriteria().setItemName(itemName)
                .setBeginDate(ZonedDateTime.ofInstant(now, marketZone))
                .setEndDate(ZonedDateTime.ofInstant(now.plus(horizon), marketZone))
                .setOrdering(FilterCriteria.Ordering.ASCENDING);

        List<HistoricItem> entries = new ArrayList<>();
        service.get().query(criteria).forEach(entries::add);
        if (entries.size() < 2) {
            logger.debug("Item '{}' carries {} future price entries, which is not enough to derive a slot width",
                    itemName, entries.size());
            return Optional.empty();
        }

        List<Slot> slots = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            Optional<Double> value = number(itemName, entries.get(index), currency, energyUnit);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            Instant start = entries.get(index).getInstant();
            Instant end = index + 1 < entries.size() ? entries.get(index + 1).getInstant()
                    : start.plus(Duration.between(entries.get(index - 1).getInstant(), start));
            if (!end.isAfter(start)) {
                logger.warn("Ignoring the future price series of item '{}': two entries share the timestamp {}",
                        itemName, start);
                return Optional.empty();
            }
            slots.add(new Slot(start, end, value.get()));
        }
        return Optional.of(new EnergyPriceSeries(new SlotSeries(slots, direction.sense()), currency, energyUnit,
                marketZone, direction));
    }

    /**
     * Returns the persistence service to read from, if it can be queried at all.
     *
     * @param serviceId the configured service id, or {@code null} for the site's default
     * @return the service, or empty when it is missing or not queryable
     */
    private Optional<QueryablePersistenceService> queryable(@Nullable String serviceId) {
        PersistenceService service = serviceId == null ? persistenceServices.getDefault()
                : persistenceServices.get(serviceId);
        if (service == null) {
            logger.warn("No persistence service {} is installed, so no future price series can be read",
                    serviceId == null ? "is configured as the default and none" : "called '" + serviceId + "'");
            return Optional.empty();
        }
        if (service instanceof QueryablePersistenceService queryable) {
            return Optional.of(queryable);
        }
        logger.warn("Persistence service '{}' cannot be queried, so no future price series can be read from it",
                service.getId());
        return Optional.empty();
    }

    /**
     * Reads a number out of a persisted state, accepting both the typed and the bare form.
     * <p>
     * A {@code Number:EnergyPrice} state is what the requirement asks a source to publish, and it is what this reads
     * first. A plain {@code DecimalType} is accepted too, because several price bindings already publish one and the
     * proposal's own impact note says they become sources "without breaking changes" - the price unit is then the
     * one the site configured rather than one the Item carried.
     * <p>
     * <strong>A typed state's own unit is authoritative and is never ignored.</strong> Where the state is denominated
     * per a different unit of energy from the one configured - the ordinary case of an ENTSO-E binding publishing
     * {@code EUR/MWh} onto a site whose page says {@code kWh} - the number is converted onto the configured
     * denominator, which is arithmetic that needs no exchange rate. Where the currencies differ the whole series is
     * refused rather than converted: crossing currencies needs a rate the user never saw, and the plane's own
     * non-goal defers exchange. Reading the number and dropping the unit, which is what this used to do, is the one
     * option that is wrong by a factor of a thousand in silence.
     *
     * @param itemName the item being read, for the message
     * @param entry the persisted entry
     * @param currency the currency the series is to be denominated in
     * @param energyUnit the unit of energy the series is to be per
     * @return the number in the configured denomination, or empty when the entry cannot be read as one
     */
    private Optional<Double> number(String itemName, HistoricItem entry, Unit<Currency> currency,
            Unit<Energy> energyUnit) {
        State state = entry.getState();
        if (state instanceof QuantityType<?> quantity) {
            Unit<?> stateUnit = quantity.getUnit();
            if (Units.ONE.equals(stateUnit)) {
                // a dimensionless quantity carries no denomination of its own, so the configured one stands
                return Optional.of(quantity.doubleValue());
            }
            OptionalDouble factor = EnergyPriceUnits.denominatorFactorFrom(stateUnit, currency, energyUnit);
            if (factor.isEmpty()) {
                logger.warn(
                        "Ignoring the future price series of item '{}': the entry at {} is denominated in {} but the "
                                + "source is configured as {} per {}, and this plane does not convert between "
                                + "currencies",
                        itemName, entry.getInstant(), stateUnit, currency.getName(), energyUnit);
                return Optional.empty();
            }
            return Optional.of(quantity.doubleValue() * factor.getAsDouble());
        }
        if (state instanceof DecimalType decimal) {
            return Optional.of(decimal.doubleValue());
        }
        logger.warn("Ignoring the future price series of item '{}': the entry at {} is {}, which is not a number",
                itemName, entry.getInstant(), state);
        return Optional.empty();
    }
}
