# Stage 2 — the price plane, the forecast plane, the objectives, and the join

Prototype track, wave 2. Companion to `STAGE1_REPORT.md`, which remains the account of wave 1 and is
not superseded by this one.

Built from `openhab-ems-spec` changes `define-price-providers`, `define-forecast-providers` and
`define-optimization-objectives`, under `docs/PROTOTYPE_TRACK.md` and the owner decisions in
`docs/OWNER_DECISIONS.md`.

---

## 1. Build state

Four modules, each `clean install`, Temurin 21 / Maven 3.9.9, run serially.

| Module | Tests | Static analysis | Spotless | Javadoc | Dependencies |
|---|---|---|---|---|---|
| `org.openhab.core.energy` | **535** / 0 failures | 0 findings | clean | 0 warnings | 3 compile + 1 test |
| `org.openhab.core.energy.series` | **26** / 0 failures | 0 findings | clean | 0 warnings | 3 compile + 1 test |
| `org.openhab.core.energy.forecast.store` | **24** / 0 failures | 0 findings | clean | 0 warnings | 4 compile + 1 test |
| `org.openhab.core.energy.publish` | **16** / 0 failures | 0 findings | clean | 0 warnings | 2 compile + 1 test |

**601 tests, 0 failures, 0 skipped.** `bom/openhab-core` registers all four; the Karaf feature file
verifies (`karaf:verify`).

Every dependency is an `openhab-core` artifact — `org.openhab.core`, `.config.core`, `.persistence`,
`.test`. **No library outside openHAB's default set appears anywhere**, and no HTTP or WebSocket
client appears at all: the source SPI is pull-only and core fetches no energy data itself.

**Wave 1 is intact test-for-test.** The same 23 wave-1 test classes still sum to exactly **354**; no
wave-1 test was deleted, disabled, weakened or renamed, and no wave-1 signature had to change. There
is no `@Disabled`, `@Ignore` or `assumeTrue` anywhere in the four bundles.

---

## 2. What was built, where each piece lives, and why

Wave 2 added four packages to the engine bundle and one new companion bundle. The placement rule is
the same one owner decision **D23** drew for wave 1's observability surfaces, applied to data:
**arithmetic over values lives in the engine; anything that touches openHAB's own data surfaces
lives in a companion that is its own opt-in feature.**

### 2.1 In the engine bundle (`org.openhab.core.energy`)

| Package | What it is | Why here |
|---|---|---|
| `price` | `EnergyPriceSeries`, `PriceComposition`, `PriceAdjustment`, `TariffCalendar`, `GridPriceProvider`, `EnergyPriceRegistry`, `SeriesAlignment` | Pure functions over values. A VAT multiplier and a seasonal tariff can be tested against a real bill without a running framework, and the registry only ever *reads* what sources hand it. |
| `forecast` | `ForecastSeries`, `ForecastRegistry`, `LayeredSeriesResolver`, `HeatingDemandDerivation`, `ForecastWindows` | Same. `HeatingDemandDerivation` is a pure function of weather and parameters; nothing in this package knows what an Item is. |
| `objective` | `OptimizationObjective`, `ObjectiveRegistry`, `CarbonSeries`, `ExportShare`, the three built-in objectives | Ranking is arithmetic. The objectives consume `EnergyContext.surplusWattsFor` rather than recomputing it, so **D27** stays in one place. |
| `window` | promoted in wave 2 to carry `CostWeights`, `SeriesSense` and the one `cost(window, weights)` | The corpus asks for the shared calculation to be reachable by "engines, rules and scripts"; the engine bundle is the one every consumer already has. |
| `internal/EnergyPlanCoordinator` | the join: price → objective inputs → objective resolution → level derivation input → `derivePlan` | See §2.3. |

### 2.2 In companion bundles

| Bundle | Touches | Why not in the engine |
|---|---|---|
| `…energy.series` | reads a future price series out of an Item through `QueryablePersistenceService`; registers core's generic grid-price provider | The engine's own structural test forbids `.query(`, `FilterCriteria`, `HistoricItem` and `PersistenceService` in its sources. **Reading an Item's history is not a write, but it is a data-surface touch**, and the same split that keeps writing visible keeps reading visible. |
| `…energy.forecast.store` | `ModifiablePersistenceService.store(Item, ZonedDateTime, State)` and `remove(FilterCriteria)`; publishes a derived demand forecast as a future `TimeSeries` | Overwriting past and present entries is the only way *Layered prediction series* is implementable at all, and it is a write. |
| `…energy.publish` | writes the engine's status, current level and planned `TimeSeries` Items | D23, unchanged from wave 1. |

Each is its own Karaf feature depending on `openhab-core-energy`, never the reverse. Installing
energy management installs none of them.

### 2.3 The coordinator, and why it is in the engine

Wave 1 shipped `EnergyLevelPlane.derivePlan(SlotSeries)` **with no production caller**. Wave 2 built
three data planes that each answer correctly and none of which talks to another. Everything was
green and the framework still could not turn an installed price source into a level plan.

`internal/EnergyPlanCoordinator` is that join, and deliberately the only one — a second thing that
also derives plans would mean two plans racing to be installed. It goes in the **engine** bundle
because everything it does is arithmetic plus in-process calls: it asks the price plane for an
effective consumption price, hands it to the objective plane as `ObjectiveInputs`, asks which series
the level bands come out of, and hands that to the level plane. It holds **no `EventPublisher`,
names no Item type and touches no persistence**, and it hands the derived schedule to
`PlannedLevelPublisher` — an in-process interface — rather than publishing it. Whoever wants that
plan on an Item publishes it from the bundle where writing is the point.

No thread is created: the optional periodic refresh runs on openHAB's shared scheduler under the
pool the engine already uses, with `scheduleWithFixedDelay` so a slow derivation delays the next
rather than overlapping it.

---

## 3. Definition of done — item by item, with evidence

From `docs/PROTOTYPE_TRACK.md`, *Definition of done*.

### ☑ Builds inside a real `openhab-core` checkout with its static analysis clean

Four modules, `clean install`, priority-1 findings fail the build. **0 checkstyle / PMD / SpotBugs
findings** across all four; `spotless:check` clean; `javadoc:javadoc` 0 warnings. The Karaf feature
resolves under `karaf:verify` and `bom/openhab-core` carries all four artifacts.

### ☑ Unit tests green, including one test per implemented scenario

601 tests, 0 failures. Sixteen requirements across the three changes; every `#### Scenario:` that the
prototype implements has a test. The one scenario that previously had **no** discriminating test now
does — *A window starts on a slot boundary*, see §4.2 — and the two scenarios that cannot be tested
are named in §5 rather than quietly passed.

### ☑ All four fixture vectors reproduced exactly

All four CSVs in `src/test/resources/fixtures/` are **byte-identical** to
`openhab-ems-spec/fixtures/` (verified by `diff`; `FixtureCsv` throws if a resource is missing, so a
fixture test cannot silently degrade to an empty list).

| Fixture | Gate | Asserted |
|---|---|---|
| `dayahead-prices.csv` | input to both planes | `PriceFixtureConformanceTest` treats it as a **direct conformance target for the price plane**, not only as level input: per-slot start and per-slot value |
| `expected-planned-levels.csv` | `LevelFixtureConformanceTest` | slot count, then per-slot `start` and per-slot `level().code()` |
| `expected-heating-control.csv` | `PriceFixtureConformanceTest` | row count equals series size, then per-slot timestamp **and** ON/OFF flag |
| `expected-boiler-control.csv` | same | same, with the heating schedule passed in as excluded |

The price plane and the level plane are cross-checked against each other on the fixture: the four
most expensive slots the price plane ranks are exactly the level fixture's `value()==0` band.

### ☑ A shadow-mode demonstration: decisions computed and logged, zero writes

`ShadowModeDemonstrationTest` — five structural witnesses plus the persistence one — is unchanged
and **unrelaxed**. Its pinned file lists did not grow across a stage that added ~70 main sources to
the bundle: still exactly three files may name `org.openhab.core.items.`, still exactly two may name
a publisher, still exactly one may post. See §6.

### ☑ Zero open questions resolved; every seam documented

The two questions the corpus explicitly leaves open are open in code, with **all** framed options
shipped behind an interface and the choice left to configuration — see §5.7 and §5.8. Wave 2 added
four further seams on questions the corpus frames without answering (`SeriesAlignment` ×3,
`LayeredWritePolicy` ×4, `AbsentDataPlanePolicy` ×3, `PreheatModel` ×2). D22's no-shipped-default
precedent was extended to five new places: `COMPOSITION_UNCONFIGURED`, `WRITE_POLICY_UNCONFIGURED`,
`LAYER_PRECEDENCE_UNCONFIGURED`, `REFRESH_INTERVAL_UNCONFIGURED`, and
`HeatingDemandParameters.unconfigured()`.

### ☑ A written report

This file.

---

## 4. The two things this stage got wrong, and how they were found

Both were invisible to every existing test, which is the part worth recording.

### 4.1 The one shared costing function did not cost the two shipped strategies alike

`LeftRiemannWindowCost` re-derived the per-slot allocation by filling slots **in time order** until
the granted time ran out. That is correct for a consecutive run and false for a ranked one, whose
part-used slot is its *worst-ranked* slot and can sit anywhere. On hourly prices `[1, 100, 2]` a
two-and-a-half-hour interruptible request was costed at 367 200 against a plan that actually costs
190 800 — out by a factor of nearly two, on the function whose stated purpose is that "two conforming
implementations cost the same dishwasher identically". The two shipped strategies did not agree with
each other through it.

Fixed by making the allocation part of the answer: `WindowSelection` now carries
`List<Duration> allocation`, positionally aligned with the chosen indices and validated to sum to
`granted`. `ConsecutiveWindowSelection` fills in time order (which is what a contiguous run *means*);
`RankedSlotsSelection` fills in rank order. The cost function reads it and never re-derives it.
Pinned by `WindowCurveAndSenseTest.aNonConsecutiveSelectionIsCostedAsTheStrategyAllocatedIt`.

### 4.2 The search ignored the weights it was supposed to search under

*Shared window calculations* asks for the selections to be made "over one costing function
`cost(window, weights)` … that takes a declared load curve as its weights when one exists", and its
first scenario has a rule ask for the cheapest window **costing the curve as weights**.
`SelectionStrategy.select` took no weights, so the search was always flat and the curve could only be
applied to an answer already chosen. A load that draws almost everything in its last hour was being
given the rectangular load's window with the shaped load's price on it.

`select(series, request, excluded, weights)` is now the method an implementation writes; the
three-argument forms are the flat case. `RankedSlotsSelection` takes the parameter and does not use
it, documented: its answer *is* the ranking of individual slots, and a slot's position within the run
is not known until the set is chosen, so weighting it would be circular.

---

## 5. Requirements that did not survive contact with the code

The most valuable section, same as wave 1's. Each item is a place where the corpus is ambiguous,
contradictory or silent, with the concrete change it implies. **Nothing here has been folded back
into `openhab-ems-spec`** — that is the owner's edit to make, and the working tree is clean.

### 5.1 `ct/kWh` is not expressible, and the requirement asks for it twice

*Generic grid-price provider*'s first scenario ends "the effective series is in ct/kWh", and the
acceptance fixture's own column is `price_ct_per_kwh`. `CurrencyUnit`'s constructor refuses any name
that is not exactly three characters, so `ct` is not constructible as a currency and `ct/kWh` is not
expressible as a `Unit<EnergyPrice>` at all. The plane carries prices in **currency per unit of
energy** and treats cents as presentation; a site that wants cents applies a `Scale` of 100 and reads
the unit as a lie it chose (`EnergyPriceUnits` Finding 1; pinned by
`PriceModellingLimitsTest.aPriceCannotBeDenominatedInCents`).

**Corpus change:** reword the scenario to "EUR/kWh with VAT" and drop the cents claim, or add a
requirement that core gain sub-unit denominations. It cannot stay as written.

### 5.2 `QuantityType.toUnit` cannot convert a price, even within one currency

`toUnit` routes through the system unit; a `CurrencyUnit`'s system converter asks `CurrencyService`
for an exchange rate. With no `CurrencyProvider` configured — every headless test, and every site
that has not set one up — the conversion throws internally and `toUnit` answers `null`. **The failure
is the same when both sides are the same currency and nothing is being exchanged.** So this plane
converts on the energy denominator alone and requires the currency to be identical
(`EnergyPriceUnits` Finding 2).

**Corpus change:** the corpus's "the currency question is already solved by `Number:EnergyPrice`"
needs a footnote. It is solved for *carrying* a price and not for *converting* one.

### 5.3 The adjustment pipeline has no stated order, and the order changes the number

*Generic grid-price provider* names four adjustments — VAT multiplier, unit conversion, fixed fees,
conditional tariffs — and never says in which order they apply. VAT on a price that already carries a
transfer fee is a different number from a fee added after VAT: on the shipped test data, 0.186 against
0.174 for the same inputs. The provider therefore has an explicit `pipeline` parameter and warns when
a site configures several adjustments without naming one.

**Corpus change:** either state the order in the requirement, or state that it is the user's and that
a conforming provider must expose it. Silence is the one option that does not work.

### 5.4 Composition across differing geometry is mandated and undefined

*Price component composition*'s headline scenario adds an hourly spot series to a tariff that changes
at two times of day and to a constant fee; *Time resolution* then **mandates** non-uniform and mixed
intervals in one series. So components of differing geometry are guaranteed rather than exceptional —
and nothing says what adding two of them means. Three readings are defensible and all three ship
(`SeriesAlignment`: union-of-boundaries, resample-to-finest, refuse).

**Corpus change:** *Price component composition* needs a scenario for two components whose slots do
not line up. Until it has one, the alignment is configuration.

### 5.5 A `TimeSeries`' last entry has no width, and every plane needs one

openHAB's `TimeSeries` carries one timestamp per entry and no interval. Converting one to slots
therefore cannot know how long the final entry lasts. `ForecastTimeSeries.toSlots` refuses a series of
fewer than two entries rather than guessing, and infers the last width from the one before it
otherwise — an inference that is stated and bounded, and wrong on a series whose last slot is a
different width.

**Corpus change:** *Prices as future-timestamped series* and *Forecasts as future-timestamped series*
should say what the last entry's interval is. This is a framework-level gap, not a modelling
preference.

### 5.6 The layer ledger is not durable, which sharpens the corpus's own disposition

`define-forecast-providers` design.md's disposition (Part C · FP-2) holds that two of the three
collision options are **unimplementable** "because a published time series carries no writer identity
at all". That is exactly right for a series arriving over the event bus, and not the whole picture for
writes through this framework's own layered surface, which knows which layer is calling because the
caller says so. Both write-time options compile and work — with one caveat that is part of the option:
**the layer bookkeeping lives in memory and does not survive a restart**, because persistence stores a
number and a timestamp and nothing else.

**Corpus change:** the finding is narrower and sharper than "two options are impossible": they are
implementable, they are **not durable**, and the durable option is the one that never writes the cap
into the prediction at all.

### 5.7 Open question kept open — forecast writer precedence

*Layered prediction series* says "the new values replace the old ones for those timestamps"; its very
next scenario says the capped entries hold the capped value. A refresh arriving after a cap therefore
silently erases it, and the corpus frames three ways out and picks none.

**In code:** `LayeredWritePolicy` opens "this enum is an open question, not an answer". All four
positions ship — the requirement's literal words plus the three framed alternatives — and
`energy-forecast-store.xml` ships **no `<default>` at all**. Unset raises
`WRITE_POLICY_UNCONFIGURED`, applies the requirement's literal words, and reports the erasure as
`CAP_OVERWRITTEN_BY_REFRESH`. `LayeredPredictionTest` asserts the erasure is *visible* and its own
JavaDoc says the test does not resolve either.

### 5.8 Open question kept open — an objective without its data plane

What the carbon objective should do on a site with no carbon source is undecided.
`AbsentDataPlanePolicy` opens "this is an open question in the corpus and it is not answered here";
all three ship (fall back to cost and report it · do not offer the objective · plan nothing) and
`AbsentDataPlaneTest` asserts they are three different behaviours.

**But a default is shipped** (`fall-back`, `energy.xml`), unlike the forecast case where nothing is
selected. It is framed as "the disposition already on record elsewhere" rather than a verdict, and
every unconfigured site does get one of the three answers.

**Corpus change:** `define-optimization-objectives/design.md` §4 still reads "Undecided." with no note
that the reference implementation ships a default and where it lives. That is spec-vs-code drift and
should be recorded either way the owner decides.

### 5.9 The forecast surplus does not exist in the corpus, and two requirements need it

Requirement O1 places a deferrable load "into the surplus" — a statement about *future* slots — while
surplus is defined only as an instantaneous figure from the cycle snapshot. There is no total-demand
forecast role to subtract. **This is the one genuine invention in wave 2**: the coordinator can
assemble solar production netted against the one demand role that exists (heating), it is **off by
default**, and when no demand forecast is installed it reports
`SURPLUS_FORECAST_IS_PRODUCTION_ONLY` — an upper bound labelled as one.

**Corpus change:** define a forecast surplus, or add a total-demand role, or say that O1 is a
dispatch-time statement only. As written it asks for a quantity nothing defines.

### 5.10 `ExportShare` has no ranking-time definition

*Carbon credit for exported energy* has to be applied to *future* slots, so something must say how
much of a load's energy would have been exported in each of them. The corpus defines surplus only
instantaneously, so the ranking-time answer does not exist. Three honest positions ship (`None`,
`FromSurplusForecast`, `Live`) and both evaluation points are present, because the requirement is not
evaluable at one of them alone and picking one silently would hide that.

### 5.11 The level/objective interaction is a seam the corpus does not name

Whether the four-level signal follows the active objective or stays price-based is undecided, and
every production system behind the corpus only ever ran price-based levels. The shipped value is the
consumption price — the behaviour that already exists — and a site that wants carbon-shaped levels
selects `levelInput=objective`. Nothing in the code prefers either.

### 5.12 Objectives and algorithms disagree about duplicate ids

`ObjectiveRegistry` is first-registration-wins with a refusal warning; the wave-1 algorithm registry
resolves the same collision differently. Two registries in one framework answering "what happens when
two contributions claim one id" two ways is a real inconsistency, recorded and **not** smoothed over
here because unifying them touches wave-1 behaviour.

**Corpus change:** `define-extension-points` should state one rule for contributed identity.

### 5.13 The source SPI is pull-only, so nothing can notice new prices

A day-ahead source that fetches tomorrow's prices at midday has no way to announce it, and nothing in
the framework can notice. This is the one number wave 2 refused to invent **and** refused to let be
silently inert: `refreshInterval` ships no default, and leaving it unset raises
`REFRESH_INTERVAL_UNCONFIGURED` with a configuration description that states the consequence.

**Corpus change / wave 3:** give `EnergySeriesSource` a change notification, or this stays a poll.
This is the single most consequential gap in the stage.

### 5.14 The price plane's conditions are registry-side only, so a *source* cannot report one

`PricePlaneCondition` is raised by composition and by the registry. A **source** that is installed but
unconfigured has no way to say so: the generic grid-price provider going inert for want of a market
zone surfaces only as `NO_SOURCE` ("nothing is publishing prices for SPOT"), which is true and does
not say why. The provider logs an actionable message and stays inert, matching how a missing `item`
was already handled.

**Corpus change:** `define-extension-points` should say whether a source reports its own configuration
conditions, and through what.

### 5.15 A curve over a non-consecutive selection is undefined

`CostWeights` maps a relative run time of 0→1 across the window, which describes a load running once
from start to finish. Costing a scattered set of slots under a curve assumes the load resumes where it
left off, in time order — a reading nothing in the corpus states, because *Shared window calculations*
pairs curves with the **consecutive** search and says nothing about interrupting one. It is the only
reading available and it is arithmetically consistent; it is recorded at `WindowCost` as this
implementation's reading rather than the corpus's.

### 5.16 The "earlier slot wins a tie" rule is defeated by floating point

D21's tie-break is exact where it is applied to *values* (`SlotSeries` compares the numbers
themselves). Applied to *window costs* it compares computed doubles, and two windows that are equal on
paper can differ by ~1e-12 through a different order of the same multiplications — which silently
decides which hour a dishwasher runs. Encountered while building §4.2's test, where a deliberately
symmetric price shape chose the later window. Not fixed here: an epsilon is a policy, and choosing one
would answer a question the corpus has not asked.

**Corpus change:** *Shared window calculations* should say whether the tie-break is on exact equality
or within a tolerance, and if the latter, whose.

### 5.17 A shipped default contradicted the requirement in the file that implements it

Not an ambiguity — a defect, recorded because of what it shows. `marketZone` shipped
`<default>UTC</default>` in the very element whose description reads "it is never inferred from it".
A CET market's day runs 23:00Z→22:00Z, so `deliveryDay()` returned the *earlier* UTC date for every
whole delivery day, with **no condition reported anywhere**. The default is gone; the provider is
inert until a site names a zone. The currency default (`EUR`) went with it on D22's precedent — an
item carries a number and says nothing about its denomination, and a guessed one is what `describe()`
renders onto a page somebody acts on.

**This goes one step beyond what the requirement literally demands** (it forbids inferring the *zone*
and says nothing about currency), and it is flagged here as a judgement call rather than a derivation.

---

## 6. How the invariant survived a stage whose whole job is publishing data

The engine bundle must remain **structurally incapable** of writing to an Item. Wave 2 is the stage
most likely to break that, because a data plane naturally wants to publish a series.

**It held, and it is proven the same two ways it was in wave 1.**

**(a) Structural, over comment-stripped sources.** `ShadowModeDemonstrationTest` carries five
witnesses — no code path that writes to an Item, only one place that posts an event, every posted
event is this bundle's own, the compiled bundle cannot resolve an Item event type, the only Item
access is reading — plus the persistence witness. The pinned file lists are **unchanged and did not
grow**: exactly three files may name `org.openhab.core.items.`, exactly two may name a publisher,
exactly one may post `.post(`.

**(b) The compiled artefact.** The freshly built jar carries 215 class files. Its manifest imports
`org.openhab.core.items;version="[5.3,6)"` — reads — and **does not import
`org.openhab.core.items.events` at all**. A byte-level scan of the packaged classes finds **zero**
references to `org/openhab/core/items/events`, `ItemCommandEvent`, `ItemStateEvent`,
`ItemStateUpdatedEvent`, `ItemTimeSeriesEvent`, `ItemEventFactory`, `ItemStatePredictedEvent`,
`GenericItem`, `ManagedItemProvider`, `sendCommand`, `postUpdate` or `sendTimeSeries`. The positive
control fires correctly: `ItemRegistry` is found in exactly `RegistryItemStateReader.class` and
`EnergyEngine.class`, whose sole use is `itemRegistry.getItem(itemName)`.

**Where each piece went, and why.** Three wave-2 capabilities genuinely needed a data surface, and
each was split out rather than argued into the engine:

- **reading a price series from an Item** → `…energy.series`, because `.query(` is a forbidden token
  in the engine and reading a site's own history is a data-surface touch even though it is not a
  write;
- **overwriting past and present forecast entries** → `…energy.forecast.store`, because
  `ModifiablePersistenceService.store` is a write;
- **publishing a derived demand forecast as a future `TimeSeries`** → also `…energy.forecast.store`.

**Governance note the owner should have explicitly: the Item-write licence is now a two-item list.**
D23 named `org.openhab.core.energy.publish` as the bundle that may write Items. Wave 2 added a second
writer — `DerivedHeatingDemandSource` posts an `ItemTimeSeriesEvent` for a *future* series. It is
defensible (a future series is neither a command nor a current-state update), it is bounded by that
bundle's own `WriteBoundTest` — `theWritingIsConfinedToTwoClasses`, alongside
`nothingInThisBundleCommandsAnItemOrChangesItsCurrentState` and two witnesses that re-check the
framework bundle next door — and it ships as its own opt-in feature. Nothing breaks. But "which
bundles may write" is the load-bearing fact of this stage, and it changed.

---

## 7. What a reviewer should look at first

1. **`window/WindowSelection.java` and `window/internal/LeftRiemannWindowCost.java`** — the one
   shared calculation and the reason the allocation is stated rather than derived (§4.1). This is the
   only place wave 2 got the arithmetic wrong, and the fix changes a public record.
2. **`internal/EnergyPlanCoordinator.java`** — the join, and the component the invariant was most
   likely to break on. Check that it holds no publisher and names no Item type.
3. **`ShadowModeDemonstrationTest`** — specifically that the pinned file lists did **not** grow.
4. **`price/SeriesAlignment.java`, `forecast/LayeredWritePolicy.java`,
   `objective/AbsentDataPlanePolicy.java`** — the seams. Every framed option ships; check that none of
   them is silently preferred in code.
5. **`series/internal/GridPriceSource.java`** — the Item/persistence edge, where four of this stage's
   defects lived (§5.17, plus the ignored unit, the lazily-parsed pipeline and the missing i18n).

## 8. Honest limitations

- **The plan is a poll.** §5.13. Until the source SPI can announce new data, a site that wants
  tomorrow's prices picked up on the day they arrive must configure a cadence.
- **The forecast surplus is an invention** (§5.9), off by default, and reports itself as an upper
  bound when there is no demand forecast.
- **Series alignment is answered once, in the price plane, and refused a second time in the
  coordinator.** A misaligned demand forecast is reported (`SURPLUS_FORECAST_UNALIGNED`) and never
  resampled. That is deliberate — a second, different notion of alignment is how a framework acquires
  two incompatible ones — but it means the surplus feature is unavailable to sites whose two forecast
  sources publish on different grids.
- **The layer ledger does not survive a restart** (§5.6).
- **No REST view of the plane exists.** JAX-RS is outside the default-library set for this bundle;
  the corpus's UI change is wave 3.
- **Nothing has been verified against a real market feed.** Every price in every test is either the
  corpus fixture or a hand-built series. The generic provider has been exercised against a fake
  persistence service, not against a live ENTSO-E binding.
- **The chain now runs in one process.** Superseded on 2026-08-29 by
  `itests/org.openhab.core.energy.tests`, an OSGi integration test that starts all four bundles in a
  real framework. **Five tests, and they establish what no unit test could**: the bundles resolve and
  reach ACTIVE, they find each other through the service registry, and
  `Item → persistence → provider → registry → coordinator → level plan` runs end to end — an Item's
  future prices in a persistence service, read by the `series` bundle's Item-backed source, composed
  by the registry in the engine bundle, installed as a plan on the level plane. Nothing in that test
  is wired by hand; the only connection between the parts is OSGi.

  The persistence service is a test implementation, because core ships no store — rrd4j and InfluxDB
  live in openhab-addons — but it is a real `QueryablePersistenceService` discovered through the real
  `PersistenceServiceRegistry`, which is the extension point an actual store plugs into. What remains
  unverified is therefore the market feed itself (§8 above), not the wiring.

  Two findings came out of writing it. **On a fresh framework an installed price source derives
  nothing**, because core ships no composition: the coordinator reports `PRICE_COMPOSITION_FAILED`
  and `NO_SERIES_TO_DERIVE_FROM` rather than inventing a price. That is the contract working as
  intended, but it means "install a price source" is not by itself a working configuration, and it
  had never been shown end to end. It has its own test now. And the first version of the suite passed
  or failed **depending on the order the tests ran in** — the framework and its ConfigAdmin state are
  shared across a class — so each test now tears its own configuration and services down again.

- **`RankedSlotsSelection` ignores the load curve** (§4.2), documented and defensible, but it does
  mean a shaped interruptible load gets a flat-ranked answer.

---

## 9. Can the framework answer the question it exists for?

> *Given tomorrow's prices and a solar forecast, can it produce a plan for a wallbox?*

**For the price half: yes, end to end, and this is new in wave 2.** With
`openhab-core-energy-series` installed, a site names the Item its binding publishes day-ahead prices
to, its market zone and its currency; names what its effective consumption price is made of; and
`EnergyPlanCoordinator` composes the price, resolves the objective, and installs a
`PlannedLevelSchedule` on the level plane. `EnergyPlanCoordinatorTest.anInstalledPriceSourceBecomesALevelPlan`
drives exactly that through the real registries, and the wallbox — declared as an
`EnergyConsumer` with a level gate — is then throttled or released per slot by the wave-1 engine.
Before the coordinator existed, this produced an empty plan no matter what was installed.

**For the solar half: partially, and the gap is named.** A solar production forecast is a first-class
series with a registry, a role, ranked source selection and window calculations over it, and the
sunniest-window question is answerable today. What it does **not** do is combine a price plan and a
solar forecast into one wallbox schedule. The quantity that would join them — how much surplus each
*future* slot will have — is not defined anywhere in the corpus (§5.9). What wave 2 ships is an
opt-in approximation that reports itself as production rather than surplus, and the honest answer is
that the self-consumption objective ranks on a series the corpus has not specified.

**Two further things are missing before this is a wallbox plan a site should act on:**

1. **A refresh cadence has to be configured** (§5.13), or tomorrow's prices are not picked up on the
   day they arrive.
2. **Nothing writes the plan anywhere by default.** The coordinator hands the schedule to
   `PlannedLevelPublisher`; publishing it to an Item requires `openhab-core-energy-publish`, and
   actuating on it requires shadow mode off and an actuation sink named — neither of which is a
   default, by design.

So: **the loop closes on price, and it is genuinely closed rather than claimed.** The solar side is
a complete data plane with an unspecified junction to the planner, and saying which is more useful
than shipping a number nobody defined.
