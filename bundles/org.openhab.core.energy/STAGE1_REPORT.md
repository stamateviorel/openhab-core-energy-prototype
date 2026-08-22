# `org.openhab.core.energy` — stage-1 report

What changed when the wave-1 prototype was reworked against the owner's answers
(`openhab-ems-spec/docs/OWNER_DECISIONS.md`), what those answers cost, and what still does not work.

**Two rounds of answers are recorded here.** The first, **D1–D22**, was the rework this report was
originally written for. Building it surfaced eight places where those decisions were incomplete,
contradictory or unimplementable as written; the owner answered all eight as **D23–D30** on
2026-08-03, and §3 — which used to be a list of open questions — is now the record of that loop being
closed. Three of the eight were confirmations of what the code already did; five changed behaviour
that was green and tested.

D23 also split the framework in two: the status Item and the REST view moved to a companion bundle,
[`org.openhab.core.energy.publish`](../org.openhab.core.energy.publish/README.md), which has its own
README and its own rules. **That bundle may write Items. This one may not, and still cannot.**

The prototype-era account is [`PROTOTYPE_REPORT.md`](PROTOTYPE_REPORT.md), now marked historical: it
describes the bundle as built against the *unanswered* spec and several of its statements are no
longer true of this tree. The type-by-type contract is [`README.md`](README.md).

---

## 0. Build state

```
cd /home/openhab/work/openhab-core && mvn -pl bundles/org.openhab.core.energy,\
    bundles/org.openhab.core.energy.publish clean install

openHAB Core :: Bundles :: Energy Management ............ Tests run: 354, Failures: 0, Errors: 0
openHAB Core :: Bundles :: Energy Management Publishing .. Tests run:  16, Failures: 0, Errors: 0
BUILD SUCCESS
```

| Gate | `energy` | `energy.publish` |
|---|---|---|
| `clean install` | success | success |
| Tests | **354**, 0 failures, 0 errors, 0 skipped | **16**, 0 / 0 / 0 |
| spotless (`codestyle_check`) | pass | pass |
| checkstyle | **0 errors** (`target/code-analysis/checkstyle-result.xml`) | **0** |
| PMD | **0 violations** | **0** |
| SpotBugs | **0 bug instances** | **0** |
| `javadoc:javadoc` | exit 0, no warnings | exit 0, no warnings |
| EPL header · `@NonNullByDefault` · `@author` | 137/137 each | 7/7 each |
| `package-info.java` per package | 7 packages, 7 files | 2 packages, 2 files |
| static or non-final loggers · thread creation | 0 · 0 | 0 · 0 |
| Fixtures | all four CSVs byte-identical (`md5sum`) to `openhab-ems-spec/fixtures/` | n/a |
| Dependencies | **four**: `org.openhab.core`, `org.openhab.core.config.core`, `org.openhab.core.persistence`, `org.openhab.core.test` (test scope) | three: `org.openhab.core`, `org.openhab.core.energy`, `org.openhab.core.test` (test scope) |

**The dependency count went from three to four, and the addition is exactly D28's.** The bundle now
reads `org.openhab.core.persistence` — the *configuration* registry only, never a persistence
service — because that is the only way to tell "this Item's history is not being kept" from "this
Item has not changed yet", which D7+ requires it to report and §3.2 reported it could not. A reviewer
will notice the new import; it is meant to be noticed. `theOnlyPersistenceAccessInTheBundleIsReadingTheConfiguration`
holds the seam read-only.

Always build with `clean`: a warm rebuild produces three false checkstyle findings from the
incremental cache.

---

## 1. Before and after

Three columns, because there were two rounds. "Prototype" is the bundle as built against the
*unanswered* spec; "after D1–D22" is what the first rework produced and what this report originally
described; "now" is after the follow-up round.

| | Prototype | After D1–D22 | Now (after D23–D30) |
|---|---:|---:|---:|
| Main sources | 101 | 103 | **112** |
| Test sources | 24 | 24 | **25** |
| Java lines (`src`, main + test) | 21,249 | 22,147 | **24,920** (16,266 main + 8,654 test) |
| Tests | 303 | 318 | **354** |
| Test classes | 22 | 22 | **23** |
| DS components | 9 | 10 | **11** |
| Dependencies (compile) | 2 | 2 | **3** |
| Checkstyle / PMD / SpotBugs | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 |

Plus a second bundle that did not exist before: `org.openhab.core.energy.publish` — 5 main sources,
2 test sources, 781 lines, 16 tests, 2 DS components.

(The bundles are not yet under version control in this checkout — `git status` reports them
untracked — so the "prototype" column is a measurement taken at the start of that session, not a
diff.)

Nine new main classes since D1–D22: the two event types and their two DTOs, `EnergyEventFactory`,
`CycleEventReporter` and an `events` package-info (all D23); `ProtectionHistory` (D28); and
`DeclaredProtections` (D25). One new test class, `EnergyEventPublicationTest`. **No test was
deleted, in either round.** Two were *renamed* to stop claiming more than they now prove —
`anUndeclaredProfileFallsBackToTheDocumentedDefault` → `anUndeclaredProfileIsRefusedRatherThanDefaultedToSimple`
in the first round, and `aContributedAlgorithmCannotClaimARungAboveTheLevelGate` →
`aContributedClaimWithNothingBehindItIsDemotedToTheLevelGate` in this one — and each carries a
JavaDoc recording what it used to assert and why that is no longer the whole rule.

### The no-write invariant

**Unchanged, and better pinned than before.** It is, and has always been: *this bundle never commands
an Item, never updates an Item's state, and never mutates the `ItemRegistry`.*

D23 changed the *test* that stands in front of it, deliberately, and the argument is recorded on
`ShadowModeDemonstrationTest.theBundleContainsNoCodePathThatWritesToAnItem` rather than in a commit
message. The old forbidden-token list had six entries and included `EventPublisher` — a token that
over-reached the invariant, because posting an `Event` is not an Item write. The list now has nine:
`EventPublisher` is gone, and `ItemCommandEvent`, `ItemStateEvent`, `ItemStateUpdatedEvent` and
`ItemTimeSeriesEvent` are added. Those four are not decoration — they close a route the old list
closed only incidentally, a hand-rolled `AbstractEvent` whose `getType()` returns the literal
`"ItemCommandEvent"` on an `openhab/items/…/command` topic, which the receiving side's
`ItemEventFactory` reconstructs and `ItemUpdater` acts on: a genuine Item write with no
`org.openhab.core.items` import anywhere in it.

Five tests now stand where two did:

| Witness | What it holds |
|---|---|
| `theBundleContainsNoCodePathThatWritesToAnItem` | the nine-token scan over all 112 comment-stripped main sources |
| `theOnlyPlaceThatPostsAnEventIsTheReporter` | exactly two files may name an `EventPublisher`; exactly one may call `.post(` |
| `theOnlyItemAccessInTheBundleIsReading` | the whole `org.openhab.core.items.` prefix is pinned to three named read-only files, and `EnergyEngine` mentions `itemRegistry` exactly twice |
| `theOnlyPersistenceAccessInTheBundleIsReadingTheConfiguration` | D28's new dependency is the configuration registry and nothing else — no `.store(`, no `.query(`, no `HistoricItem` |
| **`theCompiledBundleCannotEvenResolveAnItemEventType`** | **new.** Reads the emitted `.class` constant pools: no reference to `org.openhab.core.items.events` anywhere. This is what bnd derives `Import-Package` from, so the package is absent from the bundle manifest and an Item event is **not resolvable in this bundle's classloader at runtime** — a compiled-in write would fail with `NoClassDefFoundError`, not merely fail a review. It has a positive control, so it cannot pass by looking at nothing. |

Plus the behavioural one, `aSyntheticSiteRunsSeveralCyclesAndWritesNothing`, and
`everyEventThisBundlePostsIsItsOwn`, which runs a live site with a publisher bound and checks that
every event emitted is one of this bundle's own two types on an `openhab/energy/` topic.

**What the widening honestly costs.** Before, no main source could name `EventPublisher`, so the
bundle could post *nothing of any type*. Now `CycleEventReporter` could in principle post a foreign
non-Item openHAB event — a `ThingStatusInfoEvent`, say — from a branch no test exercises, and the
token list would not name it. That is small, and it is outside the invariant as stated, but it is the
answer to "what did the widening cost". The one thing no grep can catch is a deliberately
concatenated literal; that was equally true of the old list.

**The one new publication surface that is a pull**, `EnergyConfigStatus`, is unchanged:
`ConfigStatusService` asks it, it posts nothing and writes nothing.

---

## 2. Decision by decision

### Fixed in this rework

**D16·A8 — an absent profile class is a configuration error, never defaulted to Simple.**
`EnergyMetadataParser.profileKind` threw for an *unreadable* class and silently returned `SIMPLE` for
an *absent* one, which is the alternative the decision rejects. It now refuses both, with an error
naming the four classes; `DEFAULT_PROFILE_KIND` is gone. The blast radius is the point: every
`{ energy="consumer" }` with no `profile=` used to register as a Simple device the engine believed it
could switch. `profil="controllable"` is one keystroke from `profile="controllable"` and leaves the
key *absent*, so the softened half was letting through exactly the typo the strict half exists to
catch. Thirteen tests that had relied on the default — ten in the parser, three in the declaration
source — now declare `profile="simple"` explicitly, so each still fails, or passes, for the reason it
names.

**D13 — a contributed algorithm can no longer buy a stronger rung by labelling its own decision.**
`DecisionKind` is a field on a public record and nothing corroborated it, so
`new Decision(…, DecisionKind.DEVICE_PROTECTION, …)` from any contributed algorithm was exempt from
the user's level gate (`EngineEnforcedParticipantGuard` consults it only below the gate rung), exempt
from D14's stale-measurement freeze (`ElectricalLimitFloor` exempts that kind), and stronger than the
engine's own protection for the same participant. `EvaluationPass` now normalises every proposal from
an algorithm that is not `EngineOwnedAlgorithm` down to `LEVEL_GATE` at the strongest.
`EngineOwnedAlgorithm` is a marker in `org.openhab.core.energy.internal`, which the bundle ships as
`Private-Package` — so the claim is unforgeable rather than discouraged. An **id** would not have
done: two algorithms may carry the same id, and a contribution may use the engine's.

**D1 — the shipped description of the master stop.** `OH-INF/config/energy.xml` and the i18n bundle
told the operator "the engine keeps evaluating and logging while it is engaged" — the dispatch-only
reading the owner overrode, promising that protections are still enforced at the moment somebody
reaches for the kill switch. Both now read "Halt everything: while it is engaged the engine takes no
snapshot, invokes no algorithm, and enforces no device protection." A new conformance test pins the
text, because the Java was already right and nothing was checking the only thing an operator reads.

**D2 — a running managed load nobody decided about is booked.** `ElectricalLimitFloor.Ledger` booked
only the participants it was *forbidden* to shed. A consumer that is running, metered and has no
decision this cycle — the ordinary case, since a device already in the state it would be commanded to
gets no decision at all — was in neither the ledger nor the uncontrolled figure, which subtracts
measured consumer draw precisely because the floor is supposed to account for it. A 10 kW site with a
3 kW boiler running would admit a 9 kW heating decision untrimmed. The ledger now pre-books every
running consumer at its measured draw and releases the booking when a decision for it is committed,
or when the floor sheds it (so shedding frees headroom within the same cycle). A participant's own
pre-booking is returned to it in `headroomFor`, because a command replaces a draw rather than adding
to it.

**D4 — the tie-break key.** (See also §3.6, where D30 supplies the clause D4 could not reach.)
`Decision.PRIORITY_ORDER` broke equal priorities on *algorithm* id
before participant id. The requirement says participant id ascending. Invisible in conflict
resolution (all decisions in a group share one participant id) but load-bearing in the
electrical-limit floor, which hands out headroom in this order: two equal-priority consumers
addressed by two different algorithms were served by whose algorithm sorted first. Clauses swapped.

**D7+ — one protection clock, read by all three enforcement points.** The first-observation fallback
existed only inside `DeviceProtectionAlgorithm`; `EngineProhibitions.protectionHolding`, which the
electrical floor and the safe state both call, treated an unreadable history as *no protection*. On a
site that does not persist the steered Item the same fridge was protected by the algorithm and shed
by the floor in the same cycle. The stamp moved to `EnergyContextFactory` — one map, taken once per
snapshot, retained to the participants of the current cycle — and is read through
`ParticipantState.protectionElapsed(Instant)`, which every enforcement point now uses. This also
makes "the engine keeps no protection timers of its own" literally true rather than true-in-one-class.

**D3 — a configured stop lands like a runtime stop.** `setStopped` mutated the live `ActuationGate`,
so a stop engaged during a cycle stopped that cycle's remaining dispatches; a configuration carrying
`stopped=true` *replaced* the gate, which the in-flight cycle never looked at. One control, two
behaviours, decided by which route the operator took. There is now exactly one gate instance for the
engine's lifetime and `configure(…)` installs values into it.

**D16·A8 — the report is machine-readable.** New `EnergyConfigStatus`, a `ConfigStatusProvider` for
the `org.openhab.core.energy` PID. Five conditions — six since D28 added the information-level note
described in §3.2: a declaration refused whole (error), one accepted
with a remark (warning), a Simple consumer with no declared rating, a protected participant whose Item
history cannot be read, and a participant naming an actuation sink that is not installed. The last
three are re-derived from each cycle's snapshot and replace the previous set wholesale. This was the
largest cleanly-buildable gap: `org.openhab.core.config.core.status` was already on the classpath and
importing it trips neither structural test.

Documentation fixed at the same time: the `{@link ParticipantGuard}` in `EngineProhibitions` (the
type was renamed when D13 collapsed the seam, so `javadoc:javadoc` had a dangling reference); the
`ParticipantState` class comment, which still said phases were not part of the participant model and
that `lastChangedAt` was an unspecified inference; `EnergyEngine.lastCycle()`, which claimed events
and REST "need a write this bundle is structurally incapable of making" — false for both, and now
split into the three separate reasons (§3.1, which D23 has since answered); and the README's stale component table, stale class
names and stale test counts, plus a new §10.6 and §12.

### Already correct, verified

| Decision | Where |
|---|---|
| **D1** halt everything | `EnergyEngine.runCycleNow` returns before the snapshot and before any algorithm; `EngineControlsTest.nothingContributedRunsWhileTheEngineIsStopped` proves a recording algorithm records nothing |
| **D2** ladder | `ConstraintLadder.rank`, written once as data, with no configurable reordering |
| **D3** two controls, fresh install = shadowed | `ActuationGate`; four permutations covered |
| **D5** one SPI, Item-name identity, duplicates, precedence, named sink | `EnergyParticipantRegistryImpl.BY_AUTHORITY`; `EnergyEngine.selectedSink` refuses to substitute for a named-but-absent sink |
| **D6** the engine computes the level | `EnergyContextFactory.LevelResolver`; the level plane has no `Clock` |
| **D8** tolerance counts, expiry lapses | `EngineAcknowledgementTracker`; the band narrows to *exact* rather than being reinterpreted across dimensions |
| **D9** priority orders, shape decides | `ElectricalLimitFloor`; trimmability never enters the ordering |
| **D10** surplus = export + reclaimable charge | `EnergyContext.surplusWattsFor` — **amended by D27**, see §3.3 |
| **D11** sign conventions | `SignConvention`, stated once |
| **D12** 0/1/2/3 + the SG-ready offset | `EnergyLevel`, `SgReadyMode` |
| **D14** freeze and floor | `SafeStatePass` + the floor's refusal of increases; protections, running Batch and hands-off all excluded |
| **D15** `ratedPower` optional, gap reported | `SimpleProfile.ratingIsInferred()`; nothing makes it mandatory. What the floor *books* is `max(declared, measured)` — **confirmed by D29**, see §3.8 |
| **D16·A10** watts/amps, mandatory clamp, phases 1/2/3 | model + parser; phases are device-declared |
| **D16·A12** window requests, flat weights, slot boundaries, partial answers | `WindowRequest`, `LeftRiemannWindowCost` |
| **D16·A13** curve on the profile, relative time, LEFT Riemann | `LoadCurve`, `BatchProfile` |
| **D17** Part B defaults | `EnergyEngineConfiguration` + `energy.xml`, held to each other by `ConfigDescriptionConformanceTest` |
| **D21** earlier slot wins | `PriceSeries.rankedIndices` |
| **D22** graded, no default, reported unconfigured | `GradedSurplusEscalation` / `UnconfiguredSurplusEscalation` |

### Not wave-1 code decisions

- **D18** (Part C, 22 questions) — "no action, already answered". One item of it *is* buildable and is
  not built: EP-5.1, a `MetadataConfigDescriptionProvider` publishing the `energy:` key vocabulary.
  See §6.
- **D19** (a budget test vector derived from the owner's own site) — a corpus-side sourcing task. The
  bundle reproduces the four fixtures the corpus ships today, unmodified.
- **D20** (no carbon credit at a negative feed-in price) — belongs to the objectives change, wave 2.
  Nothing in wave 1 computes carbon.
- **D16·A14** (capacity tariff) — split between `grid-constraints` (look-ahead) and the engine's
  runtime floor. Wave 1 implements neither the tariff nor the look-ahead; the floor it *does*
  implement is the electrical one.

---

## 3. The eight decisions that did not survive contact with the code — and how they were answered

**This is still the section to read.** It was written as eight questions for the owner. All eight now
have answers (**D23–D30**, 2026-08-03, `openhab-ems-spec/docs/OWNER_DECISIONS.md`), so each entry
below now records the question, the ruling, and what actually changed in the tree.

The section numbers are kept exactly as they were, because the spec corpus cites them by number.

| § | Question was about | Decision | Outcome |
|---|---|---|---|
| 3.1 | A8's observability inside a shadow-only bundle | **D23** | **CHANGED** — events here, status Item + REST in a companion bundle |
| 3.2 | telling *not persisted* from *never changed* | **D28** | **CHANGED** — dependency allowed, report is now precise |
| 3.3 | surplus when the site imports while charging | **D27** | **CHANGED** — `max(0, grid + reclaimable)` |
| 3.4 | a contributor with a *genuine* protection claim | **D25** | **CHANGED** — corroborated, not capped |
| 3.5 | a protection coming due during a stale-input freeze | **D24** | **CONFIRMED** — the code was right |
| 3.6 | the tie-break conflict resolution actually reaches | **D30** | **CONFIRMED** — the code was right |
| 3.7 | a malformed declaration promoting a contributed one | **D26** | **CHANGED** — the participant is blocked |
| 3.8 | `max(declared, measured)` versus "prefer the measurement" | **D29** | **CONFIRMED** — the code was right |

Three confirmations is not three no-ops. In each of those cases the code carried an inference the
decisions did not literally state, the report said so, and the answer turned the code's reading into
an owner decision — which is the difference between a behaviour somebody chose and a behaviour
somebody happened to write. All three now carry that provenance on the class that implements them.

### 3.1 D16·A8 cannot be met inside a shadow-only bundle, and it fails three different ways — **answered: D23**

**The question.** A8 asks for an outcome and a reason on every decision (shipped), the current cycle
readable (shipped), decisions **published as deduplicated events**, and **one engine status Item**.
The last two, and D6's own note that a published level would be an output, were absent for *three
different* reasons, which the prototype's account had conflated: the status Item is a genuine
conflict with the no-write invariant; events are blocked only by this bundle's own structural test,
whose token list was deliberately wider than the invariant; the REST view needs JAX-RS, which Rule 5
forbids.

**The decision — D23. Split three ways.** Events ship **in core**, because posting an `Event` is not
an Item write: widen the structural test's forbidden-token list deliberately, keeping the real
invariant intact. The **status Item and the REST view move to a companion bundle**
(`org.openhab.core.energy.publish`), which is also where D6's published level belongs. Core stays
provably incapable of touching a device.

**What changed.**

- New `org.openhab.core.energy.events` package, exported: `EnergyDecisionEvent`, `EnergyCycleEvent`
  and their two DTOs, plus a registered `EnergyEventFactory` so a subscriber can deserialize them
  without depending on this bundle's types.
- New `CycleEventReporter` — the only thing in the bundle that calls `.post(`, holding a
  `0..1 dynamic` `EventPublisher` and returning early when none is bound. Only two test classes ever
  bind one; the other 21 run with no publisher at all, which is the engine's own default state.
  Events are deduplicated on change, matching the shadow log's own rule.
- The structural test's token list had **one token removed** (`EventPublisher`) and **four added**
  (the Item-event class names), with the argument written on the test rather than in a commit
  message, plus two new compensating witnesses (§1).
- New bundle `org.openhab.core.energy.publish`: `EnergyItemProvider` provides `EnergyEngineStatus`
  and `EnergyCurrentLevel`; `EnergyStatusPublisher` subscribes to the two events and updates them.
  **State updates only, never commands, two Items, both its own** — all three bounds asserted. It is
  a second opt-in Karaf feature that depends on `openhab-core-energy` and not the reverse; deleting
  it from the local repository and rebuilding the framework alone still gives 354 green tests.
- The REST view is **still not built**, and that is recorded rather than forgotten: D23 settled
  *where* it would go, not *that* it must be, and JAX-RS is a maintainer decision about a new
  dependency in core. See the companion README.

*Alternatives preserved:* relax A8 for core and drop all three surfaces; move everything, events
included, into the companion bundle.

### 3.2 D7+'s "report a protected participant whose state is not persisted" is not implementable here — **answered: D28**

**The question.** The decision requires the engine to distinguish *this Item is not persisted* from
*this Item has simply never changed since the engine started*. `Item.getLastStateChange()` returns
null for both. Telling them apart needs `org.openhab.core.persistence` — a new dependency, which
Rule 5 forbade. What shipped was the strictly weaker **protection-unknown**: reported, run on a
first-observation clock, without saying why.

**The decision — D28. Allow the dependency.** A core bundle, so no default-library breach, but a
genuinely new dependency reviewers will notice.

**What changed.** New `ProtectionHistory` enum — `FROM_DEVICE`, `NOT_KEPT`, `NO_CHANGE_YET`,
`UNDETERMINED` — carried on `ParticipantState` beside the fallback clock, and stamped by
`RegistryItemStateReader`, which now also holds a `PersistenceServiceConfigurationRegistry`. The two
conditions are reported apart, and that is the whole point: an Item nothing keeps is a fault the site
can fix and is a `WARNING` in the config status plus a log warning; an Item that *is* kept and has
not changed yet is the ordinary state of the first minutes after a restart and is an `INFORMATION`
message plus an info line. Reporting them as one condition names a problem the user cannot act on,
because one of the two causes is not a problem. `EnergyConfigStatus` grew a fifth message key,
`participant-note`, for exactly that.

The dependency's blast radius is held by a test rather than by a promise:
`theOnlyPersistenceAccessInTheBundleIsReadingTheConfiguration` allows the two configuration types and
forbids `PersistenceService`, `PersistenceManager`, `FilterCriteria`, `HistoricItem`, `.store(` and
`.query(`.

*Alternatives preserved:* keep the weaker report; drop the clause.

### 3.3 D10's composition is under-specified when the site is importing while charging — **answered: D27**

**The question.** "Grid export + battery charging that can be reclaimed" is a sum of two non-negative
terms, which is what shipped. It over-states in one case: grid at −1 kW (importing) with a battery
charging at 3 kW reports 3 kW of surplus, where stopping the battery would free only 2 kW before the
site returns to import.

**The decision — D27. `max(0, grid + reclaimable)`.** Net against import first, so a consumer started
on that figure does not immediately push the site back into import. **This amends D10 as literally
worded**, and the amendment is stated in those terms in `EnergyContext`'s JavaDoc rather than left to
be inferred from the arithmetic.

**What changed.** `EnergyContext.surplusWatts()` and `surplusWattsFor(int)` net first and clamp at
zero. The corpus's own battery scenario has grid at exactly 0, where the two readings agree, so
**no existing fixture discriminates** — the proof is new tests here and two new scenarios in the
corpus, and a discriminating fixture vector is filed as a corpus-side task rather than pretended to
exist.

*Alternatives preserved:* keep the literal sum; publish both figures separately.

### 3.4 D13 does not say whether a contributed algorithm may ever legitimately claim a rung — **answered: D25**

**The question.** D13 says a contributor "cannot promote a reading of its own into" a prohibition. It
does not say what happens to a contribution with a *genuine* claim — a binding that knows its own
compressor's duty cycle better than the metadata does, or the peak-shaver that `DecisionKind`'s own
JavaDoc names as the legitimate `ELECTRICAL_LIMIT` case. The slice implemented the strict reading: a
contributed proposal capped at `LEVEL_GATE`, full stop. That was flagged as **a real reduction in
flexibility taken on a reading the owner did not make explicitly.**

**The decision — D25. Corroborate the claim against the declaration.** A contributed decision may
claim the device-protection rung only where the engine can independently see a declared protection is
due for that participant; otherwise it is demoted.

**What changed.** New `DeclaredProtections`, which holds the four protection rules *once* — the
`DeviceProtectionAlgorithm` and the corroboration check now read the same table, so a contributor's
claim cannot be judged against a subtly different rule from the one the engine enforces. Its
`corroborate(Decision, EnergyContext)` implements the three conjuncts and returns the reason when one
fails:

1. the participant's own **effective declaration** carries the protection being claimed;
2. that protection is **due at this cycle**, measured through `ParticipantState.protectionElapsed` —
   the way every other enforcement point measures it;
3. the **rendered action is the action that protection requires**.

`EvaluationPass.admissibleKind` calls it, and a claim that fails is demoted to `LEVEL_GATE` **carrying
the reason on its own `Decision.reason()`**, so the demotion reaches the cycle event, the outcome and
the "would have done" line rather than only a debug log. Protection-unknown does not corroborate: an
unreadable history is the absence of evidence, and reading it as corroboration would put the
strongest reachable rung within reach exactly on the sites least able to check it.

**What D25 does not open, stated rather than discovered.** The **electrical-limit rung** has no
declaration to corroborate a claim against — a site's limits are the engine's own inputs — so a
contributed decision still cannot reach it, and `corroborate` refuses it in one line. **The
peak-shaver half of this question is therefore still open.** It is recorded as open in the corpus
(`define-extension-points` design.md §6.1) rather than read into an answer that did not mention it.

*Alternatives preserved:* the strict cap that shipped; trusting a claimed rung as claimed.

### 3.5 D2 and D14 disagree about a protection that wants to *increase* load during a freeze — **answered: D24 (confirms the code)**

**The question.** D2 puts electrical limits above device protections. D14 says freeze-and-floor is
"ALL subject to device protections" and, in the same breath, "refuse increases". A duty-cycle
guarantee that comes due while a current clamp is stale wants to switch a fridge **on**, which is an
increase. The code let it through and said so in `ElectricalLimitFloor`'s JavaDoc — an inference
beyond D14's flat wording.

**The decision — D24. Let the protection through.** A stale reading is an unknown, not a known
overload, and a fridge refused for hours because a CT went stale spoils food.

**What changed: the behaviour, nothing; the provenance, everything.** The paragraph in
`ElectricalLimitFloor` that used to say *"this is the ladder being read into it"* now cites D24 by
name and date, states the ground the decision rests on — the freeze's authority is a *measured*
overload, which is exactly what a stale input means the engine does not have — and preserves both
rejected alternatives (freeze-means-freeze; a bounded blind allowance). The same paragraph also had
to be corrected for D25: it claimed the exception "cannot be claimed" because only an
`EngineOwnedAlgorithm` reaches this rung, which is no longer true — a corroborated contributed
protection reaches it too, which is the point of D25.

### 3.6 D4's tie-break does not reach the case conflict resolution actually has — **answered: D30 (confirms the code)**

**The question.** "Equal priorities are tie-broken by participant id ascending" is exactly right for
*serving* order. Conflict resolution groups decisions **by participant**, so inside a group the
participant id always ties and the decision is silent about what breaks it. The code fell through to
algorithm id and then to the rendered action — deterministic and stateless, but the code's choice,
with `"OFF" < "ON"` doing real work in it.

**The decision — D30. Algorithm id, then the rendered action.** Deterministic, stateless,
reproducible from the snapshot — the properties D4 protects. Noted and accepted: comparing action
text means `"OFF" < "ON"`, so on a dead heat the safer action wins by alphabetical accident rather
than by design.

**What changed: provenance only.** `Decision.PRIORITY_ORDER`'s JavaDoc now says that the last two
clauses are the tie-break conflict resolution actually reaches, cites D4 and D30, records the
`"OFF" < "ON"` consequence as accepted-not-designed, and preserves both alternatives (make the safety
bias explicit; refuse the tie and report it).

### 3.7 D5 and D16·A8 interact in a way neither of them states — **answered: D26**

**The question.** D5: a duplicate declaration is "a further statement about the same participant,
never an error", with explicit metadata beating a contributed declaration. A8: a malformed
declaration "skips the WHOLE participant". Put together: if your own metadata for a device is
malformed, it is withdrawn — and the add-on's contributed declaration for the same identity,
previously outranked, silently becomes effective. The device stays managed, on somebody else's terms,
from a typo. The config-status error made the *typo* visible; nothing made the *transfer* visible.

**The decision — D26. A malformed declaration blocks the participant entirely.** The device is
unmanaged until fixed; no lower-ranked contribution inherits it. Intent to control survives even when
the text is wrong, so a typo degrades to "nothing happens", never "something else happens".

**What changed.**

- `EnergyParticipantSource` gained `getBlockedParticipants()` and `getOrigin()`, both defaulted, so a
  source whose declarations arrive as typed objects never has to know the mechanism exists.
- `AbstractEnergyParticipantSource` gained `block(String)` / `unblock(String)`. Blocking is **not**
  withdrawing: a withdrawal lets the next statement down the chain through, a block does not.
- `EnergyParticipantRegistryImpl.applyBlocks` removes a blocked identity from the resolved view
  unless the statement that survived precedence is **strictly more authoritative** than the block, so
  an add-on that cannot read its own declaration can never disable a site's. The comparison is on
  authority alone — origin, then `service.ranking` — deliberately not on the alphabetical source-id
  clause that makes the precedence chain total: that clause decides which of two *readable*
  statements wins, and has no business deciding whether a device is steered at all.
- `MetadataParticipantSource` blocks on a refused parse, and blocks **both** the identity the
  declaration claims now and the one it last successfully produced where they differ — the second is
  what a contribution would otherwise inherit from a user who is mid-edit. Over-blocking there is
  deliberate: D26's chosen failure mode is "nothing happens".
- The block lifts by itself when the declaration parses again, when it is deleted, and when the
  source deactivates — a source that goes away takes its blocks with it, so the *graceful degradation
  on contributor loss* path is untouched.
- The machine-readable error now says what the block means, not only which key failed.

*Alternatives preserved:* let the add-on take over (what shipped); take over after the user
acknowledges the error.

### 3.8 D15 says "prefer a fresh measurement over an estimate"; the floor books the larger of the two — **answered: D29 (confirms the code)**

**The question.** Implemented as `max(declared, measured)` while the participant is running.
Literally "preferring the measurement" would book 8 kW for a device measured at 8 kW that declares
3 kW — the same thing — but would also book 3 kW for one measured at 3 kW that declares 8 kW and is
about to ramp. Conservative in both directions, stated in the class JavaDoc, but an interpretation.

**The decision — D29. Book the larger of the two while running.** A ramping wallbox books its
declaration; an appliance exceeding its declared envelope is constrained rather than hidden behind
its command.

**What changed: provenance only.** `ElectricalLimitFloor`'s booking paragraph now cites D15 and D29,
names the ramping wallbox as the case the literal reading gets wrong, and preserves both alternatives
(book the measurement literally; always book the declaration).

---

## 4. Seams that collapsed

Every one of these is flexibility a reviewer should see traded away, and each is named in the JavaDoc
of the class that lost it.

| Collapsed | Because | What a site can no longer do |
|---|---|---|
| `PrecedenceStrategy` (contributable, two implementations) | D2 fixed the ladder | Reorder the constraint ladder, or ship a protections-first one |
| `ParticipantGuard` (a guard that vetoed nothing, selected by config) | D13 made prohibitions engine-owned | Configure the engine to trust an algorithm with the hands-off flag, the level gate or the interlock |
| One-control `ActuationGate` variant + the `gate` parameter | D3 fixed the two-control shape | Have the stop imply shadow |
| `unknownDemandPolicy` | D15 exempted ModeControllable | Choose to defer a mode change whose draw is unknown |
| `degradeSafeOnStaleMeasurements` | D14 made freeze-and-floor unconditional | Keep running on last known values |
| `precedence` (declaration sources) | D5 fixed explicit > contributed > discovered | Decide that a contributed declaration outranks your own metadata |
| `levelOutsidePlan`, `escalation=none\|maximum` | D22 + D12 | Set the out-of-plan level, or take any-surplus-jumps-to-maximum |
| `phases` engine-config parameter | D16·A10 made phases a device declaration | Assign phases centrally — including for a participant whose declaration you cannot edit |
| A dimensionless acknowledgement band | D8 | Have a tolerance in one dimension widen a comparison in another |
| A contributed algorithm's `DecisionKind` above `LEVEL_GATE`, *uncorroborated* | D13, then **D25** | Ship a peak-shaver that outranks a device protection. **Partly re-opened by D25:** a contribution may now reach the *protection* rung when the engine can independently see the participant's declared protection is due and requires that very action. The *electrical-limit* rung stays closed — a site's limits are the engine's inputs, so there is nothing to corroborate against |
| An absent `profile` | D16·A8 | Declare a plain on/off device without naming its class |
| **New this round:** a lower-ranked declaration inheriting a blocked identity | **D26** | Keep a device managed by an add-on's contribution while your own declaration for it is malformed. The device is unmanaged until the text is fixed |

The D25 row is the only entry in this table that has ever moved in the direction of *more*
flexibility, and it is worth naming as such: the corpus preserves the strict cap as the option to
return to if corroboration proves fragile in practice.

---

## 5. What a reviewer should look at first

1. **`DeclaredProtections.corroborate` + `EvaluationPass.admissibleKind`** (~60 lines together). The
   security boundary of the whole extension surface, and the one place either round *widened* what a
   third party may do. Read the three conjuncts against the four rules they are checked with — the
   two live in one file precisely so they cannot drift. Tests:
   `EvaluationPassTest.aContributedClaimWithNothingBehindItIsDemotedToTheLevelGate`,
   `…aContributedClaimTheDeclarationCorroboratesIsHonouredAtThatRung`,
   `…aClaimPointingTheWrongWayIsNotCorroborated`, `…anUnreadableHistoryDoesNotCorroborate`,
   `…theSameSnapshotCorroboratesTheSameClaimEveryTime`,
   `…aContributedClaimToTheElectricalLimitRungIsNeverCorroborated`,
   `ParticipantGuardTest.aContributedAlgorithmCannotBypassTheGateByCallingItsDecisionADeviceProtection`,
   `ScriptAlgorithmProofTest.aScriptCannotLiftALevelGateByCallingItsDecisionAProtection`.
2. **`EnergyParticipantRegistryImpl.applyBlocks` + `outranks`** (~30 lines). D26 is a rule about when
   a device is steered *by nobody*, so the risk is symmetrical: too weak and a typo transfers control,
   too strong and an add-on's bug un-manages a site. Read `outranks` and ask what happens when the two
   authorities are equal.
3. **`ElectricalLimitFloor.Ledger`.** Pre-booking is a change to what the floor believes the site is
   drawing, so it is the change most able to be wrong in a direction that matters. Read
   `headroomFor` and `commit` together, and the three tests around them.
4. **`EnergyMetadataParser.profileKind` and the thirteen tests that had to grow a `profile` key.**
   The change with the largest blast radius on existing declarations.
5. **`ParticipantState.protectionElapsed`** and its call sites, now four with the corroboration check.
   The safety property is that all of them answer the same question the same way.
6. **`ShadowModeDemonstrationTest`** — and specifically
   `theCompiledBundleCannotEvenResolveAnItemEventType`, which is the reason to trust everything above
   even after D23 widened the source-level grep.
7. **§3 of this report**, which is worth more than any of it.

---

## 6. Honest limitations of wave 1 after this rework

**Requirements accepted but not implemented anywhere in these two bundles:**

- A8's **REST view** of the current cycle (§3.1). D23 settled where it would live — the companion
  bundle, never here — but not that it must be built, and JAX-RS is outside the default-library set,
  which is a maintainer decision about a new dependency in core rather than a design question this
  corpus can settle. Everything a REST resource would serve already leaves the framework on the event
  bus, so a UI can subscribe over SSE today; what REST would add is a poll instead of a wait.
- D18·**EP-5.1**: no `MetadataConfigDescriptionProvider` for the `metadata:energy` namespace. The
  parser's key vocabulary is still unpublished, so the UI cannot offer it and cannot validate it.
  This is **buildable today**: `config.core` is already a dependency and it trips no structural test.
  Nobody has claimed it.
- **The electrical-limit half of §3.4.** D25 opened the protection rung to a corroborated contributed
  claim and named nothing else; a contributed peak-shaver still cannot reach `ELECTRICAL_LIMIT`,
  because a site's limits are the engine's own inputs and there is no declaration to corroborate
  against. Recorded as open in `define-extension-points` design.md §6.1 rather than read into an
  answer that did not mention it.
- **A discriminating fixture for D27.** The corpus's battery scenario has grid at exactly 0, where
  the literal sum and `max(0, grid + reclaimable)` agree. The amendment is proved by new unit tests
  and two new corpus scenarios; a fixture vector that tells the two apart is filed as a corpus-side
  task and does not exist yet.

**Closed since this report was first written:** A8's **events** and the **engine status Item** (D23),
and D7+'s stronger unpersisted-protection report (D28) — both of which this section used to list as
gaps.

**Scope:** wave 1 is four changes of twelve. No price plane, no forecast, no objectives, no
grid-constraint planner, no named profiles, no learning, no UI, no semantic discovery. The engine
computes a level from a plan something else must supply, and `EnergyLevelPlane` fetches no prices.

**Known thinness inside wave 1:**

- **Per-phase uncontrolled load is not read.** A provider can name a reading Item per phase and the
  model carries it, but nothing reads those Items into the snapshot, so per-phase headroom is
  enforced only against what the engine itself dispatches plus the participants it can see. On a site
  with unmanaged three-phase load this under-counts.
- **A Batch programme's "running" state is inferred** from the steered Item reporting itself on. The
  model carries no programme-start moment, so a Batch consumer whose Item reports nothing is treated
  as not running.
- **A ModeControllable load books nothing** unless the site declares `modeDraws`. The gap is
  reported, never a refusal.
- **The engine subscribes to nothing.** `triggerEvaluation()` exists and is debounced, but no Item
  change drives a cycle; the cadence is the fixed tick.
- **Algorithm identity collisions are unmanaged.** `registerAlgorithm(id, …)` replaces silently, which
  is what a reloaded script needs and means one script can take over another's id unnoticed —
  deliberately asymmetric with the declaration plane, and flagged rather than fixed.
- **Three of the four device protections live in an ordinary whiteboard component.** `maxOn`,
  `minOff` and `maxOff` are `DeviceProtectionAlgorithm` decisions, so stopping that bundle disables
  them. Only the prohibition half — the `minOn` hold in `EngineProhibitions.protectionHolding`, called
  directly by the floor and the safe state — is enforced by code that cannot be unloaded. Defensible
  under D13 (a prohibition is a "no", and the engine owns every "no") but a reviewer should know it.
  The class JavaDoc now says so, and also corrects its own earlier claim that it "can be displaced by
  registering something else under its id": it cannot — both would run.

- **Only the framework's own three device protections are corroborable.** D25's test reads a
  `SimpleProfile`, because that is the only profile class carrying protection parameters. A binding
  that genuinely knows something about a Batch, Controllable or ModeControllable device's protection
  has nothing to corroborate against and is still capped at the level gate. That follows from the
  model rather than from D25, and is not a defect of either, but a reviewer should know the reach of
  the opening is narrower than "contributions may now claim protections".

**Corpus-side, unchanged:** D21 exists only in `docs/OWNER_DECISIONS.md`. The `energy-levels` *Level
derivation* requirement still says on its Source line that the tie-break "was not decided and stays
open in design.md §6", which now contradicts the decision record. The code matches D21 and the
*Repeated prices* test exists; the requirement text needs the fold-in.
