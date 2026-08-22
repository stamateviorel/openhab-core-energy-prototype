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
/**
 * What the engine says about itself: the two event types every cycle publishes, and the payload objects they carry.
 * <p>
 * <strong>This is the one outward-facing surface this bundle has, and it is deliberately not an Item.</strong>
 * Posting an {@link org.openhab.core.events.Event} is not an Item write - nothing here commands a device, updates a
 * state or touches the {@code ItemRegistry} - so the engine can report what it decided while remaining provably
 * incapable of steering anything itself. The surfaces that <em>do</em> need a write, a summarising status Item and a
 * published current level, live in the separate {@code org.openhab.core.energy.publish} bundle, which subscribes to
 * these events.
 * <p>
 * <strong>FOLLOW-UP - D23</strong> (owner decision, 2026-08-03,
 * {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}), which split A8's three observability surfaces three ways after
 * the wave-1 slice reported that one accepted requirement was failing three different ways inside a bundle that
 * writes nothing. Events ship here; the status Item and the REST view move out. Alternatives preserved in
 * {@code define-engine-contract} design.md §23: relax the requirement for the framework, or move everything
 * including the events into the publishing component.
 * <p>
 * Events are <strong>deduplicated</strong>: an unchanged decision about an unchanged participant is published once
 * and not re-emitted on every tick, so a week of shadow-mode decisions is comparable against a week of the user's
 * own automation rather than being a log line repeated fourteen hundred times a day.
 * <p>
 * Null-safety follows the openhab-core convention: {@code @NonNullByDefault} sits on every type in this package
 * rather than on the package itself, and the two payload objects are DTOs, which the review checklist exempts from
 * the annotation altogether.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy.events;
