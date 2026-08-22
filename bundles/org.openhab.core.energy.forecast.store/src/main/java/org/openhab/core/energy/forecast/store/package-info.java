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
 * The Item and persistence edge of the forecast plane: the read-write layered prediction series, the Item-backed
 * forecast source, and the publication of a derived series.
 * <p>
 * <strong>Everything in this bundle exists because it touches storage.</strong> Its neighbour
 * {@code org.openhab.core.energy} may never write an Item, may not query persistence and may not name
 * {@code .store(}, {@code .query(} or {@code FilterCriteria} - five tests hold it to that - so the forecast plane's
 * arithmetic lives there and its writes live here. The split is D23's, applied to the data plane: the value is
 * computed where nothing can be written, and the write happens where writing is the point.
 * <p>
 * The dependency runs one way. This bundle needs the framework; the framework does not know this bundle exists, and a
 * checkout with this directory deleted still builds and passes.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy.forecast.store;
