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
import static org.mockito.Mockito.mock;
import static org.openhab.core.energy.internal.EngineTestFixtures.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.core.energy.ControlAction;
import org.openhab.core.energy.ControllableProfile;
import org.openhab.core.energy.Decision;
import org.openhab.core.energy.DecisionKind;
import org.openhab.core.energy.EnergyConsumer;
import org.openhab.core.energy.EnergyParticipant;
import org.openhab.core.energy.EnergyProvider;
import org.openhab.core.energy.LevelGate;
import org.openhab.core.energy.ProviderRole;
import org.openhab.core.energy.SimpleProfile;
import org.openhab.core.energy.events.EnergyCycleEvent;
import org.openhab.core.energy.events.EnergyDecisionEvent;
import org.openhab.core.events.Event;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.test.java.JavaTest;

/**
 * <strong>THE SHADOW-MODE DEMONSTRATION</strong> required by {@code docs/PROTOTYPE_TRACK.md}'s definition of done:
 * "decisions computed and logged, zero writes".
 * <p>
 * A small synthetic site - grid, PV and a controllable battery as providers, a Simple boiler and a Controllable
 * wallbox as consumers - is declared through the <em>real</em> wiring rather than a stub: participants go into a
 * {@link ProgrammaticParticipantSource}, which an {@link EnergyParticipantRegistryImpl} aggregates, which a
 * {@link RegistryParticipantSnapshotSource} feeds to the engine. Several cycles are then run across a day whose
 * surplus rises and falls.
 * <p>
 * What is asserted is deliberately of three different kinds, because "it wrote nothing" deserves more than one
 * witness:
 * <ol>
 * <li><strong>Behavioural</strong> - decisions were computed, every one of them came back {@code SHADOWED}, and the
 * actuation sink was never handed anything.</li>
 * <li><strong>Observable</strong> - the "would have applied" lines really are in the log, captured from the engine's
 * own SLF4J logger, so an operator running this on a live site has the side-by-side comparison the requirement
 * promises.</li>
 * <li><strong>Structural</strong> - the bundle's main sources contain no call that could write to an Item at all.
 * This is the grep a reviewer would run, executed as a test, and it holds no matter what shadow mode is set to.</li>
 * <li><strong>Published</strong> - since D23 the engine reports its decisions on the event bus, so the fourth
 * witness is what a live cycle actually posts: this bundle's own two event types and nothing else, with the sink
 * still empty while they went out.</li>
 * </ol>
 * <p>
 * <strong>FOLLOW-UP - D23</strong> (owner decision, 2026-08-03,
 * {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}) removed exactly one token from the structural test's
 * forbidden list and added four, and {@link #theBundleContainsNoCodePathThatWritesToAnItem} carries the argument for
 * it. The invariant itself - no command, no state update, no {@code ItemRegistry} mutation - is unchanged, and is
 * now pinned by five tests rather than two, the last of which reads the emitted class files rather than the source.
 *
 * @author Stamate Viorel - Initial contribution
 */
@NonNullByDefault
public class ShadowModeDemonstrationTest extends JavaTest {

    private final MutableClock clock = new MutableClock(T0);
    private final MapItemStateReader reader = new MapItemStateReader();
    private final RecordingSink sink = new RecordingSink();
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    // the synthetic site
    private final EnergyProvider grid = EnergyProvider.of("grid", "Grid_Power", ProviderRole.GRID);
    private final EnergyProvider pv = EnergyProvider.of("pv", "PV_Power", ProviderRole.PV);
    private final EnergyProvider battery = EnergyProvider.controllable("battery", "Battery_Power", ProviderRole.BATTERY,
            "Battery_Setpoint", -5000, 5000, "Battery_SoC");
    private final EnergyConsumer boiler = EnergyConsumer
            .of("boiler", "Boiler_Switch", new SimpleProfile(new QuantityType<Power>(2000, Units.WATT), null, null,
                    null, null, null, LevelGate.always()), 2)
            .withMeasurement("Boiler_Power");
    private final EnergyConsumer wallbox = EnergyConsumer
            .of("wallbox", "Wallbox_Current", ControllableProfile.amperes(6, 32), 1).withMeasurement("Wallbox_Power");

    @BeforeEach
    public void captureTheEngineLog() {
        setupInterceptedLogger(EnergyEngine.class, LogLevel.INFO);
    }

    @AfterEach
    public void releaseTheEngineLog() {
        stopInterceptedLogger(EnergyEngine.class);
    }

    /**
     * Builds the site through the production wiring: contributor -> source -> registry -> snapshot source -> engine.
     *
     * @param configuration the engine configuration
     * @return the engine, with the synthetic site behind it
     */
    private EnergyEngine site(Map<String, Object> configuration) {
        ProgrammaticParticipantSource source = new ProgrammaticParticipantSource();
        for (EnergyParticipant participant : List.of(grid, pv, battery, boiler, wallbox)) {
            source.declare("demo", participant);
        }
        EnergyParticipantRegistryImpl registry = new EnergyParticipantRegistryImpl(Map.of());
        registry.addProvider(source);

        EnergyEngine engine = new EnergyEngine(scheduler, clock, reader, withRecordingSink(configuration));
        engine.setParticipantSnapshotSource(new RegistryParticipantSnapshotSource(registry));
        engine.addActuationSink(sink);
        engine.addAlgorithm(new DefaultSurplusAlgorithm());
        // a wallbox algorithm, so the demonstration also exercises a continuous action and the limit floor
        engine.registerAlgorithm("demo-wallbox", 1,
                context -> context.surplusWatts().isPresent() && context.surplusWatts().getAsDouble() > 1400
                        ? List.of(new Decision("wallbox", ControlAction.amperes(32), "demo-wallbox", 1,
                                DecisionKind.OPTIMIZATION, "surplus is worth charging on"))
                        : List.of());
        return engine;
    }

    /**
     * Moves the synthetic day on one step: a new reading for every Item the site declares, and a new hour.
     * <p>
     * Every declared reading is written, including the two consumer measurements. That is not decoration: a declared
     * reading the engine cannot read at all counts as a degraded safety input and freezes the site, so a demonstration
     * that left them unset would be demonstrating the safe state rather than shadow mode.
     *
     * @param gridWatts the grid reading, positive = export
     * @param pvWatts the PV reading
     */
    private void nextHour(double gridWatts, double pvWatts) {
        reader.putWatts("Grid_Power", gridWatts);
        reader.putWatts("PV_Power", pvWatts);
        reader.putWatts("Battery_Power", 0);
        reader.putWatts("Boiler_Power", 0);
        reader.putWatts("Wallbox_Power", 0);
        clock.advance(Duration.ofHours(1));
    }

    @Test
    public void aSyntheticSiteRunsSeveralCyclesAndWritesNothing() {
        EnergyEngine engine = site(Map.of());
        assertThat(engine.isShadow(), is(true));

        List<CycleOutcome> day = new ArrayList<>();
        // morning: nothing to spare -> afternoon: a real surplus -> evening: importing again
        for (double[] hour : new double[][] { { -200, 0 }, { 400, 900 }, { 3200, 5200 }, { 4800, 7000 }, { 1800, 4100 },
                { -900, 300 } }) {
            nextHour(hour[0], hour[1]);
            day.add(engine.runCycleNow());
        }

        // decisions were computed
        long decisions = day.stream().mapToLong(outcome -> outcome.outcomes().size()).sum();
        assertThat("the demonstration is worthless if the engine decided nothing", decisions, greaterThan(0L));

        // every one of them was shadowed, and nothing else
        List<DecisionOutcome> all = day.stream().flatMap(outcome -> outcome.outcomes().stream()).toList();
        assertThat(all.stream().map(DecisionOutcome::status).distinct().toList(), contains(DecisionStatus.SHADOWED));

        // nothing reached the sink - and the sink is a recorder, not a writer, so even this is a proof by margin
        assertThat(sink.dispatched(), is(empty()));
    }

    @Test
    public void theShadowDecisionsAppearInTheLog() {
        EnergyEngine engine = site(Map.of());
        nextHour(4800, 7000);

        CycleOutcome outcome = engine.runCycleNow();

        // every shadowed decision is in the log, verbatim - asserted per decision rather than by counting lines
        assertThat(outcome.shadowed(), hasSize(2));
        for (Decision shadowed : outcome.shadowed()) {
            assertLogMessage(EnergyEngine.class, LogLevel.INFO,
                    "Shadow mode: would have applied " + shadowed.describe());
        }
        assertThat(outcome.shadowed().stream().map(Decision::participantId).toList(),
                containsInAnyOrder("boiler", "wallbox"));
        // the log line carries the reasoning, which is what makes side-by-side validation possible
        Decision boilerDecision = outcome.shadowed().stream()
                .filter(decision -> "boiler".equals(decision.participantId())).findFirst().orElseThrow();
        assertThat(boilerDecision.describe(), containsString("surplus covers its threshold"));
        assertLogMessage(EnergyEngine.class, LogLevel.INFO,
                "Shadow mode: would have applied " + boilerDecision.describe());
    }

    /**
     * The other half of "observable": the trail has to be readable, which means the same unchanged decision must
     * not be repeated at {@code info} on every tick. At the default cadence that would be fourteen hundred
     * identical lines a day per device, in the mode a fresh installation starts in.
     */
    @Test
    public void theSameShadowDecisionIsNotRepeatedOnEveryCycle() {
        EnergyEngine engine = site(Map.of());
        nextHour(4800, 7000);

        CycleOutcome first = engine.runCycleNow();
        for (Decision shadowed : first.shadowed()) {
            assertLogMessage(EnergyEngine.class, LogLevel.INFO,
                    "Shadow mode: would have applied " + shadowed.describe());
        }

        // nothing about the site changes, so the next cycle reaches exactly the same decisions ...
        stopInterceptedLogger(EnergyEngine.class);
        setupInterceptedLogger(EnergyEngine.class, LogLevel.INFO);
        CycleOutcome second = engine.runCycleNow();
        assertThat(second.shadowed().stream().map(Decision::describe).toList(),
                is(first.shadowed().stream().map(Decision::describe).toList()));

        // ... and has nothing new to say about them
        assertNoLogMessage(EnergyEngine.class);
    }

    /**
     * The same site with the master stop engaged writes nothing - and, under the halt-everything reading, decides
     * nothing either. The engine does not evaluate, so there is no "would have applied" trail while it is stopped;
     * that is the cost of the stop, and it is the reason shadow mode is a separate control.
     */
    @Test
    public void theSameSiteStillWritesNothingWhenTheMasterStopIsEngaged() {
        EnergyEngine engine = site(Map.of("shadow", "false"));
        engine.setStopped(true);
        nextHour(4800, 7000);

        CycleOutcome outcome = engine.runCycleNow();

        assertThat(outcome.stopped(), is(true));
        assertThat(outcome.outcomes(), is(empty()));
        assertThat(outcome.applied(), is(empty()));
        assertThat(sink.dispatched(), is(empty()));
    }

    @Test
    public void leavingShadowModeChangesOnlyWhichLineIsLoggedNotWhetherAnItemIsWritten() {
        EnergyEngine engine = site(Map.of());
        nextHour(4800, 7000);
        engine.runCycleNow();
        assertThat(sink.dispatched(), is(empty()));

        engine.setShadow(false);
        reader.put("Boiler_Switch", OnOffType.OFF);
        CycleOutcome live = engine.runCycleNow();

        // decisions now reach the sink...
        assertThat(live.applied(), is(not(empty())));
        assertThat(sink.dispatched(), is(not(empty())));
        // ...but the only sink this bundle ships logs, which is the point of the next test
        assertThat(new LoggingActuationSink().getId(), is("log"));
    }

    @Test
    public void theSiteIsSteeredEntirelyFromDeclarationsAndReadings() {
        EnergyEngine engine = site(Map.of());
        nextHour(4800, 7000);

        CycleOutcome outcome = engine.runCycleNow();

        // the snapshot really was assembled from the declared providers of the registry
        assertThat(outcome.context().participants().keySet(),
                containsInAnyOrder("grid", "pv", "battery", "boiler", "wallbox"));
        assertThat(outcome.context().surplusWatts().getAsDouble(), is(4800.0));
        assertThat(outcome.context().providers(), hasSize(3));
        assertThat(outcome.context().consumers(), hasSize(2));
        // wallbox is priority 1, boiler priority 2: the consumer order is the declared one, not the map order
        assertThat(outcome.context().consumers().get(0).id(), is("wallbox"));
    }

    /**
     * Returns a source file with its comments removed, so that a structural scan judges the code rather than the
     * prose about the code - {@link LoggingActuationSink}'s own JavaDoc, for instance, names the very calls this
     * test forbids, in order to tell a reviewer to grep for them.
     *
     * @param file the source file
     * @return the file's code, comments blanked out
     * @throws IOException if the file cannot be read
     */
    private static String codeOf(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$",
                " ");
    }

    /**
     * Returns every main source file of this bundle.
     *
     * @return the source files
     * @throws IOException if the tree cannot be walked
     */
    private static List<Path> mainSources() throws IOException {
        Path main = Path.of("src", "main", "java");
        assertThat("the source tree must be where this test expects it", Files.isDirectory(main), is(true));
        try (Stream<Path> sources = Files.walk(main)) {
            return sources.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    /**
     * The structural half of the shadow-only rule: there is no code path in this bundle that could write to an Item,
     * whatever the configuration says. This is the reviewer's grep, run as a test.
     * <p>
     * <strong>One token was removed from this list on purpose, and this paragraph is the argument for it.</strong>
     * The token is {@code EventPublisher}. It was here because the list was written as a proxy - "name everything
     * that smells like output" - and a proxy that over-reaches is cheap right up until the moment the requirement it
     * is standing in front of needs one of the things it over-reached onto. That moment arrived: A8 asks for
     * decisions to be published as deduplicated events, and the owner answered the resulting three-way blockage as
     * D23 (2026-08-03, {@code openhab-ems-spec/docs/OWNER_DECISIONS.md}) - events ship in this bundle, the status
     * Item and the REST view move to {@code org.openhab.core.energy.publish}. So the list is widened here rather
     * than the requirement being weakened there.
     * <p>
     * <strong>Why posting an event is not an Item write.</strong> The invariant this test exists for is: this bundle
     * never commands an Item, never updates an Item's state, and never mutates the {@code ItemRegistry}. An
     * {@link org.openhab.core.events.Event} is a message on a bus that nothing is obliged to act on; the engine's
     * two event types carry a decision and a cycle summary, and a subscriber that wants an Item written has to write
     * it itself, in its own bundle, under its own rules. The distinction is core's own: core publishes events
     * everywhere and leaves persistence, presentation and actuation to whoever wants them.
     * <p>
     * <strong>What the list still guarantees, with {@code EventPublisher} gone.</strong> An Item write over the bus
     * needs an {@code ItemCommandEvent}, an {@code ItemStateEvent}, an {@code ItemStateUpdatedEvent} or an
     * {@code ItemTimeSeriesEvent}, and every one of those four has a <em>protected</em> constructor: the only way to
     * build one is {@code ItemEventFactory}, which is still forbidden here, as are the four event classes
     * themselves. (One class in {@code org.openhab.core.items.events} does have a public constructor -
     * {@code ItemStatePredictedEvent} - and it is deliberately not on this list, because it writes nothing: it is
     * autoupdate's prediction hint, it changes no registry and no Item state. Importing it would still fail
     * {@link #theOnlyItemAccessInTheBundleIsReading}, which pins the whole package prefix rather than a list of
     * class names.) Belt and braces, that test independently pins {@code org.openhab.core.items.} to three named
     * read-only files, none of which is allowed to touch a publisher. The direct routes - {@code sendCommand},
     * {@code postUpdate}, {@code GenericItem.setState} - were never on the bus and are all still listed. So a write
     * now needs a reviewer to defeat three independent tests and a protected constructor, where before it needed to
     * defeat two tests. The invariant did not move; one over-broad proxy for it did.
     * <p>
     * <strong>The four added tokens are not decoration.</strong> They close a route the old list closed only
     * incidentally, through {@code EventPublisher}: a hand-rolled {@code AbstractEvent} whose {@code getType()}
     * returns the literal {@code "ItemCommandEvent"} on topic {@code openhab/items/X/command} is reconstructed by
     * the receiving side's registered {@code ItemEventFactory} and acted on by {@code ItemUpdater} - a genuine Item
     * write with no {@code org.openhab.core.items} import anywhere in it. The string literal now trips this grep.
     * What no grep can catch is a deliberately concatenated literal; that was equally true of the old list, and
     * {@link #everyEventThisBundlePostsIsItsOwn} catches it at runtime for every path a cycle exercises.
     * <p>
     * The narrowness of the widening is itself asserted, by
     * {@link #theOnlyPlaceThatPostsAnEventIsTheReporter} (only two files may name a publisher at all), by
     * {@link #everyEventThisBundlePostsIsItsOwn} (what actually comes out of a running cycle) and by
     * {@link #theCompiledBundleCannotEvenResolveAnItemEventType} (what the compiler actually emitted).
     *
     * @throws IOException if the sources cannot be read
     */
    @Test
    public void theBundleContainsNoCodePathThatWritesToAnItem() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : mainSources()) {
            String code = codeOf(file);
            for (String forbidden : List.of("sendCommand", "postUpdate", "ItemEventFactory", "ItemCommandEvent",
                    "ItemStateEvent", "ItemStateUpdatedEvent", "ItemTimeSeriesEvent", "setState(", "send(")) {
                if (code.contains(forbidden)) {
                    offences.add(file.getFileName() + " calls " + forbidden);
                }
            }
        }

        assertThat(offences, is(empty()));
    }

    /**
     * The compensating pin for the one token the list above gave up: an event publisher may be named in exactly two
     * files, and only one of them may post anything.
     * <p>
     * Without this, "we removed {@code EventPublisher} from the grep" would be an invitation for the next component
     * in this bundle to acquire one quietly. With it, acquiring a second one is a failing test that a reviewer has
     * to read the argument above before deleting.
     *
     * @throws IOException if the sources cannot be read
     */
    @Test
    public void theOnlyPlaceThatPostsAnEventIsTheReporter() throws IOException {
        List<String> holdingAPublisher = new ArrayList<>();
        List<String> posting = new ArrayList<>();
        for (Path file : mainSources()) {
            String code = codeOf(file);
            if (code.contains("EventPublisher")) {
                holdingAPublisher.add(file.getFileName().toString());
            }
            if (code.contains(".post(")) {
                posting.add(file.getFileName().toString());
            }
        }

        // the engine, which receives one and hands it straight on, and the reporter, which is the only thing that
        // turns a finished cycle into events
        assertThat(holdingAPublisher, containsInAnyOrder("EnergyEngine.java", "CycleEventReporter.java"));
        assertThat(posting, contains("CycleEventReporter.java"));
    }

    /**
     * The behavioural half of the same compensation: run the real site through several cycles with a publisher
     * bound, and check what actually came out.
     * <p>
     * A grep proves the bundle cannot build an Item event. This proves it does not post one - every event a live
     * cycle emits is one of this bundle's own two types, and the actuation sink still received nothing while they
     * were emitted.
     */
    @Test
    public void everyEventThisBundlePostsIsItsOwn() {
        EnergyEngine engine = site(Map.of());
        RecordingEventPublisher published = new RecordingEventPublisher();
        engine.setEventPublisher(published);

        for (double[] hour : new double[][] { { -200, 0 }, { 3200, 5200 }, { 4800, 7000 }, { -900, 300 } }) {
            nextHour(hour[0], hour[1]);
            engine.runCycleNow();
        }

        assertThat("the proof is worthless if nothing was published", published.events(), is(not(empty())));
        for (Event event : published.events()) {
            assertThat(event, is(anyOf(instanceOf(EnergyDecisionEvent.class), instanceOf(EnergyCycleEvent.class))));
            assertThat(event.getType(), is(in(List.of(EnergyDecisionEvent.TYPE, EnergyCycleEvent.TYPE))));
            assertThat(event.getTopic(), startsWith("openhab/energy/"));
        }
        assertThat(sink.dispatched(), is(empty()));
    }

    /**
     * The strongest of the four witnesses, and the only one no source-level trick can get past: the classes the
     * compiler actually emitted contain no reference to {@code org.openhab.core.items.events} at all.
     * <p>
     * This is what OSGi ends up enforcing. bnd derives {@code Import-Package} from exactly these references, so a
     * package that appears in none of them is absent from the bundle manifest, and a class in it is therefore
     * <em>not resolvable in this bundle's classloader at runtime</em> - an Item write compiled in here would fail
     * with {@code NoClassDefFoundError} rather than merely fail a review. The assertion is made against
     * {@code target/classes} rather than the built jar because surefire runs before bnd packages one; the input to
     * the manifest is the same either way, and this version cannot be skipped by building differently.
     * <p>
     * It also survives refactors the token scan cannot see - a renamed class, a wildcard import, a fully-qualified
     * inline reference, an anonymous subclass - because it reads type references rather than text. The positive
     * control is deliberate: the same scan must <em>find</em> the read-only Item API the bundle does use, or it
     * would be passing by looking at nothing.
     *
     * @throws IOException if the compiled classes cannot be read
     */
    @Test
    public void theCompiledBundleCannotEvenResolveAnItemEventType() throws IOException {
        Path classes = Path.of("target", "classes");
        assertThat("the compiled classes must be where this test expects them", Files.isDirectory(classes), is(true));
        List<String> referencingItemEvents = new ArrayList<>();
        List<String> referencingItemRegistry = new ArrayList<>();
        try (Stream<Path> compiled = Files.walk(classes)) {
            for (Path file : compiled.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                // ISO-8859-1 maps bytes one-to-one onto chars, so an ASCII needle matches the constant pool verbatim
                String constantPool = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                if (constantPool.contains("org/openhab/core/items/events")) {
                    referencingItemEvents.add(file.getFileName().toString());
                }
                if (constantPool.contains("org/openhab/core/items/ItemRegistry")) {
                    referencingItemRegistry.add(file.getFileName().toString());
                }
            }
        }

        assertThat(referencingItemEvents, is(empty()));
        assertThat("the scan has to be able to see a reference at all, or it proves nothing", referencingItemRegistry,
                is(not(empty())));
    }

    /**
     * The other half of the same guarantee: only three files in the bundle refer to openHAB's Item API at all, and
     * every one of them only reads.
     *
     * @throws IOException if the sources cannot be read
     */
    @Test
    public void theOnlyItemAccessInTheBundleIsReading() throws IOException {
        List<String> touchingItems = new ArrayList<>();
        for (Path file : mainSources()) {
            if (codeOf(file).contains("org.openhab.core.items.")) {
                touchingItems.add(file.getFileName().toString());
            }
        }

        // the reader that calls getState(), the metadata source that watches the MetadataRegistry, and the engine,
        // which only receives an ItemRegistry in its DS constructor and hands it straight to the reader
        assertThat(touchingItems, containsInAnyOrder("RegistryItemStateReader.java", "MetadataParticipantSource.java",
                "EnergyEngine.java"));

        String engine = codeOf(
                Path.of("src", "main", "java", "org", "openhab", "core", "energy", "internal", "EnergyEngine.java"));
        // D28 gave the reader a second registry - the persistence configuration - and this pin grew by exactly that
        // argument. The file list above did not: the new dependency is read in the file that already read Items.
        assertThat(engine, containsString("new RegistryItemStateReader(itemRegistry, persistenceConfigurations)"));
        // the engine never touches the registry other than to build the reader
        assertThat(engine.split("itemRegistry", -1).length - 1, is(2));
        assertThat(engine.split("persistenceConfigurations", -1).length - 1, is(2));
    }

    /**
     * D28's dependency is read-only in the same sense the Item one is: the bundle asks the persistence
     * <em>configuration</em> registry what a site has declared and never calls a persistence service, so no history
     * is queried, nothing is stored, and the engine cannot write through this seam either.
     *
     * @throws IOException if the sources cannot be read
     */
    @Test
    public void theOnlyPersistenceAccessInTheBundleIsReadingTheConfiguration() throws IOException {
        List<String> touchingPersistence = new ArrayList<>();
        List<String> offences = new ArrayList<>();
        for (Path file : mainSources()) {
            String code = codeOf(file);
            if (code.contains("org.openhab.core.persistence")) {
                touchingPersistence.add(file.getFileName().toString());
            }
            // the two configuration types are the whole of the permitted surface, so they are taken out first and
            // whatever is left of the persistence API in this file is an offence
            String scanned = code.replace("PersistenceServiceConfigurationRegistry", "")
                    .replace("PersistenceServiceConfiguration", "");
            for (String forbidden : List.of("PersistenceService", "PersistenceManager", "FilterCriteria",
                    "HistoricItem", ".store(", ".query(")) {
                if (scanned.contains(forbidden)) {
                    offences.add(file.getFileName() + " uses " + forbidden);
                }
            }
        }

        // the engine, which receives the registry in its DS constructor, and the reader it hands it to
        assertThat(touchingPersistence, containsInAnyOrder("RegistryItemStateReader.java", "EnergyEngine.java"));
        assertThat(offences, is(empty()));
    }
}
