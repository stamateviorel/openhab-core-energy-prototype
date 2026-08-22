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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * How a {@link ParticipantDeclaration} came to exist.
 * <p>
 * The origin is <strong>not</strong> the declaration mechanism: it says whether a human stated this participant
 * on purpose ({@link #EXPLICIT}) or whether some piece of software offered it on the user's behalf
 * ({@link #CONTRIBUTED}). Two sources using entirely different mechanisms may report the same origin.
 * <p>
 * {@link #rank()} is the <strong>first and strongest term of the one precedence chain</strong> that resolves the
 * statements an identity collects: explicit {@code energy:} metadata over contributed over discovered, with ties
 * between contributed declarations broken by {@code service.ranking}. It is not configuration and not a fallback:
 * the chain is fixed, so that a restart which registers two contributors in the opposite order produces the same
 * outcome. It is deliberately an {@code int} rather than the enum ordinal so that further origins can be slotted in
 * between the existing ones without renumbering.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public enum DeclarationOrigin {

    /**
     * The user stated this participant themselves, for instance through item metadata.
     */
    EXPLICIT(100),

    /**
     * An add-on, binding or script offered this participant programmatically.
     */
    CONTRIBUTED(50),

    /**
     * The system derived this participant by itself and offers it as a proposal.
     * <p>
     * No source produces it yet - discovery from the semantic model is a later wave - but the rank is stated here
     * because the precedence chain is fixed and stating only two thirds of a fixed chain would invite the third to
     * be invented somewhere else.
     */
    DISCOVERED(10);

    private final int rank;

    DeclarationOrigin(int rank) {
        this.rank = rank;
    }

    /**
     * Returns the authority rank of this origin, where a numerically higher value is more authoritative.
     *
     * @return the authority rank
     */
    public int rank() {
        return rank;
    }
}
