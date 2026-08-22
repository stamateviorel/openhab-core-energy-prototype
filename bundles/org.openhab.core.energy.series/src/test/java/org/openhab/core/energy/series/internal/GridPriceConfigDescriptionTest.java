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
package org.openhab.core.energy.series.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Holds {@code OH-INF/config/energy-gridprice.xml}, the component that reads it, and the translations bundle to each
 * other.
 * <p>
 * <strong>This bundle had no test of its configuration surface at all.</strong> Fourteen declared parameters matched
 * fourteen read keys by care alone, and the bundle shipped no {@code OH-INF/i18n} directory, so every label and
 * description on the one page core ships for sites with no add-on for their market would have stayed English for
 * everybody, forever - Crowdin never sees a string that has no key. Three sibling planes in the engine bundle assert
 * exactly this and each asserts only its own page, which is how a whole bundle slipped through.
 * <p>
 * The two parameters with no {@code <default>} are pinned as well, because their absence is the point: a delivery day
 * is never inferred from the site's zone or from UTC, and a currency is never guessed. A default appearing under
 * either later would be invisible in a diff of the Java.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class GridPriceConfigDescriptionTest {

    private static final Path CONFIG_XML = Path.of("src", "main", "resources", "OH-INF", "config",
            "energy-gridprice.xml");
    private static final Path TRANSLATIONS = Path.of("src", "main", "resources", "OH-INF", "i18n",
            "energy-gridprice.properties");

    @Test
    public void theGridPriceParametersAreDeclaredExactlyAsTheyAreRead() throws Exception {
        assertThat(declaredParameters().keySet(), is(new TreeSet<>(GridPriceSource.CONFIG_KEYS)));
    }

    /**
     * Neither declaration about what the raw numbers mean carries a shipped value.
     *
     * @throws Exception if the configuration description cannot be read
     */
    @Test
    public void theMarketZoneAndTheCurrencyShipNoDefault() throws Exception {
        Map<String, Element> parameters = declaredParameters();

        for (String name : new String[] { GridPriceSource.CONFIG_MARKET_ZONE, GridPriceSource.CONFIG_CURRENCY }) {
            Element parameter = Objects.requireNonNull(parameters.get(name));
            assertThat("'" + name + "' must not ship a value, because nothing can infer it",
                    parameter.getElementsByTagNameNS("*", "default").getLength(), is(0));
            assertThat(text(parameter, "description"), containsString("no default"));
        }
        assertThat("the market zone says what an inferred one would cost",
                text(Objects.requireNonNull(parameters.get(GridPriceSource.CONFIG_MARKET_ZONE)), "description"),
                containsString("UTC"));
    }

    @Test
    public void everyUserVisibleStringHasATranslationKey() throws Exception {
        Map<String, String> translations = translations();
        assertThat(translations, hasKey("service.system.energy-gridprice.label"));

        String prefix = GridPriceSource.CONFIG_URI.replace(":", ".config.");
        Map<String, Element> parameters = declaredParameters();
        assertThat("the reflection has to find parameters, or this test passes vacuously", parameters.keySet(),
                is(not(empty())));
        parameters.forEach((name, parameter) -> {
            assertThat(translations, hasEntry(prefix + "." + name + ".label", text(parameter, "label")));
            assertThat(translations, hasEntry(prefix + "." + name + ".description", text(parameter, "description")));
            NodeList options = parameter.getElementsByTagNameNS("*", "option");
            for (int index = 0; index < options.getLength(); index++) {
                Element option = (Element) options.item(index);
                assertThat(translations, hasEntry(prefix + "." + name + ".option." + option.getAttribute("value"),
                        option.getTextContent().trim()));
            }
        });
    }

    private static Map<String, String> translations() throws Exception {
        assertThat("the default translations must be shipped", Files.isReadable(TRANSLATIONS), is(true));
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(TRANSLATIONS, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        Map<String, String> translations = new TreeMap<>();
        properties.forEach((key, value) -> translations.put(String.valueOf(key), String.valueOf(value)));
        return translations;
    }

    private static String text(Element parameter, String tag) {
        NodeList children = parameter.getElementsByTagNameNS("*", tag);
        assertThat("parameter '" + parameter.getAttribute("name") + "' has no <" + tag + ">", children.getLength(),
                is(1));
        return children.item(0).getTextContent().replaceAll("\\s+", " ").trim();
    }

    private static Map<String, Element> declaredParameters() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        assertThat("the configuration description must be where this test expects it", Files.isReadable(CONFIG_XML),
                is(true));
        Document document;
        try (var stream = Files.newInputStream(CONFIG_XML)) {
            document = factory.newDocumentBuilder().parse(stream);
        }

        Map<String, Element> parameters = new TreeMap<>();
        NodeList descriptions = document.getElementsByTagNameNS("*", "config-description");
        for (int index = 0; index < descriptions.getLength(); index++) {
            Element description = (Element) descriptions.item(index);
            if (!GridPriceSource.CONFIG_URI.equals(description.getAttribute("uri"))) {
                continue;
            }
            NodeList children = description.getChildNodes();
            for (int child = 0; child < children.getLength(); child++) {
                Node node = children.item(child);
                if (node instanceof Element element && "parameter".equals(element.getLocalName())) {
                    parameters.put(element.getAttribute("name"), element);
                }
            }
        }
        assertThat("no parameters declared for " + GridPriceSource.CONFIG_URI, parameters.keySet(), is(not(empty())));
        return parameters;
    }
}
