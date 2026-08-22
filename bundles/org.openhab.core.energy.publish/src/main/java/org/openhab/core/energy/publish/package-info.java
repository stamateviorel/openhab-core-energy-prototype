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
 * The energy framework's publishing component: the two Items that carry what the engine decided.
 * <p>
 * <strong>THIS BUNDLE WRITES ITEMS. THAT IS WHAT IT IS FOR.</strong> Its neighbour
 * {@code org.openhab.core.energy} must never write one - that is a hard invariant, proved by four structural and
 * behavioural tests over there - and the two rules must not be confused. If a surface needs a write, it belongs
 * here. If it does not, it belongs there, where it can be reasoned about without asking what it might touch.
 * <p>
 * The split is <strong>D23</strong> (owner decision, 2026-08-03,
 * {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}). A8 asked for four observability surfaces and three of them
 * were blocked inside a framework that writes nothing, for three different reasons. The answer separated them:
 * decision and cycle <em>events</em> stay with the framework, because posting an event is not an Item write; the
 * summarising <em>status Item</em> and the published <em>current level</em> come here, because they genuinely are;
 * and the <em>REST view</em> comes here too but is deliberately not built yet - see the bundle README. Alternatives
 * preserved in {@code define-engine-contract} design.md §23.
 * <p>
 * This package holds the public names an operator or a rule refers to. Everything that does the work is in
 * {@code .internal}.
 * <p>
 * Null-safety follows the openhab-core convention: {@code @NonNullByDefault} sits on every type in this package
 * rather than on the package itself.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy.publish;
