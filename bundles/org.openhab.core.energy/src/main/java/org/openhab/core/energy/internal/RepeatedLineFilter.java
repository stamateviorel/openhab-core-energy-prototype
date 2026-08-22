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

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Tells whether a line about a subject is new, so that a decision the engine keeps re-reaching is reported once
 * rather than on every tick.
 * <p>
 * The engine is a control loop: for as long as the world does not change, every cycle reaches the same decision
 * about the same device and would render exactly the same line. At the default cadence that is fourteen hundred
 * identical lines a day per undecided device - which is why the shadow trail, the master-stop trail and the
 * dispatch trail are all gated on this rather than logged unconditionally. The <em>first</em> occurrence and every
 * <em>change</em> are still operator-visible at {@code info}; the repetitions drop to {@code debug}, where an
 * operator who wants the full per-cycle trail can still get it.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
class RepeatedLineFilter {

    private final Map<String, String> lastLineBySubject = new ConcurrentHashMap<>();

    /**
     * Records a line and says whether it differs from the one last recorded for the same subject.
     *
     * @param subject what the line is about, usually a participant id
     * @param line the rendered line
     * @return {@code true} if this is the first line about the subject or it differs from the previous one
     */
    boolean isNew(String subject, String line) {
        return !Objects.equals(lastLineBySubject.put(subject, line), line);
    }

    /**
     * Forgets every subject outside the given set, so that a device which leaves the site and comes back is
     * reported again rather than silently suppressed, and the map cannot grow without bound.
     *
     * @param subjects the subjects still worth remembering
     */
    void retainOnly(Set<String> subjects) {
        lastLineBySubject.keySet().retainAll(subjects);
    }
}
