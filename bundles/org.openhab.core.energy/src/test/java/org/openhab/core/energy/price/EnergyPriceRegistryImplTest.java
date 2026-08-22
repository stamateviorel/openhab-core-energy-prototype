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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.price.internal.EnergyPriceRegistryImpl;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The per-role source selection, the composition it feeds, and the configuration description it is held to.
 * <p>
 * Two properties matter here and they are both about <em>not</em> surprising a user. Selection must never depend on
 * the order in which bundles happened to start, which is what a ranking plus a total tie-break buys; and the plane
 * must never answer with a price nobody supplied, which is what every condition below asserts the absence of.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EnergyPriceRegistryImplTest {

    private static final ZoneId HELSINKI = ZoneId.of("Europe/Helsinki");
    private static final Instant MIDNIGHT = LocalDate.of(2023, 1, 11).atStartOfDay(HELSINKI).toInstant();
    private static final Path CONFIG_XML = Path.of("src", "main", "resources", "OH-INF", "config", "energy-price.xml");

    /**
     * A price source that answers with whatever it was built with.
     */
    private static final class TestSource implements EnergyPriceSource {

        private final String sourceId;
        private final PriceRole role;
        private final int ranking;
        private final @Nullable EnergyPriceSeries series;

        TestSource(String sourceId, PriceRole role, int ranking, @Nullable EnergyPriceSeries series) {
            this.sourceId = sourceId;
            this.role = role;
            this.ranking = ranking;
            this.series = series;
        }

        @Override
        public String getSourceId() {
            return sourceId;
        }

        @Override
        public PriceRole getRole() {
            return role;
        }

        @Override
        public int getServiceRanking() {
            return ranking;
        }

        @Override
        public Optional<EnergyPriceSeries> getSeries() {
            return Optional.ofNullable(series);
        }
    }

    private static EnergyPriceSeries series(PriceDirection direction, double... prices) {
        return EnergyPriceSeries.of(MIDNIGHT, Duration.ofHours(1), EnergyPriceUnits.currency("EUR"),
                EnergyPriceUnits.defaultEnergyUnit(), HELSINKI, direction, prices);
    }

    private static TestSource source(String id, PriceRole role, int ranking, double... prices) {
        return new TestSource(id, role, ranking,
                series(role == PriceRole.FEED_IN ? PriceDirection.FEED_IN : PriceDirection.CONSUMPTION, prices));
    }

    @Test
    public void aFreshInstallationComposesNothingAndSaysSo() {
        EnergyPriceRegistryImpl registry = new EnergyPriceRegistryImpl(Map.of());

        assertThat(registry.conditions(), contains(PricePlaneCondition.COMPOSITION_UNCONFIGURED));
        assertThat(assertThrows(PriceCompositionException.class, registry::effectiveConsumptionPrice).getCondition(),
                is(PricePlaneCondition.COMPOSITION_UNCONFIGURED));
    }

    @Test
    public void aConfiguredCompositionWithNoSourceReportsTheMissingRoleRatherThanAPrice() {
        EnergyPriceRegistryImpl registry = new EnergyPriceRegistryImpl(
                Map.of(EnergyPriceRegistryImpl.CONFIG_COMPONENTS, "spot,gridTariff"));
        registry.addSource(source("entsoe", PriceRole.SPOT, 0, 0.10, 0.20));

        PriceCompositionException refused = assertThrows(PriceCompositionException.class,
                registry::effectiveConsumptionPrice);

        assertThat(refused.getCondition(), is(PricePlaneCondition.NO_SOURCE));
        assertThat(refused.getMessage(), containsString("GRID_TARIFF"));
        assertThat(registry.conditions(), contains(PricePlaneCondition.NO_SOURCE));
    }

    @Test
    public void theHighestRankedSourceOfARoleWins() {
        EnergyPriceRegistryImpl registry = new EnergyPriceRegistryImpl(
                Map.of(EnergyPriceRegistryImpl.CONFIG_COMPONENTS, "spot"));
        registry.addSource(source("core-generic", PriceRole.SPOT, -2, 0.99));
        registry.addSource(source("entsoe", PriceRole.SPOT, 0, 0.10));

        assertThat(registry.sourceFor(PriceRole.SPOT).orElseThrow().getSourceId(), is("entsoe"));
    }

    /**
     * The core-shipped generic provider registers at {@code -2} so that it is a floor rather than a privilege, and
     * removing the add-on that outranked it hands the role straight back.
     */
    @Test
    public void aCoreShippedProviderIsUsedOnlyWhileNothingBetterIsInstalled() {
        EnergyPriceRegistryImpl registry = new EnergyPriceRegistryImpl(
                Map.of(EnergyPriceRegistryImpl.CONFIG_COMPONENTS, "spot"));
        TestSource generic = source("core-generic", PriceRole.SPOT, -2, 0.99);
        TestSource addOn = source("entsoe", PriceRole.SPOT, 0, 0.10);
        registry.addSource(generic);
        registry.addSource(addOn);

        assertThat(registry.sourceFor(PriceRole.SPOT).orElseThrow().getSourceId(), is("entsoe"));
        registry.removeSource(addOn);
        assertThat(registry.sourceFor(PriceRole.SPOT).orElseThrow().getSourceId(), is("core-generic"));
    }

    /**
     * Equal rankings are broken on the source id, so the answer is the same whichever bundle started first.
     */
    @Test
    public void aTiedRankingIsBrokenOnTheSourceIdRatherThanOnRegistrationOrder() {
        EnergyPriceRegistryImpl first = new EnergyPriceRegistryImpl(Map.of());
        first.addSource(source("alpha", PriceRole.SPOT, 0, 0.10));
        first.addSource(source("beta", PriceRole.SPOT, 0, 0.20));
        EnergyPriceRegistryImpl second = new EnergyPriceRegistryImpl(Map.of());
        second.addSource(source("beta", PriceRole.SPOT, 0, 0.20));
        second.addSource(source("alpha", PriceRole.SPOT, 0, 0.10));

        assertThat(first.sourceFor(PriceRole.SPOT).orElseThrow().getSourceId(), is("alpha"));
        assertThat(second.sourceFor(PriceRole.SPOT).orElseThrow().getSourceId(), is("alpha"));
    }

    @Test
    public void aSiteMayNameTheSourceItWantsForARole() {
        EnergyPriceRegistryImpl registry = new EnergyPriceRegistryImpl(Map.of(EnergyPriceRegistryImpl.CONFIG_COMPONENTS,
                "spot", EnergyPriceRegistryImpl.CONFIG_PREFERRED_SOURCES, "spot:core-generic"));
        registry.addSource(source("core-generic", PriceRole.SPOT, -2, 0.99));
        registry.addSource(source("entsoe", PriceRole.SPOT, 0, 0.10));

        assertThat(registry.sourceFor(PriceRole.SPOT).orElseThrow().getSourceId(), is("core-generic"));
    }

    /**
     * A source that is present but currently has nothing - a day-ahead feed before publication - is not a source
     * missing, and neither of the two is met with an invented price.
     */
    @Test
    public void aSourceWithNothingToOfferIsNotTheSameAsNoSource() {
        EnergyPriceRegistryImpl registry = new EnergyPriceRegistryImpl(
                Map.of(EnergyPriceRegistryImpl.CONFIG_COMPONENTS, "spot"));
        registry.addSource(new TestSource("entsoe", PriceRole.SPOT, 0, null));

        assertThat(registry.sourceFor(PriceRole.SPOT).isPresent(), is(true));
        assertThat(registry.seriesFor(PriceRole.SPOT).isPresent(), is(false));
        assertThat(registry.conditions(), contains(PricePlaneCondition.NO_SOURCE));
    }

    @Test
    public void theComposedPriceIsTheSumOfTheConfiguredRolesInTheConfiguredOrder() throws PriceCompositionException {
        EnergyPriceRegistryImpl registry = new EnergyPriceRegistryImpl(
                Map.of(EnergyPriceRegistryImpl.CONFIG_COMPONENTS, List.of("spot", "gridTariff", "taxesAndFees")));
        registry.addSource(source("entsoe", PriceRole.SPOT, 0, 0.10, 0.20));
        registry.addSource(source("dso", PriceRole.GRID_TARIFF, 0, 0.03, 0.03));
        registry.addSource(source("tax", PriceRole.TAXES_AND_FEES, 0, 0.02, 0.02));

        EnergyPriceSeries effective = registry.effectiveConsumptionPrice();

        assertThat(registry.components().stream().map(PriceComponent::role).toList(),
                is(List.of(PriceRole.SPOT, PriceRole.GRID_TARIFF, PriceRole.TAXES_AND_FEES)));
        assertThat(effective.values().valueAt(0), is(closeTo(0.15, 1e-9)));
        assertThat(effective.values().valueAt(1), is(closeTo(0.25, 1e-9)));
        assertThat(registry.conditions(), is(empty()));
    }

    /**
     * Feed-in is a role, never a component of the consumption sum: adding it to the composition would make an export
     * price part of what a site pays to import.
     */
    @Test
    public void feedInIsARoleOfItsOwnAndNotPartOfTheConsumptionSum() throws PriceCompositionException {
        EnergyPriceRegistryImpl registry = new EnergyPriceRegistryImpl(
                Map.of(EnergyPriceRegistryImpl.CONFIG_COMPONENTS, "spot"));
        registry.addSource(source("entsoe", PriceRole.SPOT, 0, 0.10));
        registry.addSource(source("dso-feedin", PriceRole.FEED_IN, 0, -0.05));

        assertThat(registry.effectiveConsumptionPrice().values().valueAt(0), is(closeTo(0.10, 1e-9)));
        assertThat(registry.feedInPrice().orElseThrow().direction(), is(PriceDirection.FEED_IN));
        assertThat(registry.feedInPrice().orElseThrow().values().valueAt(0), is(closeTo(-0.05, 1e-9)));
    }

    /**
     * An unknown component name is ignored with a warning rather than taken as a role, so a typo cannot silently
     * change what a site's effective price is made of.
     */
    @Test
    public void anUnknownComponentNameIsIgnoredRatherThanGuessed() {
        EnergyPriceRegistryImpl registry = new EnergyPriceRegistryImpl(
                Map.of(EnergyPriceRegistryImpl.CONFIG_COMPONENTS, "spot,gridtarrif"));
        registry.addSource(source("entsoe", PriceRole.SPOT, 0, 0.10));

        assertThat(registry.components().stream().map(PriceComponent::role).toList(), is(List.of(PriceRole.SPOT)));
    }

    /**
     * A feed-in component named in the composition is dropped with a warning rather than summed into the consumption
     * price.
     * <p>
     * The configuration page only offers the three consumption roles, but a site configuring from a file is not held
     * to the page's options. Summing a feed-in series into a consumption price produces a series whose sense is
     * inverted, and the plan derived from it blocks the cheapest hours of the day - a plausible-looking answer that
     * is exactly backwards. <em>Feed-in pricing</em> requires the two to be modelled separately, so the accessor
     * answers it and the composition refuses it.
     */
    @Test
    public void aFeedInComponentIsNotSummedIntoTheConsumptionPrice() throws Exception {
        EnergyPriceRegistryImpl registry = new EnergyPriceRegistryImpl(
                Map.of(EnergyPriceRegistryImpl.CONFIG_COMPONENTS, "feedIn,taxesAndFees"));
        registry.addSource(source("dso-feedin", PriceRole.FEED_IN, 0, -0.05, -0.02));
        registry.addSource(source("tax", PriceRole.TAXES_AND_FEES, 0, 0.02, 0.02));

        assertThat("only the consumption component survives the configuration",
                registry.components().stream().map(PriceComponent::role).toList(),
                is(List.of(PriceRole.TAXES_AND_FEES)));
        assertThat(registry.effectiveConsumptionPrice().values().sense(),
                is(org.openhab.core.energy.window.SeriesSense.LOWER_IS_BETTER));
        assertThat("and it is still readable where it belongs", registry.feedInPrice().isPresent(), is(true));
    }

    /**
     * Composition refuses a direction mismatch the way it already refuses a currency and a market-zone one.
     * <p>
     * The registry drops the role before it can happen, so this drives {@link PriceComposition} directly: the guard
     * belongs beside the other two rather than depending on one caller having filtered its input, and anything that
     * builds components itself - a script, a rule, a future contributed composition - gets the same refusal.
     */
    @Test
    public void composingAcrossTheTwoDirectionsIsRefused() {
        List<PriceComponent> components = List.of(
                PriceComponent.of("spot", PriceRole.SPOT, series(PriceDirection.CONSUMPTION, 0.10, 0.20)),
                PriceComponent.of("feedin", PriceRole.FEED_IN, series(PriceDirection.FEED_IN, -0.05, -0.02)));

        PriceCompositionException refused = assertThrows(PriceCompositionException.class,
                () -> PriceComposition.compose(components));

        assertThat(refused.getCondition(), is(PricePlaneCondition.DIRECTION_MISMATCH));
        assertThat(refused.getMessage(), containsString("feedin"));
    }

    /**
     * The configuration description and the component that reads it are two statements about the same thing, and this
     * is the test wave 1 introduced to keep them from drifting apart.
     *
     * @throws Exception if the configuration description cannot be read
     */
    @Test
    public void thePriceParametersAreDeclaredExactlyAsTheyAreRead() throws Exception {
        assertThat(declaredParameters(EnergyPriceRegistryImpl.CONFIG_URI),
                is(new TreeSet<>(EnergyPriceRegistryImpl.CONFIG_KEYS)));
    }

    /**
     * Every label, description and option an operator sees on the price page has a key in the translations bundle.
     * <p>
     * The price plane was the only one of the four whose configuration page had <em>no</em> entries at all: three
     * sibling planes assert this and each asserted only its own, so the page that is the entry point to the whole
     * capability would have stayed English for everybody, forever, and nothing would have gone red.
     *
     * @throws Exception if the configuration description or the properties bundle cannot be read
     */
    @Test
    public void everyUserVisibleStringOnThePricePageHasATranslationKey() throws Exception {
        Properties translations = new Properties();
        Path bundle = Path.of("src", "main", "resources", "OH-INF", "i18n", "energy.properties");
        try (var reader = Files.newBufferedReader(bundle, StandardCharsets.UTF_8)) {
            translations.load(reader);
        }

        assertThat(translations, hasKey("service.system.energy-price.label"));
        String prefix = EnergyPriceRegistryImpl.CONFIG_URI.replace(":", ".config.");
        Map<String, Element> parameters = declaredElements(EnergyPriceRegistryImpl.CONFIG_URI);
        assertThat("the reflection has to find parameters, or this test passes vacuously", parameters.keySet(),
                is(not(empty())));
        for (Map.Entry<String, Element> parameter : parameters.entrySet()) {
            assertThat(translations, hasKey(prefix + "." + parameter.getKey() + ".label"));
            assertThat(translations, hasKey(prefix + "." + parameter.getKey() + ".description"));
            NodeList options = parameter.getValue().getElementsByTagName("option");
            for (int index = 0; index < options.getLength(); index++) {
                Element option = (Element) options.item(index);
                assertThat(translations,
                        hasKey(prefix + "." + parameter.getKey() + ".option." + option.getAttribute("value")));
            }
        }
    }

    /**
     * Reads the parameter elements declared for one configuration URI.
     *
     * @param uri the configuration description URI
     * @return the declared parameters by name
     * @throws Exception if the XML cannot be read
     */
    private static Map<String, Element> declaredElements(String uri) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                Files.readString(CONFIG_XML, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8)));
        Map<String, Element> parameters = new java.util.TreeMap<>();
        NodeList descriptions = document.getElementsByTagName("config-description");
        for (int index = 0; index < descriptions.getLength(); index++) {
            Node node = descriptions.item(index);
            if (node instanceof Element description && uri.equals(description.getAttribute("uri"))) {
                NodeList declared = description.getElementsByTagName("parameter");
                for (int parameter = 0; parameter < declared.getLength(); parameter++) {
                    if (declared.item(parameter) instanceof Element element) {
                        parameters.put(element.getAttribute("name"), element);
                    }
                }
            }
        }
        return parameters;
    }

    /**
     * Reads the parameter names declared for one configuration URI.
     *
     * @param uri the configuration description URI
     * @return the declared parameter names
     * @throws Exception if the XML cannot be read
     */
    private static Set<String> declaredParameters(String uri) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                Files.readString(CONFIG_XML, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8)));
        Set<String> names = new TreeSet<>();
        NodeList descriptions = document.getElementsByTagName("config-description");
        for (int index = 0; index < descriptions.getLength(); index++) {
            Node node = descriptions.item(index);
            if (node instanceof Element description && uri.equals(description.getAttribute("uri"))) {
                NodeList parameters = description.getElementsByTagName("parameter");
                for (int parameter = 0; parameter < parameters.getLength(); parameter++) {
                    Node candidate = parameters.item(parameter);
                    if (candidate instanceof Element element) {
                        names.add(element.getAttribute("name"));
                    }
                }
            }
        }
        return names;
    }
}
