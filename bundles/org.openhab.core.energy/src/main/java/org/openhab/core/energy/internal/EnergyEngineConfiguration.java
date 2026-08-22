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

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.ConfigParser;
import org.openhab.core.energy.ElectricalLimits;

/**
 * Everything about the engine that is a choice rather than a rule.
 * <p>
 * <strong>Five parameters are gone, and their absence is the point.</strong> The prototype offered
 * {@code precedenceStrategy}, {@code gate}, {@code participantGuard}, {@code unknownDemandPolicy} and
 * {@code degradeSafeOnStaleMeasurements} so that a site could choose between two readings of a question the corpus
 * had not answered. The owner has answered all five, so each is now a rule rather than a choice: the ladder is fixed
 * ({@link ConstraintLadder}), stop and shadow are two controls ({@link ActuationGate}), the prohibitions are
 * engine-owned ({@link EngineEnforcedParticipantGuard}), a mode change is exempt from the budget to the extent of the
 * figure it declares ({@link ElectricalLimitFloor}), and a degraded safety input always freezes and floors the site
 * ({@link SafeStatePass}). A reviewer should read that as the reduction in configurability it is.
 * <p>
 * Two parameters still exist purely to keep an open question open, and each names the question it belongs to:
 * {@code ackHandling} (design.md §3, engine or adapter) and {@code ackSuppressChangedCommands} (§3, whether a
 * command differing from the outstanding one is a repeat). A third, {@code eventResponsive} (§1, fixed tick or tick
 * plus event-driven re-evaluation), exists because the engine subscribes to nothing yet.
 * <p>
 * {@code actuationSink} exists because nothing in the corpus says how a site picks between several contributed
 * actuation adapters. It is empty by default, which means the engine dispatches to the bundle's own logging sink:
 * a writing sink is used only when an operator names it, never because it happened to bind first.
 * <p>
 * {@code nominalVoltage} exists because the participant model does not carry it: it converts declared currents into
 * powers. Phases used to sit here for the same reason and no longer do - a phase is a property of the device, so it
 * is declared with the device ({@link org.openhab.core.energy.EnergyConsumer#phases()} and a provider's per-phase
 * reading Items) and the engine reads it from there.
 *
 * @param shadow whether decisions are only logged - {@code true} on a fresh install, as the requirement demands
 * @param stopped whether the master stop starts engaged
 * @param cycleInterval how often the engine evaluates
 * @param eventResponsive whether {@code triggerEvaluation()} may run a cycle between ticks
 * @param triggerDebounce the shortest time between two triggered cycles
 * @param ackWindow how long to wait for a device to acknowledge a command before giving up on it
 * @param ackSuppressChangedCommands whether a command differing from the outstanding one is suppressed too
 * @param ackHandlingId which {@link AcknowledgementTracker} to use
 * @param actuationSinkId the id of the actuation sink to dispatch to, or empty to keep logging only
 * @param shadowedAlgorithms algorithms kept in shadow while global shadow is off
 * @param shadowedParticipants participants kept in shadow while global shadow is off
 * @param limits the declared electrical limits
 * @param nominalVoltage the nominal phase voltage used to convert currents into powers
 * @param staleAfter the age at which a declared measurement counts as stale, or {@code null} to ignore age
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public record EnergyEngineConfiguration(boolean shadow, boolean stopped, Duration cycleInterval,
        boolean eventResponsive, Duration triggerDebounce, Duration ackWindow, boolean ackSuppressChangedCommands,
        String ackHandlingId, String actuationSinkId, Set<String> shadowedAlgorithms, Set<String> shadowedParticipants,
        ElectricalLimits limits, double nominalVoltage, @Nullable Duration staleAfter) {

    /**
     * The cadence the engine falls back to. {@code design.md} §1 records that a default was never agreed - Kai
     * suggested scraping "possibly up to every minute" - so one minute it is, and the parameter is prominent.
     */
    public static final Duration DEFAULT_CYCLE_INTERVAL = Duration.ofMinutes(1);

    /**
     * The acknowledgement window the engine falls back to. Nothing in the corpus sizes it.
     */
    public static final Duration DEFAULT_ACK_WINDOW = Duration.ofSeconds(60);

    /**
     * The shortest time between two triggered cycles when none is configured.
     */
    public static final Duration DEFAULT_TRIGGER_DEBOUNCE = Duration.ofSeconds(5);

    /**
     * The nominal phase voltage used when none is configured.
     */
    public static final double DEFAULT_NOMINAL_VOLTAGE = 230;

    static final String CONFIG_SHADOW = "shadow";
    static final String CONFIG_STOPPED = "stopped";
    static final String CONFIG_CYCLE_INTERVAL = "cycleInterval";
    static final String CONFIG_EVENT_RESPONSIVE = "eventResponsive";
    static final String CONFIG_TRIGGER_DEBOUNCE = "triggerDebounce";
    static final String CONFIG_ACK_HANDLING = "ackHandling";
    static final String CONFIG_ACK_WINDOW = "ackWindow";
    static final String CONFIG_ACK_SUPPRESS_CHANGED_COMMANDS = "ackSuppressChangedCommands";
    static final String CONFIG_ACTUATION_SINK = "actuationSink";
    static final String CONFIG_SHADOWED_ALGORITHMS = "shadowedAlgorithms";
    static final String CONFIG_SHADOWED_PARTICIPANTS = "shadowedParticipants";
    static final String CONFIG_BUDGET = "budget";
    static final String CONFIG_PHASE_BUDGETS = "phaseBudgets";
    static final String CONFIG_NOMINAL_VOLTAGE = "nominalVoltage";
    static final String CONFIG_STALE_AFTER = "staleAfter";

    /**
     * Every parameter this record reads, so that the configuration description and the code can be held to each
     * other instead of drifting apart.
     */
    static final Set<String> CONFIG_KEYS = Set.of(CONFIG_SHADOW, CONFIG_STOPPED, CONFIG_CYCLE_INTERVAL,
            CONFIG_EVENT_RESPONSIVE, CONFIG_TRIGGER_DEBOUNCE, CONFIG_ACK_HANDLING, CONFIG_ACK_WINDOW,
            CONFIG_ACK_SUPPRESS_CHANGED_COMMANDS, CONFIG_ACTUATION_SINK, CONFIG_SHADOWED_ALGORITHMS,
            CONFIG_SHADOWED_PARTICIPANTS, CONFIG_BUDGET, CONFIG_PHASE_BUDGETS, CONFIG_NOMINAL_VOLTAGE,
            CONFIG_STALE_AFTER);

    /**
     * Takes immutable copies of the collection-valued parameters.
     */
    public EnergyEngineConfiguration {
        shadowedAlgorithms = Set.copyOf(shadowedAlgorithms);
        shadowedParticipants = Set.copyOf(shadowedParticipants);
    }

    /**
     * Returns the configuration of an engine nobody has configured: shadow on, nothing stopped, no limits.
     *
     * @return the default configuration
     */
    public static EnergyEngineConfiguration defaults() {
        return fromProperties(Map.of());
    }

    /**
     * Reads a configuration out of OSGi component properties.
     * <p>
     * Unknown values fall back to the default and are not rejected, so a typo in one parameter cannot take the
     * engine's safety floor down with it.
     *
     * @param properties the component properties
     * @return the configuration
     */
    public static EnergyEngineConfiguration fromProperties(Map<String, Object> properties) {
        return fromProperties(properties, rejected -> {
        });
    }

    /**
     * Reads a configuration out of OSGi component properties, reporting anything it had to reject.
     * <p>
     * The rejections are handed to the caller rather than logged here, because a value type has no business owning
     * a logger - and because the component that reads the configuration is the one that knows whether this is a
     * fresh activation or a reconfiguration.
     *
     * @param properties the component properties
     * @param rejected receives one description per entry of a multi-part parameter that could not be read
     * @return the configuration
     */
    public static EnergyEngineConfiguration fromProperties(Map<String, Object> properties, Consumer<String> rejected) {
        double budget = doubleValue(properties, CONFIG_BUDGET, Double.NaN);
        ElectricalLimits limits = Double.isNaN(budget) ? ElectricalLimits.unlimited()
                : ElectricalLimits.ofWatts(budget);
        for (Map.Entry<Integer, Double> entry : parsePhaseBudgets(stringValue(properties, CONFIG_PHASE_BUDGETS, ""),
                rejected).entrySet()) {
            limits = limits.withPhaseWatts(entry.getKey(), entry.getValue());
        }
        Duration staleAfter = duration(properties, CONFIG_STALE_AFTER, Duration.ZERO, true);
        return new EnergyEngineConfiguration(booleanValue(properties, CONFIG_SHADOW, true),
                booleanValue(properties, CONFIG_STOPPED, false),
                duration(properties, CONFIG_CYCLE_INTERVAL, DEFAULT_CYCLE_INTERVAL, false),
                booleanValue(properties, CONFIG_EVENT_RESPONSIVE, false),
                duration(properties, CONFIG_TRIGGER_DEBOUNCE, DEFAULT_TRIGGER_DEBOUNCE, true),
                duration(properties, CONFIG_ACK_WINDOW, DEFAULT_ACK_WINDOW, false),
                booleanValue(properties, CONFIG_ACK_SUPPRESS_CHANGED_COMMANDS, false),
                stringValue(properties, CONFIG_ACK_HANDLING, EngineAcknowledgementTracker.ID),
                stringValue(properties, CONFIG_ACTUATION_SINK, ""),
                ConfigLists.toSet(properties.get(CONFIG_SHADOWED_ALGORITHMS)),
                ConfigLists.toSet(properties.get(CONFIG_SHADOWED_PARTICIPANTS)), limits,
                doubleValue(properties, CONFIG_NOMINAL_VOLTAGE, DEFAULT_NOMINAL_VOLTAGE),
                staleAfter.isZero() ? null : staleAfter);
    }

    /**
     * Returns a copy of this configuration with a different shadow flag, so the runtime control and the
     * configuration never drift apart.
     *
     * @param newShadow whether decisions are only logged
     * @return the copy
     */
    public EnergyEngineConfiguration withShadow(boolean newShadow) {
        return new EnergyEngineConfiguration(newShadow, stopped, cycleInterval, eventResponsive, triggerDebounce,
                ackWindow, ackSuppressChangedCommands, ackHandlingId, actuationSinkId, shadowedAlgorithms,
                shadowedParticipants, limits, nominalVoltage, staleAfter);
    }

    /**
     * Returns a copy of this configuration with a different master-stop flag.
     *
     * @param newStopped whether all actuation is halted
     * @return the copy
     */
    public EnergyEngineConfiguration withStopped(boolean newStopped) {
        return new EnergyEngineConfiguration(shadow, newStopped, cycleInterval, eventResponsive, triggerDebounce,
                ackWindow, ackSuppressChangedCommands, ackHandlingId, actuationSinkId, shadowedAlgorithms,
                shadowedParticipants, limits, nominalVoltage, staleAfter);
    }

    private static boolean booleanValue(Map<String, Object> properties, String key, boolean fallback) {
        return ConfigParser.valueAsOrElse(properties.get(key), Boolean.class, fallback);
    }

    private static double doubleValue(Map<String, Object> properties, String key, double fallback) {
        return ConfigParser.valueAsOrElse(properties.get(key), Double.class, fallback);
    }

    private static String stringValue(Map<String, Object> properties, String key, String fallback) {
        return ConfigParser.valueAsOrElse(properties.get(key), String.class, fallback).trim();
    }

    /**
     * Reads a parameter declared in seconds.
     *
     * @param properties the component properties
     * @param key the parameter name
     * @param fallback the value used when the parameter is absent or unusable
     * @param allowZero whether a configured zero is a value in its own right ("no debounce", "never stale by age")
     *            rather than a request for the fallback
     * @return the duration
     */
    private static Duration duration(Map<String, Object> properties, String key, Duration fallback, boolean allowZero) {
        Double seconds = ConfigParser.valueAs(properties.get(key), Double.class);
        if (seconds == null || !Double.isFinite(seconds) || seconds < 0 || (seconds == 0 && !allowZero)) {
            return fallback;
        }
        return Duration.ofMillis(Math.round(seconds * 1000));
    }

    /**
     * Parses {@code "1:5750,2:5750,3:5750"} into per-phase budgets in watts.
     * <p>
     * A phase budget is a map from a phase number to a power, and {@code config-description} has no parameter type
     * for a map, so this one parameter carries a grammar of its own. That is a reported gap, not a preference.
     *
     * @param value the configured value
     * @param rejected receives one description per malformed entry
     * @return the budgets by phase number, skipping - and naming - malformed entries
     */
    private static Map<Integer, Double> parsePhaseBudgets(String value, Consumer<String> rejected) {
        Map<Integer, Double> budgets = new TreeMap<>();
        if (value.isBlank()) {
            return budgets;
        }
        for (String element : value.split(",")) {
            if (element.isBlank()) {
                continue;
            }
            String[] parts = element.split(":");
            if (parts.length != 2) {
                reject(rejected, CONFIG_PHASE_BUDGETS, element, "expected 'phase:watts'");
                continue;
            }
            try {
                budgets.put(Integer.parseInt(parts[0].trim()), Double.parseDouble(parts[1].trim()));
            } catch (NumberFormatException e) {
                reject(rejected, CONFIG_PHASE_BUDGETS, element, "expected 'phase:watts' with two numbers");
            }
        }
        return budgets;
    }

    /**
     * Reports a rejected entry of a multi-part parameter.
     * <p>
     * The rest of the parameter is still applied: one mistyped phase budget must not take the whole safety floor
     * down with it. Saying so out loud is the difference between a forgiving parser and a silent one.
     *
     * @param rejected the caller's collector
     * @param key the parameter the entry belongs to
     * @param entry the rejected entry
     * @param reason what was expected instead
     */
    private static void reject(Consumer<String> rejected, String key, String entry, String reason) {
        rejected.accept("ignoring '" + entry.trim() + "' in the '" + key + "' configuration: " + reason);
    }
}
