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

import java.time.Duration;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.library.types.QuantityType;

/**
 * What "acknowledged" means for one participant: how long the engine waits, and how close is close enough.
 * <p>
 * Both are per participant, because devices differ in ways a site-wide number cannot express - an OCPP charger
 * confirms a current limit in tens of seconds and echoes it imprecisely, an inverter setpoint lands in under a
 * second and echoes it to the watt. Both are optional: a participant that declares neither is judged by the engine's
 * default window and by an exact comparison.
 * <p>
 * <strong>The tolerance band is an absolute quantity in the control Item's own dimension</strong> - 0.01 A, 5 W -
 * and never a fraction of the commanded value. It is carried here as declared, and converted into the unit of the
 * comparison at the point that unit is known; a band whose dimension does not match the command it would judge is
 * not applicable to it and the comparison falls back to exact.
 * <p>
 * {@link #declaredBy} is the one place these two attributes are read off a declaration. A participant that declares
 * neither is not a special case: it is handed the engine's default window and a {@code null} band, which the
 * comparison reads as "exact".
 *
 * @param window how long a command may stay unacknowledged before it lapses
 * @param tolerance the absolute band, as declared, within which a reported value acknowledges a command, or
 *            {@code null} when the participant declares none and the comparison is exact
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record AcknowledgementTerms(Duration window, @Nullable QuantityType<?> tolerance) {

    /**
     * Returns the terms a participant declares, falling back to the engine's default window and to an exact
     * comparison for anything it leaves unsaid.
     *
     * @param participant the participant declaration
     * @param defaultWindow the window used when the participant declares none
     * @return the terms in force for that participant
     */
    public static AcknowledgementTerms declaredBy(EnergyParticipant participant, Duration defaultWindow) {
        Duration declaredWindow = participant.ackWindow();
        return new AcknowledgementTerms(declaredWindow == null ? defaultWindow : declaredWindow,
                participant.ackTolerance());
    }
}
