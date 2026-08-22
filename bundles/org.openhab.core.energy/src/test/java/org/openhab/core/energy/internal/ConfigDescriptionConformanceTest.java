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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.level.FixtureCsv;
import org.openhab.core.energy.level.PlannedLevelSchedule;
import org.openhab.core.energy.window.SlotSeries;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Holds {@code OH-INF/config/energy.xml} and the components that read it to each other.
 * <p>
 * The configuration description is what an operator sees and what the framework persists when a settings page is
 * saved, so a default declared there and a default hard-coded in Java are two statements about the same thing and
 * have to agree. They did not: the three percentile fractions were declared as {@code 0.1667} and implemented as
 * {@code 1.0 / 6}, and because the nearest-rank ceiling turns {@code 0.1667 * 24} into a fifth slot, merely opening
 * and saving the Energy Levels page reclassified the day away from the value the acceptance fixture pins.
 * <p>
 * Three properties are asserted, and together they close that whole class of defect rather than the one instance:
 * <ol>
 * <li>every parameter declared in the XML is one the code reads, and every parameter the code reads is declared;</li>
 * <li>configuring every declared default explicitly produces exactly the configuration the components fall back to
 * when nothing is configured at all - checked both with the raw declared text and with the typed values the
 * framework materialises them into;</li>
 * <li>the two level derivations still agree on the acceptance fixture when the percentile one runs on the declared
 * defaults, which is the behaviour the original divergence broke.</li>
 * </ol>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ConfigDescriptionConformanceTest {

    private static final Path CONFIG_XML = Path.of("src", "main", "resources", "OH-INF", "config", "energy.xml");

    @Test
    public void theEngineParametersAreDeclaredExactlyAsTheyAreRead() throws Exception {
        assertThat(declaredParameters(EnergyEngine.CONFIG_URI).keySet(),
                is(new TreeSet<>(EnergyEngineConfiguration.CONFIG_KEYS)));
    }

    @Test
    public void theLevelParametersAreDeclaredExactlyAsTheyAreRead() throws Exception {
        assertThat(declaredParameters(EnergyLevelPlane.CONFIG_URI).keySet(),
                is(new TreeSet<>(EnergyLevelConfiguration.CONFIG_KEYS)));
    }

    @Test
    public void theDeclarationParametersAreDeclaredExactlyAsTheyAreRead() throws Exception {
        assertThat(declaredParameters(EnergyParticipantRegistryImpl.CONFIG_URI).keySet(),
                is(new TreeSet<>(Set.of(EnergyParticipantRegistryImpl.CONFIG_SOURCES))));
    }

    /**
     * The precedence chain is fixed rather than configured, so no parameter may offer to reorder it. Asserting the
     * absence rather than only the presence keeps a well-meaning re-introduction from passing the parity check.
     *
     * @throws Exception if the configuration description cannot be read
     */
    @Test
    public void noParameterOffersToReorderTheFixedPrecedenceChain() throws Exception {
        assertThat(declaredParameters(EnergyParticipantRegistryImpl.CONFIG_URI).keySet(), not(hasItem("precedence")));
    }

    @Test
    public void theDeclaredEngineDefaultsAreTheDefaultsTheEngineFallsBackTo() throws Exception {
        EnergyEngineConfiguration unconfigured = EnergyEngineConfiguration.fromProperties(Map.of());

        assertThat(EnergyEngineConfiguration.fromProperties(declaredDefaults(EnergyEngine.CONFIG_URI, false)),
                is(unconfigured));
        assertThat(EnergyEngineConfiguration.fromProperties(declaredDefaults(EnergyEngine.CONFIG_URI, true)),
                is(unconfigured));
    }

    @Test
    public void theDeclaredLevelDefaultsAreTheDefaultsTheLevelPlaneFallsBackTo() throws Exception {
        EnergyLevelConfiguration unconfigured = EnergyLevelConfiguration.defaults();

        assertThat(EnergyLevelConfiguration.fromProperties(declaredDefaults(EnergyLevelPlane.CONFIG_URI, false)),
                is(unconfigured));
        assertThat(EnergyLevelConfiguration.fromProperties(declaredDefaults(EnergyLevelPlane.CONFIG_URI, true)),
                is(unconfigured));
    }

    /**
     * The behavioural half: an operator who saves the Energy Levels page untouched and then switches the derivation
     * to percentiles must get the same classification of the acceptance fixture as the fixed-count default gives.
     *
     * @throws Exception if the configuration description cannot be read
     */
    @Test
    public void theDeclaredFractionsClassifyTheFixtureLikeTheDeclaredCounts() throws Exception {
        SlotSeries prices = FixtureCsv.prices();
        Map<String, Object> saved = declaredDefaults(EnergyLevelPlane.CONFIG_URI, true);

        PlannedLevelSchedule byCounts = EnergyLevelConfiguration.fromProperties(saved).createDerivation()
                .derive(prices);
        Map<String, Object> percentiles = new LinkedHashMap<>(saved);
        percentiles.put(EnergyLevelConfiguration.CONFIG_DERIVATION, EnergyLevelPlane.DERIVATION_PERCENTILES);
        PlannedLevelSchedule byFractions = EnergyLevelConfiguration.fromProperties(percentiles).createDerivation()
                .derive(prices);

        assertThat("the fixture is only a gate if it has slots", prices.size(), is(24));
        for (int slot = 0; slot < prices.size(); slot++) {
            EnergyLevel expected = byCounts.levelAt(prices.slotAt(slot).start()).orElseThrow();
            assertThat("slot " + slot + " is classified differently by the two declared defaults",
                    byFractions.levelAt(prices.slotAt(slot).start()).orElseThrow(), is(expected));
        }
    }

    /**
     * Every label and description an operator sees has to be translatable, which means it has to have a key in the
     * default properties bundle. Without one it never reaches Crowdin and stays English for everybody, forever.
     *
     * @throws Exception if the configuration description or the properties bundle cannot be read
     */
    @Test
    public void everyUserVisibleStringHasATranslationKey() throws Exception {
        Map<String, String> translations = translations();

        assertThat(translations, hasKey("service.system.energy.label"));
        assertThat(translations, hasKey("service.system.energy-level.label"));
        assertThat(translations, hasKey("service.system.energy-declaration.label"));

        for (String uri : Set.of(EnergyEngine.CONFIG_URI, EnergyLevelPlane.CONFIG_URI,
                EnergyParticipantRegistryImpl.CONFIG_URI)) {
            // "system:energy" is rendered as "system.config.energy" in the properties bundle
            String prefix = uri.replace(":", ".config.");
            declaredParameters(uri).forEach((name, parameter) -> {
                assertThat(translations, hasEntry(prefix + "." + name + ".label", text(parameter, "label")));
                assertThat(translations,
                        hasEntry(prefix + "." + name + ".description", text(parameter, "description")));
                NodeList options = parameter.getElementsByTagNameNS("*", "option");
                for (int i = 0; i < options.getLength(); i++) {
                    Element option = (Element) options.item(i);
                    assertThat(translations, hasEntry(prefix + "." + name + ".option." + option.getAttribute("value"),
                            option.getTextContent().trim()));
                }
            });
        }
    }

    /**
     * The master stop halts <em>everything</em>, protections included, and that is the one description an operator
     * reads at the moment they reach for the kill switch. It used to promise the opposite - "the engine keeps
     * evaluating and logging while it is engaged" - which is the reading the owner overrode, and a wrong promise in
     * that direction is the dangerous one: it says the protections are still being enforced when they are not.
     *
     * @throws Exception if the configuration description cannot be read
     */
    @Test
    public void theMasterStopDescriptionSaysThatEvaluationStopsToo() throws Exception {
        Element stop = declaredParameters(EnergyEngine.CONFIG_URI).get("stopped");
        assertThat("the master stop must be a declared parameter", stop, is(notNullValue()));
        String description = text(Objects.requireNonNull(stop), "description");

        assertThat(description, containsString("no device protection"));
        assertThat(description, not(containsString("keeps evaluating")));
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

    /**
     * Returns the text of a parameter's child element, collapsed the way the translation generator collapses it.
     *
     * @param parameter the parameter element
     * @param tag the child element name
     * @return the collapsed text
     */
    private static String text(Element parameter, String tag) {
        NodeList children = parameter.getElementsByTagNameNS("*", tag);
        assertThat("parameter '" + parameter.getAttribute("name") + "' has no <" + tag + ">", children.getLength(),
                is(1));
        return children.item(0).getTextContent().replaceAll("\\s+", " ").trim();
    }

    /**
     * Returns the parameters declared for one configuration description, by name.
     *
     * @param uri the configuration description URI
     * @return the parameter elements, keyed and ordered by parameter name
     * @throws Exception if the configuration description cannot be read
     */
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

    /**
     * Returns every declared default of one configuration description as component properties.
     *
     * @param uri the configuration description URI
     * @param typed {@code true} to convert each default into the type the framework materialises it as, {@code false}
     *            to keep the declared text, which is what a {@code .cfg} file delivers
     * @return the properties
     * @throws Exception if the configuration description cannot be read
     */
    private static Map<String, Object> declaredDefaults(String uri, boolean typed) throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>();
        declaredParameters(uri).forEach((name, parameter) -> {
            NodeList defaults = parameter.getElementsByTagNameNS("*", "default");
            if (defaults.getLength() == 0) {
                return;
            }
            String declared = defaults.item(0).getTextContent().trim();
            properties.put(name, typed ? typedValue(parameter.getAttribute("type"), declared) : declared);
        });
        assertThat("no defaults declared for " + uri, properties.keySet(), is(not(empty())));
        return properties;
    }

    private static Object typedValue(String type, String declared) {
        return switch (type) {
            case "boolean" -> Boolean.valueOf(declared);
            case "integer", "decimal" -> new BigDecimal(declared);
            default -> declared;
        };
    }
}
