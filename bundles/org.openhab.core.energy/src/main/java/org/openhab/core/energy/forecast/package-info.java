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
 * The forecast data plane: every forecast a site plans on - solar production, temperature, cloud cover, wind, and the
 * demand series derived from them - as one shape, from sources nothing above them has to know about.
 * <p>
 * <strong>What is here, and what deliberately is not.</strong> Everything in this package is a value type, a pure
 * function or a registry of in-process contributions. Not one line of it reads an Item, queries persistence or posts
 * an event, because it lives in the bundle that is structurally incapable of writing to an Item and is proved so by
 * five tests. The half of the forecast plane that genuinely has to touch storage - reading an Item's future series,
 * and the read-write layered prediction series with its baseline, its refreshes and its caps - lives in
 * {@code org.openhab.core.energy.forecast.store}, a separate bundle with a separate feature. The split line is D23's:
 * <em>the value is computed where nothing can be written, and the write happens where writing is the point</em>.
 * <p>
 * That is why {@link org.openhab.core.energy.forecast.LayeredSeriesResolver} produces a
 * {@link org.openhab.core.energy.forecast.LayeredWritePlan} rather than performing a write. Deciding what a layered
 * write should do is arithmetic over timestamps; carrying it out is somebody else's bundle.
 * <p>
 * <strong>The numbers themselves are not re-implemented here.</strong> A forecast's slot geometry, its ranking, its
 * tie-break and its window search are {@link org.openhab.core.energy.window}'s, unchanged - the same calculation a
 * price goes through. {@link org.openhab.core.energy.forecast.ForecastWindows} adds nothing but the re-signing that
 * lets "the best three hours" mean the sunniest ones on a series where more is better.
 * <p>
 * <strong>Nothing here ships a number.</strong> Part B of the decision record covers thirty parameters and not one of
 * them is in this plane, so the pattern is D22's throughout: the shape ships, the site supplies the values, and an
 * unsupplied value is reported as a named
 * {@link org.openhab.core.energy.forecast.ForecastPlaneCondition} rather than quietly doing nothing. That applies to
 * the building's heat-loss coefficient, to how old a forecast may be, and to the one question the corpus explicitly
 * left open - what happens when a refresh lands on a capped entry, which is
 * {@link org.openhab.core.energy.forecast.LayeredWritePolicy}.
 * <p>
 * Null-safety follows the openhab-core convention: {@code @NonNullByDefault} sits on every type in this package
 * rather than on the package itself.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy.forecast;
