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

import java.time.Instant;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.EnergyContext;

/**
 * The other reading of "where does the acknowledgement window live": in the per-device adapter, not in the engine.
 * <p>
 * The engine then stays ignorant of Item names and write mechanics - it dispatches every admitted decision and the
 * adapter behind the {@link org.openhab.core.energy.ActuationSink} decides whether that turns into a write. This
 * tracker is therefore a deliberate no-op, kept as a first-class implementation so that selecting the option is a
 * configuration change rather than a code change.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class AdapterAcknowledgementTracker implements AcknowledgementTracker {

    /**
     * The id under which this tracker is selected in configuration.
     */
    public static final String ID = "adapter";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public void observe(EnergyContext context) {
        // the adapter behind the sink tracks acknowledgements itself
    }

    @Override
    public boolean isSuppressed(Decision decision, EnergyContext context) {
        return false;
    }

    @Override
    public void recordDispatch(Decision decision, Instant at) {
        // the adapter behind the sink tracks acknowledgements itself
    }

    @Override
    public Set<String> pendingParticipants() {
        return Set.of();
    }

    @Override
    public void reset() {
        // nothing is held here
    }
}
