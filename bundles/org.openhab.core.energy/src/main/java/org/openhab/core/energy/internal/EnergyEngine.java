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
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.config.core.ConfigurableService;
import org.openhab.core.energy.ActuationSink;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.EnergyAlgorithm;
import org.openhab.core.energy.EnergyAlgorithmRegistry;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyContext;
import org.openhab.core.energy.EnergyLevel;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.energy.ParticipantState;
import org.openhab.core.energy.level.CurrentLevelFunction;
import org.openhab.core.energy.spi.ParticipantSnapshotSource;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.persistence.registry.PersistenceServiceConfigurationRegistry;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one central engine: on every cycle it takes a single snapshot, evaluates every algorithm against it, resolves
 * the conflicts, enforces the electrical-limit floor, and only then lets anything reach the actuation sink - past
 * the master stop, the shadow gate and the acknowledgement window.
 * <p>
 * The order of those steps is the contract, and it is the same for every algorithm, built-in or contributed:
 *
 * <pre>
 * master stop -&gt; snapshot -&gt; algorithms -&gt; conflict resolution -&gt; electrical limits -&gt; safe state
 *             -&gt; prohibitions -&gt; shadow -&gt; acknowledgement -&gt; sink
 * </pre>
 *
 * Nothing an algorithm returns can skip a step, which is what makes "scripts first-class" safe to offer.
 * <p>
 * <strong>The master stop is first, and that is a decision, not an implementation detail.</strong> While it is
 * engaged the engine takes no snapshot, invokes no contributed algorithm and enforces no device protection: it
 * returns before any of that. A stop that only suppressed writes would have stopped the engine's writes rather than
 * the site's, and would have relied on every add-on to no-op - which is exactly what a kill switch may not assume.
 * The price is stated rather than hidden: while stopped the engine is a black box, it cannot say what it would have
 * done, and the protections it normally enforces are not enforced. It <em>reports</em> that non-enforcement, once
 * per engagement, so the trade is visible on the site rather than only here. The one place a {@code stopped} outcome
 * still appears is a cycle already in flight when the stop lands, whose remaining decisions are not dispatched.
 * <p>
 * The engine creates no threads: it takes openHAB's shared scheduler and drives itself with
 * {@code scheduleWithFixedDelay}, so a slow cycle delays the next one instead of overlapping with it. Cycles are
 * additionally serialized on a lock, because {@link #triggerEvaluation()} - the seam for the event-responsive
 * option of {@code design.md} §1 - can ask for one between ticks. A cycle that arrives from the scheduler while
 * another is still running is <em>skipped</em> rather than queued: a cycle calls out to contributed algorithms and
 * to the actuation sink, so waiting would let a slow one pile up blocked threads on a pool the whole runtime
 * shares. {@link #runCycleNow()} keeps waiting, because "run one now" is an explicit request with a return value.
 * <p>
 * <strong>Shadow-only.</strong> The engine defaults to shadow mode on a fresh install, and in this prototype the
 * only sink logs. Leaving shadow mode changes which line is logged, not whether an Item is written.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
@Component(immediate = true, service = EnergyAlgorithmRegistry.class, configurationPid = EnergyEngine.CONFIGURATION_PID, configurationPolicy = ConfigurationPolicy.OPTIONAL, property = Constants.SERVICE_PID
        + "=org.openhab.core.energy")
@ConfigurableService(category = "system", label = "Energy Management", description_uri = EnergyEngine.CONFIG_URI)
public class EnergyEngine implements EnergyAlgorithmRegistry {

    /**
     * The configuration PID under which the engine reads its parameters.
     */
    public static final String CONFIGURATION_PID = "org.openhab.core.energy";

    /**
     * The URI of the configuration description that renders the engine's parameters in the UI.
     */
    public static final String CONFIG_URI = "system:energy";

    /**
     * The name of the shared scheduler pool the engine drives itself with.
     */
    public static final String THREAD_POOL_NAME = "energy";

    private final Logger logger = LoggerFactory.getLogger(EnergyEngine.class);

    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final EnergyContextFactory contextFactory;
    private final ReentrantLock cycleLock = new ReentrantLock();
    private final List<EnergyAlgorithm> contributedAlgorithms = new CopyOnWriteArrayList<>();
    private final Map<String, EnergyAlgorithm> registeredAlgorithms = new ConcurrentHashMap<>();
    private final ActuationSink loggingSink = new LoggingActuationSink();
    private final Map<String, ActuationSink> actuationSinks = new ConcurrentHashMap<>();
    private final Object configurationLock = new Object();
    private final RepeatedLineFilter decisionLines = new RepeatedLineFilter();
    private final CycleEventReporter events = new CycleEventReporter();

    private final EvaluationPass evaluationPass = new EvaluationPass();
    private final SafeStatePass safeState = new SafeStatePass();
    private final EngineEnforcedParticipantGuard participantGuard = new EngineEnforcedParticipantGuard();
    private final AtomicLong rejectedDecisions = new AtomicLong();

    private volatile EnergyEngineConfiguration configuration = EnergyEngineConfiguration.defaults();
    private volatile ElectricalLimitFloor limitFloor = new ElectricalLimitFloor(
            new PowerEstimator(EnergyEngineConfiguration.DEFAULT_NOMINAL_VOLTAGE));
    private final ActuationGate gate = new ActuationGate(true, false, Set.of(), Set.of());
    private volatile AcknowledgementTracker acknowledgements = new EngineAcknowledgementTracker(
            EnergyEngineConfiguration.DEFAULT_ACK_WINDOW, false);
    private volatile Instant lastCycleAt = Instant.EPOCH;
    private volatile @Nullable CycleOutcome lastCycle;
    private volatile boolean nonEnforcementReported;

    /**
     * When a triggered cycle was last <em>submitted</em>. The debounce has to be stamped here rather than only on
     * {@link #lastCycleAt}, which is written inside the cycle: two triggers arriving before the submitted cycle
     * starts would both see a stale {@code lastCycleAt}, both pass the debounce, and both queue a cycle.
     */
    private final AtomicReference<Instant> lastTriggerAt = new AtomicReference<>(Instant.EPOCH);

    private volatile @Nullable Boolean shadowOverride;
    private volatile @Nullable Boolean stoppedOverride;
    private volatile boolean configuredShadow;
    private volatile boolean configuredStopped;

    private volatile @Nullable EnergyConfigStatus configStatus;
    private volatile @Nullable ParticipantSnapshotSource participantSnapshotSource;
    private volatile @Nullable CurrentLevelFunction currentLevelFunction;
    private volatile @Nullable ScheduledFuture<?> cycleHandle;

    /**
     * Creates the engine as OSGi activates it.
     * <p>
     * Both registries are handed straight to the reader and are never touched again from here. The persistence one is
     * read for a single question - whether a steered Item's history is being kept - which is what lets an absent last
     * state change be reported as a misconfiguration or as the ordinary just-restarted case rather than as one
     * undifferentiated "unknown" (owner decision D28, {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}). It is a
     * mandatory reference on purpose: a report that is precise on some installations and vague on others would be
     * worse than either, and the component supplying it is unconditional in the persistence bundle.
     *
     * @param itemRegistry the registry the snapshot builder reads Item states from - read-only, always
     * @param persistenceConfigurations what each persistence service is configured to keep - read-only, always
     * @param properties the component configuration
     */
    @Activate
    public EnergyEngine(@Reference ItemRegistry itemRegistry,
            @Reference PersistenceServiceConfigurationRegistry persistenceConfigurations,
            Map<String, Object> properties) {
        this(ThreadPoolManager.getScheduledPool(THREAD_POOL_NAME), Clock.systemUTC(),
                new RegistryItemStateReader(itemRegistry, persistenceConfigurations), properties);
    }

    /**
     * Creates the engine with everything injected, which is how a test drives it: a scheduler that never fires, a
     * fixed clock and a map-backed Item reader make a cycle a pure function call.
     *
     * @param scheduler the scheduler the engine drives itself with; it creates no threads of its own
     * @param clock the clock stamping each snapshot
     * @param reader the reader giving access to Item states
     * @param properties the configuration
     */
    public EnergyEngine(ScheduledExecutorService scheduler, Clock clock, ItemStateReader reader,
            Map<String, Object> properties) {
        this.scheduler = scheduler;
        this.clock = clock;
        contextFactory = new EnergyContextFactory(reader, clock);
        applyConfiguration(read(properties));
    }

    /**
     * Starts the cycle once the component is fully constructed.
     * <p>
     * Scheduling deliberately does not happen in the constructor: that would publish a partially constructed engine
     * to a shared scheduler pool before OSGi has bound a single reference.
     */
    @Activate
    protected void activate() {
        schedule();
        logger.info("Energy management started in {} mode, evaluating every {}",
                configuration.shadow() ? "shadow" : "live", configuration.cycleInterval());
    }

    /**
     * Re-reads the configuration and reschedules.
     *
     * @param properties the new component configuration
     */
    @Modified
    public void modified(Map<String, Object> properties) {
        applyConfiguration(read(properties));
        schedule();
    }

    /**
     * Reads the configuration and reports whatever it had to reject, with this component's own logger.
     *
     * @param properties the component properties
     * @return the configuration
     */
    private EnergyEngineConfiguration read(Map<String, Object> properties) {
        return EnergyEngineConfiguration.fromProperties(properties, rejected -> logger.warn("{}", rejected));
    }

    /**
     * Stops the cycle when OSGi deactivates the component.
     */
    @Deactivate
    public void deactivate() {
        cancel();
        logger.debug("Energy engine deactivated");
    }

    /**
     * Runs one cycle now, on the calling thread, under every guardrail.
     *
     * @return what the cycle decided and what became of each decision
     */
    public CycleOutcome runCycleNow() {
        cycleLock.lock();
        try {
            EnergyEngineConfiguration config = configuration;
            AcknowledgementTracker tracker = acknowledgements;
            ActuationGate activeGate = gate;

            if (activeGate.isStopped()) {
                return haltedCycle();
            }
            nonEnforcementReported = false;

            EnergyContext snapshot = contextFactory.createSnapshot(participants(), this::currentLevel,
                    new EnergyContextFactory.SnapshotRequest(config.limits(), tracker.pendingParticipants(),
                            config.staleAfter()));
            tracker.observe(snapshot);
            EnergyContext context = refreshPending(snapshot, tracker.pendingParticipants());
            lastCycleAt = context.timestamp();
            Map<String, String> gaps = declarationGaps(context);
            reportDeclarationGaps(gaps, declarationNotes(context));

            List<DecisionOutcome> outcomes = new ArrayList<>();
            EvaluationPass.Result evaluated = evaluationPass.run(context, algorithms());
            outcomes.addAll(evaluated.discarded());
            ElectricalLimitFloor.Result floored = limitFloor.apply(context, evaluated.winners());
            outcomes.addAll(floored.rejected());

            for (ElectricalLimitFloor.Admission admission : safeState.apply(context, floored.admitted())) {
                outcomes.add(dispatch(admission, context, activeGate, tracker));
            }
            CycleOutcome outcome = new CycleOutcome(context, outcomes, activeGate.isShadow(), activeGate.isStopped());
            count(outcome);
            decisionLines.retainOnly(context.participants().keySet());
            events.retainParticipants(context.participants().keySet());
            logCycle(outcome);
            events.report(outcome, gaps);
            lastCycle = outcome;
            return outcome;
        } finally {
            cycleLock.unlock();
        }
    }

    /**
     * Returns the cycle a stopped engine runs, which is no cycle at all.
     * <p>
     * Nothing is read, nothing is evaluated and no contributed algorithm is invoked, so there is nothing to report
     * about the site - only about the engine. The one thing that <em>is</em> reported is what the stop costs: the
     * device protections the engine would otherwise enforce are not being enforced. That line is written once per
     * engagement rather than once per cycle, because at the default cadence the latter is fourteen hundred identical
     * warnings a day.
     *
     * @return an outcome carrying an empty snapshot and no decisions
     */
    private CycleOutcome haltedCycle() {
        if (!nonEnforcementReported) {
            nonEnforcementReported = true;
            logger.warn("The energy engine master stop is engaged: no evaluation runs, no contributed algorithm is "
                    + "invoked, and the device protections the engine would enforce are not enforced while it lasts");
        } else {
            logger.trace("Skipping an energy cycle: the master stop is engaged");
        }
        CycleOutcome halted = new CycleOutcome(EnergyContext.builder(clock.instant(), EnergyLevel.NORMAL).build(),
                List.of(), gate.isShadow(), true);
        events.report(halted, Map.of());
        lastCycle = halted;
        return halted;
    }

    /**
     * Counts the decisions a cycle rejected, so that an algorithm addressing a device nobody declared is a number an
     * operator can see rather than a debug line nobody reads.
     *
     * @param outcome the finished cycle
     */
    private void count(CycleOutcome outcome) {
        long rejected = outcome.outcomes().stream().filter(each -> each.status() == DecisionStatus.REJECTED).count();
        if (rejected > 0) {
            rejectedDecisions.addAndGet(rejected);
        }
    }

    /**
     * Returns what the last completed cycle decided.
     * <p>
     * This is the readable-current-cycle half of the observability requirement. The three publication surfaces the
     * requirement also asks for were blocked for <em>three different</em> reasons, which the owner then answered
     * separately as <strong>D23</strong> (2026-08-03, {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}):
     * <ul>
     * <li><strong>events</strong> ship here. Posting an event is not an Item write and the invariant never forbade
     * it; what forbade it was this bundle's own structural test, whose forbidden-token list was deliberately wider
     * than the rule it stands for. That list has been widened on purpose, with the argument written beside it in
     * {@code ShadowModeDemonstrationTest}. See {@link CycleEventReporter};</li>
     * <li>an engine <strong>status Item</strong> is a genuine conflict with the invariant - writing an Item is
     * exactly what this bundle may not do - and now lives in the separate {@code org.openhab.core.energy.publish}
     * bundle, which subscribes to those events;</li>
     * <li>a <strong>REST</strong> view needs JAX-RS, outside the default-library set, so it moved out with the
     * status Item and is deliberately not built yet.</li>
     * </ul>
     * What ships alongside this accessor is therefore the outcome vocabulary and a reason on every decision, both
     * published as deduplicated events, and {@link EnergyConfigStatus}, which carries the machine-readable half of
     * the participant-condition report through {@code ConfigStatusProvider} - a pull, and therefore neither an event
     * nor a write.
     *
     * @return the last cycle, or {@code null} before the first one has run
     */
    public @Nullable CycleOutcome lastCycle() {
        return lastCycle;
    }

    /**
     * Returns how many decisions this engine has rejected since it started - decisions naming a participant no cycle
     * knew about, and decisions an actuation sink refused.
     *
     * @return the running count
     */
    public long rejectedDecisionCount() {
        return rejectedDecisions.get();
    }

    /**
     * Asks for a cycle outside the fixed tick - the seam an event-responsive re-evaluation would use.
     * <p>
     * {@code design.md} §1 leaves open whether the engine should react to Item changes at all, so this prototype
     * subscribes to nothing: it only offers the entry point, honours it when {@code eventResponsive} is configured,
     * and debounces it. Whether and what to subscribe to stays a maintainer decision.
     *
     * @return {@code true} if a cycle was submitted, {@code false} if the trigger was ignored or debounced
     */
    public boolean triggerEvaluation() {
        EnergyEngineConfiguration config = configuration;
        if (gate.isStopped()) {
            logger.trace("Refusing an evaluation trigger: the master stop is engaged");
            return false;
        }
        if (!config.eventResponsive()) {
            logger.trace("Ignoring an evaluation trigger: the engine is configured for a fixed cadence only");
            return false;
        }
        Instant now = clock.instant();
        Instant submitted = lastTriggerAt.get();
        Instant reference = submitted.isAfter(lastCycleAt) ? submitted : lastCycleAt;
        if (now.isBefore(reference.plus(config.triggerDebounce()))) {
            logger.trace("Debouncing an evaluation trigger: the last cycle was less than {} ago",
                    config.triggerDebounce());
            return false;
        }
        if (!lastTriggerAt.compareAndSet(submitted, now)) {
            logger.trace("Debouncing an evaluation trigger: another one was submitted at the same moment");
            return false;
        }
        scheduler.execute(this::runCycleSafely);
        return true;
    }

    /**
     * Tells whether decisions are currently only logged.
     *
     * @return {@code true} if the engine is in shadow mode
     */
    public boolean isShadow() {
        return gate.isShadow();
    }

    /**
     * Turns global shadow mode on or off.
     *
     * @param shadow {@code true} to only log decisions
     */
    public void setShadow(boolean shadow) {
        synchronized (configurationLock) {
            shadowOverride = shadow;
            if (gate.isShadow() == shadow) {
                return;
            }
            gate.setShadow(shadow);
            configuration = configuration.withShadow(shadow);
        }
        logger.info("Energy engine shadow mode {}", shadow ? "engaged" : "released");
    }

    /**
     * Tells whether the master stop is engaged.
     *
     * @return {@code true} if all actuation is halted
     */
    public boolean isStopped() {
        return gate.isStopped();
    }

    /**
     * Engages or releases the master stop. Engaging it halts all actuation from the next decision on and forgets
     * outstanding commands; releasing it resumes normal operation without touching any participant declaration.
     *
     * @param stopped {@code true} to halt all actuation
     */
    public void setStopped(boolean stopped) {
        synchronized (configurationLock) {
            stoppedOverride = stopped;
            if (gate.isStopped() == stopped) {
                return;
            }
            gate.setStopped(stopped);
            configuration = configuration.withStopped(stopped);
            if (stopped) {
                acknowledgements.reset();
            }
        }
        logger.info("Energy engine master stop {}", stopped ? "engaged" : "released");
    }

    /**
     * Registers an algorithm that carries its own identity - the path an add-on's OSGi service and a script class
     * both take.
     *
     * @param algorithm the algorithm
     */
    @Override
    public void registerAlgorithm(EnergyAlgorithm algorithm) {
        registerAlgorithm(algorithm.getId(), algorithm.getPriority(), algorithm);
    }

    /**
     * Registers an algorithm under an explicit identity - the path a script lambda takes, since a lambda has no
     * meaningful class name to derive an id from.
     *
     * @param id the id to register the algorithm under
     * @param priority the priority its decisions carry, lower is stronger
     * @param algorithm the algorithm
     */
    @Override
    public void registerAlgorithm(String id, int priority, EnergyAlgorithm algorithm) {
        registeredAlgorithms.put(id, new NamedAlgorithm(id, priority, algorithm));
        logger.debug("Registered energy algorithm '{}' with priority {}", id, priority);
    }

    /**
     * Removes a previously registered algorithm.
     *
     * @param id the id it was registered under
     * @return {@code true} if an algorithm was removed
     */
    @Override
    public boolean unregisterAlgorithm(String id) {
        boolean removed = registeredAlgorithms.remove(id) != null;
        if (removed) {
            logger.debug("Unregistered energy algorithm '{}'", id);
        }
        return removed;
    }

    /**
     * Returns the algorithms of the next cycle, in the order they will be evaluated in.
     *
     * @return the algorithms, contributed and registered alike
     */
    @Override
    public List<EnergyAlgorithm> algorithms() {
        List<EnergyAlgorithm> all = new ArrayList<>(contributedAlgorithms);
        all.addAll(registeredAlgorithms.values());
        return EvaluationPass.ordered(all);
    }

    /**
     * Returns the configuration in force.
     *
     * @return the configuration
     */
    public EnergyEngineConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Binds an algorithm contributed as an OSGi service by an add-on.
     *
     * @param algorithm the contributed algorithm
     */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    protected void addAlgorithm(EnergyAlgorithm algorithm) {
        contributedAlgorithms.add(algorithm);
        logger.debug("Energy algorithm '{}' contributed", algorithm.getId());
    }

    /**
     * Unbinds a contributed algorithm. The cycle simply carries on with the remaining ones.
     *
     * @param algorithm the algorithm that went away
     */
    protected void removeAlgorithm(EnergyAlgorithm algorithm) {
        contributedAlgorithms.remove(algorithm);
        logger.debug("Energy algorithm '{}' withdrawn", algorithm.getId());
    }

    /**
     * Binds the configuration status surface the engine reports declaration gaps to.
     *
     * @param status the status surface
     */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    protected void setConfigStatus(EnergyConfigStatus status) {
        configStatus = status;
    }

    /**
     * Unbinds it; the engine then reports its gaps to the log alone.
     *
     * @param status the status surface that went away
     */
    protected void unsetConfigStatus(EnergyConfigStatus status) {
        if (Objects.equals(configStatus, status)) {
            configStatus = null;
        }
    }

    /**
     * Binds the event bus the engine reports its decisions on.
     * <p>
     * <strong>This is a reporting dependency and nothing else.</strong> The engine posts exactly two event types,
     * both of them its own, through {@link CycleEventReporter}; it constructs no Item event and could not, since the
     * class that builds one lives in a package a structural test keeps out of this bundle. Posting an event is not
     * an Item write, and the no-write invariant is unchanged by this reference existing - see D23 (owner decision,
     * 2026-08-03, {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}), which put the events here and the status Item
     * in the separate {@code org.openhab.core.energy.publish} bundle.
     * <p>
     * The reference is optional, so an engine on a runtime without an event bus - or in a unit test - simply reports
     * nothing outward and carries on deciding.
     *
     * @param eventPublisher the event bus
     */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    protected void setEventPublisher(EventPublisher eventPublisher) {
        events.setPublisher(eventPublisher);
    }

    /**
     * Unbinds the event bus; the engine then decides in silence, as it did before D23.
     *
     * @param eventPublisher the publisher that went away
     */
    protected void unsetEventPublisher(EventPublisher eventPublisher) {
        events.setPublisher(null);
    }

    /**
     * Binds the source of participants.
     *
     * @param source the source
     */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    protected void setParticipantSnapshotSource(ParticipantSnapshotSource source) {
        participantSnapshotSource = source;
    }

    /**
     * Unbinds the source of participants; the engine then evaluates an empty site.
     *
     * @param source the source that went away
     */
    protected void unsetParticipantSnapshotSource(ParticipantSnapshotSource source) {
        if (Objects.equals(participantSnapshotSource, source)) {
            participantSnapshotSource = null;
        }
    }

    /**
     * Binds the level plane - the function this engine <em>calls</em> to compute the level of a cycle.
     * <p>
     * The engine is on the calling side of this dependency and never on the reading side. Publishing the level it
     * computed, as an Item or as a planned series, is an output of the cycle and no part of this binding: the level
     * leaves here on the cycle event, and the {@code org.openhab.core.energy.publish} bundle turns it into an Item
     * (D23, owner decision, 2026-08-03, {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}).
     *
     * @param function the level function
     */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    protected void setCurrentLevelFunction(CurrentLevelFunction function) {
        currentLevelFunction = function;
    }

    /**
     * Unbinds the level plane; the engine then computes {@link EnergyLevel#NORMAL} for every cycle.
     *
     * @param function the function that went away
     */
    protected void unsetCurrentLevelFunction(CurrentLevelFunction function) {
        if (Objects.equals(currentLevelFunction, function)) {
            currentLevelFunction = null;
        }
    }

    /**
     * Binds a contributed actuation sink. Binding one does not put it in charge: the engine dispatches to the sink
     * named by the {@code actuationSink} configuration parameter, and to its own logging sink until an operator
     * names another one. Nothing may become the site's writer by winning a binding race.
     *
     * @param sink the contributed sink
     */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    protected void addActuationSink(ActuationSink sink) {
        actuationSinks.put(sink.getId(), sink);
        String selected = configuration.actuationSinkId();
        if (selected.isBlank() && !LoggingActuationSink.ID.equals(sink.getId())) {
            logger.debug(
                    "Actuation sink '{}' is available but not selected; set the 'actuationSink' parameter to use it",
                    sink.getId());
        }
        rebuildDerivedComponents();
    }

    /**
     * Unbinds a contributed actuation sink; the engine falls back to logging.
     *
     * @param sink the sink that went away
     */
    protected void removeActuationSink(ActuationSink sink) {
        actuationSinks.remove(sink.getId(), sink);
        rebuildDerivedComponents();
    }

    private DecisionOutcome dispatch(ElectricalLimitFloor.Admission admission, EnergyContext context,
            ActuationGate activeGate, AcknowledgementTracker tracker) {
        Decision decision = admission.decision();
        Optional<String> veto = participantGuard.veto(decision, context);
        if (veto.isPresent()) {
            logger.debug("Withholding {}: {}", decision.describe(), veto.get());
            return new DecisionOutcome(decision, admission.original(), DecisionStatus.WITHHELD, veto.get());
        }
        if (!activeGate.allowsWrite(decision)) {
            if (activeGate.isStopped()) {
                if (isWorthReporting(decision, DecisionStatus.STOPPED)) {
                    logger.info("Master stop engaged: would have applied {}", decision.describe());
                } else {
                    logger.debug("Master stop engaged: would have applied {}", decision.describe());
                }
                return new DecisionOutcome(decision, admission.original(), DecisionStatus.STOPPED,
                        "the master stop was engaged while this cycle was in flight");
            }
            if (isWorthReporting(decision, DecisionStatus.SHADOWED)) {
                logger.info("Shadow mode: would have applied {}", decision.describe());
            } else {
                logger.debug("Shadow mode: would have applied {}", decision.describe());
            }
            return new DecisionOutcome(decision, admission.original(), DecisionStatus.SHADOWED, admission.detail());
        }
        if (tracker.isSuppressed(decision, context)) {
            logger.debug("Not re-sending {}: the previous command has not been acknowledged yet", decision.describe());
            return new DecisionOutcome(decision, admission.original(), DecisionStatus.SUPPRESSED,
                    "the previous command has not been acknowledged yet");
        }
        try {
            selectedSink(context.participant(decision.participantId()).map(ParticipantState::participant).orElse(null))
                    .dispatch(decision, context);
        } catch (RuntimeException e) {
            logger.warn("Actuation of {} failed: {}", decision.describe(), e.getMessage());
            logger.debug("The actuation sink threw", e);
            return new DecisionOutcome(decision, admission.original(), DecisionStatus.REJECTED,
                    "actuation failed: " + e.getMessage());
        }
        tracker.recordDispatch(decision, context.timestamp());
        return new DecisionOutcome(decision, admission.original(), DecisionStatus.APPLIED, admission.detail());
    }

    /**
     * Tells whether a decision that was reached but not applied is worth an {@code info} line.
     * <p>
     * It is, the first time and on every change; it is not for as long as the engine keeps reaching the same
     * decision about the same participant, in which case the caller drops to {@code debug}. Without that, a device
     * the engine has decided about but cannot act on - the ordinary state of affairs in shadow mode, which is the
     * default - would produce one identical line per cycle for as long as it lasted.
     *
     * @param decision the decision
     * @param status what became of it
     * @return {@code true} if this decision has not just been reported in the same terms
     */
    private boolean isWorthReporting(Decision decision, DecisionStatus status) {
        return decisionLines.isNew(decision.participantId(), status + " " + decision.describe());
    }

    private Collection<EnergyParticipant> participants() {
        ParticipantSnapshotSource source = participantSnapshotSource;
        if (source == null) {
            return List.of();
        }
        try {
            return source.getParticipants();
        } catch (RuntimeException e) {
            logger.warn("The participant source failed; this cycle sees no participants: {}", e.getMessage());
            logger.debug("The participant source threw", e);
            return List.of();
        }
    }

    /**
     * Publishes the gaps this cycle's declarations carry to the pull surface, so they are machine-readable rather
     * than a log line.
     * <p>
     * The same set also rides on the cycle event, which is the push half of the same report: a participant condition
     * has one vocabulary and two surfaces, not two vocabularies. Where the summary of those conditions ends up
     * <em>as an Item</em> was settled by the owner as D23 - in the separate {@code org.openhab.core.energy.publish}
     * bundle, never here.
     *
     * @param gaps the gaps this cycle saw
     * @param notes the conditions this cycle saw that need nothing done
     */
    private void reportDeclarationGaps(Map<String, String> gaps, Map<String, String> notes) {
        EnergyConfigStatus status = configStatus;
        if (status != null) {
            status.participantGaps(gaps);
            status.participantNotes(notes);
        }
    }

    /**
     * Derives the gaps this cycle's declarations carry.
     * <p>
     * Three conditions, all re-derived from the snapshot every cycle and all of them accepted declarations rather
     * than refused ones: a Simple consumer with no {@code ratedPower}, whose on-threshold is booked as its rating; a
     * protected participant whose Item history is <em>not being kept</em>, so its protections run on the
     * first-observation clock and start over on every restart; and a participant naming an actuation sink nothing
     * installed, which is therefore steered by nothing at all. The first two are declaration gaps the requirements
     * name explicitly; the third is the cost of the sink being chosen by name rather than by ranking, and an
     * operator has no other way to see it.
     * <p>
     * A protected participant whose Item <em>is</em> kept and has simply not changed state yet is on the same
     * fallback clock and is <strong>not</strong> a gap: it is a note, see {@link #declarationNotes(EnergyContext)}.
     * Which of the two applies comes from {@link ParticipantState#protectionHistory()} - owner decision D28
     * ({@code openhab-ems-spec/docs/OWNER_DECISIONS.md}).
     *
     * @param context the snapshot of the cycle
     * @return the gaps, participant id to the gap in words, sorted so the report is stable
     */
    private Map<String, String> declarationGaps(EnergyContext context) {
        Map<String, String> gaps = new TreeMap<>();
        for (ParticipantState state : context.participants().values()) {
            List<String> found = new ArrayList<>();
            state.consumer().ifPresent(consumer -> {
                if (consumer.ratingIsInferred()) {
                    found.add("it declares no rated power, so its on-threshold is booked as its rating");
                }
                if (degradedProtection(state, consumer) && state.protectionHistory().isFixable()) {
                    found.add(protectionHistoryReport(state));
                }
            });
            String sink = state.participant().sinkId();
            if (sink != null && !sink.isBlank() && !actuationSinks.containsKey(sink)) {
                found.add("it names the actuation sink '" + sink + "', which is not installed, so nothing is "
                        + "dispatched for it");
            }
            if (!found.isEmpty()) {
                gaps.put(state.id(), String.join("; ", found));
            }
        }
        return gaps;
    }

    /**
     * Derives the conditions this cycle saw that a site is meant to know about but cannot and need not fix.
     * <p>
     * There is exactly one so far: a protected participant whose Item history <em>is</em> being kept and holds no
     * state change yet, which is the ordinary state of the first minutes after a restart. It rides the same surface
     * as a gap and carries a different severity, so a fresh restart does not present itself as a misconfiguration.
     * Source: owner decision D28, which is also what makes the distinction available at all.
     *
     * @param context the snapshot of the cycle
     * @return the notes, participant id to the condition in words, sorted so the report is stable
     */
    private Map<String, String> declarationNotes(EnergyContext context) {
        Map<String, String> notes = new TreeMap<>();
        for (ParticipantState state : context.participants().values()) {
            state.consumer().ifPresent(consumer -> {
                if (degradedProtection(state, consumer) && !state.protectionHistory().isFixable()) {
                    notes.put(state.id(), protectionHistoryReport(state));
                }
            });
        }
        return notes;
    }

    /**
     * Tells whether this participant's declared protections are running on the fallback clock.
     *
     * @param state the participant state
     * @param consumer its consumer declaration
     * @return {@code true} if it declares protections and its state history could not be read
     */
    private static boolean degradedProtection(ParticipantState state, EnergyConsumer consumer) {
        return state.protectionHistoryUnknown() && consumer.declaresProtections();
    }

    /**
     * Words the protection-clock condition of one participant, naming which of the conditions the engine found
     * rather than the one undifferentiated "unknown" it could report before D28.
     *
     * @param state the participant state
     * @return the sentence to report
     */
    private static String protectionHistoryReport(ParticipantState state) {
        return switch (state.protectionHistory()) {
            case NOT_KEPT -> "its state history is not being kept, so its protections are measured from the first "
                    + "cycle that observed it and start over on every restart; persist that Item with "
                    + "restoreOnStartup to close this";
            case NO_CHANGE_YET -> "its state history is kept and holds no state change yet, so its protections are "
                    + "measured from the first cycle that observed it until the device next changes state; there is "
                    + "nothing to fix";
            default -> "its state history cannot be read and the engine cannot see whether any persistence service "
                    + "keeps it, so its protections are measured from the first cycle that observed it";
        };
    }

    /**
     * Computes the level of this cycle, from this cycle's own moment and this cycle's own surplus.
     * <p>
     * This is the engine's whole relationship with the level plane: it calls a function and passes it the snapshot it
     * already has. It does not read a level Item, and it does not let the level plane look at a clock, so the level
     * and the readings acted on can never come from two different moments. Publishing what this returns is a
     * separate, outward step that no part of this bundle performs.
     *
     * @param moment the instant this cycle's snapshot was taken at
     * @param surplusWatts the surplus this cycle measured, or empty when there is no usable grid reading
     * @return the level in force
     */
    private EnergyLevel currentLevel(Instant moment, OptionalDouble surplusWatts) {
        CurrentLevelFunction function = currentLevelFunction;
        if (function == null) {
            return EnergyLevel.NORMAL;
        }
        try {
            return function.levelAt(moment, surplusWatts);
        } catch (RuntimeException e) {
            logger.warn("The level plane failed; this cycle assumes {}: {}", EnergyLevel.NORMAL, e.getMessage());
            logger.debug("The level plane threw", e);
            return EnergyLevel.NORMAL;
        }
    }

    /**
     * Rewrites the "a command is outstanding" flags after the acknowledgement tracker has reconciled them. Only
     * engine bookkeeping changes here - not a single Item is read again, so the cycle still sees one snapshot of
     * the world.
     *
     * @param context the snapshot as built
     * @param pending the participants with an outstanding command after reconciliation
     * @return the same snapshot, or a copy carrying the corrected flags
     */
    private EnergyContext refreshPending(EnergyContext context, Set<String> pending) {
        Map<String, ParticipantState> updated = new TreeMap<>();
        boolean changed = false;
        for (Map.Entry<String, ParticipantState> entry : context.participants().entrySet()) {
            ParticipantState state = entry.getValue();
            boolean isPending = pending.contains(entry.getKey());
            if (state.commandPending() != isPending) {
                state = state.withCommandPending(isPending);
                changed = true;
            }
            updated.put(entry.getKey(), state);
        }
        if (!changed) {
            return context;
        }
        return new EnergyContext(context.timestamp(), context.level(), context.gridPower(), context.pvPower(),
                context.batteryPower(), context.uncontrolledLoad(), context.uncontrolledPhaseLoad(), updated,
                context.limits(), context.measurementsStale());
    }

    /**
     * Installs a configuration, preserving anything an operator engaged at runtime.
     * <p>
     * Shadow mode and the master stop can be set two ways - in configuration and through
     * {@link #setShadow(boolean)} / {@link #setStopped(boolean)} - and a runtime setting must not evaporate the
     * next time an unrelated parameter is edited or a service binds. A runtime setting therefore survives until
     * either the operator changes it again, or the corresponding configuration value itself changes, which is an
     * explicit statement about that control and is allowed to win.
     *
     * @param config the configuration to install
     */
    private void applyConfiguration(EnergyEngineConfiguration config) {
        synchronized (configurationLock) {
            if (config.shadow() != configuredShadow) {
                shadowOverride = null;
            }
            if (config.stopped() != configuredStopped) {
                stoppedOverride = null;
            }
            configuredShadow = config.shadow();
            configuredStopped = config.stopped();
            Boolean shadow = shadowOverride;
            Boolean stopped = stoppedOverride;
            EnergyEngineConfiguration effective = config;
            if (shadow != null) {
                effective = effective.withShadow(shadow);
            }
            if (stopped != null) {
                effective = effective.withStopped(stopped);
            }
            configuration = effective;
            rebuildDerivedComponents(effective);
        }
        logConfiguration();
    }

    /**
     * Rebuilds everything derived from the configuration and the currently bound services, leaving the
     * configured-versus-runtime reconciliation alone.
     * <p>
     * A service binding is not a statement about shadow mode or the master stop, so it must not be routed through
     * {@link #applyConfiguration}. That method reads its argument as the <em>configured</em> value, and the only
     * configuration a binding has to hand is the <em>effective</em> one, with any runtime override already folded
     * in. Feeding the effective configuration back in therefore looks exactly like an operator changing the
     * configured value: it clears the override and lets the next unrelated edit restore the ConfigAdmin setting -
     * silently releasing a master stop an operator had engaged.
     */
    private void rebuildDerivedComponents() {
        synchronized (configurationLock) {
            rebuildDerivedComponents(configuration);
        }
        logConfiguration();
    }

    /**
     * Rebuilds the derived components from an already-resolved effective configuration. The caller must hold
     * {@link #configurationLock}.
     *
     * @param effective the effective configuration, runtime overrides already applied
     */
    private void rebuildDerivedComponents(EnergyEngineConfiguration effective) {
        limitFloor = new ElectricalLimitFloor(new PowerEstimator(effective.nominalVoltage()));
        gate.configure(effective.shadow(), effective.stopped(), effective.shadowedAlgorithms(),
                effective.shadowedParticipants());
        AcknowledgementTracker previous = acknowledgements;
        AcknowledgementTracker tracker = createTracker(effective);
        tracker.adopt(previous);
        acknowledgements = tracker;
    }

    private void logConfiguration() {
        logger.debug(
                "Energy engine configured: cycle every {}, shadow {}, master stop {}, acknowledgement handling '{}', "
                        + "actuation sink '{}'",
                configuration.cycleInterval(), configuration.shadow() ? "on" : "off",
                configuration.stopped() ? "engaged" : "released", acknowledgements.getId(),
                configuration.actuationSinkId().isBlank() ? LoggingActuationSink.ID : configuration.actuationSinkId());
    }

    /**
     * Returns the sink one decision is dispatched through: the one the participant names, otherwise the one an
     * operator named site-wide, otherwise the engine's own logging sink.
     * <p>
     * <strong>A sink is chosen by being named and never by service ranking</strong>, registration order or any other
     * race between installed components. That is the one deliberate exception to the precedence chain that resolves
     * every other statement about a participant, and it exists because getting a ranked selection wrong moves
     * hardware: a second adapter binding at boot must not silently take over the writes.
     * <p>
     * A named sink that is not installed is <em>not</em> quietly replaced by another one. The engine logs instead of
     * dispatching, which leaves the site unsteered rather than steered by a component nobody chose.
     *
     * @param participant the participant the decision is about, or {@code null} when this cycle no longer knows it
     * @return the selected sink, never {@code null}
     */
    private ActuationSink selectedSink(@Nullable EnergyParticipant participant) {
        String declared = participant == null ? null : participant.sinkId();
        if (declared != null && !declared.isBlank()) {
            ActuationSink sink = actuationSinks.get(declared);
            if (sink != null) {
                return sink;
            }
            logger.warn("Participant '{}' names actuation sink '{}', which is not available; logging instead of "
                    + "dispatching", participant.id(), declared);
            return loggingSink;
        }
        String selected = configuration.actuationSinkId();
        if (selected.isBlank()) {
            return loggingSink;
        }
        ActuationSink sink = actuationSinks.get(selected);
        if (sink != null) {
            return sink;
        }
        logger.warn("Configured actuation sink '{}' is not available; logging instead of dispatching", selected);
        return loggingSink;
    }

    private AcknowledgementTracker createTracker(EnergyEngineConfiguration config) {
        if (AdapterAcknowledgementTracker.ID.equals(config.ackHandlingId())) {
            return new AdapterAcknowledgementTracker();
        }
        if (!EngineAcknowledgementTracker.ID.equals(config.ackHandlingId())) {
            logger.warn("Unknown acknowledgement handling '{}'; falling back to '{}'", config.ackHandlingId(),
                    EngineAcknowledgementTracker.ID);
        }
        return new EngineAcknowledgementTracker(config.ackWindow(), config.ackSuppressChangedCommands());
    }

    /**
     * Runs a cycle on a pool thread, and only if no other cycle is running.
     * <p>
     * A cycle calls out to contributed algorithms and to the actuation sink, so its duration is not the engine's to
     * bound. Waiting for the lock here would let a slow cycle pile up an unbounded number of blocked threads on a
     * pool the whole runtime shares; skipping is both cheaper and more honest, because the next tick will evaluate a
     * fresher snapshot than the one this thread was queued for anyway.
     */
    private void runCycleSafely() {
        if (!cycleLock.tryLock()) {
            logger.debug("Skipping an energy cycle: the previous one is still running");
            return;
        }
        try {
            runCycleNow();
        } catch (RuntimeException e) {
            logger.warn("Energy cycle failed: {}", e.getMessage());
            logger.debug("The energy cycle threw", e);
        } finally {
            cycleLock.unlock();
        }
    }

    private void schedule() {
        synchronized (configurationLock) {
            cancel();
            Duration interval = configuration.cycleInterval();
            cycleHandle = scheduler.scheduleWithFixedDelay(this::runCycleSafely, interval.toMillis(),
                    interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void cancel() {
        synchronized (configurationLock) {
            ScheduledFuture<?> handle = cycleHandle;
            if (handle != null) {
                handle.cancel(false);
                cycleHandle = null;
            }
        }
    }

    private void logCycle(CycleOutcome outcome) {
        if (!logger.isDebugEnabled()) {
            return;
        }
        Map<DecisionStatus, Integer> counts = new TreeMap<>();
        for (DecisionOutcome decisionOutcome : outcome.outcomes()) {
            counts.merge(decisionOutcome.status(), 1, Integer::sum);
        }
        logger.debug("Energy cycle at {} ({} participants, level {}{}): {}", outcome.context().timestamp(),
                outcome.context().participants().size(), outcome.context().level(),
                outcome.context().measurementsStale() ? ", measurements stale" : "",
                counts.isEmpty() ? "nothing to decide" : counts);
        for (DecisionOutcome decisionOutcome : outcome.outcomes()) {
            logger.debug("  {}", decisionOutcome.describe());
        }
    }

    /**
     * An algorithm registered under an explicit identity, which is how a bare lambda from a script gets an id and a
     * priority without the script having to implement three methods.
     *
     * @param id the registered id
     * @param priority the priority its decisions carry
     * @param delegate the algorithm itself
     *
     * @author Stamate Viorel - Initial contribution
     */
    private record NamedAlgorithm(String id, int priority, EnergyAlgorithm delegate) implements EnergyAlgorithm {

        @Override
        public List<Decision> evaluate(EnergyContext context) {
            return delegate.evaluate(context);
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public int getPriority() {
            return priority;
        }
    }
}
