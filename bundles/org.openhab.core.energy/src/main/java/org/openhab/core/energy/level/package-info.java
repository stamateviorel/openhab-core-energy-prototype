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
 * The energy level classifier: pure functions that turn a series into a planned
 * {@link org.openhab.core.energy.EnergyLevel} schedule, and a planned schedule plus live surplus into the current
 * level.
 * <p>
 * <strong>The engine calls into this package; this package never calls back.</strong>
 * {@link org.openhab.core.energy.level.CurrentLevelFunction} is the whole coupling, and it is a function of a moment
 * and a surplus - both supplied by the engine's own one-cycle snapshot. Publishing the current level as an Item and
 * the plan as a {@code TimeSeries} is an output of that computation, a report of what the engine decided, and never
 * the path by which a level arrives.
 * <p>
 * <strong>The shared window calculation no longer lives here.</strong> {@code SlotSeries}, {@code Slot},
 * {@code WindowRequest}, {@code WindowSelection}, {@code SlotSelection}, {@code WindowCost}, {@code CostWeights} and
 * {@code SelectionStrategy} moved to {@link org.openhab.core.energy.window} when the price plane became a second
 * caller: it is one calculation shared by every plane, and a package named for levels was the wrong home for it.
 * Nothing about the arithmetic changed.
 * <p>
 * Nothing in this package touches OSGi, the clock, an Item or a logger. Time is always an argument, never a lookup,
 * which is what makes the direction above true rather than merely intended - a classifier with a clock could answer
 * for a different moment than the readings the cycle acts on. Every behaviour here is reproducible from a fixture
 * file.
 * <p>
 * Two derivation strategies live behind {@link org.openhab.core.energy.level.LevelDerivation} because the corpus has
 * not decided between them (change {@code define-energy-levels}, task 2.1 - "Reconcile percentile-based derivation
 * with fixed-hour-count derivation - both, or one with the other as configuration?"). Selecting one is a maintainer
 * decision, so both are implemented and the choice is configuration. Surplus escalation, by contrast, is <em>no
 * longer</em> a choice: core ships the graded policy alone, and an undeclared threshold is reported as
 * {@link org.openhab.core.energy.level.LevelPlaneCondition#ESCALATION_UNCONFIGURED} rather than silently doing
 * nothing.
 * <p>
 * Null-safety follows the openhab-core convention: {@code @NonNullByDefault} sits on every type in this package
 * rather than on the package itself.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy.level;
