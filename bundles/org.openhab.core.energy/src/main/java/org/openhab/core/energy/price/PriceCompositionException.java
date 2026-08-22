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
package org.openhab.core.energy.price;

import java.io.Serial;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * A composition or an adjustment a site has configured that cannot be carried out, with the reason a user can act on.
 * <p>
 * Checked on purpose, per openHAB's own rule that custom exceptions extend {@link Exception}: every one of these is
 * an expected outcome of a configuration a user wrote - two components in different currencies, two market zones in
 * one sum, a tariff calendar covering a span it was never meant to. None of them is a bug, and none of them may be
 * met by falling back to a number nobody chose. The caller catches it, keeps the last good effective series and
 * reports the condition.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class PriceCompositionException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    private final PricePlaneCondition condition;

    /**
     * Creates the exception.
     *
     * @param condition the machine-readable condition this is an instance of
     * @param message what a user has to do about it
     */
    public PriceCompositionException(PricePlaneCondition condition, String message) {
        super(message);
        this.condition = condition;
    }

    /**
     * Returns the condition this exception reports.
     * <p>
     * A log line is not a report: whoever renders the site's status needs a value to key on, which is why the reason
     * is an enum beside the message rather than only inside it.
     *
     * @return the condition
     */
    public PricePlaneCondition getCondition() {
        return condition;
    }
}
