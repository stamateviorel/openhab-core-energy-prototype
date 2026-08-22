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
package org.openhab.core.energy;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The one sign convention every reading, every setpoint and every figure derived from them obeys.
 * <p>
 * It is fixed centrally rather than declared per participant, because surplus and site load cannot be computed at
 * all until every role's sign is known, and an add-on that guesses produces a 100 % error that looks perfectly
 * plausible on a chart:
 * <ul>
 * <li><strong>grid</strong> positive = export, negative = import;</li>
 * <li><strong>PV</strong> positive = producing;</li>
 * <li><strong>battery</strong> positive = charging;</li>
 * <li><strong>consumers</strong> positive = consuming;</li>
 * <li>a controllable provider's <strong>setpoint takes the same sign as its own reading</strong>, so a positive
 * battery setpoint commands charging.</li>
 * </ul>
 * A device that reports the opposite sign is normalised <em>at the edge</em> - see
 * {@link EnergyProvider#invert()} and {@link #normalise(double, boolean)} - never by letting that device carry a
 * convention of its own. Nothing above the declaration knows the device disagreed.
 * <p>
 * Everything that computes with a provider's power goes through this class, so that the convention exists in one
 * place and cannot be re-interpreted downstream.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class SignConvention {

    private SignConvention() {
    }

    /**
     * Normalises a raw device reading or setpoint onto the site convention.
     *
     * @param rawWatts the value as the device reports or accepts it
     * @param invert whether the device counts the opposite way round
     * @return the value in the site convention
     */
    public static double normalise(double rawWatts, boolean invert) {
        return invert ? -rawWatts : rawWatts;
    }

    /**
     * Returns the coefficient a provider role's reading carries in the site-load identity
     * {@code siteLoad = pv - battery - grid}: what the consumers of the site are drawing, given what is produced,
     * what the battery is absorbing and what crosses the grid connection.
     *
     * @param role the provider role
     * @return {@code +1} for PV, {@code -1} for battery and grid
     */
    public static int siteLoadCoefficient(ProviderRole role) {
        return switch (role) {
            case PV -> 1;
            case BATTERY, GRID -> -1;
        };
    }

    /**
     * Returns the load the consumers of the site are drawing.
     * <p>
     * A battery charging at 2 kW is subtracted because it is not consumer load; a grid reading is subtracted because
     * a positive one is export. The identity is stated once, here, so two calculations cannot read one battery
     * reading two ways.
     *
     * @param pvWatts the PV production, positive while producing
     * @param batteryWatts the battery power, positive while charging
     * @param gridWatts the grid power, positive while exporting
     * @return the site's consumer load in watts
     */
    public static double siteLoadWatts(double pvWatts, double batteryWatts, double gridWatts) {
        return siteLoadCoefficient(ProviderRole.PV) * pvWatts + siteLoadCoefficient(ProviderRole.BATTERY) * batteryWatts
                + siteLoadCoefficient(ProviderRole.GRID) * gridWatts;
    }

    /**
     * Returns how much load a provider's own power counts as - the quantity an electrical-limit floor books against
     * the site's limits.
     * <p>
     * A battery is the only role the model lets the engine steer, and a battery charging at 3 kW <em>is</em> 3 kW of
     * load; PV production and grid export are supply and therefore negative load. Because a setpoint takes the sign
     * of the reading, the same conversion applies to a commanded value as to a measured one.
     *
     * @param role the provider role
     * @param providerWatts the provider's power in the site convention
     * @return the load in watts, negative where the provider supplies
     */
    public static double loadWatts(ProviderRole role, double providerWatts) {
        return role == ProviderRole.BATTERY ? providerWatts : -providerWatts;
    }

    /**
     * Returns how much load a participant's own power counts as: a consumer's power is load as it stands, a
     * provider's follows {@link #loadWatts(ProviderRole, double)}.
     *
     * @param participant the participant
     * @param watts the participant's power in the site convention
     * @return the load in watts
     */
    public static double loadWatts(EnergyParticipant participant, double watts) {
        return participant instanceof EnergyProvider provider ? loadWatts(provider.role(), watts) : watts;
    }
}
