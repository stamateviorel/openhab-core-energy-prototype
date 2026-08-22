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

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.measure.Unit;
import javax.measure.quantity.Energy;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.ConfigurableService;
import org.openhab.core.energy.price.EnergyPriceSeries;
import org.openhab.core.energy.price.EnergyPriceSource;
import org.openhab.core.energy.price.EnergyPriceUnits;
import org.openhab.core.energy.price.GridPriceProvider;
import org.openhab.core.energy.price.PriceAdjustment;
import org.openhab.core.energy.price.PriceCompositionException;
import org.openhab.core.energy.price.PriceDirection;
import org.openhab.core.energy.price.PriceRole;
import org.openhab.core.energy.price.TariffCalendar;
import org.openhab.core.energy.price.TariffPeriod;
import org.openhab.core.i18n.TimeZoneProvider;
import org.openhab.core.library.dimension.Currency;
import org.openhab.core.library.unit.Units;
import org.openhab.core.persistence.PersistenceServiceRegistry;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The generic configurable grid-price provider openHAB ships, wired up: it reads a future price series from an Item
 * and puts it through the adjustments a site configured.
 * <p>
 * This is Kai's {@code GridEnergyProvider} - "so common cases need no custom binding". A site whose supplier
 * publishes raw ENTSO-E prices to an Item needs nobody to write a binding for its country's VAT rate; it names the
 * Item, the rate and the tariff windows and gets a consumer price.
 * <p>
 * <strong>Registered at {@code service.ranking = -2}</strong>, the number
 * {@code DefaultStateDescriptionFragmentProvider} uses for core's own shipped defaults. A core-shipped provider is a
 * floor, not a privilege: any add-on that publishes a better series for the same role outranks it without the user
 * configuring anything.
 * <p>
 * <strong>The arithmetic is not here.</strong> Every adjustment, the tariff calendar and the composition live in the
 * engine bundle where they are pure functions over values and can be tested against a bill. What is here is the
 * reading, the configuration and the OSGi lifecycle - and the reading is the whole reason this bundle exists.
 *
 * <h2>Configuration ({@value #CONFIGURATION_PID})</h2>
 * <dl>
 * <dt>{@value #CONFIG_ITEM}</dt>
 * <dd>The Item carrying the raw future price series. Nothing is read until this is set.</dd>
 * <dt>{@value #CONFIG_PERSISTENCE_SERVICE}</dt>
 * <dd>Which persistence service holds it; empty uses the site's default.</dd>
 * <dt>{@value #CONFIG_ROLE}</dt>
 * <dd>What the resulting series is - spot, grid tariff, taxes and fees, or feed-in.</dd>
 * <dt>{@value #CONFIG_CURRENCY} / {@value #CONFIG_ENERGY_UNIT} / {@value #CONFIG_MARKET_ZONE}</dt>
 * <dd>What the raw numbers mean, since an Item carrying a plain number says none of it. <strong>The currency and the
 * market zone have no shipped value and the provider stays inert until both are named</strong>, alongside the Item:
 * the zone because the requirement forbids inferring a delivery day from the site's zone or from UTC, the currency
 * because a guessed denomination is user-facing misinformation on a page somebody acts on.</dd>
 * <dt>{@value #CONFIG_HORIZON_HOURS}</dt>
 * <dd>How far ahead to read.</dd>
 * <dt>{@value #CONFIG_VAT_PERCENT} / {@value #CONFIG_FIXED_FEE} / {@value #CONFIG_SCALE} /
 * {@value #CONFIG_TARGET_ENERGY_UNIT}</dt>
 * <dd>The adjustments. <strong>They are applied in the order named in {@value #CONFIG_PIPELINE}</strong>, because the
 * requirement lists four adjustments and never says which order they apply in - and VAT on a price that already
 * carries a transfer fee is a different number from a fee added after VAT.</dd>
 * <dt>{@value #CONFIG_TARIFF_PERIODS}</dt>
 * <dd>Conditional tariff windows, written {@code id;months;days;from;to;amount}, for example
 * {@code winter-day;DEC,JAN,FEB;MON,TUE,WED,THU,FRI,SAT;07:00;22:00;0.06}. Months and days may be left empty for
 * "any". The window times are read in the <em>site's</em> zone, which is not the market's.</dd>
 * </dl>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(immediate = true, service = EnergyPriceSource.class, configurationPid = GridPriceSource.CONFIGURATION_PID, configurationPolicy = ConfigurationPolicy.OPTIONAL, property = {
        Constants.SERVICE_PID + "=org.openhab.core.energy.gridprice", Constants.SERVICE_RANKING + ":Integer=-2" })
@ConfigurableService(category = "system", label = "Energy Management Grid Price", description_uri = GridPriceSource.CONFIG_URI)
public class GridPriceSource implements EnergyPriceSource {

    /**
     * The configuration PID under which the generic provider reads its parameters.
     */
    public static final String CONFIGURATION_PID = "org.openhab.core.energy.gridprice";

    /**
     * The URI of the configuration description that renders the provider's parameters in the UI.
     */
    public static final String CONFIG_URI = "system:energy-gridprice";

    /**
     * The ranking a core-shipped default registers at, so that any add-on outranks it.
     */
    public static final int CORE_DEFAULT_RANKING = -2;

    /** The {@code item} configuration key. */
    public static final String CONFIG_ITEM = "item";
    /** The {@code persistenceService} configuration key. */
    public static final String CONFIG_PERSISTENCE_SERVICE = "persistenceService";
    /** The {@code role} configuration key. */
    public static final String CONFIG_ROLE = "role";
    /** The {@code currency} configuration key. */
    public static final String CONFIG_CURRENCY = "currency";
    /** The {@code energyUnit} configuration key. */
    public static final String CONFIG_ENERGY_UNIT = "energyUnit";
    /** The {@code marketZone} configuration key. */
    public static final String CONFIG_MARKET_ZONE = "marketZone";
    /** The {@code horizonHours} configuration key. */
    public static final String CONFIG_HORIZON_HOURS = "horizonHours";
    /** The {@code vatPercent} configuration key. */
    public static final String CONFIG_VAT_PERCENT = "vatPercent";
    /** The {@code fixedFee} configuration key. */
    public static final String CONFIG_FIXED_FEE = "fixedFee";
    /** The {@code scale} configuration key. */
    public static final String CONFIG_SCALE = "scale";
    /** The {@code targetEnergyUnit} configuration key. */
    public static final String CONFIG_TARGET_ENERGY_UNIT = "targetEnergyUnit";
    /** The {@code tariffPeriods} configuration key. */
    public static final String CONFIG_TARIFF_PERIODS = "tariffPeriods";
    /** The {@code tariffDefault} configuration key. */
    public static final String CONFIG_TARIFF_DEFAULT = "tariffDefault";
    /** The {@code pipeline} configuration key. */
    public static final String CONFIG_PIPELINE = "pipeline";

    /**
     * Every configuration key this component reads, which the configuration description is held to.
     */
    public static final Set<String> CONFIG_KEYS = Set.of(CONFIG_ITEM, CONFIG_PERSISTENCE_SERVICE, CONFIG_ROLE,
            CONFIG_CURRENCY, CONFIG_ENERGY_UNIT, CONFIG_MARKET_ZONE, CONFIG_HORIZON_HOURS, CONFIG_VAT_PERCENT,
            CONFIG_FIXED_FEE, CONFIG_SCALE, CONFIG_TARGET_ENERGY_UNIT, CONFIG_TARIFF_PERIODS, CONFIG_TARIFF_DEFAULT,
            CONFIG_PIPELINE);

    private final Logger logger = LoggerFactory.getLogger(GridPriceSource.class);
    private final ItemPriceSeriesReader reader;
    private final TimeZoneProvider timeZoneProvider;
    private final Clock clock;

    private volatile GridPriceConfiguration configuration = GridPriceConfiguration.unconfigured();

    /**
     * Creates the provider as OSGi activates it.
     *
     * @param persistenceServices the persistence services a site has installed
     * @param timeZoneProvider the site's own zone, which is what a tariff calendar is read in
     * @param properties the component configuration
     */
    @Activate
    public GridPriceSource(@Reference PersistenceServiceRegistry persistenceServices,
            @Reference TimeZoneProvider timeZoneProvider, Map<String, Object> properties) {
        this(new ItemPriceSeriesReader(persistenceServices), timeZoneProvider, Clock.systemUTC(), properties);
    }

    /**
     * Creates the provider with its collaborators supplied, which is how it is tested without OSGi.
     *
     * @param reader the Item-backed reader
     * @param timeZoneProvider the site's own zone
     * @param clock the clock the horizon is measured from
     * @param properties the component configuration
     */
    public GridPriceSource(ItemPriceSeriesReader reader, TimeZoneProvider timeZoneProvider, Clock clock,
            Map<String, Object> properties) {
        this.reader = reader;
        this.timeZoneProvider = timeZoneProvider;
        this.clock = clock;
        applyConfiguration(properties);
    }

    /**
     * Re-reads the configuration.
     *
     * @param properties the new component configuration
     */
    @Modified
    public void modified(Map<String, Object> properties) {
        applyConfiguration(properties);
    }

    @Override
    public String getSourceId() {
        return "gridprice";
    }

    @Override
    public PriceRole getRole() {
        return configuration.role();
    }

    @Override
    public int getServiceRanking() {
        return CORE_DEFAULT_RANKING;
    }

    @Override
    public Optional<EnergyPriceSeries> getSeries() {
        GridPriceConfiguration active = configuration;
        String item = active.item();
        Unit<Currency> currency = active.currency();
        ZoneId marketZone = active.marketZone();
        if (item == null || currency == null || marketZone == null) {
            return Optional.empty();
        }
        Optional<EnergyPriceSeries> raw = reader.read(item, active.persistenceService(), active.horizon(), currency,
                active.energyUnit(), marketZone, active.direction(), clock.instant());
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(active.provider(timeZoneProvider.getTimeZone()).apply(raw.get()));
        } catch (PriceCompositionException e) {
            logger.warn("The grid price pipeline could not be applied to item '{}': {}", item, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns the pipeline as a user would read it back, which is what a status page shows.
     *
     * @return the description
     */
    public String describe() {
        return configuration.provider(timeZoneProvider.getTimeZone()).describe();
    }

    private void applyConfiguration(Map<String, Object> properties) {
        configuration = GridPriceConfiguration.fromProperties(properties, warning -> logger.warn("{}", warning));
        List<String> missing = new ArrayList<>();
        if (configuration.item() == null) {
            missing.add(CONFIG_ITEM);
        }
        if (configuration.currency() == null) {
            missing.add(CONFIG_CURRENCY);
        }
        if (configuration.marketZone() == null) {
            missing.add(CONFIG_MARKET_ZONE);
        }
        if (missing.isEmpty()) {
            logger.debug("Generic grid price provider reading '{}' as {}", configuration.item(), configuration.role());
        } else {
            logger.info("The generic grid price provider is inert; still to configure: {}. An item carries bare "
                    + "numbers and says nothing about what currency they are in or which market's day they belong "
                    + "to, and none of the three is inferred", missing);
        }
    }

    /**
     * What the generic provider was configured with, parsed once so that {@link #getSeries()} does no parsing.
     *
     * @param item the Item carrying the raw prices, or {@code null} while the provider is unconfigured
     * @param persistenceService the persistence service to read from, or {@code null} for the site's default
     * @param role what the resulting series is
     * @param currency the currency the numbers are in, or {@code null} while nobody has said
     * @param energyUnit the unit of energy the raw numbers are per
     * @param marketZone the market's zone, which names the delivery day, or {@code null} while nobody has said
     * @param horizon how far ahead to read
     * @param adjustments the pipeline, in the order the site named, parsed
     * @param tariffPeriods the conditional tariff windows
     * @param tariffDefault what applies where no window does
     */
    record GridPriceConfiguration(@Nullable String item, @Nullable String persistenceService, PriceRole role,
            @Nullable Unit<Currency> currency, Unit<Energy> energyUnit, @Nullable ZoneId marketZone, Duration horizon,
            List<Step> adjustments, List<TariffPeriod> tariffPeriods, double tariffDefault) {

        private static final String STEP_VAT = "vat";
        private static final String STEP_FEE = "fee";
        private static final String STEP_SCALE = "scale";
        private static final String STEP_DENOMINATION = "denomination";
        private static final String STEP_TARIFF = "tariff";

        /**
         * One parsed pipeline entry.
         * <p>
         * Parsed when the configuration is read rather than when a series is asked for. A typo in the argument -
         * {@code vat=24%} - used to reach {@code Double.parseDouble} on every single read, throwing a
         * {@link NumberFormatException} out through the plane's own status surface. A configuration mistake is
         * reported where the configuration is read, like every other one here.
         *
         * @param name the step's name, lower case
         * @param argument its argument, zero for a step that takes none
         */
        record Step(String name, double argument) {
        }

        /**
         * Returns the configuration of a provider nobody has set up.
         * <p>
         * <strong>Neither the currency nor the market zone has a shipped value.</strong> An Item carrying bare
         * numbers says nothing about either, and both are claims about the site's own supply that this bundle cannot
         * check. {@code price-data} <em>Delivery-day identity and the market zone</em> is explicit that a delivery day
         * is "never inferred from the site's zone or from UTC", so defaulting the zone to UTC - which this used to do
         * - names a CET market's delivery day one day early with nothing reported. The currency follows the same
         * reasoning under D22's precedent: a guessed denomination reaches the user as {@code EUR/kWh} on a page they
         * act on.
         *
         * @return the unconfigured state
         */
        static GridPriceConfiguration unconfigured() {
            return new GridPriceConfiguration(null, null, PriceRole.SPOT, null, Units.KILOWATT_HOUR, null,
                    Duration.ofHours(48), List.of(), List.of(), 0);
        }

        /**
         * Returns the price direction implied by the role: only the feed-in role values an exported kilowatt-hour.
         *
         * @return the direction
         */
        PriceDirection direction() {
            return role == PriceRole.FEED_IN ? PriceDirection.FEED_IN : PriceDirection.CONSUMPTION;
        }

        /**
         * Builds the pipeline this configuration describes.
         *
         * @param siteZone the site's own zone, in which the tariff windows are read
         * @return the provider
         */
        GridPriceProvider provider(ZoneId siteZone) {
            List<PriceAdjustment> steps = new ArrayList<>(adjustments.size());
            for (Step step : adjustments) {
                steps.add(build(step, siteZone));
            }
            return new GridPriceProvider("gridprice", steps);
        }

        private PriceAdjustment build(Step step, ZoneId siteZone) {
            // every step name was checked against the known set when the configuration was read, so the last branch
            // is the tariff and not a catch-all for something unrecognised
            return switch (step.name()) {
                case STEP_VAT -> new PriceAdjustment.Vat(step.argument());
                case STEP_FEE -> new PriceAdjustment.FixedFee(step.argument(), energyUnit);
                case STEP_SCALE -> new PriceAdjustment.Scale(step.argument());
                case STEP_DENOMINATION -> new PriceAdjustment.Denomination(
                        step.argument() == 1000 ? Units.MEGAWATT_HOUR : Units.KILOWATT_HOUR);
                default ->
                    new PriceAdjustment.ConditionalTariff(new TariffCalendar(siteZone, tariffPeriods, tariffDefault));
            };
        }

        /**
         * Parses the component configuration.
         *
         * @param properties the component configuration
         * @param onWarning where a rejected entry is reported
         * @return the configuration
         */
        static GridPriceConfiguration fromProperties(Map<String, Object> properties,
                java.util.function.Consumer<String> onWarning) {
            GridPriceConfiguration defaults = unconfigured();
            String item = text(properties, CONFIG_ITEM);
            String service = text(properties, CONFIG_PERSISTENCE_SERVICE);
            PriceRole role = defaults.role();
            String configuredRole = text(properties, CONFIG_ROLE);
            if (configuredRole != null) {
                PriceRole parsed = role(configuredRole);
                if (parsed == null) {
                    onWarning.accept("Ignoring unknown price role '" + configuredRole + "'");
                } else {
                    role = parsed;
                }
            }
            Unit<Currency> currency = null;
            String configuredCurrency = text(properties, CONFIG_CURRENCY);
            if (configuredCurrency != null) {
                try {
                    currency = EnergyPriceUnits.currency(configuredCurrency);
                } catch (IllegalArgumentException e) {
                    onWarning.accept("Ignoring currency '" + configuredCurrency + "': " + e.getMessage());
                }
            }
            Unit<Energy> energyUnit = "MWh".equalsIgnoreCase(String.valueOf(text(properties, CONFIG_ENERGY_UNIT)))
                    ? Units.MEGAWATT_HOUR
                    : Units.KILOWATT_HOUR;
            ZoneId marketZone = null;
            String configuredZone = text(properties, CONFIG_MARKET_ZONE);
            if (configuredZone != null) {
                try {
                    marketZone = ZoneId.of(configuredZone);
                } catch (java.time.DateTimeException e) {
                    onWarning.accept("Ignoring unknown market zone '" + configuredZone + "'");
                }
            }
            Duration horizon = Duration.ofHours(number(properties, CONFIG_HORIZON_HOURS, 48).longValue());
            List<Step> pipeline = pipeline(properties, onWarning);
            List<TariffPeriod> periods = periods(properties, onWarning);
            return new GridPriceConfiguration(item, service, role, currency, energyUnit, marketZone, horizon, pipeline,
                    periods, number(properties, CONFIG_TARIFF_DEFAULT, 0).doubleValue());
        }

        /**
         * Reads the pipeline, parsing every argument here rather than on each read.
         * <p>
         * An unparseable argument or an unknown step name is warned about and dropped, which is the treatment already
         * given to an unknown role, an unknown currency, an unknown zone and a malformed tariff period. Leaving it to
         * be discovered on the next {@code getSeries()} put a {@link NumberFormatException} on a path with no
         * exception handling for it.
         *
         * @param properties the component configuration
         * @param onWarning where a rejected entry is reported
         * @return the parsed steps, in the order the site named
         */
        private static List<Step> pipeline(Map<String, Object> properties,
                java.util.function.Consumer<String> onWarning) {
            List<String> explicit = list(properties.get(CONFIG_PIPELINE));
            if (explicit.isEmpty()) {
                // no order named: build one from whichever adjustments carry a value, and say so, because the order of
                // a pipeline is exactly the thing the requirement does not decide
                explicit = derived(properties, onWarning);
            }
            List<Step> steps = new ArrayList<>(explicit.size());
            for (String entry : explicit) {
                String[] parts = entry.split("=", 2);
                String name = parts[0].trim().toLowerCase(Locale.ROOT);
                if (!Set.of(STEP_VAT, STEP_FEE, STEP_SCALE, STEP_DENOMINATION, STEP_TARIFF).contains(name)) {
                    onWarning.accept("Ignoring unknown pipeline step '" + entry + "'; the known ones are "
                            + List.of(STEP_VAT, STEP_FEE, STEP_SCALE, STEP_DENOMINATION, STEP_TARIFF));
                    continue;
                }
                double argument = 0;
                if (parts.length == 2) {
                    try {
                        argument = Double.parseDouble(parts[1].trim());
                    } catch (NumberFormatException e) {
                        onWarning.accept(
                                "Ignoring pipeline step '" + entry + "': '" + parts[1].trim() + "' is not a number");
                        continue;
                    }
                }
                steps.add(new Step(name, argument));
            }
            return List.copyOf(steps);
        }

        private static List<String> derived(Map<String, Object> properties,
                java.util.function.Consumer<String> onWarning) {
            List<String> derived = new ArrayList<>();
            if (properties.containsKey(CONFIG_SCALE)) {
                derived.add(STEP_SCALE + "=" + number(properties, CONFIG_SCALE, 1));
            }
            if (properties.containsKey(CONFIG_TARGET_ENERGY_UNIT)) {
                derived.add(STEP_DENOMINATION + "="
                        + ("MWh".equalsIgnoreCase(String.valueOf(text(properties, CONFIG_TARGET_ENERGY_UNIT))) ? 1000
                                : 1));
            }
            if (properties.containsKey(CONFIG_TARIFF_PERIODS)) {
                derived.add(STEP_TARIFF);
            }
            if (properties.containsKey(CONFIG_VAT_PERCENT)) {
                derived.add(STEP_VAT + "=" + number(properties, CONFIG_VAT_PERCENT, 0));
            }
            if (properties.containsKey(CONFIG_FIXED_FEE)) {
                derived.add(STEP_FEE + "=" + number(properties, CONFIG_FIXED_FEE, 0));
            }
            if (derived.size() > 1) {
                onWarning.accept("No '" + CONFIG_PIPELINE + "' order was configured, so the adjustments are applied as "
                        + derived + "; state the order yourself if your bill applies them differently");
            }
            return derived;
        }

        private static List<TariffPeriod> periods(Map<String, Object> properties,
                java.util.function.Consumer<String> onWarning) {
            List<TariffPeriod> periods = new ArrayList<>();
            for (String entry : list(properties.get(CONFIG_TARIFF_PERIODS))) {
                String[] parts = entry.split(";", -1);
                if (parts.length != 6) {
                    onWarning.accept(
                            "Ignoring tariff period '" + entry + "': it has to read id;months;days;from;to;amount");
                    continue;
                }
                try {
                    periods.add(new TariffPeriod(parts[0].trim(), months(parts[1]), days(parts[2]),
                            LocalTime.parse(parts[3].trim()), LocalTime.parse(parts[4].trim()),
                            Double.parseDouble(parts[5].trim())));
                } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
                    onWarning.accept("Ignoring tariff period '" + entry + "': " + e.getMessage());
                }
            }
            return periods;
        }

        /**
         * Reads a price role, accepting the enum spelling and the camel-case one a configuration page shows.
         *
         * @param name the configured name
         * @return the role, or {@code null} when nothing matches
         */
        private static @Nullable PriceRole role(String name) {
            String normalised = name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
            for (PriceRole candidate : PriceRole.values()) {
                if (candidate.name().replace("_", "").toLowerCase(Locale.ROOT).equals(normalised)) {
                    return candidate;
                }
            }
            return null;
        }

        /**
         * Reads month names, accepting both the full name and the three-letter abbreviation a bill uses.
         *
         * @param text the comma-separated list, empty meaning every month
         * @return the months
         * @throws IllegalArgumentException if a name matches nothing
         */
        private static Set<Month> months(String text) {
            Set<Month> months = EnumSet.noneOf(Month.class);
            for (String name : text.split(",")) {
                if (name.isBlank()) {
                    continue;
                }
                String wanted = name.trim().toUpperCase(Locale.ROOT);
                Month found = null;
                for (Month candidate : Month.values()) {
                    if (candidate.name().equals(wanted)
                            || candidate.name().startsWith(wanted) && wanted.length() >= 3) {
                        found = candidate;
                        break;
                    }
                }
                if (found == null) {
                    throw new IllegalArgumentException("'" + name.trim() + "' is not a month");
                }
                months.add(found);
            }
            return months;
        }

        /**
         * Reads day names, accepting both the full name and the three-letter abbreviation.
         *
         * @param text the comma-separated list, empty meaning every day
         * @return the days
         * @throws IllegalArgumentException if a name matches nothing
         */
        private static Set<DayOfWeek> days(String text) {
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            for (String name : text.split(",")) {
                if (name.isBlank()) {
                    continue;
                }
                String wanted = name.trim().toUpperCase(Locale.ROOT);
                DayOfWeek found = null;
                for (DayOfWeek candidate : DayOfWeek.values()) {
                    if (candidate.name().equals(wanted)
                            || candidate.name().startsWith(wanted) && wanted.length() >= 3) {
                        found = candidate;
                        break;
                    }
                }
                if (found == null) {
                    throw new IllegalArgumentException("'" + name.trim() + "' is not a day of the week");
                }
                days.add(found);
            }
            return days;
        }

        private static @Nullable String text(Map<String, Object> properties, String key) {
            Object value = properties.get(key);
            if (value == null || value.toString().isBlank()) {
                return null;
            }
            return value.toString().trim();
        }

        private static Number number(Map<String, Object> properties, String key, Number fallback) {
            Object value = properties.get(key);
            if (value instanceof Number configured) {
                return configured;
            }
            if (value != null) {
                try {
                    return Double.valueOf(value.toString().trim());
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
            return fallback;
        }

        private static List<String> list(@Nullable Object value) {
            List<String> entries = new ArrayList<>();
            if (value instanceof Iterable<?> iterable) {
                for (Object entry : iterable) {
                    if (!String.valueOf(entry).isBlank()) {
                        entries.add(String.valueOf(entry).trim());
                    }
                }
            } else if (value != null) {
                for (String entry : value.toString().split(",")) {
                    if (!entry.isBlank()) {
                        entries.add(entry.trim());
                    }
                }
            }
            return entries;
        }
    }
}
