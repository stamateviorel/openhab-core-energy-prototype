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
 * The objective plane's own components: the three built-in objectives, the two export-credit rules, the whiteboard
 * that resolves which objective is in force, and the configuration it reads.
 * <p>
 * The built-ins are ordinary contributions. They register through the same SPI a script or an add-on registers
 * through, at {@code service.ranking = -2}, so a contributed objective displaces one of them with no configuration
 * at all - which is what the extension surface means by core defaults having no privilege.
 * <p>
 * Nothing in this package writes to an Item, queries persistence or creates a thread.
 *
 * @author Stamate Viorel - Initial contribution
 */
package org.openhab.core.energy.objective.internal;
