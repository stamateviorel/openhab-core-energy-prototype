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

import java.util.Locale;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.Decision;

/**
 * One proposal and what the cycle did with it, including the untrimmed original when the electrical-limit floor
 * reduced the action.
 * <p>
 * <strong>Every outcome carries a status and a reason.</strong> An outcome with nothing of its own to say falls back
 * to the reason the deciding algorithm gave, and then to the status itself, so that no decision anywhere in a cycle
 * comes back as a bare verdict an operator has to guess at.
 *
 * @param decision the decision as it ended up - trimmed, if it was trimmed
 * @param original the decision as proposed, or {@code null} when it was not modified
 * @param status what became of it
 * @param detail why, in words - never empty
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record DecisionOutcome(Decision decision, @Nullable Decision original, DecisionStatus status, String detail) {

    /**
     * Fills in a reason for an outcome that carries none of its own.
     */
    public DecisionOutcome {
        if (detail.isBlank()) {
            detail = decision.reason().isBlank() ? status.toString().toLowerCase(Locale.ROOT) : decision.reason();
        }
    }

    /**
     * Creates an outcome for an unmodified decision.
     *
     * @param decision the decision
     * @param status what became of it
     * @param detail a short explanation
     * @return the outcome
     */
    public static DecisionOutcome of(Decision decision, DecisionStatus status, String detail) {
        return new DecisionOutcome(decision, null, status, detail);
    }

    /**
     * Tests whether the electrical-limit floor changed the action before it was admitted.
     *
     * @return {@code true} if the decision was trimmed
     */
    public boolean trimmed() {
        return original != null;
    }

    /**
     * Renders the outcome for a log line.
     *
     * @return a compact one-line rendering
     */
    public String describe() {
        Decision proposed = original;
        String trim = proposed == null ? "" : " (trimmed from " + proposed.action().describe() + ")";
        return status + ": " + decision.describe() + trim + " - " + detail;
    }
}
