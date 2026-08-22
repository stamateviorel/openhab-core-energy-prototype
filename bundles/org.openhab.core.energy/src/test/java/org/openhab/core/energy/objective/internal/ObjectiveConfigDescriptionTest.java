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
package org.openhab.core.energy.objective.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.objective.ObjectiveInputs;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Holds the objective plane's configuration description, the Java that reads it, and the translation bundle to each
 * other - the same three-way check the engine and the level plane already carry, for the same reason: a default
 * declared in the XML and a default hard-coded in Java are two statements about the same thing, and a settings page
 * that is opened and saved untouched must not change how a site behaves.
 * <p>
 * The objective plane makes that check load-bearing in a way the earlier ones did not: three of its five parameters
 * <em>are</em> open questions, so a drift between the declared default and the implemented one would silently answer
 * a question the corpus deliberately leaves open.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ObjectiveConfigDescriptionTest {

    private static final Path CONFIG_XML = Path.of("src", "main", "resources", "OH-INF", "config", "energy.xml");
    private static final String CONFIG_URI = "system:energy-objective";

    @Test
    public void everyDeclaredParameterIsOneThePlaneReadsAndTheOtherWayRound() throws Exception {
        assertThat(declaredParameters().keySet(),
                containsInAnyOrder("objective", "absentDataPlane", "exportCarbonCredit", "levelInput", "carbonSource"));
    }

    /**
     * Saving the settings page untouched has to leave the site behaving exactly as it did unconfigured, which for
     * this plane means: optimizing for cost, falling back rather than refusing, withdrawing the export credit on a
     * negative price, and cutting the level bands out of the price series.
     *
     * @throws Exception if the configuration description cannot be read
     */
    @Test
    public void theDeclaredDefaultsAreTheDefaultsThePlaneFallsBackTo() throws Exception {
        ObjectivePlane saved = ObjectiveFixtures.planeWithBuiltIns(declaredDefaults());
        ObjectivePlane unconfigured = ObjectiveFixtures.planeWithBuiltIns(Map.of());

        ObjectiveInputs inputs = ObjectiveFixtures.fullyEquippedSite();

        assertThat(saved.absentDataPlanePolicy(), is(unconfigured.absentDataPlanePolicy()));
        assertThat(saved.resolve(inputs).effectiveId(), is(unconfigured.resolve(inputs).effectiveId()));
        assertThat(saved.levelDerivationInput(inputs, saved.resolve(inputs)),
                is(unconfigured.levelDerivationInput(inputs, unconfigured.resolve(inputs))));
        assertThat(new CarbonObjective(declaredDefaults()).getExportCarbonCredit().getId(),
                is(new CarbonObjective().getExportCarbonCredit().getId()));
    }

    /**
     * The three shipped objectives are the three the requirement asks for, and every one of them is offered in the
     * configuration description so that a user can actually pick it.
     *
     * @throws Exception if the configuration description cannot be read
     */
    @Test
    public void theThreeBuiltInObjectivesAreOfferedForSelection() throws Exception {
        Element objective = declaredParameters().get("objective");
        assertThat(objective, is(notNullValue()));

        assertThat(optionValues(Objects.requireNonNull(objective)),
                containsInAnyOrder(CostObjective.ID, SelfConsumptionObjective.ID, CarbonObjective.ID));
    }

    /**
     * The export-credit rule and its preserved alternative are both offered, which is what makes the corpus's most
     * overturnable decision a configuration change rather than a code change.
     *
     * @throws Exception if the configuration description cannot be read
     */
    @Test
    public void bothExportCreditRulesAreOfferedAndTheShippedOneIsTheDefault() throws Exception {
        Element credit = declaredParameters().get("exportCarbonCredit");
        assertThat(credit, is(notNullValue()));
        Element declared = Objects.requireNonNull(credit);

        assertThat(optionValues(declared), containsInAnyOrder(NegativeFeedInCarbonCredit.ID, "always"));
        assertThat(text(declared, "default"), is(NegativeFeedInCarbonCredit.ID));
        assertThat("the description says what the rule rests on, because a user picking it deserves to know",
                text(declared, "description"), containsString("reasoning alone"));
    }

    /**
     * Every label, description and option a user sees has a key in the default translation bundle, or it never
     * reaches Crowdin and stays English for everybody.
     *
     * @throws Exception if the configuration description or the properties bundle cannot be read
     */
    @Test
    public void everyUserVisibleStringHasATranslationKey() throws Exception {
        Map<String, String> translations = translations();

        assertThat(translations, hasKey("service.system.energy-objective.label"));
        assertThat(translations, hasKey("config-status.warning.objective-condition"));
        assertThat(translations, hasKey("config-status.information.objective-note"));

        String prefix = CONFIG_URI.replace(":", ".config.");
        declaredParameters().forEach((name, parameter) -> {
            assertThat(translations, hasEntry(prefix + "." + name + ".label", text(parameter, "label")));
            assertThat(translations, hasEntry(prefix + "." + name + ".description", text(parameter, "description")));
            NodeList options = parameter.getElementsByTagNameNS("*", "option");
            for (int i = 0; i < options.getLength(); i++) {
                Element option = (Element) options.item(i);
                assertThat(translations, hasEntry(prefix + "." + name + ".option." + option.getAttribute("value"),
                        option.getTextContent().replaceAll("\\s+", " ").trim()));
            }
        });
    }

    private static java.util.List<String> optionValues(Element parameter) {
        NodeList options = parameter.getElementsByTagNameNS("*", "option");
        java.util.List<String> values = new java.util.ArrayList<>();
        for (int i = 0; i < options.getLength(); i++) {
            values.add(((Element) options.item(i)).getAttribute("value"));
        }
        return values;
    }

    private static String text(Element parameter, String tag) {
        NodeList children = parameter.getElementsByTagNameNS("*", tag);
        assertThat("parameter '" + parameter.getAttribute("name") + "' has no <" + tag + ">", children.getLength(),
                is(1));
        return children.item(0).getTextContent().replaceAll("\\s+", " ").trim();
    }

    private static Map<String, Object> declaredDefaults() throws Exception {
        Map<String, Object> defaults = new LinkedHashMap<>();
        declaredParameters().forEach((name, parameter) -> {
            NodeList declared = parameter.getElementsByTagNameNS("*", "default");
            if (declared.getLength() == 1) {
                defaults.put(name, declared.item(0).getTextContent().trim());
            }
        });
        return defaults;
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
            if (!CONFIG_URI.equals(description.getAttribute("uri"))) {
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
        assertThat("no parameters declared for " + CONFIG_URI, parameters.keySet(), is(not(empty())));
        return parameters;
    }
}
