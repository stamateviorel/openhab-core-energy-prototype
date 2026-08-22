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
package org.openhab.core.energy.price.internal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.ConfigurableService;
import org.openhab.core.energy.price.EnergyPriceRegistry;
import org.openhab.core.energy.price.EnergyPriceSeries;
import org.openhab.core.energy.price.EnergyPriceSource;
import org.openhab.core.energy.price.PriceComponent;
import org.openhab.core.energy.price.PriceComposition;
import org.openhab.core.energy.price.PriceCompositionException;
import org.openhab.core.energy.price.PricePlaneCondition;
import org.openhab.core.energy.price.PriceRole;
import org.openhab.core.energy.price.SeriesAlignment;
import org.openhab.core.energy.spi.SourceRanking;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves one {@link EnergyPriceSource} per {@link PriceRole} and composes the effective consumption price out of
 * the roles the site has switched on.
 *
 * <h2>Selection</h2>
 * A site may name the source it wants for a role; where it has not, the highest {@code service.ranking} wins, and a
 * tie between equal rankings is broken on the source id so that the answer never depends on the order in which
 * bundles started. This is core's own ranked-aggregator pattern, the same one {@code StateDescriptionServiceImpl}
 * uses and the same one wave 1 applied to participant declarations.
 *
 * <h2>Nothing is invented</h2>
 * Core ships <strong>no default composition</strong>. A fresh installation composes nothing and says so through
 * {@link PricePlaneCondition#COMPOSITION_UNCONFIGURED}, following owner decision D22's precedent: ship the shape,
 * ship no number, report unconfigured. The alternative - defaulting to "spot alone" - would silently present a
 * wholesale price as a consumer price, and every window a site optimised on it would be optimised on a number that
 * is not what it pays.
 *
 * <h2>Configuration ({@value #CONFIGURATION_PID})</h2>
 * <dl>
 * <dt>{@value #CONFIG_COMPONENTS}</dt>
 * <dd>Which roles make up the effective consumption price, in the order they are summed, for example
 * {@code spot,gridTariff,taxesAndFees}. Empty means the price plane composes nothing.</dd>
 * <dt>{@value #CONFIG_PREFERRED_SOURCES}</dt>
 * <dd>Which source to prefer for a role, written {@code role:sourceId}, for example {@code spot:entsoe}. Empty
 * prefers the highest ranked source of each role.</dd>
 * <dt>{@value #CONFIG_ALIGNMENT}</dt>
 * <dd>How components whose slots do not line up are brought onto one geometry: {@code union} (the default),
 * {@code finest} or {@code strict}. The corpus does not decide this; all three readings are implemented.</dd>
 * </dl>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(immediate = true, service = EnergyPriceRegistry.class, configurationPid = EnergyPriceRegistryImpl.CONFIGURATION_PID, configurationPolicy = ConfigurationPolicy.OPTIONAL, property = Constants.SERVICE_PID
        + "=org.openhab.core.energy.price")
@ConfigurableService(category = "system", label = "Energy Management Prices", description_uri = EnergyPriceRegistryImpl.CONFIG_URI)
public class EnergyPriceRegistryImpl implements EnergyPriceRegistry {

    /**
     * The configuration PID under which the price plane reads its parameters.
     */
    public static final String CONFIGURATION_PID = "org.openhab.core.energy.price";

    /**
     * The URI of the configuration description that renders the price parameters in the UI.
     */
    public static final String CONFIG_URI = "system:energy-price";

    /**
     * The {@code components} configuration key.
     */
    public static final String CONFIG_COMPONENTS = "components";

    /**
     * The {@code preferredSources} configuration key.
     */
    public static final String CONFIG_PREFERRED_SOURCES = "preferredSources";

    /**
     * The {@code alignment} configuration key.
     */
    public static final String CONFIG_ALIGNMENT = "alignment";

    /**
     * Every configuration key this component reads, which the configuration description is held to.
     */
    public static final Set<String> CONFIG_KEYS = Set.of(CONFIG_COMPONENTS, CONFIG_PREFERRED_SOURCES, CONFIG_ALIGNMENT);

    private final Logger logger = LoggerFactory.getLogger(EnergyPriceRegistryImpl.class);
    private final List<EnergyPriceSource> sources = new CopyOnWriteArrayList<>();

    private volatile List<PriceRole> composition = List.of();
    private volatile Map<PriceRole, String> preferred = Map.of();
    private volatile SeriesAlignment alignment = SeriesAlignment.unionOfBoundaries();

    /**
     * Creates the registry as OSGi activates it.
     *
     * @param properties the component configuration
     */
    @Activate
    public EnergyPriceRegistryImpl(Map<String, Object> properties) {
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

    /**
     * Adds a source as its bundle starts.
     *
     * @param source the source
     */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    public void addSource(EnergyPriceSource source) {
        sources.add(source);
        logger.debug("Price source '{}' publishing {} joined at ranking {}", source.getSourceId(), source.getRole(),
                source.getServiceRanking());
    }

    /**
     * Removes a source as its bundle stops.
     *
     * @param source the source
     */
    public void removeSource(EnergyPriceSource source) {
        sources.remove(source);
        logger.debug("Price source '{}' publishing {} left", source.getSourceId(), source.getRole());
    }

    @Override
    public Optional<EnergyPriceSource> sourceFor(PriceRole role) {
        @Nullable
        String wanted = preferred.get(role);
        List<EnergyPriceSource> candidates = new ArrayList<>();
        for (EnergyPriceSource source : sources) {
            if (source.getRole() == role && (wanted == null || wanted.equals(source.getSourceId()))) {
                candidates.add(source);
            }
        }
        return SourceRanking.best(candidates);
    }

    @Override
    public Optional<EnergyPriceSeries> seriesFor(PriceRole role) {
        return sourceFor(role).flatMap(EnergyPriceSource::getSeries);
    }

    @Override
    public List<PriceComponent> components() {
        List<PriceComponent> resolved = new ArrayList<>(composition.size());
        for (PriceRole role : composition) {
            seriesFor(role).ifPresent(
                    series -> resolved.add(PriceComponent.of(role.name().toLowerCase(Locale.ROOT), role, series)));
        }
        return List.copyOf(resolved);
    }

    @Override
    public EnergyPriceSeries effectiveConsumptionPrice() throws PriceCompositionException {
        if (composition.isEmpty()) {
            throw new PriceCompositionException(PricePlaneCondition.COMPOSITION_UNCONFIGURED,
                    "the effective consumption price is not configured: name the components it is made of in '"
                            + CONFIG_COMPONENTS + "', for example spot,gridTariff,taxesAndFees");
        }
        List<PriceComponent> resolved = components();
        if (resolved.size() < composition.size()) {
            List<PriceRole> missing = new ArrayList<>(composition);
            resolved.forEach(component -> missing.remove(component.role()));
            throw new PriceCompositionException(PricePlaneCondition.NO_SOURCE, "no source is publishing prices for "
                    + missing + ", so the effective consumption price cannot be " + "composed");
        }
        return PriceComposition.compose(resolved, alignment);
    }

    @Override
    public Optional<EnergyPriceSeries> feedInPrice() {
        return seriesFor(PriceRole.FEED_IN);
    }

    @Override
    public Set<PricePlaneCondition> conditions() {
        Set<PricePlaneCondition> conditions = EnumSet.noneOf(PricePlaneCondition.class);
        if (composition.isEmpty()) {
            conditions.add(PricePlaneCondition.COMPOSITION_UNCONFIGURED);
        } else {
            try {
                effectiveConsumptionPrice();
            } catch (PriceCompositionException e) {
                conditions.add(e.getCondition());
            } catch (RuntimeException e) {
                // a contributed source is arbitrary code; the surface that exists to report configuration problems
                // must not be the one that throws
                logger.warn("A price source failed while the price plane was reporting its conditions", e);
                conditions.add(PricePlaneCondition.NO_SOURCE);
            }
        }
        return conditions;
    }

    private void applyConfiguration(Map<String, Object> properties) {
        List<PriceRole> roles = new ArrayList<>();
        for (String entry : split(properties.get(CONFIG_COMPONENTS))) {
            role(entry).ifPresentOrElse(role -> {
                if (role == PriceRole.FEED_IN) {
                    // the feed-in price is answered by feedInPrice(), not summed into the consumption price: the two
                    // value opposite kilowatt-hours, so a composition holding both ranks its slots backwards
                    logger.warn(
                            "Ignoring price component '{}': the feed-in price is modelled separately and is read "
                                    + "through the feed-in accessor rather than summed into the consumption price",
                            entry);
                } else {
                    roles.add(role);
                }
            }, () -> logger.warn("Ignoring unknown price component '{}'; the known ones are {}", entry, knownRoles()));
        }
        Map<PriceRole, String> selections = new LinkedHashMap<>();
        for (String entry : split(properties.get(CONFIG_PREFERRED_SOURCES))) {
            int separator = entry.indexOf(':');
            if (separator <= 0 || separator == entry.length() - 1) {
                logger.warn("Ignoring preferred price source '{}': it has to read role:sourceId, for example spot:{}",
                        entry, "entsoe");
                continue;
            }
            String roleName = entry.substring(0, separator);
            String sourceId = entry.substring(separator + 1);
            role(roleName).ifPresentOrElse(role -> selections.put(role, sourceId),
                    () -> logger.warn("Ignoring preferred price source for unknown role '{}'; the known ones are {}",
                            roleName, knownRoles()));
        }
        @Nullable
        Object configuredAlignment = properties.get(CONFIG_ALIGNMENT);
        composition = List.copyOf(roles);
        preferred = Map.copyOf(selections);
        alignment = SeriesAlignment.byId(configuredAlignment == null ? "" : configuredAlignment.toString());
        if (composition.isEmpty()) {
            logger.info("The energy price plane composes nothing yet - name the components of your effective "
                    + "consumption price in '{}'; there is no shipped default", CONFIG_COMPONENTS);
        } else {
            logger.debug("Effective consumption price composed from {} aligned by '{}', preferring {}", composition,
                    alignment.getId(), preferred);
        }
    }

    private static List<String> split(@Nullable Object value) {
        List<String> entries = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object entry : iterable) {
                add(entries, String.valueOf(entry));
            }
        } else if (value != null) {
            for (String entry : value.toString().split(",")) {
                add(entries, entry);
            }
        }
        return entries;
    }

    private static void add(List<String> entries, String entry) {
        String trimmed = entry.trim();
        if (!trimmed.isEmpty()) {
            entries.add(trimmed);
        }
    }

    /**
     * Reads a role from a configuration entry, accepting both the enum name and the camel-case spelling a
     * configuration page shows.
     *
     * @param name the configured name
     * @return the role, or empty when nothing matches
     */
    private static Optional<PriceRole> role(String name) {
        String normalised = name.replace("_", "").toLowerCase(Locale.ROOT);
        for (PriceRole role : PriceRole.values()) {
            if (role.name().replace("_", "").toLowerCase(Locale.ROOT).equals(normalised)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }

    private static List<String> knownRoles() {
        List<String> names = new ArrayList<>();
        for (PriceRole role : PriceRole.values()) {
            names.add(role.name().toLowerCase(Locale.ROOT));
        }
        return names;
    }
}
