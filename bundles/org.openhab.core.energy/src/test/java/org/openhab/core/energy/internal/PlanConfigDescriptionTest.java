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
package org.openhab.core.energy.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * Holds {@code OH-INF/config/energy-plan.xml} and the coordinator that reads it to each other.
 * <p>
 * The coordinator has the one parameter in wave 2 whose absence changes what the framework does rather than merely
 * what it reports, so this test carries a little more than parameter parity: it pins that the refresh interval ships
 * <em>no</em> default and that its description says what an empty value costs. A default appearing there later would
 * be somebody deciding a cadence the corpus never decides, and it would be invisible in a diff of the Java.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class PlanConfigDescriptionTest {

    private static final Path CONFIG_XML = Path.of("src", "main", "resources", "OH-INF", "config", "energy-plan.xml");

    /**
     * Every key the coordinator reads is declared, and nothing is declared that it does not read.
     *
     * @throws Exception if the configuration description cannot be read
     */
    @Test
    public void thePlanParametersAreDeclaredExactlyAsTheyAreRead() throws Exception {
        assertThat(declaredParameters().keySet(), is(new TreeSet<>(EnergyPlanCoordinator.CONFIG_KEYS)));
    }

    /**
     * The refresh interval declares no default, and says what leaving it empty means.
     * <p>
     * This is the one number wave 2 was tempted to invent. Shipping a cadence would have made the plane look
     * complete while quietly deciding how often a site talks to its price source; leaving it out makes the gap the
     * site's to close, which is only honest if the settings page says so.
     *
     * @throws Exception if the configuration description cannot be read
     */
    @Test
    public void theRefreshIntervalShipsNoDefaultAndSaysWhatItsAbsenceCosts() throws Exception {
        Element interval = Objects
                .requireNonNull(declaredParameters().get(EnergyPlanCoordinator.CONFIG_REFRESH_INTERVAL));

        assertThat("a shipped cadence would decide something the corpus does not",
                interval.getElementsByTagNameNS("*", "default").getLength(), is(0));
        assertThat(text(interval, "description"), containsString("no default"));
        assertThat(text(interval, "description"), containsString("re-derived only when the configuration changes"));
    }

    /**
     * The surplus-forecast switch is off by default, and its description admits the quantity is an approximation.
     * <p>
     * Unlike the interval this one does ship a default, and the default is "do not": building a surplus series out of
     * a solar forecast alone produces an upper bound rather than a surplus, and a site should opt into that rather
     * than discover it.
     *
     * @throws Exception if the configuration description cannot be read
     */
    @Test
    public void theSurplusForecastIsOffByDefaultAndSaysWhyItIsApproximate() throws Exception {
        Element surplus = Objects
                .requireNonNull(declaredParameters().get(EnergyPlanCoordinator.CONFIG_SURPLUS_FORECAST));

        assertThat(text(surplus, "default"), is("false"));
        assertThat(text(surplus, "description"), containsString("no requirement defines this quantity"));
    }

    /**
     * Every label and description an operator sees has a key in the default properties bundle, or it never reaches
     * Crowdin and stays English for everybody.
     *
     * @throws Exception if the configuration description or the properties bundle cannot be read
     */
    @Test
    public void everyUserVisibleStringHasATranslationKey() throws Exception {
        Map<String, String> translations = translations();
        assertThat(translations, hasKey("service.system.energy-plan.label"));

        String prefix = EnergyPlanCoordinator.CONFIG_URI.replace(":", ".config.");
        declaredParameters().forEach((name, parameter) -> {
            assertThat(translations, hasEntry(prefix + "." + name + ".label", text(parameter, "label")));
            assertThat(translations, hasEntry(prefix + "." + name + ".description", text(parameter, "description")));
        });
    }

    /**
     * Every status-message key the engine can report has a translation, checked by reflecting over the constants
     * rather than by listing them.
     * <p>
     * <strong>This is here because listing them failed.</strong> {@code EnergyConfigStatus} declared eight
     * {@code KEY_} constants and the properties bundle carried seven: the plan conditions - the newest of them, and
     * the ones carrying the one degradation wave 2 refused to default - rendered with no text at all. Each sibling
     * test pinned only its own key, so nothing noticed the eighth. Reflecting over the constants closes the family
     * instead of the two instances, and a ninth added tomorrow is covered the moment it is declared.
     * <p>
     * The type is not asserted, only that <em>some</em> severity carries the key, because whether a condition is a
     * warning or a note is the reporting component's judgement and not this test's business.
     *
     * @throws Exception if the properties bundle cannot be read
     */
    @Test
    public void everyConfigStatusMessageKeyHasATranslation() throws Exception {
        Map<String, String> translations = translations();
        List<String> declared = new ArrayList<>();
        for (Field field : EnergyConfigStatus.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers())
                    && field.getName().startsWith("KEY_")) {
                declared.add(String.valueOf(field.get(null)));
            }
        }

        assertThat("the reflection has to find the constants, or this test passes vacuously", declared,
                hasSize(greaterThanOrEqualTo(8)));
        for (String key : declared) {
            assertThat("no translation for the '" + key + "' status message",
                    translations.keySet().stream()
                            .anyMatch(candidate -> candidate.equals("config-status.error." + key)
                                    || candidate.equals("config-status.warning." + key)
                                    || candidate.equals("config-status.information." + key)),
                    is(true));
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
        for (int i = 0; i < descriptions.getLength(); i++) {
            Element description = (Element) descriptions.item(i);
            if (!EnergyPlanCoordinator.CONFIG_URI.equals(description.getAttribute("uri"))) {
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
        assertThat("no parameters declared for " + EnergyPlanCoordinator.CONFIG_URI, parameters.keySet(),
                is(not(empty())));
        return parameters;
    }
}
