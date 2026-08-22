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
package org.openhab.core.energy.price;

import java.util.List;
import java.util.OptionalDouble;

import javax.measure.Unit;
import javax.measure.quantity.Energy;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.dimension.Currency;
import org.openhab.core.library.dimension.EnergyPrice;
import org.openhab.core.library.unit.CurrencyUnit;
import org.openhab.core.library.unit.CurrencyUnits;
import org.openhab.core.library.unit.Units;

/**
 * How a price unit is built and converted here, and the two places core's own currency support does not reach.
 * <p>
 * <strong>Finding 1 - a price cannot be denominated in cents.</strong> {@link CurrencyUnit}'s constructor refuses any
 * name that is not exactly three characters, so {@code ct} is not constructible as a currency and {@code ct/kWh} is
 * not expressible as a {@code Unit<EnergyPrice>} at all. The corpus asks for one anyway: {@code price-data}
 * <em>Generic grid-price provider</em> ends its first scenario "the effective series is in ct/kWh", and the
 * acceptance fixture's own column is {@code price_ct_per_kwh}. This plane therefore carries prices in
 * <em>currency</em> per unit of energy and treats cents as a presentation concern; a site that wants the numbers in
 * cents applies a {@link PriceAdjustment.Scale} of 100 and reads the unit as a lie it chose. The scenario's wording
 * needs correcting, not the model - and {@code aPriceCannotBeDenominatedInCents} in the tests pins the reason so the
 * next reader does not rediscover it.
 * <p>
 * <strong>Finding 2 - converting EUR/MWh to EUR/kWh is not the native operation the corpus assumes.</strong>
 * {@code QuantityType#toUnit} routes through the system unit, and a {@link CurrencyUnit}'s system converter asks
 * {@code CurrencyService} for an exchange rate. With no {@code CurrencyProvider} configured - which is every headless
 * test, and every site that has not set one up - that rate is absent, the conversion throws
 * {@code UnconvertibleException} internally and {@code toUnit} answers {@code null}. The failure is the same even
 * when both sides are the same currency and nothing is being exchanged at all, because the currency does not cancel
 * before the system unit is reached. So this class converts on the <em>energy denominator alone</em>: the currency is
 * required to be identical and is then left untouched, which is arithmetic that cannot fail and needs no exchange
 * service. Cross-currency composition stays refused, which the proposal's own non-goal wanted anyway.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public final class EnergyPriceUnits {

    private EnergyPriceUnits() {
    }

    /**
     * Returns the currency unit for a three-letter code, preferring one the running system already knows.
     * <p>
     * A currency registered by the active {@code CurrencyProvider} carries its symbol and its exchange rate, so
     * preferring it is what makes a price render as {@code EUR/kWh} rather than as {@code null/kWh} and what lets
     * anything outside this plane convert it. Creating one is the fallback for a headless build and for a site whose
     * provider does not list the code, and it is deliberately not registered globally: a price series is not a reason
     * for a core bundle to add a currency to the system.
     *
     * @param code the three-letter currency code, for example {@code EUR}
     * @return the currency unit
     * @throws IllegalArgumentException if the code is not exactly three characters
     */
    public static Unit<Currency> currency(String code) {
        for (Unit<?> known : CurrencyUnits.getInstance().getUnits()) {
            if (known instanceof CurrencyUnit && code.equals(known.getName())) {
                return asCurrency(known);
            }
        }
        return CurrencyUnits.createCurrency(code, code);
    }

    /**
     * Returns the typed price unit for a currency and a unit of energy - {@code EUR/kWh}, {@code DKK/MWh}.
     *
     * @param currency the currency
     * @param energyUnit the unit of energy the price is per
     * @return the price unit
     */
    public static Unit<EnergyPrice> priceUnit(Unit<Currency> currency, Unit<Energy> energyUnit) {
        return asPrice(currency.divide(energyUnit));
    }

    /**
     * Returns the factor a price per {@code from} is multiplied by to become a price per {@code to}.
     * <p>
     * It is the size of one {@code to} expressed in {@code from}: one kilowatt-hour is a thousandth of a megawatt
     * hour, so a price per megawatt hour becomes a price per kilowatt hour by multiplying by {@code 0.001} - the
     * "EUR/MWh to ct/kWh" division by ten the requirement names, minus the hundredfold that belongs to the cents that
     * are not expressible.
     *
     * @param from the unit of energy the price is currently per
     * @param to the unit of energy the price should be per
     * @return the multiplier
     */
    public static double energyDenominatorFactor(Unit<Energy> from, Unit<Energy> to) {
        return to.getConverterTo(from).convert(1d);
    }

    /**
     * Returns the factor that takes a value denominated in {@code stateUnit} to one denominated in {@code currency}
     * per {@code energyUnit}, or empty when {@code stateUnit} is not that currency per a unit of energy this plane
     * can name.
     * <p>
     * This exists because a source publishing a typed {@code Number:EnergyPrice} states its own denominator, and it
     * need not be the one a site configured: an ENTSO-E binding publishes {@code EUR/MWh} while the configuration
     * page's default denominator is {@code kWh}, and reading the number as-is is wrong by a factor of a thousand
     * silently. Finding 2 above rules out {@link javax.measure.Quantity#to} for this, so the comparison is made
     * against each unit of energy this plane offers and the conversion is on the denominator alone.
     * <p>
     * <strong>Matching is by unit equality and deliberately not by how the unit renders.</strong> An unregistered
     * currency has no {@code SimpleUnitFormat} label - the limitation {@code PriceModellingLimitsTest} already pins -
     * so two different currencies can render identically and a string comparison would accept a Danish price as a
     * euro one. {@link #currency} prefers the unit the running system registered, which is what makes the two sides
     * the same object on a real installation; where they are genuinely two objects for one currency, differing only
     * in symbol, this answers empty and the caller refuses the series naming both units. Refusing a readable series
     * is recoverable; reading a Danish krone as a euro is not.
     *
     * @param stateUnit the unit the published state carries
     * @param currency the currency the series is to be denominated in
     * @param energyUnit the unit of energy the series is to be per
     * @return the multiplier, or empty when the two denominations are not the same currency
     */
    public static OptionalDouble denominatorFactorFrom(Unit<?> stateUnit, Unit<Currency> currency,
            Unit<Energy> energyUnit) {
        for (Unit<Energy> candidate : List.of(Units.WATT_HOUR, Units.KILOWATT_HOUR, Units.MEGAWATT_HOUR)) {
            if (priceUnit(currency, candidate).equals(stateUnit)) {
                return OptionalDouble.of(energyDenominatorFactor(candidate, energyUnit));
            }
        }
        return OptionalDouble.empty();
    }

    /**
     * Tells whether two currencies are the same one.
     * <p>
     * Compared by name rather than by identity, because a site's registered {@code EUR} and one this plane created
     * for a headless build are two objects standing for one currency.
     *
     * @param first the first currency
     * @param second the second currency
     * @return {@code true} if both name the same currency
     */
    public static boolean isSameCurrency(Unit<Currency> first, Unit<Currency> second) {
        return first.getName().equals(second.getName());
    }

    /**
     * Returns the kilowatt hour, the unit this plane denominates prices in unless told otherwise.
     *
     * @return {@link Units#KILOWATT_HOUR}
     */
    public static Unit<Energy> defaultEnergyUnit() {
        return Units.KILOWATT_HOUR;
    }

    @SuppressWarnings("unchecked")
    private static Unit<Currency> asCurrency(Unit<?> unit) {
        return (Unit<Currency>) unit;
    }

    @SuppressWarnings("unchecked")
    private static Unit<EnergyPrice> asPrice(Unit<?> unit) {
        return (Unit<EnergyPrice>) unit;
    }
}
