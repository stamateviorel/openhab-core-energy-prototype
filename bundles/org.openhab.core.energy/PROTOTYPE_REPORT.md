# `org.openhab.core.energy` — wave-1 prototype build report

> ## ⚠ HISTORICAL — superseded by [`STAGE1_REPORT.md`](STAGE1_REPORT.md)
>
> This document describes the bundle **as it was built against the unanswered spec**, before the
> owner answered the open design questions (`docs/OWNER_DECISIONS.md`, D1–D22). It is kept because
> §5 is the record of the thirty-eight spec defects that the decisions were made *against*, and
> because the prototype track asks for it. It is **not** a description of the current tree.
>
> Specifically, the following statements in it are now false:
>
> | Says | Now |
> |---|---|
> | "185 tests … in 21 classes" (§2, §3) and the per-class table | **318 tests in 22 classes** |
> | "94 main sources (11,302 lines), 21 test sources (4,620 lines) … Nine DS components" | **103 main sources (14,549 lines), 24 test sources (7,598 lines), ten DS components** |
> | §4 "How the maintainer decisions were kept open" — seventeen seams | Most are **collapsed**: the owner answered them. `PrecedenceStrategy`, `ParticipantGuard`, the one-control `ActuationGate`, `unknownDemandPolicy`, `degradeSafeOnStaleMeasurements`, `precedence`, `levelOutsidePlan`, the escalation pair and the central `phases` parameter are all gone. Each collapse is named in the JavaDoc of the class that lost it, and listed in `STAGE1_REPORT.md` §4. |
> | §5 spec defects | Several are **closed** by D1–D22 rather than open. §5 remains the historical account of how they were found. |
> | "It resolves no open question" (§1) | The prototype resolved none; **this tree implements the owner's answers**. |
>
> What §4 and §5 are still good for: they are the argument the decisions were taken against, and a
> maintainer overturning one of them will want to read the alternative as it was originally framed.

---

Built against the `openhab-ems-spec` OpenSpec corpus, wave 1:
`define-participant-model` · `define-engine-contract` · `define-energy-levels` ·
`define-extension-points`.
Rules of engagement: `docs/PROTOTYPE_TRACK.md` in that corpus.

---

## 1. What this is, and what it is not

**It is a prototype on the prototype track.** Its purpose is to make maintainer review cheap and to
find out where the requirements do not survive contact with a compiler. It is a draft offered for
discussion, not a merge candidate.

**It writes nothing.** No decision this bundle computes ever reaches an Item. That is enforced four
ways, described in §3, and it is not a configuration setting you can accidentally turn off: the
bundle does not import a single openHAB API capable of sending a command or posting an update.

**It resolves no open question.** Every `design.md` question in the slice either has both framed
answers shipped behind a seam and selected by configuration, or is named in code at the point where
it could not be avoided. Where the prototype had to behave *somehow* to compile, the behaviour is a
configured default that the component logs, never a verdict. Two decisions could not be reduced to a
seam and are called out honestly in §4 (the level's granularity, and whether this belongs in core at
all).

**It is not complete.** Wave 1 is four changes out of twelve. There is no price plane, no forecast,
no objectives, no grid-constraint planner, no named profiles, no learning, no UI, no discovery. Some
things the wave-1 requirements themselves ask for are declared but not enforced, and §6 lists them
rather than letting a reader assume otherwise.

**One thing worth stating plainly up front:** the most useful output of this exercise is §5, not the
code. Thirty-eight distinct places where the corpus is ambiguous, contradictory or silent were hit
while building — several of them the kind that only appear when you try to type the thing out.

---

## 2. What was built

### The shape of it

The bundle is three planes that meet at two narrow seams.

**The declaration plane** answers "what participants does this site have?". A participant is a
provider (grid, PV, battery) or a consumer, and a consumer carries one of exactly four profile
classes: Simple, Controllable, ModeControllable, Batch. Declarations arrive through an SPI —
`EnergyParticipantSource` — which the corpus's biggest wave-1 open question hides behind: two
mechanisms ship, `energy:` item metadata and a programmatic contributor API, and configuration
decides which is used and which wins a conflict. `EnergyParticipantRegistryImpl` aggregates every
source into one view. Nothing above the SPI knows a mechanism exists; deleting either implementation
changes no other file.

**The level plane** is a pure function from a price series to a planned schedule of four levels, plus
a resolver that says which level is in force now. It has no OSGi, no clock of its own, no Item
access, no logger. It is gated on the corpus's own conformance fixtures and reproduces all four
vectors element-by-element. Three of its behaviours are undecided in the corpus and therefore
selectable: how the bands are cut (fixed counts / percentiles / seasonal), how far a PV surplus
escalates the current level (not at all / straight to maximum / graded), and what the level is
outside the plan.

**The engine spine** runs one cycle: take exactly one immutable snapshot, hand *the same instance* to
every algorithm, resolve conflicts deterministically, apply the electrical-limit floor, consult the
participant guard, pass the master stop, pass the shadow gate, pass the acknowledgement window, and
only then reach an actuation sink — which, in this prototype, logs. The order is the contract and it
is identical for the built-in algorithms, for an add-on's, and for a script's lambda. Two core
algorithms ship on the same whiteboard any contributor uses: a trivial surplus optimizer and a device
protection algorithm that turns `minOn`/`maxOn`/`minOff`/`maxOff` into decisions.

The two seams between the planes are one class each: `RegistryParticipantSnapshotSource` (declaration
→ engine) and `PlannedLevelSource` (level → engine). Swapping the declaration mechanism, or replacing
the level plane with something that reads prices, replaces one adapter.

```
metadata ─┐
          ├─► EnergyParticipantRegistry ─► RegistryParticipantSnapshotSource ─┐
programmatic ┘                                                               │
                                                                             ▼
PriceSeries ─► LevelDerivation ─► PlannedLevelSchedule ─► PlannedLevelSource ─► EnergyEngine ─► ActuationSink
                                                                             ▲                  (logs only)
                                    EnergyAlgorithm whiteboard ──────────────┘
                                    (core-shipped + add-on + script)
```

### File map

```
bundles/org.openhab.core.energy/
├── pom.xml                     depends only on org.openhab.core + org.openhab.core.config.core
├── NOTICE  .classpath  .project
├── README.md                   the participant-model contract (was CONTRACT.md)
├── PROTOTYPE_REPORT.md         this file
└── src/
    ├── main/java/org/openhab/core/energy/
    │   ├── (26 files)          THE MODEL + ENGINE API — exported
    │   │   EnergyParticipant (sealed) · EnergyProvider · EnergyConsumer · ProviderRole
    │   │   PowerProfile (sealed) · SimpleProfile · ControllableProfile · ModeControllableProfile
    │   │   BatchProfile · LoadCurve · Demand · Deadline (sealed) · EnergyLevel · LevelGate
    │   │   ControlAction (sealed) · Decision · DecisionKind · ElectricalLimits · ParticipantState
    │   │   EnergyContext · EnergyAlgorithm · EnergyAlgorithmRegistry · ActuationSink
    │   │   PrecedenceStrategy · ModelChecks · package-info
    │   ├── spi/ (8)            DECLARATION SPI — exported
    │   │   EnergyParticipantSource · AbstractEnergyParticipantSource · ParticipantDeclaration
    │   │   DeclarationOrigin · EnergyParticipantContributor · EnergyParticipantRegistry
    │   │   ParticipantSnapshotSource · package-info
    │   ├── level/ (15)         LEVEL PLANE API — exported
    │   │   PriceSlot · PriceSeries · PlannedLevelSchedule · SlotSelection · LevelCounts
    │   │   LevelPercentiles · LevelDerivation · SeasonalParameters · SelectionStrategy
    │   │   SurplusEscalationPolicy · CurrentLevelResolver · CurrentLevelSource
    │   │   PlannedLevelPublisher · SgReadyMode · package-info
    │   ├── level/internal/ (10)   FixedCount/Percentile/Seasonal derivations · CheapestSlots and
    │   │                          ConsecutiveWindow selections · Maximum/Graded escalations ·
    │   │                          LevelSchedules · SurplusChecks · package-info
    │   ├── internal/ (32)      THE ENGINE
    │   │   EnergyEngine · EnergyEngineConfiguration · EnergyContextFactory · EvaluationPass
    │   │   ElectricalLimitFloor · PowerEstimator · UnknownDemandPolicy · EngineUnits
    │   │   CycleOutcome · DecisionOutcome · DecisionStatus
    │   │   ActuationGate + Separate/Unified · AcknowledgementTracker + Engine/Adapter
    │   │   ParticipantGuard + AlgorithmOwned/EngineEnforced
    │   │   LimitsFirst/ProtectionsFirstPrecedenceStrategy
    │   │   ItemStateReader + RegistryItemStateReader · RegistryParticipantSnapshotSource
    │   │   PlannedLevelSource · CurrentLevelSource adapters · LoggingActuationSink
    │   │   DefaultSurplusAlgorithm · DeviceProtectionAlgorithm · EnergyParticipantRegistryImpl
    │   │   package-info
    │   └── internal/metadata/ (3)  EnergyMetadataParser · MetadataParticipantSource · package-info
    ├── main/resources/OH-INF/config/energy.xml     3 config descriptions, ~35 parameters
    └── test/
        ├── java/ (21 classes, 185 tests)
        └── resources/fixtures/  4 CSVs copied verbatim from the corpus + a provenance README
```

94 main sources (11,302 lines), 21 test sources (4,620 lines). Six packages, each with a
`package-info.java`. Nine DS components. Three exported API packages; three `.internal` packages
private.

Two files outside the bundle were touched, one line each — `bundles/pom.xml`,
`bom/openhab-core/pom.xml` — plus a five-line opt-in feature in
`features/karaf/openhab-core/src/main/feature/feature.xml`.

### What changed after the adversarial review

This report describes the state *after* a review pass that found real defects. They are listed here
because a prototype that hides its own bug history is worth less than one that shows it.

| Found | Fix |
|---|---|
| The extension seams (`ParticipantSnapshotSource`, `CurrentLevelSource`, the script registration entry point, the level-plan entry point) were all in `Private-Package`. **The seams the slice exists to shape were unreachable from outside the bundle.** | Moved to `spi` / `level`; added exported `EnergyAlgorithmRegistry` and `PlannedLevelPublisher`; the engine and the level source are registered under them. |
| A master stop engaged at runtime was **silently released by any unrelated configuration edit or service binding**. | Runtime engagement of stop/shadow now survives until the operator changes it again, or until the corresponding configuration value itself changes. Four tests. |
| The `never` gate and the readiness interlock were enforced **only inside the replaceable algorithm**, so a contributed algorithm could steer a device its owner marked hands-off. | New `ParticipantGuard` seam with both readings of who owns them; engine-enforced by default. Eight tests. |
| The four Simple protection parameters were **modelled and parsed but enforced nowhere**, which also made the precedence seam unreachable in a real cycle. | `DeviceProtectionAlgorithm`, a core-shipped algorithm on the ordinary whiteboard. Nine tests, including the two requirement scenarios that previously had none. |
| Choosing the unified stop/shadow reading **silently suppressed the "would have applied" log line** — the entire observable evidence for the shadow requirement. | Gates now declare whether they distinguish stop from shadow; the line is emitted under both readings, and a stopped engine says what it would have done too. |
| The actuation sink was selected **by DS binding accident**, with no user selection. | A sink is used only when an operator names it in configuration. Until then the engine logs. Four tests. |
| An unrelated configuration change **discarded the acknowledgement state**, so the next cycle could re-send a command still in flight. | The rebuilt tracker adopts the outstanding commands of the one it replaces. |
| The bundle was registered in the **mandatory** `openhab-core-base` feature: every installation built from this checkout would start the engine and tick every 60 s with no opt-in. | Moved into its own opt-in `openhab-core-energy` feature. |
| Three `configurationPid`s with **no configuration description at all** — ~35 parameters invisible in the UI, for a prototype whose whole argument is "the choice is configuration, not code". | `OH-INF/config/energy.xml`, `@ConfigurableService` on all three components. |
| Missing from the BOM add-ons compile against. | Added. |
| An unreadable `profile=` in metadata fell back to Simple, i.e. a typo could turn a Batch programme into something the engine believes it may switch at will. | Rejected, consistent with the parser's own stated failure policy. |
| Two PMD findings from hand-rolled logback interception in a test. | Uses `JavaTest.setupInterceptedLogger`, which asserts the exact rendered line per decision — stronger than the line-counting it replaced. |
| Log lines at INFO addressed maintainers ("these are open design questions…") and fired on every service bind. | Demoted to debug; one operator-facing INFO on activation. |
| `this` escaped the constructor into the shared scheduler. | Scheduling moved to `@Activate`. |
| Read-modify-write race on the volatile configuration. | Guarded by a lock. |

---

## 3. Definition of done

From `docs/PROTOTYPE_TRACK.md`. Every claim below has a command or a file behind it.

### ☑ Builds inside a real `openhab-core` checkout with static analysis clean

```
mvn -pl bundles/org.openhab.core.energy -am clean install
  openHAB Core ....................................... SUCCESS [ 30.173 s]
  openHAB Core :: BOM ................................ SUCCESS [  0.249 s]
  openHAB Core :: BOM :: Compile ..................... SUCCESS [  3.912 s]
  openHAB Core :: BOM :: Test ........................ SUCCESS [  1.329 s]
  openHAB Core :: Bundles ............................ SUCCESS [  9.281 s]
  openHAB Core :: Bundles :: Core .................... SUCCESS [04:32 min]
  openHAB Core :: Bundles :: Test .................... SUCCESS [ 13.313 s]
  openHAB Core :: Bundles :: Configuration Core ...... SUCCESS [ 43.213 s]
  openHAB Core :: Bundles :: Energy Management ....... SUCCESS [ 28.844 s]
  BUILD SUCCESS — 07:16 min

mvn -pl features/karaf/openhab-core clean install     BUILD SUCCESS  (02:21 min)
mvn -pl bundles/org.openhab.core.energy javadoc:javadoc   BUILD SUCCESS, no warnings
```

Static analysis, from `target/code-analysis/report.html` of the energy module: **checkstyle 0, PMD 0,
SpotBugs 0**. Compiler: **zero warnings from any file in the bundle**. Spotless: clean over all 115
files (verified with the cache deleted, so the files were actually checked and not skipped).

Registration is complete on all three surfaces the openHAB review checklist names: `bundles/pom.xml`,
the Karaf feature, and `bom/openhab-core/pom.xml`.

**One environmental caveat, stated because it will bite the next person.** A *warm* rebuild
(`install` without `clean`) fails with three false SAT findings — `src//pom.xml Missing pom.xml
file`, `src/null/pom.xml Missing /project/version`, `…artifactId`. This is maven-checkstyle-plugin's
incremental cache making the module-level checks see an empty fileset. It reproduces identically on
the pristine, untouched `org.openhab.core` bundle, so it is not caused by this code; deleting
`target/checkstyle-cachefile` alone makes the identical build pass. Always build with `clean`, which
is what CI does anyway.

### ☑ Unit tests green, one test per implemented scenario

**185 tests, 0 failures, 0 errors, 0 skipped**, in 21 classes.

| Class | Tests | | Class | Tests |
|---|---:|---|---|---:|
| `EnergyMetadataParserTest` | 29 | | `SlotSpacingAndTieBreakTest` | 10 |
| `EngineControlsTest` | 15 | | `ProgrammaticParticipantSourceTest` | 9 |
| `EnergyEngineCycleTest` | 12 | | `DeviceProtectionAlgorithmTest` | 9 |
| `ParticipantModelTest` | 12 | | `EnergyLevelScenarioTest` | 8 |
| `MetadataParticipantSourceTest` | 11 | | `LevelPlaneWiringTest` | 8 |
| `EnergyParticipantRegistryImplTest` | 11 | | `ScriptAlgorithmProofTest` | 8 |
| `ElectricalLimitFloorTest` | 10 | | `ParticipantGuardTest` | 8 |
| `ShadowModeDemonstrationTest` | 7 | | `EngineAcknowledgementTrackerTest` | 6 |
| `EvaluationPassTest` | 5 | | `LevelFixtureConformanceTest` | 5 |
| `BudgetFixtureConformanceTest` | 2 | | | |

**Scenario coverage: 37 of the 40 wave-1 `#### Scenario:` blocks have a directly corresponding
behavioural test.** The three that do not are all in `define-extension-points` and all belong to
capabilities that are out of the wave-1 slice: *Price binding installed*, *Two price sources one
composition*, *Forecast source removed mid-day*. There is no price plane and no forecast plane, so
those scenarios are covered only *by analogue* on the declaration plane — a source appearing at
runtime, a source being selected out, a shadowed declaration taking over when its source disappears.
The mechanism is proven; the price and forecast instances are not, and calling that "covered" would
be dishonest. (*Two price sources, one composition* additionally has no code path at all: the
prototype can select between contributors but cannot **compose** two data series, and nothing in
wave 1 needed to.)

The two scenarios an earlier draft of this prototype could not test — *Fridge duty-cycle guarantee*
and *Cooldown respected* — now have tests, because `DeviceProtectionAlgorithm` exists. Doing that
required deciding where "time since the last state change" comes from, which is inside the open
question `define-engine-contract/design.md` §4; the choice is confined to one defaulted method
(`ItemStateReader.lastChange`), flagged in the JavaDoc of both `ParticipantState` and the algorithm,
and recorded as spec defect **N3** below.

### ☑ All four fixture vectors reproduced exactly

The CSVs in `src/test/resources/fixtures/` are **byte-identical** to the corpus copies (verified with
`diff` after every change; a provenance README sits beside them).

| Vector | Test | Result |
|---|---|---|
| `dayahead-prices.csv` → `expected-planned-levels.csv` (24 slots; timestamp **and** code asserted per slot) | `LevelFixtureConformanceTest#fixedCountDerivationReproducesThePlannedLevelFixture` | **PASS** |
| 8 cheapest slots → `expected-heating-control.csv` (24 ON/OFF flags) | `#cheapestSlotsSelectionReproducesTheHeatingFixture` | **PASS** |
| next 3 excluding heating → `expected-boiler-control.csv` (24 flags + explicit zero-overlap check) | `#cheapestSlotsSelectionReproducesTheBoilerFixtureWithoutOverlappingHeating` | **PASS** |
| percentile derivation → same planned-level vector | `#percentileDerivationAgreesWithFixedCountsOnTheTieFreeFixture` | **PASS** |
| heating vector == union of the two cheap bands (the corpus README's own consistency claim) | `#theHeatingFixtureIsExactlyTheTwoCheapBandsOfTheLevelFixture` | **PASS** |
| the fixture plan is the plan the engine acts on | `LevelPlaneWiringTest#theFixturePlanIsWhatTheEngineActsOn` | **PASS** |
| the fixture plan dispatches unchanged through the limit floor, no false trims | `BudgetFixtureConformanceTest` | **PASS** |

The assertions are non-vacuous by construction: each asserts the slot **count** before looping, then
compares every slot including the OFF ones; there is no `closeTo`, no epsilon and no rounding
anywhere in the fixture assertions. This was mutation-proven during verification — changing one
expected level made 2 tests fail; changing one *input* price made all 5 conformance tests fail plus
the engine wiring test, which is what proves the levels are genuinely derived and not hardcoded.

**Honest scoping note on vector 3.** `expected-boiler-control.csv` is reproduced exactly by the level
plane. It is *replayed* unchanged through the electrical-limit floor, but the floor does not
*re-derive* it from a naive plan, because doing so requires look-ahead rescheduling — that is
`grid-constraints` (wave 3), not engine-contract. The test's JavaDoc says so rather than implying more.

### ☑ Shadow-mode demonstration: decisions computed and logged, zero writes

`ShadowModeDemonstrationTest` (7 tests) builds a synthetic site — grid, PV, a controllable battery,
a Simple boiler, a Controllable wallbox — declared through the *real* wiring (contributor → source →
registry → snapshot source → engine) and runs six cycles. Three independent witnesses:

1. **Behavioural** — every outcome is `SHADOWED`, the sink recorded nothing.
2. **Observable** — the engine's actual log output is intercepted and the exact line
   `Shadow mode: would have applied <decision>` is asserted *per shadowed decision*, including the
   reasoning it carries (`… - surplus covers its threshold of 2000 W`).
3. **Structural** — a comment-stripped scan of all main sources for `sendCommand`, `postUpdate`,
   `EventPublisher`, `ItemEventFactory`, `setState(`, `send(` finds nothing, and asserts that exactly
   three files touch `org.openhab.core.items.*` at all, all read-only.

Beyond the test, the bundle's full non-`java.*` import closure is twelve packages: `javax.measure*`,
`org.eclipse.jdt.annotation`, `org.osgi.*`, `org.slf4j`, and
`org.openhab.core.{common, common.registry, config.core, items, library.types, library.unit, types, util}`.
Without `EventPublisher` or `GenericItem` on the classpath there is no API in this bundle capable of
producing a command or a state update. Shadow mode is a label on something that is already
structurally true.

### ☑ Zero open questions resolved; every seam documented

See §4 — every framed choice has both (or all three) implementations present and selected by
configuration, and the two decisions that could not be reduced to a seam are named as decisions.

### ☑ A written report

This document.

### ☑ Nothing public without the owner's go

No commit, no push, no PR, no comment anywhere. `git status` in the core checkout shows exactly three
modified files (the two registrations and the feature) plus the untracked bundle directory. The
corpus repository is byte-clean and untouched.

---

## 4. How the maintainer decisions were kept open

Seventeen places where a decision was unavoidable. In each case the prototype either ships every
framed answer behind an interface and chooses by configuration, or — where that was impossible —
names the decision at the point where it is made.

### The two the brief singled out

**1 · Declaration mechanism** (`define-participant-model` §1 / `define-extension-points` §1) — *item
metadata, a description-provider SPI, a new add-on type, or scripts?*

- Seam: `spi/EnergyParticipantSource.java` (extends core's `Provider`), `spi/ParticipantDeclaration.java`,
  `spi/AbstractEnergyParticipantSource.java`.
- Implementation (a): `internal/metadata/EnergyMetadataParser.java` + `MetadataParticipantSource.java`
  (source id `metadata`, a pure function from `(itemName, value, config)` to a participant).
- Implementation (b): `internal/ProgrammaticParticipantSource.java` + `spi/EnergyParticipantContributor.java`
  (source id `programmatic`).
- Choice: `sources` on pid `org.openhab.core.energy.declaration`
  (`internal/EnergyParticipantRegistryImpl.java`). Setting it to one id runs the whole site on a
  single mechanism, which is how each candidate can be evaluated alone.
- **COLLAPSED (D5).** The `precedence` parameter is gone, and with it
  `EnergyParticipantRegistryImplTest#configuredPrecedenceOverridesTheOriginFallback`, whose whole
  point was that flipping the configuration flipped which mechanism won. The chain is now fixed in
  code — explicit metadata > contributed > discovered, ties between contributed statements broken by
  `service.ranking`, source id last — because the requirement demands that registering two
  contributors in the opposite order after a restart produce the same participant, which a
  site-editable ordering cannot guarantee. **A site can no longer decide that a contributed
  declaration outranks its own metadata**; that is a real reduction in flexibility and it is the one
  a reviewer should weigh. What replaced the test:
  `#tiesBetweenContributedDeclarationsBreakOnServiceRanking`,
  `#serviceRankingNeverLiftsAContributionOverExplicitMetadata`,
  `#aSecondDeclarationOfOneIdentityIsAFurtherStatementAndNeverAnError` and
  `#aContributorThatLeavesAndComesBackRestoresTheSameOutcome`.
- Nothing above the seam knows a mechanism exists. `EnergyEngine` sees `ParticipantSnapshotSource`
  and nothing else; the adapter between them is 20 lines.

**2 · Device protections versus the electrical-limit floor** (`define-engine-contract/design.md` §5)

- Seam: `PrecedenceStrategy.java` (exported, contributable as an OSGi service).
- `internal/LimitsFirstPrecedenceStrategy.java` — the ladder the design note proposes, everything
  trimmable.
- `internal/ProtectionsFirstPrecedenceStrategy.java` — protections untrimmable, admitted past the
  budget with a `WARN` so it is never silent.
- Choice: `precedenceStrategy`. A third party can contribute a third.
- Proof it is open: `ElectricalLimitFloorTest` asserts the same input produces opposite outcomes
  under the two strategies. **And, since this revision, the collision is reachable in a real cycle** —
  `DeviceProtectionAlgorithmTest#theCooldownDecisionOutranksAnOptimizerThatWantsToStartTheCompressor`
  goes through the whole spine. Before `DeviceProtectionAlgorithm` existed, no production code path
  ever produced a `DEVICE_PROTECTION` decision, so this seam was real in the API and unreachable in
  practice. That was the single most misleading thing about the earlier draft.

### The rest

**3 · Master stop and shadow mode: one control or two?** (`engine-contract/design.md` §2) —
`internal/ActuationGate.java` + `SeparateActuationGate` / `UnifiedActuationGate`, chosen by `gate`.
Layered per-algorithm and per-participant shadow sets collapse to global-only when empty, which keeps
the second half of the question open by configuration. Gates declare
`distinguishesStopFromShadow()`, so choosing the unified reading no longer costs the shadow log line.

**4 · Acknowledgement window: engine or adapter?** (§3) — `internal/AcknowledgementTracker.java` +
`EngineAcknowledgementTracker` / `AdapterAcknowledgementTracker` (a deliberate no-op), chosen by
`ackHandling`; plus `ActuationSink.tracksAcknowledgements()` so a sink can say it polices repeats itself.

**5 · Cadence: fixed tick, or tick plus events?** (§1) — `cycleInterval` and `eventResponsive`;
`EnergyEngine.triggerEvaluation()` is the entry point an event-driven re-evaluation would use, it is
debounced, and **it subscribes to nothing**. Whether and what to subscribe to stays a maintainer decision.

**6 · Snapshot content** (§4) — `EnergyContext` documents in its own JavaDoc that it covers the wave-1
half only and that prices and forecasts are deliberately absent, not stubbed. The one place this
question was forced (elapsed time for protections) is a defaulted method on `ItemStateReader`.

**7 · Who enforces the "never" gate and the readiness interlock?** — `internal/ParticipantGuard.java`
+ `AlgorithmOwnedParticipantGuard` / `EngineEnforcedParticipantGuard`, chosen by `participantGuard`.
This seam exists because the corpus genuinely contradicts itself: `define-participant-model` states
both prohibitions in engine language, while `define-engine-contract`'s *Replaceable algorithm*
requirement enumerates what stays engine-owned and omits both. Engine-enforced is the default because
it is the safer reading, and the engine logs which is active. Both readings are tested.

**8 · Level derivation** (`define-energy-levels` task 2.1) — `level/LevelDerivation.java` with
`fixedCounts` / `percentiles` / `seasonal`; the interface itself has no default. A finding that
narrows this question is in §5 (**L12**).

**9 · PV escalation magnitude** — `level/SurplusEscalationPolicy.java` with `none` /
`maximumOnSurplus` / `graded`. Every factory *demands* its thresholds from the caller, so no number is
invented. Default is `none`, which means **the PV-escalation requirement is inert out of the box** —
stated plainly in §6 rather than buried.

**10 · Window strategy** — `level/SelectionStrategy.java` with `cheapestSlots` and
`consecutiveWindow`, both first-class because the requirement asks for both.
`EnergyLevelScenarioTest` asserts the two **disagree** on 15-minute data, so the scenario cannot be
satisfied trivially by either.

**11 · Re-plan semantics** (task 2.2) — `PlannedLevelSchedule` deliberately offers **no** merge
operation. A re-plan is a new immutable instance. The question is left where it belongs.

> **The twelve seams are not all equally open, and the difference is not stated anywhere else.**
> `PrecedenceStrategy` (2), `LevelDerivation` (8), `SurplusEscalationPolicy` (9) and
> `SelectionStrategy` (10) are **exported**, so a third party can contribute a thirteenth answer
> without touching core. `ActuationGate` (3), `AcknowledgementTracker` (4) and `ParticipantGuard`
> (7) are **internal**: both framed answers are selectable by configuration and both are tested, but
> only the two that ship can ever be evaluated. That is deliberate — those three are questions about
> what the engine itself guarantees, and a contributed answer to "who enforces the never gate" would
> be a contributed hole in the guarantee — but it does mean a maintainer who wants to try a *third*
> reading of 3, 4 or 7 has to edit this bundle, where for 2, 8, 9 and 10 they do not.

**12 · Level outside the plan** — before prices arrive, after the plan ends, inside a gap. The corpus
does not say. `CurrentLevelResolver` answers `Optional.empty()`; a value is needed only because the
engine's seam is total, and `levelOutsidePlan` supplies it. `NORMAL` is the default because it is
exactly what the engine already does with no level source bound, so installing the level component is
behaviour-neutral until something publishes a plan.

**13 · Are actuation adapters contributable, and how does a site choose one?**
(`extension-points/design.md` §3, §4) — `ActuationSink` is exported and bound MULTIPLE/DYNAMIC, and a
sink is used **only when an operator names it** in `actuationSink`. Nothing can become a site's
writer by winning a binding race. Until named, the engine logs.

**14 · Unknown demand at the limit floor** — `internal/UnknownDemandPolicy.java`
(`assume-zero` / `defer`), chosen by `unknownDemandPolicy`. This one is a knob for a gap rather than a
seam between two framed answers, because the corpus frames nothing here (see **E5**).

**15 · Declaration precedence when nobody configured one** — **CLOSED (D5), and the seam with it.**
The extrapolation the prototype flagged (ranking `EXPLICIT` above `CONTRIBUTED` on the strength of a
wave-4 statement about *discovered* proposals) is now the corpus's own rule, stated for contributed
declarations too and extended with a `service.ranking` tie-break and a `DISCOVERED` rank the chain
reserves for wave 4. It is no longer overridable by configuration. See **B9**.

### The two that are decisions, not seams — stated as such

**16 · Site-global level, or site plus per-domain?** (`define-energy-levels` task 1.2) —
**resolved as site-global, in code, and it could not be otherwise without inventing a domain model.**
The classifier is per-series and stateless, so either answer can be built on top of it; but every
consumer above it is singular — `CurrentLevelSource` returns one `EnergyLevel`, `EnergyContext`
carries one `level()`, `LevelGate.permits` takes one. A per-domain answer makes `CurrentLevelSource` a
keyed lookup, i.e. **a signature change, not a preference**. Aggravating factor for the corpus:
`define-energy-levels` is the only wave-1 change with **no `design.md` at all**, so its open questions
live in `tasks.md` where the prototype rules never point.

**17 · Does this belong in core, and if so always-on?** (`define-participant-model/design.md` §2 /
task 1.3) — the design note does propose core, so being a core bundle is defensible. Being in the
*mandatory* base feature is a further step the corpus never discusses, and it was the one open
question this prototype originally answered entirely outside Java. It now ships as an **opt-in**
`openhab-core-energy` Karaf feature: an installation that never installs it is byte-for-byte the
installation it was before. That is still a choice, and it is named here.

---

## 5. Spec defects found

This is the section worth reading. Every item is something that was hit while building, with the
concrete corpus change it implies. Grouped by change, with cross-cutting items last. Items are
carried forward from the whole build under their original identifiers so they can be tracked.

### 5.1 `define-participant-model`

**A3 — the demand shape does not cover its own examples.** *Demand declaration* states an
energy-amount plus a deadline, then gives *"charged to 80 % by noon"* (a state-of-charge target) and
*"run 5 h within the next 12 h"* (a runtime demand inside a window). Neither is expressible in the
shape the requirement itself defines. **Change:** either add SoC-target and runtime-in-window demand
kinds, or drop the two examples and say the shape is energy+deadline only.

**A1 — the level gate's scope contradicts its cited source.** The requirement scopes "run at level ≥
N" to **Simple** consumers; the taxonomy it cites scopes it per consumer. The `never` value —
"devices the engine must leave alone" — is the one that most obviously wants to apply to all four
classes: as written, a Batch dishwasher or a Controllable wallbox **cannot be marked hands-off at
all**. **Change:** move the gate (or at least `never`) onto `EnergyConsumer`, not onto
`SimpleProfile`. Related: **F13**.

**A4 — priority has no direction, default or tie-break.** The requirement's own source note says
"lower runs first, higher wins on conflict", which points both ways in one sentence. Only the
fixtures disambiguate it (lower = better). **Change:** state the direction in prose, state a default,
and state the tie-break. A requirement that only a CSV can settle should say so.

**A5 — may a non-battery provider be controllable?** The requirement fixes a signed clamp for
batteries and says nothing about a curtailable PV inverter, which is a real device class.

**A6 — the level→mode mapping is required to exist and never specified.** ModeControllable consumers
must map site level onto *n* ordered modes "without translation logic in user rules", but no mapping
rule is given for any *n* other than 4.

**A7 — is a load curve a property of the demand or of the programme?** Both readings are in the text.

**A8 — load curves are unbounded and unspaced.** No maximum length, no required sample spacing, no
statement of whether samples are instantaneous power or interval averages.

**A9 — power-versus-current asymmetry.** `ControllableProfile` is called a *power* profile and its own
scenario is "min 6 A and max 32 A"; provider clamps are specified in power. The prototype accepts
either dimension for consumers and power only for providers, which is the spec's asymmetry, not a
design choice. **Change:** say that a controllable bound may be power **or** current and that the
conversion is the adapter's business — or mandate power and make the wallbox scenario say so.

**A10 — naming.** `EnergyConsumer`/`EnergyProvider` versus `DemandDescription`; already flagged by a
maintainer, untouched here because it is a naming pass, not a fork.

**A11 — level names differ between the spec (`blocked`/`normal`/`encouraged`/`overcapacity`) and the
taxonomy it cites (`restricted`/`normal`/`encouraged`/`maximum`).**

**A12 — participant identity is undefined exactly where it matters most.** The registry merges
statements from several sources about "the same participant", which only works if the id is
well-defined. The prototype derives it from the item name with an optional override — but **nothing
tells a binding which id the user's metadata used**, so two mechanisms agreeing on an id is a
coincidence, not a contract. This blocks "explicit wins" from working in practice. **Change:** define
participant identity normatively; it is the keystone of the whole extension story.

**E2 — the participant model carries no phase declaration, but a requirement's scenario says it
does.** `define-engine-contract`'s *Phase-aware headroom* scenario opens "a consumer that declares
which phase(s) it draws on" — and nothing in `define-participant-model` can declare a phase. The
prototype carries the assignment in *engine configuration* (`phases=wallbox=1,2,3`) purely to make the
scenario testable. **Change:** whichever declaration mechanism wins needs a phase attribute; say so in
the participant model.

**E3 — per-phase headroom also needs per-phase *measurement*.** Providers declare a single aggregate
power Item, so the snapshot's per-phase uncontrolled load is always empty and the floor can only
constrain what it dispatches itself. **Change:** a per-phase provider shape, or an explicit statement
that per-phase enforcement covers controlled load only.

**E4 — a Simple consumer has no rated power.** It has `onThreshold`, "typically rated power". A budget
check needs a rating, so the threshold is used as one. If a user sets the threshold with margin, the
budget math is quietly wrong. **Change:** add an explicit rating, or state that the threshold is the
rating.

**N1 (new) — the per-consumer measurement Item is used by three requirements and declared by none.**
`measureItemName` is load-bearing in this prototype: the surplus algorithm, the power estimator and
the staleness gate all read it. The only corpus hook is `extension-points`' "a live power measurement
that feeds the electrical-limit floor", which never says *per consumer*. **Change:** state whether a
consumer may declare its own measurement, and whether the engine should prefer it over an estimate.

**N2 (new) — `minOn` "doubles as catch-up time after a forced restart" is specified and
unimplementable.** Nothing defines what a forced restart is, or how the engine would recognise one
(an external actor changed the Item? a power cut? openHAB restarted?). **Change:** define it or delete
the clause.

### 5.2 `define-engine-contract`

**E1 — "the higher-priority decision is applied" never says whether higher priority is a larger or a
smaller number.** Same defect as **A4**, now in the public API of the engine.

**E5 — a ModeControllable mode has no power semantics at all**, so no budget can be enforced against
a mode change. This forced the `unknownDemandPolicy` seam. **Change:** let a mode carry an expected
draw, or state that mode changes are exempt from the budget.

**E6 — the sign convention is fixed only for the grid reading.** The sign of a controllable
provider's *setpoint* and of the *battery* reading are both unstated, yet both are needed for site-load
math. The prototype infers positive = supply/discharge and flags it in `PowerEstimator`. **Change:**
state the convention for every role and for setpoints.

**E7 — "surplus" is never defined** beyond being "derived from" grid export. Does a charging battery's
power count as available surplus? Curtailed PV? The prototype uses grid export only.

**E8 — "unacknowledged" is never made operational.** No window length (a never-echoing device would
block forever), no comparison tolerance (is 15.999 A an acknowledgement of 16 A?), and no rule for a
*different* command arriving while one is outstanding. Three configuration knobs now exist for one
sentence of spec. **Change:** specify a default window, a tolerance, and the changed-command rule.

**E9 — the shadow default and the master-stop default are the same statement under one reading.**
"Shadow on at first install" ≡ "stopped" if the two are one control, which the design note offers as a
live option. The requirements do not acknowledge the overlap.

**E10 — "disengaging the stop resumes normal operation"** does not say whether the engine resumes *as
if it never stopped* or *from a clean slate*. The prototype resets the acknowledgement window on stop.
That is a choice, not a reading.

**N4 (new) — nothing says whether the master stop should also halt *evaluation*.** A stopped engine
here still invokes every contributed algorithm on every tick — and a contributed algorithm is
arbitrary third-party code with its own openHAB access. "A single master control SHALL stop all engine
actuation immediately" is satisfied for the engine's dispatch and not for what the tick sets running.
**Change:** say whether the stop halts evaluation too. Halting is both the safer and the cheaper
reading.

**N5 (new) — nothing says what a protection should do while the stop is engaged.** A duty-cycle
guarantee is a device-safety statement; a master stop is an operator-safety statement. The prototype
subordinates protections to the stop (nothing is dispatched, full stop). A maintainer might reasonably
want the opposite for a fridge.

**E11 — no default cadence and no event story.** 60 s was chosen; the design note records only "possibly
up to every minute".

**E12 — "goes stale" and "conservative safe state" are both undefined** — no age, no numeric floor
("floor levels or pause"). Implemented as: an unreadable grid reading is stale (plus an optional age),
and safe means refuse increases, allow reductions. Note that **age-based staleness is off by default**
because no age is specified (§6).

**E13 — "trimmed or deferred" never says which, when.** A `Switch` cannot be trimmed at all; a
continuous load that partly fits could go either way. The prototype trims to the boundary unless that
breaks the declared minimum, then defers.

**E14 — enforce the budget against estimates or against measurements?** The model says commands are
envelopes, not orders (`measureItemName` exists precisely for that), but the floor must book
*estimates* to allocate headroom within a cycle. Nothing says how the error is corrected.

**E15 — nothing says what happens to a decision addressing an unknown participant** (a typo in a
script). The prototype rejects it and logs.

**E17 — "the same consistent context snapshot" and nothing more.** The minimum content lives only in
`design.md` §4, and nothing says whether an algorithm may retain a snapshot beyond its cycle.

**E18 — the shadow output has no defined surface.** At a one-minute tick, "decisions appear in the
logs" means the same decision is logged for hours. Whether shadow output should be deduplicated, or
emitted as events, or persisted for the *comparison* the requirement's own scenario implies, is
unstated. `CycleOutcome` exists so a future UI has something structured to read.

**N3 (new) — the protection durations have no stated source of elapsed time.** `minOn`/`maxOn`/
`minOff`/`maxOff` are all measured from the last state change, and nothing says whether the engine
learns that from the Item's own history, from persistence, or from its own memory — each of which
behaves differently across an openHAB restart. This is `design.md` §4 made concrete. The prototype
reads the Item's last state change and treats "unknown" as "cannot prove a violation". **Change:** name
the source; it determines whether protections survive a restart.

**N6 (new) — the corpus has no vocabulary for what became of a decision.** There is no notion of a
decision outcome — applied, superseded, deferred, suppressed, withheld, stopped — yet every one of
those is user-visible behaviour, and *Side-by-side validation* asks a user to compare. The prototype
invented `DecisionStatus` with seven values. **Change:** define the outcome vocabulary, or accept
whatever the first implementation invents becoming the API.

**N7 (new) — "engine-owned" is enumerated once and the enumeration is incomplete.** *Replaceable
algorithm* says evaluation, limits, shadow, stop and actuation stay engine-owned. It omits the level
gate and the readiness interlock, which `define-participant-model` states in engine language. That
omission is the whole reason seam **7** exists. **Change:** make the enumeration exhaustive and
authoritative — it is the security boundary of the entire extension story.

**E16 — `fixtures/README.md` attributes `expected-boiler-control.csv` to `grid-constraints` *and* to
engine-contract's conflict resolution**, but reproducing that file needs look-ahead rescheduling,
which no engine-contract requirement describes. A runtime floor can defer; it cannot move a load to a
cheaper hour. **Change:** attribute the file solely to `grid-constraints`, or add a
deferral→replanning feedback requirement to engine-contract.

**L18 — the boiler vector does not exercise the power budget it is filed under.** It is attributed to
a 10 kW budget with 9 kW + 3 kW loads, yet it reproduces exactly as "next 3 cheapest, excluding the
heating slots". **A naive exclusion scheduler and a real budget scheduler are indistinguishable on
this vector.** **Change:** add a vector where the budget binds non-trivially — two loads that *could*
share a slot under the budget, or a third load that only partially fits.

### 5.3 `define-energy-levels`

**L1 — the level→number mapping exists only in a CSV.** The prose never numbers the four levels;
`expected-planned-levels.csv` encodes 3 = cheapest … 0 = dearest. That is the **opposite** direction
from `priority`, where lower = better. Wave 1 therefore ships two ordinal scales pointing opposite
ways, neither stated in prose.

**L2 — the SG-ready offset is unstated.** SG-ready modes are 1–4 and level codes are 0–3, so
`mode = code + 1`; the requirement promises a mapping "without translation logic in user rules" and
never pins the offset. A reader who saw only the CSV would publish 0–3 to an SG-ready channel. (Also:
SG-ready mode 1's legal daily time cap appears nowhere in the corpus.)

**L3 — no tie-break is defined.** `fixtures/README.md` itself says one is needed and that this dataset
has none. Real markets repeat prices constantly (zero and negative hours), so this bites immediately.
The prototype ranks equal prices by earlier slot start, in one place.

**L12 (a finding that narrows task 2.1) — percentile and fixed-count derivation are *identical* on
tie-free data.** Proved on the corpus fixture with 1/6·1/6·1/6. They diverge **only on ties**: a
percentile threshold is a price and cannot split one; a count cuts the ranking wherever it lands. So
the open question is not "which algorithm" but "what happens to tied slots", plus adaptivity.
**Change:** reframe task 2.1 accordingly.

**L4 — "hours" versus "slots".** The requirement configures "4 overcapacity, 4 low-price and 4 blocked
**hours**" while another requirement demands independence from 60- and 15-minute resolution. On
15-minute data "4 hours" is either 4 slots or 16. The prototype counts slots and offers a converter
that *refuses* a mixed-width or non-whole-multiple series rather than inventing a rounding rule.

**L5 — counts that do not fit are undefined.** Routine on a partial day: day-ahead publishes around
13:00, so a live series is often 11 or 35 slots, not 24. The prototype clamps, cheap bands first.

**L6 — band precedence is unspecified even without overflow.** Three bands are described independently
over one ranking; only `sum ≤ n` keeps them disjoint.

**L13 — "consecutive" is undefined on real data.** Nothing says whether a series may be non-uniform or
gapped. The prototype makes contiguity a property of the data and treats a gap as breaking a window.

**L14 — comparing windows of unequal covered time is undefined** (arises with mixed widths).

**L15 — window start granularity is unstated.** The prototype starts windows on slot boundaries.

**L17 — under-supply behaviour is unstated and user-visible.** The prototype returns a *partial*
selection for non-consecutive requests (running in every cheap slot there is remains best) and
*nothing* for consecutive ones (an uninterrupted run either exists or does not). Both are guesses and
they differ. **Change:** one sentence in the requirement.

**L7 — "escalates" has no magnitude, no threshold and no target level.** Two readings are in the
sources; both ship, neither is defaulted, and the consequence is that the requirement is inert until
configured (§6).

**L8 — no hysteresis is specified.** "For the duration of the surplus" is a stateless function of the
instantaneous reading; on a partly cloudy day that makes the published site level chatter
minute-to-minute. Every production system needs a min-dwell or deadband.

**L9 — the current level outside the plan is undefined** (before prices, after the plan, inside a gap).

**L22 — rule-updatable hour counts have no re-derivation trigger.** Counts are "user-configurable,
including via rules", but nothing says whether changing them re-derives immediately, at midnight, or
on the next price arrival. Ties into the undecided re-plan semantics.

**L23 — the plan is described as "a future-timestamped TimeSeries" and the current level as "an
Item".** openHAB's `TimeSeries` is per-item and future-dated, so whether the plan rides on the *same*
item as the current level or a second one is unstated — and under shadow-only, nothing may be
published at all, which makes the point below sharper.

**L19 — price semantics are unstated:** currency, taxes and transfer fees, and above all **negative
prices**, which are real and increasing. Ordering-only code handles them, but "blocked = the most
expensive slots" is a strange thing to publish on a day when every price is negative.

**L16 — selection ignores the load's own shape.** "Cheapest N slots" is only correct for a flat load;
the corpus's own `BatchProfile.loadCurve` never interacts with window selection anywhere. Harmless for
these fixtures (both loads are flat), wrong for a dishwasher.

**L20/L21 — seasonal date and boundaries.** A delivery day straddles two local dates — this very
fixture starts 23:00 UTC the evening before — and the corpus never says which date names the day, nor
in which zone, nor when winter starts.

**L11 — the level names differ between the spec and the taxonomy it cites** (same as **A11**).

**Fixture format nit** — the CSVs carry slot *starts* only, so the last slot's end must be inferred
(the loader reuses the previous width and documents it). Explicit end timestamps, or a stated slot
width in the README, would remove the inference and make a future 15-minute vector unambiguous.

**N8 (new) — `define-energy-levels` has no `design.md`.** It is the only wave-1 change without one, so
its open questions (tasks 1.2, 2.1, 2.2) live where the prototype rules never point a reader. That is
how the site-global-versus-per-domain decision came within one review of being made silently.
**Change:** give it a `design.md`, even a short one.

### 5.4 `define-extension-points`

**B1 — the `energy:` namespace has no specification at all.** It is named in the sketch and cited by a
requirement, but no `spec.md` defines a single key, value grammar or type. **Every key name in the
parser is prototype invention that the corpus cannot validate.** **Change:** either write a normative
metadata schema, or say explicitly that the schema is out of core's scope.

**B2 — the key vocabulary cannot express three of its own requirements.** No consumer `min`/`max` (yet
"Wallbox as Controllable min 6 A max 32 A" is a scenario); no key for `consecutive` (yet
"Consecutive-hours demand" is a scenario); no key for the level gate (yet "Level-gated operation" is a
requirement). Four keys were added by inference.

**B3 — provider `price` and `schedule` have nowhere to land in wave 1.** Should a wave-1 mechanism
accept keys it cannot act on, or reject them? The prototype recognises and warns.

**B4 — units are unstated and the key vocabulary is internally inconsistent about them.**
`demandKwh`/`deadlineHour`/`ratedW`/`runtimeHours` bake the unit into the key name; `min`/`max`/
`onThreshold` must carry it in the value. Nothing says whether a bare `3000` is W or kW.
**Change:** openHAB has UoM — mandate quantity-typed values and drop unit-suffixed key names.

**B5 — deadlines are unreachable in their stated forms.** `deadlineHour` can only express a daily
deadline on the hour; an absolute one-off deadline and minute precision cannot be declared, and the
"run 5 h within the next 12 h" window has no expression at all. This is **A3** made concrete at the
mechanism boundary.

**B6 — no default profile class, and defaulting is safety-relevant.** An unrecognised `profile` treated
as Simple lets the engine believe it may switch what is really a Batch programme. This prototype now
*rejects* an unreadable profile and defaults only an *absent* one — a safety-conservative reading of a
silence, not a spec statement.

**B7 — no default priority, scale or direction.** The prototype's `100` is now user-visible behaviour
with no spec behind it.

**B8 — participant identity is undefined** (same as **A12**), and it is what makes cross-mechanism
precedence work or not work.

**B9 — "explicit declaration wins" is scoped to *discovery* only.** ~~The corpus states it against
*discovered proposals* (wave 4) and says nothing about explicit-versus-add-on-contributed.~~
**CLOSED (D5).** `extension-surface` *Deterministic resolution between contributors* now states the
whole chain, and the code implements it fixed rather than configured.

**B10 — "multiple contributors, user selection" names no selection mechanism and no home for it.**
`design.md` §4 admits this is open. `sources` stays on the configuration PID as the per-role
"which contributions take part" surface; `precedence` is gone, because *which one wins* is no longer
a selection (D5). Per-participant metadata, bridge-style configuration or a MainUI flow are all still
on the table for the selection that remains. **The one selection that is per-participant is the
actuation sink**, and that one is now declarable — `sinkId`, named and never ranked.

**B11 — failure semantics for a bad declaration are unspecified.** Skip the participant, or accept it
partially? The prototype skips loudly — the device keeps whatever automation the user already has —
but that means a typo in `minOff` silently un-manages a device. The corpus has no notion of a
"declaration in error" and no surface on which to show one.

**B12 — degradation has no reporting surface.** "The engine … reports the degraded source" — where? An
Item, an event, a REST resource? Also: the requirement is written for *data* contributors, and nothing
covers a *declaration* contributor vanishing, which is what actually happens when an add-on is
uninstalled.

**B13 — openHAB's own registry contract cannot express this requirement.** `AbstractRegistry` rejects a
second element with the same UID: first-come wins, with a warning — i.e. registration-order dependent,
the exact opposite of "explicit wins" and of deterministic resolution. The prototype keeps the core
pattern by keying declarations on `sourceId + participantId` and resolving on read. **Change:** decide
whether the EMS registry is not a plain core registry, or whether the core registry contract grows a
precedence notion.

**B14 — script contributors have no lifecycle hook.** ~~Nothing in openHAB reliably tells a script it
is being unloaded, so the two contribution paths have genuinely different lifecycle guarantees.~~
**RETRACTED (D18).** Core's script `providersupport` unload hook does exist — `design.md` §5.9 now
records it — so `contributorId` + `withdrawAll` is the intended shape and the paths are equivalent
after all.

**B15 — nothing says whether a declaration may name Items that do not exist.** **CLOSED (D17 · row
EP-5.10)** in the prototype's favour: an unresolved name is accepted at read time and is a *runtime*
condition, reported as a degraded source and bound without redeclaration if the Item appears later —
a different failure, with a different remedy, from an unreadable declaration. The declaration half is
tested (`MetadataParticipantSourceTest#aDeclarationNamingItemsThatDoNotExistIsStillAccepted`); the
runtime report belongs to the engine's observability surface.

**I6 — the algorithm plane has no ownership statement.** `registerAlgorithm(id, …)` replaces silently,
which a reloaded script needs, but it also lets one script take over another's id unnoticed. The
declaration plane resolves this deterministically; the algorithm plane says nothing.

**N11 (new, found while applying D5) — the programmatic source refused duplicates, which the
requirement forbids.** `ProgrammaticParticipantSource` claimed a participant id for the first
contributor to declare it and *refused* every later one (`declare` returned `false`, with a `warn`).
That is a duplicate treated as an error at a layer below the registry, and worse, it made the outcome
depend on who called first — the one thing *Deterministic resolution between contributors* forbids
("a restart that registers the two in the opposite order produces that same outcome"). It now keeps
every statement and chooses the effective one by **contributor id ascending**, so a shadowed
contributor becomes effective by itself when the other withdraws. The tie-break is a **stand-in for
`service.ranking`**, stated as one: every contribution through this service shares its single
ranking, and a user script has no ranking of its own to register at. A contributor that needs a real
ranking should register its own `EnergyParticipantSource`, at which point the registry's chain
applies to it in full.

**N9 (new) — actuation adapters have no selection story at all.** **CLOSED (D5's deliberate
exception).** The sink is chosen by explicit configuration naming it — one site-wide, with a
per-participant override the model now carries as `EnergyParticipant.sinkId()` — and never by service
ranking or registration order. Still outstanding on the engine side: honouring the per-participant
override at dispatch, and reporting "no sink named" as a configuration gap rather than as a `debug`
line.

**N10 (new) — "core-shipped defaults on equal terms" has no observable definition.** The requirement
says core defaults must work out of the box and be replaceable "on equal terms", but never says what
equal terms means: same registration mechanism? same priority range? displaceable by id? The prototype
uses the same DS whiteboard and allows displacement by id, and now tests both — but that is a reading.

### 5.5 Cross-cutting

**I1 — *PV escalation* and *Central periodic evaluation* are mutually recursive as written.** The
current level is a function of live surplus; the surplus is part of the one snapshot the engine
reasons about. Neither change mentions the other, and the corpus never says whether the site level is
an **input** the engine is handed or a **product** of its own readings. The prototype resolves the
level from the readings the snapshot already took, so the two can never disagree inside one cycle —
one method, reversible.

**I2 — the level plane is specified as *publishing Items*; the engine *consumes a level*.** Under
shadow-only nothing may be written, so publication cannot exist — meaning the only classifier→engine
path that can exist is in-process, and **the corpus describes none**. If maintainers intend the
coupling to run through the published Item, that is a materially different architecture.

**I3 — the seasonal derivation is not offered as configuration**, because the corpus defines no grammar
for season boundaries, no zone, and no rule for which local date names a straddling delivery day. It is
reachable programmatically. Nothing was invented.

**I5 — `package-info.java` with `@NonNullByDefault` is against the codebase's own convention.** The
brief asks for it; openhab-core contains **zero** `package-info.java` files outside this bundle and
annotates every type instead, and adding the annotation emits redundancy warnings under the reactor's
`-warn:+nullAnnotRedundant`. Every package here has a `package-info.java` carrying JavaDoc; the
annotation stays on types. This is a knowing deviation from the brief in favour of the real codebase,
reversible with one `sed`. (An earlier draft of this report called it "impossible"; that was an
overstatement — the compiler warns, it does not fail.)

---

## 6. What a reviewer should look at first — and the honest limitations

### Look at these five things, in this order

1. **`src/main/java/org/openhab/core/energy/` — the model.** Fourteen types, no OSGi, no I/O. If the
   modelling is wrong, nothing downstream matters. This is also where the maintainers' own stated
   first priority (consistency towards the user) lives.
2. **`internal/EnergyEngine.java#runCycleNow`** — forty lines that are the entire contract:
   snapshot → algorithms → conflicts → limits → guard → stop → shadow → acknowledgement → sink.
   Everything else in the engine package serves one of those steps.
3. **`ShadowModeDemonstrationTest`** — the shadow proof, with its three witnesses. Then
   `ScriptAlgorithmProofTest`, which registers a runtime `java.lang.reflect.Proxy` (the mechanism
   GraalJS uses to bind a script object to a Java interface) and pushes it through the same
   guardrails as the built-in algorithm.
4. **`level/LevelDerivation.java` and `LevelFixtureConformanceTest`** — the fixture gate. Break one
   input price and six tests fail; that is the property that makes this component trustworthy.
5. **§5 of this report.** The code is a means; the defect list is the product.

### Honest limitations

**Requirement statements are honoured better than requirement scenarios.** At statement level the
slice is complete. At scenario level, three of forty have no behavioural test because the capability
they belong to is not in wave 1 (§3).

**Two requirement-mandated behaviours ship disabled by default, and that is deliberate:**

- **PV escalation is off** (`escalation=none`). The requirement asks the level to escalate on surplus
  but never says by how much or from what threshold, and every policy here *demands* its thresholds
  from the caller rather than inventing one. Out of the box, the escalation requirement does nothing.
- **Age-based measurement staleness is off** (`staleAfter=0`). Only an *unreadable* safety
  measurement trips the safe state; a measurement that silently froze three hours ago does not,
  because no age is specified anywhere.

**The optimizer is a toy.** `DefaultSurplusAlgorithm` looks at one figure — current surplus — and
understands only the Simple class. It exists to prove the spine. Anything needing a plan (cheapest
hours, deadlines, load curves, batch scheduling) belongs to capabilities that are not built.

**Several model members are declared and inert.** `EnergyProvider.socItemName` is required to exist by
the *Controllable providers* requirement and is read by nothing; `Demand.consecutive()` is read by no
main source (the consecutive-window strategy exists but is not wired to a demand, because nothing
schedules demands yet); `ControlAction.Hold` is consumed but produced by nothing. These are honest
scaffolding for wave 2+, not working features.

**No batch protection.** A Batch consumer's "never interrupt before completion" is modelled and
enforced nowhere. `DeviceProtectionAlgorithm` covers the Simple class only, because that is the class
the protection parameters belong to.

**Phase-aware headroom is half-real.** The engine can constrain what *it* dispatches per phase, but
per-phase site load is always empty (**E3**), and the phase assignment comes from engine configuration
rather than from any declaration (**E2**).

**The `never` gate cannot protect a non-Simple consumer** — the requirement scopes it to Simple, and
the prototype follows the requirement rather than fixing it (**A1**, **F13**). A wallbox has no
declaration-level way to say "leave this alone"; the only alternative is `shadowedParticipants`, which
is engine configuration rather than a property of the device.

**Concurrency notes a reviewer will raise, and where they stand.** `runCycleNow` holds a lock across
calls into contributed algorithms and the sink, so a misbehaving add-on stalls the engine and its pool
thread; the lock is there because cycles must not overlap, and the two locks in the class are never
nested, so there is no ordering hazard — but the exposure is real and unaddressed.

**Two things a demo audience should be told out loud:**

1. Do not leave shadow mode on real hardware. Not because writes would escape a guardrail — there is
   no code here that can write — but because a Batch programme has no interruption protection and
   because the protection plane depends on an elapsed-time source the corpus never specified (**N3**).
2. Nothing outside this bundle can call `setShadow(false)` or `setStopped(false)`. The exported
   registry interface deliberately carries neither. The only route out of shadow is a ConfigAdmin edit
   by the operator.

---

*Wave 1 only. Waves 2–4 stay unbuilt until this has been through review.*
