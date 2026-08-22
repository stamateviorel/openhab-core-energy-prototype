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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.ActuationSink;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.EnergyContext;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only actuation sink this prototype has: it logs the decision and writes nothing.
 * <p>
 * This is the structural half of the shadow-only rule. Leaving shadow mode in this prototype does not start
 * commanding devices - it only moves the decision from the "would have done" line to this one. There is no code
 * path anywhere in the engine that sends a command to an Item, which is a property a reviewer can check by
 * grepping the bundle for {@code sendCommand} and {@code postUpdate} and finding nothing: the only Item access is
 * {@code getState()} in {@link RegistryItemStateReader}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(service = ActuationSink.class)
public class LoggingActuationSink implements ActuationSink {

    /**
     * The id of this sink, as it appears in log lines.
     */
    public static final String ID = "log";

    private final Logger logger = LoggerFactory.getLogger(LoggingActuationSink.class);
    private final RepeatedLineFilter dispatchedLines = new RepeatedLineFilter();

    @Override
    public String getId() {
        return ID;
    }

    /**
     * Logs the decision.
     * <p>
     * A decision that has not changed since the last cycle is logged at {@code debug} rather than {@code info}: the
     * engine re-reaches the same decision about the same device on every tick for as long as the world stands
     * still, and repeating it at {@code info} would fill the log with one identical line per cycle.
     *
     * @param decision the decision to dispatch
     * @param context the snapshot it was decided on
     */
    @Override
    public void dispatch(Decision decision, EnergyContext context) {
        if (dispatchedLines.isNew(decision.participantId(), decision.describe())) {
            logger.info("Energy decision dispatched at {}: {}", context.timestamp(), decision.describe());
        } else {
            logger.debug("Energy decision dispatched at {}: {}", context.timestamp(), decision.describe());
        }
    }
}
