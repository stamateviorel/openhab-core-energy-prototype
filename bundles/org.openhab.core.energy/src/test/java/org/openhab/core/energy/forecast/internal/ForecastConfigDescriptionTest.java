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
package org.openhab.core.energy.forecast.internal;

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
import org.openhab.core.energy.forecast.ForecastRole;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Holds {@code OH-INF/config/energy-forecast.xml} and the component that reads it to each other, the way wave 1's own
 * conformance test does for the engine and the level plane.
 * <p>
 * There is one difference worth stating, and it is the point of this plane: <strong>this configuration declares no
 * defaults at all</strong>, so there is nothing to compare a declared default against. That is asserted here rather
 * than left implicit, because a default quietly appearing in the XML - the natural thing for somebody filling in a
 * settings page to want - would decide a question the corpus deliberately leaves to the site.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ForecastConfigDescriptionTest {

    private static final Path CONFIG_XML = Path.of("src", "main", "resources", "OH-INF", "config",
            "energy-forecast.xml");

    @Test
    public void theForecastParametersAreDeclaredExactlyAsTheyAreRead() throws Exception {
        assertThat(declaredParameters(ForecastRegistryImpl.CONFIG_URI).keySet(),
                is(new TreeSet<>(ForecastConfiguration.CONFIG_KEYS)));
    }

    /**
     * Part B of the decision record covers thirty parameters and not one of them is in this plane. So the plane ships
     * the shape and no number, and the absence of a declared default is what makes that true of the settings page as
     * well as of the code.
     *
     * @throws Exception if the configuration description cannot be read
     */
    @Test
    public void nothingInThisPlaneShipsADefault() throws Exception {
        declaredParameters(ForecastRegistryImpl.CONFIG_URI).forEach((name, parameter) -> assertThat(
                "'" + name + "' declares a default, which decides something the corpus leaves to the site",
                parameter.getElementsByTagNameNS("*", "default").getLength(), is(0)));
    }

    /**
     * Every label and description an operator sees has to have a key in the default properties bundle, or it never
     * reaches Crowdin and stays English for everybody, forever.
     *
     * @throws Exception if the configuration description or the properties bundle cannot be read
     */
    @Test
    public void everyUserVisibleStringHasATranslationKey() throws Exception {
        Map<String, String> translations = translations();
        assertThat(translations, hasKey("service.system.energy-forecast.label"));

        String prefix = ForecastRegistryImpl.CONFIG_URI.replace(":", ".config.");
        declaredParameters(ForecastRegistryImpl.CONFIG_URI).forEach((name, parameter) -> {
            assertThat(translations, hasEntry(prefix + "." + name + ".label", text(parameter, "label")));
            assertThat(translations, hasEntry(prefix + "." + name + ".description", text(parameter, "description")));
        });
    }

    /**
     * The roles a site writes in the {@code sources} parameter are the roles the code resolves, so a settings page
     * cannot name one the framework has never heard of.
     *
     * @throws Exception if the configuration description cannot be read
     */
    @Test
    public void theRolesTheDescriptionNamesAreTheRolesTheCodeKnows() throws Exception {
        Element sources = Objects.requireNonNull(declaredParameters(ForecastRegistryImpl.CONFIG_URI).get("sources"));
        String description = text(sources, "description");

        for (ForecastRole role : ForecastRole.values()) {
            assertThat("the description has to name " + role.id(), description, containsString(role.id()));
            assertThat(ForecastRole.fromId(role.id()).orElseThrow(), is(role));
        }
    }

    private static Map<String, String> translations() throws Exception {
        Path bundle = Path.of("src", "main", "resources", "OH-INF", "i18n", "energy.properties");
        assertThat("the default translations must be shipped", Files.isReadable(bundle), is(true));
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(bundle, StandardCharsets.UTF_8)) {
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

    private static Map<String, Element> declaredParameters(String uri) throws Exception {
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
        for (int i = 0; i < descriptions.getLength(); i++) {
            Element description = (Element) descriptions.item(i);
            if (!uri.equals(description.getAttribute("uri"))) {
                continue;
            }
            NodeList children = description.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (child instanceof Element element && "parameter".equals(element.getLocalName())) {
                    parameters.put(element.getAttribute("name"), element);
                }
            }
        }
        assertThat("no parameters declared for " + uri, parameters.keySet(), is(not(empty())));
        return parameters;
    }
}
