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

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.level.PlannedLevelPublisher;
import org.openhab.core.energy.level.PlannedLevelSchedule;
import org.openhab.core.energy.price.EnergyPriceSeries;
import org.openhab.core.energy.price.EnergyPriceSource;
import org.openhab.core.energy.price.EnergyPriceUnits;
import org.openhab.core.energy.price.PriceDirection;
import org.openhab.core.energy.price.PriceRole;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.persistence.FilterCriteria;
import org.openhab.core.persistence.HistoricItem;
import org.openhab.core.persistence.PersistenceService;
import org.openhab.core.persistence.QueryablePersistenceService;
import org.openhab.core.test.java.JavaOSGiTest;
import org.openhab.core.types.State;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

/**
 * Runs the energy bundles inside a real OSGi framework.
 * <p>
 * Every other test in this prototype exercises one bundle with the next one faked. That leaves the property those
 * tests cannot reach - that the four bundles actually meet - resting on the feature file and on reading. The stage-2
 * report said so in as many words: the chain had never run in one process. This is that process.
 * <p>
 * <strong>The framework is shared by every test in this class</strong>, so each one tears its own configuration and
 * services down again. Without that they pass or fail depending on the order they run in, which was how this class
 * first behaved.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EnergyOSGiTest extends JavaOSGiTest {

    private static final String[] ENERGY_BUNDLES = { "org.openhab.core.energy", "org.openhab.core.energy.series",
            "org.openhab.core.energy.forecast.store", "org.openhab.core.energy.publish" };

    private static final String PRICE_REGISTRY_PID = "org.openhab.core.energy.price";
    private static final String GRID_PRICE_PID = "org.openhab.core.energy.gridprice";
    private static final String PRICE_ITEM = "SpotPrice";
    private static final String STORE_ID = "itest-store";

    private static final Instant MIDNIGHT = Instant.parse("2026-01-01T00:00:00Z");
    private static final ZoneId HELSINKI = ZoneId.of("Europe/Helsinki");

    private final List<ServiceRegistration<?>> registrations = new ArrayList<>();

    private @NonNullByDefault({}) EnergyPlanCoordinator coordinator;
    private @NonNullByDefault({}) PlannedLevelPublisher levelPlane;
    private @NonNullByDefault({}) ConfigurationAdmin configAdmin;

    @BeforeEach
    public void setUp() throws IOException {
        coordinator = getService(EnergyPlanCoordinator.class);
        levelPlane = getService(PlannedLevelPublisher.class);
        configAdmin = getService(ConfigurationAdmin.class);
        clearConfiguration();
    }

    @AfterEach
    public void tearDown() throws IOException {
        registrations.forEach(ServiceRegistration::unregister);
        registrations.clear();
        clearConfiguration();
        // the next test must start from a coordinator that has nothing to derive from
        waitForAssert(() -> assertThat(coordinator.derive().isEmpty(), is(true)));
    }

    /**
     * A bundle that does not resolve cannot be caught by a unit test, because a unit test never asks OSGi to wire it.
     */
    @Test
    public void everyEnergyBundleResolvesAndStarts() {
        for (String symbolicName : ENERGY_BUNDLES) {
            Bundle bundle = findBundle(symbolicName);
            assertThat("bundle missing from the runtime: " + symbolicName, bundle, is(notNullValue()));
            assertThat(symbolicName + " did not reach ACTIVE", bundle.getState(), is(Bundle.ACTIVE));
        }
    }

    /**
     * The engine's own services are reachable through the registry rather than merely constructible.
     */
    @Test
    public void theEngineRegistersItsServices() {
        assertThat(coordinator, is(notNullValue()));
        assertThat(levelPlane, is(notNullValue()));
    }

    /**
     * Without a composition the registry has nothing to build an effective price out of, and the coordinator says so
     * rather than inventing one. Asserted because it is the state a fresh installation is actually in: core ships no
     * composition, so installing a price source is not by itself a working configuration.
     */
    @Test
    public void anInstalledSourceWithoutACompositionDerivesNothingAndSaysSo() {
        register(spotSource(10, 20, 30, 40), EnergyPriceSource.class.getName());

        assertThat(coordinator.derive().isEmpty(), is(true));
        assertThat(coordinator.getConditions().toString(), containsString("PRICE_COMPOSITION_FAILED"));
    }

    /**
     * A source registered by one bundle is consumed by a registry in another and comes out the far end as a level
     * plan. The only connection between the source and the coordinator is the OSGi service registry.
     */
    @Test
    public void aPriceSourceRegisteredAsAnOsgiServiceBecomesALevelPlan() throws IOException {
        composePriceFrom("spot");
        register(spotSource(10, 20, 30, 40), EnergyPriceSource.class.getName());

        waitForAssert(() -> {
            Optional<PlannedLevelSchedule> plan = coordinator.derive();
            assertThat("the coordinator never saw the registered source; it reports " + coordinator.getConditions(),
                    plan.isPresent(), is(true));
            assertThat(plan.get().size(), is(4));
        });

        assertThat("the plane did not receive the derived plan", levelPlane.getPlan().size(), is(4));
    }

    /**
     * The whole chain, with no bundle stood in for: an Item's future prices sitting in a persistence service, read by
     * the {@code series} bundle's Item-backed source, composed by the registry in the engine bundle, and installed as
     * a level plan on the level plane.
     * <p>
     * The persistence service is a test implementation because core ships no store - rrd4j and InfluxDB live in
     * openhab-addons - but it is a real {@link QueryablePersistenceService} found through the real
     * {@code PersistenceServiceRegistry}, which is the extension point an actual store plugs into.
     */
    @Test
    public void anItemInPersistenceBecomesALevelPlanThroughTheSeriesBundle() throws IOException {
        register(new FuturePriceStore(),
                new String[] { PersistenceService.class.getName(), QueryablePersistenceService.class.getName() });
        configureGridPriceSource();
        composePriceFrom("spot");

        waitForAssert(() -> {
            Optional<PlannedLevelSchedule> plan = coordinator.derive();
            assertThat("the Item never reached the level plane; the coordinator reports " + coordinator.getConditions(),
                    plan.isPresent(), is(true));
            assertThat(plan.get().size(), is(greaterThan(0)));
        });

        assertThat(levelPlane.getPlan().size(), is(greaterThan(0)));
    }

    /**
     * The no-write invariant, asserted against the running framework rather than against the source.
     * <p>
     * The engine is not merely disciplined about not writing to an Item - OSGi has not wired it to the package that
     * builds Item events, so there is no call it could make. The publishing companion is wired to exactly that
     * package, which is what makes the contrast meaningful: this is a real boundary, not an absence of code.
     */
    @Test
    public void theEngineIsNotEvenWiredToThePackageThatWritesItems() {
        assertThat("the engine is wired to the Item event package", wiredToItemEvents("org.openhab.core.energy"),
                is(false));
        assertThat("the publishing companion should be the bundle that may write",
                wiredToItemEvents("org.openhab.core.energy.publish"), is(true));
    }

    /**
     * The opt-in publishing companion actually contributes its Items to core's registry. Registering an
     * {@code ItemProvider} is one thing; core picking it up is another, and only a framework can show the second.
     */
    @Test
    public void thePublishingCompanionContributesItsItemsToTheRegistry() {
        ItemRegistry items = getService(ItemRegistry.class);
        assertThat(items, is(notNullValue()));

        waitForAssert(() -> {
            List<String> names = new ArrayList<>();
            items.getAll().forEach(item -> names.add(item.getName()));
            assertThat(names, hasItem("EnergyEngineStatus"));
            assertThat(names, hasItem("EnergyCurrentLevel"));
        });
    }

    private boolean wiredToItemEvents(String symbolicName) {
        Bundle bundle = findBundle(symbolicName);
        assertThat("bundle missing from the runtime: " + symbolicName, bundle, is(notNullValue()));
        BundleWiring wiring = bundle.adapt(BundleWiring.class);
        for (BundleWire wire : wiring.getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE)) {
            if ("org.openhab.core.items.events"
                    .equals(wire.getCapability().getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE))) {
                return true;
            }
        }
        return false;
    }

    private void register(Object service, String interfaceName) {
        registrations.add(registerService(service, interfaceName));
    }

    private void register(Object service, String[] interfaceNames) {
        registrations.add(registerService(service, interfaceNames, new Hashtable<>()));
    }

    private void composePriceFrom(String components) throws IOException {
        update(PRICE_REGISTRY_PID, "components", components);
    }

    private void configureGridPriceSource() throws IOException {
        Configuration configuration = configAdmin.getConfiguration(GRID_PRICE_PID, null);
        Dictionary<String, Object> properties = new Hashtable<>();
        properties.put("item", PRICE_ITEM);
        properties.put("persistenceService", STORE_ID);
        properties.put("currency", "EUR");
        properties.put("marketZone", "Europe/Helsinki");
        properties.put("horizonHours", 24);
        properties.put("role", "SPOT");
        configuration.update(properties);
    }

    private void update(String pid, String key, Object value) throws IOException {
        Configuration configuration = configAdmin.getConfiguration(pid, null);
        Dictionary<String, Object> properties = new Hashtable<>();
        properties.put(key, value);
        configuration.update(properties);
    }

    private void clearConfiguration() throws IOException {
        configAdmin.getConfiguration(PRICE_REGISTRY_PID, null).delete();
        configAdmin.getConfiguration(GRID_PRICE_PID, null).delete();
    }

    private EnergyPriceSource spotSource(double... values) {
        EnergyPriceSeries series = EnergyPriceSeries.of(MIDNIGHT, Duration.ofHours(1), EnergyPriceUnits.currency("EUR"),
                EnergyPriceUnits.defaultEnergyUnit(), HELSINKI, PriceDirection.CONSUMPTION, values);
        return new EnergyPriceSource() {
            @Override
            public String getSourceId() {
                return "itest-spot";
            }

            @Override
            public PriceRole getRole() {
                return PriceRole.SPOT;
            }

            @Override
            public Optional<EnergyPriceSeries> getSeries() {
                return Optional.of(series);
            }
        };
    }

    private @Nullable Bundle findBundle(String symbolicName) {
        for (Bundle bundle : bundleContext.getBundles()) {
            if (symbolicName.equals(bundle.getSymbolicName())) {
                return bundle;
            }
        }
        return null;
    }

    /**
     * Answers a price for each of the next 24 whole hours. Ascending order and at least two entries are what the
     * reader needs to work a slot width out of.
     */
    private static final class FuturePriceStore implements QueryablePersistenceService {

        @Override
        public String getId() {
            return STORE_ID;
        }

        @Override
        public String getLabel(@Nullable Locale locale) {
            return "Integration test store";
        }

        @Override
        public void store(Item item) {
        }

        @Override
        public void store(Item item, @Nullable String alias) {
        }

        @Override
        public Iterable<HistoricItem> query(FilterCriteria filter) {
            if (!PRICE_ITEM.equals(filter.getItemName())) {
                return List.of();
            }
            ZonedDateTime start = ZonedDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1);
            List<HistoricItem> entries = new ArrayList<>();
            for (int hour = 0; hour < 24; hour++) {
                entries.add(new Entry(start.plusHours(hour), new DecimalType(10 + hour)));
            }
            return entries;
        }

        private record Entry(ZonedDateTime timestamp, State state) implements HistoricItem {

            @Override
            public ZonedDateTime getTimestamp() {
                return timestamp;
            }

            @Override
            public State getState() {
                return state;
            }

            @Override
            public String getName() {
                return PRICE_ITEM;
            }
        }
    }
}
