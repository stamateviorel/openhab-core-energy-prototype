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

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.config.core.ConfigParser;
import org.openhab.core.energy.objective.AbsentDataPlanePolicy;
import org.openhab.core.energy.objective.ExportCarbonCredit;

/**
 * Everything about the objective plane that is a choice rather than a rule, read out of the component properties once
 * and kept as an immutable snapshot.
 * <p>
 * The twin of the engine's and the level plane's own configuration records, and it exists for the same two reasons:
 * a component should read its configuration once rather than on every use, and the defaults the code falls back to
 * should be named values a test can hold the configuration description to.
 * <p>
 * <strong>Every parameter here is a seam over an open question, and none of them is a number.</strong> The corpus
 * decided no parameters at all for this capability, so nothing here invents a threshold, a horizon or a cadence.
 * What it does carry is which of several framed behaviours is in force, in three places where the corpus frames
 * options and records no decision - what to do when a selected objective's data plane is absent, what an exported
 * kilowatt-hour is worth to the carbon objective, and whether the level bands follow the objective or stay on price.
 * The shipped values keep a site behaving exactly as it did before the objective became selectable.
 * <p>
 * Nothing here rejects a configuration. An unreadable value falls back to the default and is reported, so that a typo
 * in one parameter cannot stop the plane from coming up.
 *
 * @param objectiveId the objective the site selected
 * @param absentDataPlane what happens when that objective's data plane is not there
 * @param exportCarbonCreditId which export-credit rule the carbon objective applies
 * @param levelInput which series the level bands are cut out of
 * @param preferredCarbonSource the carbon source to prefer, overriding the service ranking, empty for none
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
record ObjectivePlaneConfiguration(String objectiveId, AbsentDataPlanePolicy absentDataPlane,
        String exportCarbonCreditId, String levelInput, String preferredCarbonSource) {

    /**
     * The configuration PID the objective plane reads its parameters under.
     */
    static final String CONFIGURATION_PID = "org.openhab.core.energy.objective";

    /**
     * The URI of the configuration description that renders these parameters in the UI.
     */
    static final String CONFIG_URI = "system:energy-objective";

    /**
     * The objective in force when a site selects none.
     * <p>
     * It is the cost objective because that is what every planning requirement in the corpus assumed before the
     * objective became selectable - naming the incumbent, not preferring it. Choosing a different one is explicitly a
     * product decision the corpus leaves open.
     */
    static final String DEFAULT_OBJECTIVE = CostObjective.ID;

    /**
     * The export-credit rule in force when a site selects none: the shipped rule withdraws the carbon credit while
     * the feed-in price is negative. See {@link NegativeFeedInCarbonCredit} for why this is the most overturnable
     * default in the framework and how to overturn it.
     */
    static final String DEFAULT_EXPORT_CREDIT = NegativeFeedInCarbonCredit.ID;

    /**
     * The {@code levelInput} value that cuts the level bands out of the consumption price, which is what every
     * production system behind the corpus has ever run.
     */
    static final String LEVEL_INPUT_PRICE = "price";

    /**
     * The {@code levelInput} value that cuts the level bands out of the active objective's own ranking, so that a
     * site on the carbon objective gets carbon-shaped levels.
     */
    static final String LEVEL_INPUT_OBJECTIVE = "objective";

    static final String CONFIG_OBJECTIVE = "objective";
    static final String CONFIG_ABSENT_DATA_PLANE = "absentDataPlane";
    static final String CONFIG_EXPORT_CARBON_CREDIT = "exportCarbonCredit";
    static final String CONFIG_LEVEL_INPUT = "levelInput";
    static final String CONFIG_CARBON_SOURCE = "carbonSource";

    /**
     * Every parameter this record reads, so that the configuration description and the code can be held to each
     * other instead of drifting apart.
     */
    static final Set<String> CONFIG_KEYS = Set.of(CONFIG_OBJECTIVE, CONFIG_ABSENT_DATA_PLANE,
            CONFIG_EXPORT_CARBON_CREDIT, CONFIG_LEVEL_INPUT, CONFIG_CARBON_SOURCE);

    /**
     * Returns the configuration of an objective plane nobody has configured.
     *
     * @return the default configuration
     */
    static ObjectivePlaneConfiguration defaults() {
        return fromProperties(Map.of());
    }

    /**
     * Reads a configuration out of OSGi component properties.
     *
     * @param properties the component properties
     * @return the configuration
     */
    static ObjectivePlaneConfiguration fromProperties(Map<String, Object> properties) {
        return fromProperties(properties, rejected -> {
        });
    }

    /**
     * Reads a configuration out of OSGi component properties, reporting anything it had to reject.
     *
     * @param properties the component properties
     * @param rejected receives one description per value that could not be used
     * @return the configuration
     */
    static ObjectivePlaneConfiguration fromProperties(Map<String, Object> properties, Consumer<String> rejected) {
        return new ObjectivePlaneConfiguration(text(properties, CONFIG_OBJECTIVE, DEFAULT_OBJECTIVE),
                policy(properties, rejected), exportCredit(properties, rejected), levelInput(properties, rejected),
                text(properties, CONFIG_CARBON_SOURCE, ""));
    }

    /**
     * Returns the export-credit rule this configuration selects.
     * <p>
     * The two rules are the decided one and the preserved alternative, and this method is the whole of the switch
     * between them.
     *
     * @return the rule
     */
    ExportCarbonCredit createExportCarbonCredit() {
        return UnconditionalExportCredit.ID.equals(exportCarbonCreditId) ? new UnconditionalExportCredit()
                : new NegativeFeedInCarbonCredit();
    }

    /**
     * Returns whether the level bands are to be cut out of the active objective's ranking rather than out of the
     * price series.
     *
     * @return {@code true} if the levels follow the objective
     */
    boolean levelsFollowObjective() {
        return LEVEL_INPUT_OBJECTIVE.equals(levelInput);
    }

    private static AbsentDataPlanePolicy policy(Map<String, Object> properties, Consumer<String> rejected) {
        String configured = text(properties, CONFIG_ABSENT_DATA_PLANE, AbsentDataPlanePolicy.HIDE_UNAVAILABLE.id());
        return AbsentDataPlanePolicy.parse(configured).orElseGet(() -> {
            rejected.accept("'" + CONFIG_ABSENT_DATA_PLANE + "' is none of the known policies but was '" + configured
                    + "'; falling back to '" + AbsentDataPlanePolicy.HIDE_UNAVAILABLE.id() + "'");
            return AbsentDataPlanePolicy.HIDE_UNAVAILABLE;
        });
    }

    private static String exportCredit(Map<String, Object> properties, Consumer<String> rejected) {
        String configured = text(properties, CONFIG_EXPORT_CARBON_CREDIT, DEFAULT_EXPORT_CREDIT);
        if (NegativeFeedInCarbonCredit.ID.equals(configured) || UnconditionalExportCredit.ID.equals(configured)) {
            return configured;
        }
        rejected.accept("'" + CONFIG_EXPORT_CARBON_CREDIT + "' is none of the shipped rules but was '" + configured
                + "'; falling back to '" + DEFAULT_EXPORT_CREDIT + "'");
        return DEFAULT_EXPORT_CREDIT;
    }

    private static String levelInput(Map<String, Object> properties, Consumer<String> rejected) {
        String configured = text(properties, CONFIG_LEVEL_INPUT, LEVEL_INPUT_PRICE);
        if (LEVEL_INPUT_PRICE.equals(configured) || LEVEL_INPUT_OBJECTIVE.equals(configured)) {
            return configured;
        }
        rejected.accept(
                "'" + CONFIG_LEVEL_INPUT + "' is neither '" + LEVEL_INPUT_PRICE + "' nor '" + LEVEL_INPUT_OBJECTIVE
                        + "' but was '" + configured + "'; falling back to '" + LEVEL_INPUT_PRICE + "'");
        return LEVEL_INPUT_PRICE;
    }

    private static String text(Map<String, Object> properties, String key, String fallback) {
        String value = ConfigParser.valueAsOrElse(properties.get(key), String.class, fallback).trim();
        return value.isEmpty() ? fallback : value;
    }
}
