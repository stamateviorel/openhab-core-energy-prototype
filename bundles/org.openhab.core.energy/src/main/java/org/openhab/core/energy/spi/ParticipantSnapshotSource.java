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
package org.openhab.core.energy.spi;

import java.util.Collection;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.energy.EnergyParticipant;

/**
 * Where the engine gets the participants of a cycle from - and nothing more than that.
 * <p>
 * <strong>This is the seam that keeps THE wave-1 open question open.</strong> Whether a participant is declared
 * through {@code energy:} Item metadata, through a description-provider SPI, through a new add-on type or through
 * a script is a maintainer decision ({@code define-participant-model} / {@code define-extension-points}
 * {@code design.md} §1). The engine spine deliberately knows none of it: it asks for the current set of
 * participants once per cycle and is told nothing about where they came from.
 * <p>
 * Consequently the engine also survives having no source at all - it then evaluates an empty site, which is what a
 * fresh installation looks like before anything is declared.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public interface ParticipantSnapshotSource {

    /**
     * Returns the participants that take part in the next cycle.
     *
     * @return the participants, possibly empty, never {@code null}
     */
    Collection<EnergyParticipant> getParticipants();
}
