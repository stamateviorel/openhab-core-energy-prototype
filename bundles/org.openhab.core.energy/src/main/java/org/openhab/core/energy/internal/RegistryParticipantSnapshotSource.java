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

import java.util.Collection;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.energy.spi.EnergyParticipantRegistry;
import org.openhab.core.energy.spi.ParticipantSnapshotSource;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Feeds the engine from the participant registry - the whole coupling between the engine spine and the declaration
 * mechanism, in one delegating call.
 * <p>
 * It exists as a separate component on purpose. The engine asks a {@link ParticipantSnapshotSource} for the
 * participants of a cycle and knows nothing else; if the maintainers decide that participants arrive some other way
 * than through this registry - a different SPI, an add-on type, plain Items - this class is what gets replaced, and
 * the engine does not change at all.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(service = ParticipantSnapshotSource.class)
public class RegistryParticipantSnapshotSource implements ParticipantSnapshotSource {

    private final EnergyParticipantRegistry registry;

    /**
     * Creates the source.
     *
     * @param registry the registry holding the effective participant declarations
     */
    @Activate
    public RegistryParticipantSnapshotSource(@Reference EnergyParticipantRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Collection<EnergyParticipant> getParticipants() {
        return registry.getParticipants();
    }
}
