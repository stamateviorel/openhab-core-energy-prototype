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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.energy.ElectricalLimits;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.energy.EnergyProvider;
import org.openhab.core.energy.ParticipantState;
import org.openhab.core.energy.ProtectionHistory;
import org.openhab.core.energy.ProviderRole;
import org.openhab.core.energy.SignConvention;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes the one snapshot a cycle reasons about.
 * <p>
 * Everything the engine will look at this cycle is read here, once, and frozen into an immutable
 * {@link EnergyContext}. That is the whole mechanism behind "both are judged against the same surplus figure, not
 * values read at different moments" - no algorithm ever reaches back to an Item.
 * <p>
 * <strong>This class is also the one place that decides what counts as a safety input</strong>, which is the last
 * member of the engine's closed list of prohibitions. A contributed algorithm cannot promote a reading of its own
 * into that class, because nothing outside this file contributes to {@code measurementsStale}.
 * <p>
 * Derived figures and the assumptions behind them:
 * <ul>
 * <li><strong>Surplus</strong> is {@code max(0, grid + reclaimable)} under the site's one sign convention, positive
 * = export ({@link org.openhab.core.energy.SignConvention}): the signed grid reading and the battery charge the
 * engine could reclaim, netted against each other before the clamp, so a site importing while it charges reports
 * what stopping the battery would actually free rather than the charge itself. Owner decision D27, amending D10 as
 * literally worded; the arithmetic is stated once in {@link EnergyContext#surplusWatts()} and repeated here only
 * because the level has to be resolved before the snapshot is sealed.</li>
 * <li><strong>Site load</strong> is {@code pv - battery - grid}, stated once in {@code SignConvention} so that two
 * calculations cannot read one battery reading two ways. A battery charging at 2 kW is absorbing, not consuming, and
 * is therefore subtracted.</li>
 * <li><strong>Uncontrolled load</strong> is the site load minus the measured draw of the steered participants; it
 * is what the electrical-limit floor measures its headroom against. Consumers that declare no measurement simply
 * do not contribute, which makes the figure conservative.</li>
 * <li><strong>Per-phase uncontrolled load</strong> stays empty: the provider model can now name a reading Item per
 * phase, but nothing reads them here yet, so per-phase headroom is still enforced only against what the engine
 * itself dispatches. That is a reported gap.</li>
 * <li><strong>Stale</strong> is the trigger for the safe state, and a declared reading trips it two ways:
 * <em>always</em> when it cannot be read at all - a missing Item, {@code UNDEF}, {@code NULL}, a state that is not a
 * number - and <em>additionally</em> when a staleness age is configured and the reading has not been updated within
 * it. A site with no age configured is therefore still protected against a bridge that stops answering; an age is
 * what protects it against one that answers with a value frozen an hour ago. A site can also turn a frozen Item into
 * an unreadable one with core's own {@code expire} namespace.</li>
 * <li><strong>Time in state</strong> comes from the steered Item's own last state change and from nowhere else: the
 * engine keeps no protection timers. Where that history is unreadable this class stamps the participant's
 * <em>first observation</em> - once, here, for every enforcement point to share - and the participant is reported as
 * protection-unknown by {@link DeviceProtectionAlgorithm}. That stamp is not a timer: it measures nothing about the
 * device, it is dropped the moment real history appears, and losing it costs only conservatism. It lives here rather
 * than in the algorithm because the floor and the safe state ask the same question and have to get the same
 * answer. <strong>Which</strong> of the two conditions produced the unreadable history - nothing is keeping it, or
 * it is kept and holds no change yet - is read from the site's persistence configuration in the same step and
 * frozen alongside it, because the absent timestamp cannot tell them apart and their remedies are opposite (owner
 * decision D28).</li>
 * </ul>
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class EnergyContextFactory {

    private final Logger logger = LoggerFactory.getLogger(EnergyContextFactory.class);

    private final ItemStateReader reader;
    private final Clock clock;

    /**
     * When each participant whose state history is unreadable was first observed. It is retained to the participants
     * of the current cycle, so a device that is withdrawn does not keep a stamp nothing will ever read again.
     */
    private final Map<String, Instant> firstObserved = new ConcurrentHashMap<>();

    /**
     * Creates the factory.
     *
     * @param reader the reader giving access to Item states
     * @param clock the clock stamping the snapshot, injectable so cycles are reproducible in tests
     */
    public EnergyContextFactory(ItemStateReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    /**
     * Reads everything one cycle needs and freezes it, against a level that is already known.
     *
     * @param participants the participants of this cycle
     * @param level the site energy level in force
     * @param request the parameters that do not come from Items
     * @return the immutable snapshot
     */
    public EnergyContext createSnapshot(Collection<EnergyParticipant> participants, EnergyLevel level,
            SnapshotRequest request) {
        return createSnapshot(participants, (moment, surplus) -> level, request);
    }

    /**
     * Reads everything one cycle needs and freezes it, resolving the site level from the very readings it took.
     * <p>
     * The resolver is consulted once, after the grid reading of this cycle is known and before the snapshot is
     * sealed, and it is handed <em>this</em> snapshot's own instant along with <em>this</em> snapshot's own surplus.
     * That is what makes the level a product of the cycle: a level that escalates on surplus escalates on the
     * reading the rest of the snapshot was built from, and a level that comes out of a planned schedule is looked up
     * at the moment the snapshot was taken rather than at whatever the level plane's own clock happens to say.
     *
     * @param participants the participants of this cycle
     * @param level computes the site energy level from this cycle's moment and surplus
     * @param request the parameters that do not come from Items
     * @return the immutable snapshot
     */
    public EnergyContext createSnapshot(Collection<EnergyParticipant> participants, LevelResolver level,
            SnapshotRequest request) {
        Instant now = clock.instant();
        List<ParticipantState> states = new ArrayList<>();

        double pvWatts = 0;
        double batteryWatts = 0;
        double gridWatts = 0;
        boolean gridKnown = false;
        boolean pvKnown = false;
        boolean batteryKnown = false;
        boolean gridDeclared = false;
        boolean stale = false;
        double steeredWatts = 0;

        for (EnergyParticipant participant : participants) {
            Set<Integer> phases = phasesOf(participant);
            boolean pending = request.pendingParticipants().contains(participant.id());
            switch (participant) {
                case EnergyProvider provider -> {
                    OptionalDouble reading = normalise(readWatts(provider.itemName()), provider.invert());
                    stale |= isUnreadable(provider.itemName(), reading)
                            || isStale(provider.itemName(), now, maxAgeOf(provider, request));
                    if (provider.role() == ProviderRole.GRID) {
                        gridDeclared = true;
                    }
                    if (reading.isPresent()) {
                        switch (provider.role()) {
                            case GRID -> {
                                gridWatts += reading.getAsDouble();
                                gridKnown = true;
                            }
                            case PV -> {
                                pvWatts += reading.getAsDouble();
                                pvKnown = true;
                            }
                            case BATTERY -> {
                                batteryWatts += reading.getAsDouble();
                                batteryKnown = true;
                            }
                        }
                    }
                    states.add(providerState(provider, reading, phases, pending));
                }
                case EnergyConsumer consumer -> {
                    String measureItem = consumer.measureItemName();
                    OptionalDouble measured = measureItem == null ? OptionalDouble.empty() : readWatts(measureItem);
                    if (measureItem != null) {
                        stale |= isUnreadable(measureItem, measured)
                                || isStale(measureItem, now, maxAgeOf(consumer, request));
                    }
                    if (measured.isPresent()) {
                        steeredWatts += measured.getAsDouble();
                    }
                    states.add(consumerState(consumer, measured, phases, pending, now));
                }
            }
        }

        firstObserved.keySet().retainAll(states.stream().map(ParticipantState::id).toList());

        // The escalation thresholds apply to max(0, grid + reclaimable) under the one central sign convention
        // (grid + = export, battery + = charging), so an importing site nets the import off the charge it could
        // reclaim before anything is compared to a threshold (D27). This is the same site-wide figure
        // EnergyContext.surplusWatts() reports; it has to be computed here as well because the level is resolved
        // while the snapshot is still being assembled.
        double reclaimableCharge = batteryKnown ? Math.max(0, batteryWatts) : 0;
        OptionalDouble surplus = gridKnown ? OptionalDouble.of(Math.max(0, gridWatts + reclaimableCharge))
                : OptionalDouble.empty();
        EnergyContext.Builder builder = EnergyContext.builder(now, level.resolve(now, surplus))
                .limits(request.limits());
        states.forEach(builder::participant);

        if (gridKnown) {
            builder.gridWatts(gridWatts);
        } else if (gridDeclared) {
            stale = true;
            logger.debug("No usable grid reading in this cycle; the engine degrades to a safe state");
        }
        if (pvKnown) {
            builder.pvWatts(pvWatts);
        }
        if (batteryKnown) {
            builder.batteryWatts(batteryWatts);
        }
        if (gridKnown) {
            double siteLoad = SignConvention.siteLoadWatts(pvWatts, batteryWatts, gridWatts);
            builder.uncontrolledWatts(Math.max(0, siteLoad - steeredWatts));
        }
        return builder.measurementsStale(stale).build();
    }

    /**
     * Computes the site energy level from the moment one cycle was taken at and the surplus it measured.
     * <p>
     * The engine <strong>computes</strong> the level of a cycle; it never reads one back. Both arguments come from
     * this snapshot, so the level and the readings it was derived from cannot come from two different moments - not
     * from two reads of the same Item, and not from two clocks. See
     * {@link org.openhab.core.energy.level.CurrentLevelFunction}, which is the level plane's side of this seam, for
     * what publishing the level as an Item then means: an output of the computation, never its input.
     *
     * @author Stamate Viorel - Initial contribution
     */
    @FunctionalInterface
    public interface LevelResolver {

        /**
         * Returns the level in force for this cycle.
         *
         * @param moment the instant this cycle's snapshot was taken at
         * @param surplusWatts the surplus this cycle measured, or empty when there is no usable grid reading
         * @return the level, never {@code null}
         */
        EnergyLevel resolve(Instant moment, OptionalDouble surplusWatts);
    }

    private ParticipantState providerState(EnergyProvider provider, OptionalDouble reading, Set<Integer> phases,
            boolean pending) {
        ParticipantState state = ParticipantState.of(provider).withPhases(phases).withCommandPending(pending);
        if (reading.isPresent()) {
            state = state.withMeasuredWatts(reading.getAsDouble());
        }
        String controlItem = provider.controlItemName();
        String reported = controlItem == null ? null : readText(controlItem);
        return reported == null ? state : state.withReportedState(reported);
    }

    /**
     * Builds the state of one consumer, including the clock its protections are measured from.
     *
     * @param consumer the declaration
     * @param measured its measured draw, if it declares one
     * @param phases the phases it declares
     * @param pending whether a command of an earlier cycle is still outstanding
     * @param now the moment this snapshot was taken at
     * @return the frozen state
     */
    private ParticipantState consumerState(EnergyConsumer consumer, OptionalDouble measured, Set<Integer> phases,
            boolean pending, Instant now) {
        ParticipantState state = ParticipantState.of(consumer).withPhases(phases).withCommandPending(pending)
                .withReady(isReady(consumer));
        if (measured.isPresent()) {
            state = state.withMeasuredWatts(measured.getAsDouble());
        }
        String reported = readText(consumer.itemName());
        if (reported != null) {
            state = state.withReportedState(reported);
        }
        Instant lastChange = reader.lastChange(consumer.itemName());
        if (lastChange != null) {
            firstObserved.remove(consumer.id());
            return state.withLastChangedAt(lastChange);
        }
        Instant first = firstObserved.putIfAbsent(consumer.id(), now);
        return state.withFirstObservedAt(first == null ? now : first)
                .withProtectionHistory(conditionOf(consumer.itemName()));
    }

    /**
     * Says which of the two conditions produced an unreadable state history, by asking the site's persistence
     * configuration rather than by inferring from the absent timestamp - which cannot tell them apart.
     * <p>
     * Source: owner decision D28 ({@code openhab-ems-spec/docs/OWNER_DECISIONS.md}). The question is asked here,
     * once per cycle per participant, and frozen into the snapshot with everything else, so the algorithm, the
     * electrical floor and the report all read the same answer.
     *
     * @param itemName the steered Item whose history could not be read
     * @return the condition to report
     */
    private ProtectionHistory conditionOf(String itemName) {
        return switch (reader.retentionOf(itemName)) {
            case KEPT -> ProtectionHistory.NO_CHANGE_YET;
            case NOT_KEPT -> ProtectionHistory.NOT_KEPT;
            case UNKNOWN -> ProtectionHistory.UNDETERMINED;
        };
    }

    /**
     * Evaluates the readiness interlock. A consumer that declares none is always ready; one whose interlock Item
     * cannot be read is treated as not ready, because an interlock that cannot be checked is not an interlock.
     *
     * @param consumer the consumer
     * @return whether an engine-initiated start is permitted
     */
    private boolean isReady(EnergyConsumer consumer) {
        String readyItem = consumer.readyItemName();
        if (readyItem == null) {
            return true;
        }
        State state = reader.readState(readyItem);
        if (state == null) {
            logger.debug("Readiness Item '{}' of consumer '{}' has no usable state; treating it as not ready",
                    readyItem, consumer.id());
            return false;
        }
        return OnOffType.ON.equals(state);
    }

    private OptionalDouble readWatts(String itemName) {
        State state = reader.readState(itemName);
        if (state == null) {
            return OptionalDouble.empty();
        }
        if (state instanceof QuantityType<?> quantity) {
            return EngineUnits.watts(quantity);
        }
        if (state instanceof DecimalType decimal) {
            return OptionalDouble.of(decimal.doubleValue());
        }
        return EngineUnits.parse(state.toFullString(), Units.WATT);
    }

    private @Nullable String readText(String itemName) {
        State state = reader.readState(itemName);
        return state == null ? null : state.toFullString();
    }

    /**
     * Normalises a raw device reading onto the site's one sign convention.
     *
     * @param raw the reading as the device reports it
     * @param invert whether the device counts the opposite way round
     * @return the reading in the site convention
     */
    private static OptionalDouble normalise(OptionalDouble raw, boolean invert) {
        return raw.isEmpty() ? raw : OptionalDouble.of(SignConvention.normalise(raw.getAsDouble(), invert));
    }

    /**
     * Tells whether a declared reading could not be read at all, which always trips the safe state - whether or not
     * the site declared a staleness age.
     * <p>
     * A missing Item, an {@code UNDEF} or {@code NULL} state and a state that is not a number are the same condition
     * from the engine's point of view: it declared a safety input and cannot see it. This is what makes core's
     * {@code expire} namespace a usable staleness mechanism, because {@code expire} turns a frozen Item into an
     * {@code UNDEF} one.
     *
     * @param itemName the Item name, for the log line
     * @param reading what came back
     * @return {@code true} if the reading is unusable
     */
    private boolean isUnreadable(String itemName, OptionalDouble reading) {
        if (reading.isPresent()) {
            return false;
        }
        logger.debug("Declared reading '{}' has no usable state; the engine degrades to a safe state", itemName);
        return true;
    }

    /**
     * Returns the phases this participant draws on, as its own declaration states them.
     * <p>
     * Phases are a property of the device, so they are declared with the device rather than configured on the
     * engine. A consumer names the indices directly; a provider names a reading Item per phase, and the phases it
     * touches are the ones it named. A participant declaring none is exempt from per-phase enforcement and still
     * constrained by the site total - it is never attributed to all three phases nor to a guessed one.
     *
     * @param participant the participant
     * @return the phase indices, empty when it declares none
     */
    private static Set<Integer> phasesOf(EnergyParticipant participant) {
        return switch (participant) {
            case EnergyConsumer consumer -> consumer.phases();
            case EnergyProvider provider -> provider.phaseItemNames().keySet();
        };
    }

    /**
     * Returns the age at which this participant's reading counts as stale: its own declared maximum where it states
     * one, the engine-wide setting where it does not.
     * <p>
     * A participant that declares an age overrides the engine for itself alone, which is the point of declaring it -
     * a grid clamp updating twice a second and a battery reporting once a minute cannot share one number. Neither is
     * required: an unreadable state and the {@code UNDEF} and {@code NULL} states count as stale through
     * {@link #isUnreadable} whether or not any age is declared anywhere.
     *
     * @param participant the participant whose reading is being judged
     * @param request the parameters of this snapshot
     * @return the age, or {@code null} when neither the participant nor the engine judges a reading by its age
     */
    private static @Nullable Duration maxAgeOf(EnergyParticipant participant, SnapshotRequest request) {
        Duration declared = participant.maxReadingAge();
        return declared != null ? declared : request.staleAfter();
    }

    private boolean isStale(String itemName, Instant now, @Nullable Duration staleAfter) {
        if (staleAfter == null) {
            return false;
        }
        Instant lastUpdate = reader.lastUpdate(itemName);
        if (lastUpdate == null) {
            return false;
        }
        boolean stale = lastUpdate.plus(staleAfter).isBefore(now);
        if (stale) {
            logger.debug("Item '{}' has not been updated since {}, which is older than the configured {}", itemName,
                    lastUpdate, staleAfter);
        }
        return stale;
    }

    /**
     * The parameters of a snapshot that do not come from Items.
     *
     * @param limits the declared electrical limits
     * @param pendingParticipants the participants with an unacknowledged command
     * @param staleAfter the age at which a declared measurement counts as stale, or {@code null} to never judge a
     *            measurement by its age
     *
     * @author Stamate Viorel - Initial contribution
     */
    public record SnapshotRequest(ElectricalLimits limits, Set<String> pendingParticipants,
            @Nullable Duration staleAfter) {
    }
}
