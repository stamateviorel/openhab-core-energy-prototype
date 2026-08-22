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
package org.openhab.core.energy.internal.metadata;

import java.io.Serial;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Thrown when an {@code energy} item-metadata declaration cannot be turned into a participant.
 * <p>
 * It is checked on purpose. The entire input of {@link EnergyMetadataParser} is text a user typed, so a declaration
 * that does not parse is the ordinary case the parser exists to recognise, not an unexpected condition or a
 * programming error - and a caller that forgets to handle it should not compile.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EnergyMetadataParseException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong with the declaration, in terms the user can act on
     */
    public EnergyMetadataParseException(String message) {
        super(message);
    }

    /**
     * Creates the exception from a rejected value the participant model itself refused.
     *
     * @param message what is wrong with the declaration, in terms the user can act on
     * @param cause the rejection
     */
    public EnergyMetadataParseException(@Nullable String message, Throwable cause) {
        super(message, cause);
    }
}
