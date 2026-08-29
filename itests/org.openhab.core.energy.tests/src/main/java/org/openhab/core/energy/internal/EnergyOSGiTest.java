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

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.level.PlannedLevelPublisher;
import org.openhab.core.energy.level.PlannedLevelSchedule;
import org.openhab.core.energy.price.EnergyPriceSeries;
import org.openhab.core.energy.price.EnergyPriceSource;
import org.openhab.core.energy.price.EnergyPriceUnits;
import org.openhab.core.energy.price.PriceDirection;
import org.openhab.core.energy.price.PriceRole;
import org.openhab.core.test.java.JavaOSGiTest;
import org.osgi.framework.Bundle;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

/**
 * Runs the energy bundles inside a real OSGi framework.
 * <p>
 * Every other test in this prototype exercises one bundle with the next one faked. That leaves the property those
 * tests cannot reach - that the four bundles actually meet - resting on the feature file and on reading. The stage-2
 * report says so in as many words: the chain has never run in one process. This is that process.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EnergyOSGiTest extends JavaOSGiTest {

    private static final String[] ENERGY_BUNDLES = { "org.openhab.core.energy", "org.openhab.core.energy.series",
            "org.openhab.core.energy.forecast.store", "org.openhab.core.energy.publish" };

    private static final Instant MIDNIGHT = Instant.parse("2026-01-01T00:00:00Z");
    private static final ZoneId HELSINKI = ZoneId.of("Europe/Helsinki");

    private static final String PRICE_REGISTRY_PID = "org.openhab.core.energy.price";

    private @NonNullByDefault({}) EnergyPlanCoordinator coordinator;
    private @NonNullByDefault({}) PlannedLevelPublisher levelPlane;
    private @NonNullByDefault({}) ConfigurationAdmin configAdmin;

    @BeforeEach
    public void setUp() {
        coordinator = getService(EnergyPlanCoordinator.class);
        levelPlane = getService(PlannedLevelPublisher.class);
        configAdmin = getService(ConfigurationAdmin.class);
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
     * The whole point of the exercise: a source registered by one bundle is consumed by a registry in another, and
     * comes out the far end as a level plan. Nothing here is injected by hand - the only connection between the
     * source and the coordinator is the OSGi service registry.
     */
    @Test
    public void aPriceSourceRegisteredAsAnOsgiServiceBecomesALevelPlan() throws IOException {
        assertThat("no plan should exist before a source is installed", levelPlane.getPlan().size(), is(0));

        // core ships no composition, so an installed source alone derives nothing - this is the site's half of the
        // contract, and going through ConfigAdmin is what a site actually does
        composePriceFrom("spot");
        registerService(spotSource(10, 20, 30, 40), EnergyPriceSource.class.getName());

        waitForAssert(() -> {
            Optional<PlannedLevelSchedule> plan = coordinator.derive();
            assertThat("the coordinator never saw the registered source; it reports " + coordinator.getConditions(),
                    plan.isPresent(), is(true));
            assertThat(plan.get().size(), is(4));
        });

        assertThat("the plane did not receive the derived plan", levelPlane.getPlan().size(), is(4));
    }

    /**
     * Without a composition the registry has nothing to build an effective price out of, and the coordinator says so
     * rather than inventing one. Asserted here because it is the state a fresh installation is actually in.
     */
    @Test
    public void anInstalledSourceWithoutACompositionDerivesNothingAndSaysSo() {
        registerService(spotSource(10, 20, 30, 40), EnergyPriceSource.class.getName());

        assertThat(coordinator.derive().isEmpty(), is(true));
        assertThat(coordinator.getConditions().toString(), containsString("PRICE_COMPOSITION_FAILED"));
    }

    private void composePriceFrom(String components) throws IOException {
        Configuration configuration = configAdmin.getConfiguration(PRICE_REGISTRY_PID, null);
        Dictionary<String, Object> properties = new Hashtable<>();
        properties.put("components", components);
        configuration.update(properties);
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

    private org.osgi.framework.@org.eclipse.jdt.annotation.Nullable Bundle findBundle(String symbolicName) {
        for (Bundle bundle : bundleContext.getBundles()) {
            if (symbolicName.equals(bundle.getSymbolicName())) {
                return bundle;
            }
        }
        return null;
    }
}
