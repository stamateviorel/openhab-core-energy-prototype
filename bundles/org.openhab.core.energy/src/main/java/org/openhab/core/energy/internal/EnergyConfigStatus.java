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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.status.ConfigStatusCallback;
import org.openhab.core.config.core.status.ConfigStatusMessage;
import org.openhab.core.config.core.status.ConfigStatusProvider;
import org.openhab.core.config.core.status.ConfigStatusSource;
import org.osgi.service.component.annotations.Component;

/**
 * The machine-readable half of the engine's report: what a site declared that the engine could not use, and what it
 * accepted with a gap in it.
 * <p>
 * <strong>Why this exists as a {@code ConfigStatusProvider} and not as a log line.</strong> A malformed declaration
 * skips the <em>whole</em> participant, never half of it, and the requirement is that this is reported rather than
 * inferred from a warning somebody has to be tailing the log to see. A pull-based status provider is the surface core
 * already has for exactly that, it is what a settings page renders, and it needs neither an event nor an Item write -
 * so it is the one publication surface the no-write invariant leaves open to this bundle. What it cannot do is push:
 * {@link #setConfigStatusCallback} is honoured, so a UI following the topic is told when the set changes, but nothing
 * here writes anything anywhere.
 * <p>
 * Five conditions reach it, from three components:
 * <ul>
 * <li>a declaration that could not be parsed, from the metadata participant source - an <em>error</em>, because the
 * participant is not being managed at all;</li>
 * <li>a declaration that parsed with something redundant in it - a <em>warning</em>, since the device is managed;</li>
 * <li>a Simple consumer with no declared rating, whose on-threshold is booked instead;</li>
 * <li>a protected participant whose state history is not being kept, so its protections run from first observation
 * and will start over on every restart - a <em>warning</em>, because the site can close it;</li>
 * <li>a participant naming an actuation sink that is not installed, which is therefore steered by nothing.</li>
 * </ul>
 * The last three are re-derived from each cycle's own snapshot and replace the previous cycle's set wholesale, so a
 * gap that is closed disappears without anything having to remember to clear it.
 * <p>
 * <strong>A sixth condition is deliberately not a gap.</strong> A protected participant whose history <em>is</em>
 * kept and simply holds no state change yet is the ordinary state of the first minutes after a restart: it is
 * reported through {@link #participantNotes(Map)} as an <em>information</em> message, so that it is visible without
 * being presented as something to fix. Reporting the two as one condition - which is all the engine could do before
 * it was allowed to read the persistence configuration - names a problem the user cannot act on, because one of the
 * two causes is not a problem. Source: owner decision D28.
 * <p>
 * The {@code parameterName} of each message is the item or participant the message is about rather than an engine
 * parameter, because that is what a reader has to go and edit. The engine's own parameters are all validated where
 * they are read, in {@link EnergyEngineConfiguration}.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(immediate = true, service = { ConfigStatusProvider.class, EnergyConfigStatus.class })
public class EnergyConfigStatus implements ConfigStatusProvider {

    /**
     * The message key suffix of a declaration the parser refused.
     */
    public static final String KEY_MALFORMED_DECLARATION = "malformed-declaration";

    /**
     * The message key suffix of a declaration that was accepted with something redundant in it.
     */
    public static final String KEY_DECLARATION_WARNING = "declaration-warning";

    /**
     * The message key suffix of a participant the engine manages with a gap in its declaration.
     */
    public static final String KEY_PARTICIPANT_GAP = "participant-gap";

    /**
     * The message key suffix of a condition the engine reports about a participant that needs nothing done.
     */
    public static final String KEY_PARTICIPANT_NOTE = "participant-note";

    /**
     * The message key suffix of something the objective plane could not do as the site asked.
     */
    public static final String KEY_OBJECTIVE_CONDITION = "objective-condition";

    /**
     * The message key suffix of something the objective plane is reporting that needs nothing done.
     */
    public static final String KEY_OBJECTIVE_NOTE = "objective-note";

    /**
     * The message key suffix of something the forecast plane has to report about a role.
     */
    public static final String KEY_FORECAST_CONDITION = "forecast-condition";

    /**
     * The message key suffix of something the plan coordinator has to report about its last derivation.
     */
    public static final String KEY_PLAN_CONDITION = "plan-condition";

    private static final String ERRORS = "1:";
    private static final String DECLARATION_WARNINGS = "2:";
    private static final String PARTICIPANT_GAPS = "3:";
    private static final String PARTICIPANT_NOTES = "4:";
    private static final String OBJECTIVE_CONDITIONS = "5:";
    private static final String OBJECTIVE_NOTES = "6:";
    private static final String FORECAST_CONDITIONS = "7:";
    private static final String PLAN_CONDITIONS = "8:";

    private final Map<String, ConfigStatusMessage> messages = new ConcurrentSkipListMap<>();

    private volatile @Nullable ConfigStatusCallback callback;

    @Override
    public Collection<ConfigStatusMessage> getConfigStatus() {
        return List.copyOf(messages.values());
    }

    @Override
    public boolean supportsEntity(String entityId) {
        return EnergyEngine.CONFIGURATION_PID.equals(entityId);
    }

    @Override
    public void setConfigStatusCallback(@Nullable ConfigStatusCallback configStatusCallback) {
        callback = configStatusCallback;
    }

    /**
     * Records that a declaration was refused whole, replacing anything previously reported about that item.
     *
     * @param itemName the item carrying the declaration
     * @param reason why it could not be used
     */
    public void declarationRejected(String itemName, String reason) {
        boolean changed = forget(itemName);
        changed |= messages.put(ERRORS + itemName, ConfigStatusMessage.Builder.error(itemName)
                .withMessageKeySuffix(KEY_MALFORMED_DECLARATION).withArguments(itemName, reason).build()) == null;
        announce(changed);
    }

    /**
     * Records that a declaration was accepted, with whatever was redundant in it.
     *
     * @param itemName the item carrying the declaration
     * @param warnings what the parser reported, possibly empty
     */
    public void declarationAccepted(String itemName, List<String> warnings) {
        boolean changed = forget(itemName);
        for (String warning : warnings) {
            changed |= messages.put(DECLARATION_WARNINGS + itemName + ":" + warning,
                    ConfigStatusMessage.Builder.warning(itemName).withMessageKeySuffix(KEY_DECLARATION_WARNING)
                            .withArguments(itemName, warning).build()) == null;
        }
        announce(changed);
    }

    /**
     * Forgets everything reported about one item, which is what deleting its declaration means.
     *
     * @param itemName the item that no longer declares anything
     */
    public void declarationWithdrawn(String itemName) {
        announce(forget(itemName));
    }

    /**
     * Replaces the set of gaps in the declarations the engine is currently working from.
     *
     * @param gaps participant id to the gap, as the cycle that just ran saw it
     */
    public void participantGaps(Map<String, String> gaps) {
        boolean changed = messages.keySet().removeIf(key -> key.startsWith(PARTICIPANT_GAPS));
        for (Map.Entry<String, String> gap : gaps.entrySet()) {
            messages.put(PARTICIPANT_GAPS + gap.getKey(), ConfigStatusMessage.Builder.warning(gap.getKey())
                    .withMessageKeySuffix(KEY_PARTICIPANT_GAP).withArguments(gap.getKey(), gap.getValue()).build());
            changed = true;
        }
        announce(changed);
    }

    /**
     * Replaces the set of conditions the engine is reporting that need nothing done.
     * <p>
     * These are {@code INFORMATION} rather than {@code WARNING} on purpose: a protected participant whose Item is
     * persisted and has simply not changed yet is not a fault, and presenting it as one would make the ordinary
     * minutes after a restart look like a misconfiguration. Source: owner decision D28.
     *
     * @param notes participant id to the condition, as the cycle that just ran saw it
     */
    public void participantNotes(Map<String, String> notes) {
        boolean changed = messages.keySet().removeIf(key -> key.startsWith(PARTICIPANT_NOTES));
        for (Map.Entry<String, String> note : notes.entrySet()) {
            messages.put(PARTICIPANT_NOTES + note.getKey(), ConfigStatusMessage.Builder.information(note.getKey())
                    .withMessageKeySuffix(KEY_PARTICIPANT_NOTE).withArguments(note.getKey(), note.getValue()).build());
            changed = true;
        }
        announce(changed);
    }

    /**
     * Replaces the set of conditions the objective plane is reporting that a site should act on: an objective that is
     * selected but not installed, one whose data plane is absent, a selection that was degraded or refused, a
     * contributed objective that was refused an id.
     * <p>
     * Wholesale replacement for the same reason the participant gaps use it: a condition that goes away has to
     * disappear without anything having to remember to clear it, and every one of these is re-derived from the
     * resolution that just ran.
     *
     * @param conditions condition name to its description, as the resolution that just ran saw it
     */
    public void objectiveConditions(Map<String, String> conditions) {
        boolean changed = messages.keySet().removeIf(key -> key.startsWith(OBJECTIVE_CONDITIONS));
        for (Map.Entry<String, String> condition : conditions.entrySet()) {
            messages.put(OBJECTIVE_CONDITIONS + condition.getKey(),
                    ConfigStatusMessage.Builder.warning(condition.getKey())
                            .withMessageKeySuffix(KEY_OBJECTIVE_CONDITION)
                            .withArguments(condition.getKey(), condition.getValue()).build());
            changed = true;
        }
        announce(changed);
    }

    /**
     * Replaces the set of conditions the objective plane is reporting that need nothing done: a site optimizing for
     * cost because it selected nothing, or a rule that is in force but inert because the data it turns on is not
     * forecast anywhere.
     * <p>
     * {@code INFORMATION} rather than {@code WARNING} for the same reason the participant notes are: neither is a
     * fault, and presenting "you are optimizing for money" as something to fix would be wrong.
     *
     * @param notes condition name to its description, as the resolution that just ran saw it
     */
    public void objectiveNotes(Map<String, String> notes) {
        boolean changed = messages.keySet().removeIf(key -> key.startsWith(OBJECTIVE_NOTES));
        for (Map.Entry<String, String> note : notes.entrySet()) {
            messages.put(OBJECTIVE_NOTES + note.getKey(), ConfigStatusMessage.Builder.information(note.getKey())
                    .withMessageKeySuffix(KEY_OBJECTIVE_NOTE).withArguments(note.getKey(), note.getValue()).build());
            changed = true;
        }
        announce(changed);
    }

    /**
     * Replaces the set of conditions the forecast plane is reporting: a role nothing is registered for, a source that
     * went dark and left the site planning on a baseline, a run older than the age the site declared, a preferred
     * source that is not installed, a series published in a unit its role does not carry.
     * <p>
     * This is where `extension-surface` <em>Graceful degradation on contributor loss</em> becomes visible to a user:
     * the site keeps planning on whatever remains, and the settings page says what it settled for. Wholesale
     * replacement, for the same reason the participant gaps use it - a condition that goes away disappears without
     * anything having to remember to clear it.
     *
     * @param conditions role id to the condition, as the resolution that just ran saw it
     */
    public void forecastConditions(Map<String, String> conditions) {
        boolean changed = messages.keySet().removeIf(key -> key.startsWith(FORECAST_CONDITIONS));
        for (Map.Entry<String, String> condition : conditions.entrySet()) {
            messages.put(FORECAST_CONDITIONS + condition.getKey(),
                    ConfigStatusMessage.Builder.warning(condition.getKey()).withMessageKeySuffix(KEY_FORECAST_CONDITION)
                            .withArguments(condition.getKey(), condition.getValue()).build());
            changed = true;
        }
        announce(changed);
    }

    /**
     * Replaces what the plan coordinator has to say about its most recent derivation.
     * <p>
     * Wholesale replacement for the same reason the forecast conditions use it: the set is the state of the last run
     * rather than a log of runs, so a condition that has stopped applying has to disappear without anything having to
     * remember to clear it.
     *
     * @param conditions condition name to its description, as the derivation that just ran saw it
     */
    public void planConditions(Map<String, String> conditions) {
        boolean changed = messages.keySet().removeIf(key -> key.startsWith(PLAN_CONDITIONS));
        for (Map.Entry<String, String> condition : conditions.entrySet()) {
            messages.put(PLAN_CONDITIONS + condition.getKey(),
                    ConfigStatusMessage.Builder.warning(condition.getKey()).withMessageKeySuffix(KEY_PLAN_CONDITION)
                            .withArguments(condition.getKey(), condition.getValue()).build());
            changed = true;
        }
        announce(changed);
    }

    private boolean forget(String itemName) {
        return messages.keySet()
                .removeIf(key -> key.equals(ERRORS + itemName) || key.startsWith(DECLARATION_WARNINGS + itemName + ":")
                        || key.equals(PARTICIPANT_GAPS + itemName) || key.equals(PARTICIPANT_NOTES + itemName));
    }

    private void announce(boolean changed) {
        ConfigStatusCallback listener = callback;
        if (changed && listener != null) {
            listener.configUpdated(new EnergyConfigStatusSource());
        }
    }

    /**
     * Names the engine as the entity whose configuration status changed.
     *
     * @author Stamate Viorel - Initial contribution
     */
    private static final class EnergyConfigStatusSource extends ConfigStatusSource {

        private EnergyConfigStatusSource() {
            super(EnergyEngine.CONFIGURATION_PID);
        }

        @Override
        public String getTopic() {
            return "openhab/energy/" + entityId + "/config/status";
        }
    }
}
