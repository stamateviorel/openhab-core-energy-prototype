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
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.forecast.ForecastRole;
import org.openhab.core.energy.forecast.ForecastSeriesSource;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers one {@link ItemForecastSource} per Item a site named, and takes them away again when it stops naming
 * them.
 * <p>
 * The sources have to be registered rather than injected because how many there are is configuration: a site with a
 * stored photovoltaic baseline and a stored temperature series has two, a site with none has none. Registering them
 * as ordinary services is also what makes them indistinguishable from a contributed add-on's source, which is the
 * point of _Core-shipped defaults without privilege_ - they compete on ranking like everything else, and core's own
 * default sits at {@code service.ranking = -2} so that any contribution outranks it untouched.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(immediate = true, configurationPid = PersistenceLayeredStore.CONFIGURATION_PID, configurationPolicy = ConfigurationPolicy.OPTIONAL)
public class ItemForecastSourceRegistrar {

    private final Logger logger = LoggerFactory.getLogger(ItemForecastSourceRegistrar.class);

    private final BundleContext bundleContext;
    private final PersistenceLayeredStore store;
    private final List<ServiceRegistration<ForecastSeriesSource>> registrations = new ArrayList<>();

    /**
     * Creates the registrar and registers whatever the site has named.
     *
     * @param bundleContext the context the sources are registered in
     * @param store the store the sources read through
     * @param properties the component properties
     */
    @Activate
    public ItemForecastSourceRegistrar(BundleContext bundleContext, final @Reference PersistenceLayeredStore store,
            Map<String, Object> properties) {
        this.bundleContext = bundleContext;
        this.store = store;
        register();
    }

    /**
     * Re-registers the sources after a configuration change.
     *
     * @param properties the component properties
     */
    @Modified
    public void modified(Map<String, Object> properties) {
        unregister();
        register();
    }

    /**
     * Takes every source away, which is what uninstalling this component has to mean.
     */
    @Deactivate
    public void deactivate() {
        unregister();
    }

    private void register() {
        Map<ForecastRole, String> series = store.configuration().seriesItems();
        for (Map.Entry<ForecastRole, String> entry : series.entrySet()) {
            ItemForecastSource source = new ItemForecastSource(entry.getValue(), entry.getKey(), store,
                    Clock.systemUTC(), null);
            Dictionary<String, Object> properties = new Hashtable<>();
            properties.put(Constants.SERVICE_RANKING, ItemForecastSource.CORE_DEFAULT_RANKING);
            registrations.add(bundleContext.registerService(ForecastSeriesSource.class, source, properties));
            logger.debug("Item '{}' is now a {} forecast source", entry.getValue(), entry.getKey().id());
        }
    }

    private void unregister() {
        registrations.forEach(ServiceRegistration::unregister);
        registrations.clear();
    }
}
