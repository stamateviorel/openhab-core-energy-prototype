# `org.openhab.core.energy` — participant model contract

The data model an energy-management framework reasons about: who produces and consumes energy on a
site, and how each device may be steered.

> **This bundle is a prototype, not a merge candidate.** It computes and logs; it never writes to an
> Item. Several behaviours are deliberately left selectable because the corresponding design decision
> has not been taken. `PROTOTYPE_REPORT.md` in this directory records what was built, what is
> deliberately unfinished, and every specification ambiguity found while building it.

This file is the **API contract** the other components in the bundle code against — the declaration
sources, the engine and the level classifier. Signatures below are transcribed from `javap` on the
built classes, so they are exact.

- Bundle: `org.openhab.core.energy` (artifactId + `Bundle-SymbolicName`), version `5.3.0-SNAPSHOT`
- Exported packages: `org.openhab.core.energy`, `org.openhab.core.energy.events`, `org.openhab.core.energy.level`, `org.openhab.core.energy.spi`
  (this section describes the first one; §10 describes how the assembled bundle is wired, §12 the events)
- Build: `mvn -pl bundles/org.openhab.core.energy -am clean install` from the checkout root. Always run `mvn -pl bundles/org.openhab.core.energy spotless:apply` first; the build enforces the format.
- Format before every build: `mvn -pl bundles/org.openhab.core.energy spotless:apply` (the build *fails* on format violations)

## 0. Ground rules this model follows

| Rule | Consequence for you |
|---|---|
| Pure data | No OSGi, no I/O, no `Item` access, no logging. Constructing any type is free and side-effect-less. |
| Immutable | All types are `record`s or `enum`s; list components are defensively copied via `List.copyOf`. `equals`/`hashCode` are value-based, so records are safe as map keys and in assertions. |
| Fail fast | Compact constructors throw `IllegalArgumentException` on invalid input. Never construct model objects from untrusted input without catching that. |
| Mechanism-neutral | Nothing here knows how a participant was *declared*. Item metadata vs. description-provider SPI is an open maintainer question and lives behind someone else's seam. |
| `@NonNullByDefault` | Every type carries it. A parameter/return without `@Nullable` is never null. `package-info.java` deliberately does **not** carry the annotation — see §7. |

## 1. `EnergyParticipant` — the sealed root

```java
public sealed interface org.openhab.core.energy.EnergyParticipant
        permits EnergyProvider, EnergyConsumer {
    String id();          // stable, unique per site
    String itemName();    // provider: live power reading; consumer: the Item the engine steers
}
```

Sealed to exactly two implementations, so `switch (participant) { case EnergyProvider p -> …; case EnergyConsumer c -> …; }`
is exhaustive without a `default`.

A participant is attached to an **Item**, never to a Thing or a channel — that is the point of the
"No Thing required" scenario.

## 2. `ProviderRole`

```java
public enum org.openhab.core.energy.ProviderRole {
    GRID, PV, BATTERY;

    public List<String> names();                        // canonical name first, then aliases
    public static Optional<ProviderRole> parse(String);  // case-insensitive, trims
}
```

| Constant | Canonical name | Aliases | Notes |
|---|---|---|---|
| `GRID` | `grid` | — | **positive = export, negative = import.** Surplus is this reading's export plus reclaimable battery charge. |
| `PV` | `pv` | `solar` | **positive = producing.** |
| `BATTERY` | `battery` | `storage` | **positive = charging.** The primary controllable-provider case; a setpoint takes the same sign as the reading, so a positive setpoint commands charging. |

**The convention is fixed centrally in `SignConvention`, not declared per participant** — surplus and
site load cannot be computed at all until every role's sign is known, and an add-on that guesses
produces a 100 % error that looks perfectly plausible on a chart. A device that counts the other way
round is normalised **at the edge** by `EnergyProvider.invert()`, so nothing above the declaration
knows it disagreed. Consumers are positive while consuming.

**Surplus is grid export plus the battery charging the engine can reclaim.** A battery absorbing
3 kW is a decision the engine itself made, not a fixed load, so it is available to a consumer with a
better priority — a strictly lower priority number than the battery's. `EnergyContext.surplusWatts()`
is the site-wide figure the level plane's escalation thresholds apply to and counts all of it;
`EnergyContext.surplusWattsFor(priority)` answers what one consumer may claim. Without this, the
solar-first EV on the owner's own site idled while the battery absorbed everything. Two sub-questions
stay open by the owner's own note: whether the figure is instantaneous, averaged or forecast (it is
instantaneous, because that is all one snapshot holds), and whether a running managed consumer's own
draw counts (each algorithm still decides that for itself).

```java
public final class org.openhab.core.energy.SignConvention {
    public static double normalise(double rawWatts, boolean invert);
    public static int siteLoadCoefficient(ProviderRole role);     // +1 PV, -1 battery, -1 grid
    public static double siteLoadWatts(double pv, double battery, double grid);
    public static double loadWatts(ProviderRole role, double providerWatts);
    public static double loadWatts(EnergyParticipant participant, double watts);
}
```

`parse` exists only so that every declaration mechanism resolves aliases the same way; it carries no
policy.

## 3. `EnergyProvider`

```java
public record org.openhab.core.energy.EnergyProvider(
        String id,
        String itemName,
        ProviderRole role,
        @Nullable String controlItemName,
        @Nullable QuantityType<?> minPower,
        @Nullable QuantityType<?> maxPower,
        @Nullable String socItemName,
        int priority,
        boolean invert,
        Map<Integer, String> phaseItemNames,
        @Nullable String sinkId) implements EnergyParticipant {

    public static final int DEFAULT_PRIORITY;   // 100, the same scale consumers use

    public static EnergyProvider of(String itemName, ProviderRole role);   // id = itemName
    public static EnergyProvider of(String id, String itemName, ProviderRole role);
    public static EnergyProvider controllable(String id, String itemName, ProviderRole role,
            String controlItemName, double minWatts, double maxWatts, @Nullable String socItemName);

    public EnergyProvider withPriority(int);
    public EnergyProvider inverted();
    public EnergyProvider withPhaseItems(Map<Integer, String>);
    public EnergyProvider withSink(String);

    public double normalise(double rawWatts);   // onto the site convention
    public boolean isControllable();            // controlItemName != null
    public boolean hasStateOfCharge();          // socItemName != null
    public boolean isPowerClamped();
    public boolean isCurrentClamped();
    public boolean hasPerPhaseReadings();
    public Optional<String> phaseItemName(int phase);
}
```

Invariants enforced in the constructor:

- `id`, `itemName`, and (when present) `controlItemName` / `socItemName` / `sinkId` are non-blank;
  **all are stored trimmed**
- **a controllable provider must declare both `minPower` and `maxPower`** — accepting a
  `controlItemName` without a clamp would mean an unbounded setpoint write to an inverter, so such a
  declaration is refused whole rather than half-accepted
- the two bounds must be **both powers or both currents**, exactly like a Controllable consumer's
  bounds, and `minPower <= maxPower`
- phase indices are the integers 1, 2 and 3

The clamp is **signed** in the site convention: a battery that charges and discharges declares e.g.
`min = -3000 W`, `max = 3000 W`, the positive end being charge.

`priority` is on the **same scale consumers use**, lower being better, defaulting to 100 — it is what
makes "is this battery's charging power reclaimable for that consumer?" decidable by comparing two
declared numbers rather than by an assumption.

## 4. `EnergyConsumer`

```java
public record org.openhab.core.energy.EnergyConsumer(
        String id,
        String itemName,
        PowerProfile profile,
        @Nullable Demand demand,
        int priority,
        @Nullable String measureItemName,
        @Nullable String readyItemName,
        boolean handsOff,
        Set<Integer> phases,
        @Nullable String sinkId) implements EnergyParticipant {

    public static final int DEFAULT_PRIORITY;   // 100, shared with EnergyParticipant
    public static final Comparator<EnergyConsumer> PRIORITY_ORDER;

    public static EnergyConsumer of(String itemName, PowerProfile profile);   // id = itemName
    public static EnergyConsumer of(String id, String itemName, PowerProfile profile, int priority);

    public EnergyConsumer withDemand(Demand);
    public EnergyConsumer withMeasurement(String);
    public EnergyConsumer withReadiness(String);
    public EnergyConsumer withHandsOff();
    public EnergyConsumer withPhases(Set<Integer>);
    public EnergyConsumer withSink(String);

    public Optional<QuantityType<?>> powerFigure();     // class-determined; empty for ModeControllable
    public Optional<QuantityType<?>> admissionFigure(); // Batch books its curve peak while admitting
    public boolean ratingIsInferred();        // Simple without ratedPower: a declaration gap
    public Optional<LevelGate> levelGate();   // present only for SimpleProfile consumers
    public boolean hasReadinessInterlock();   // readyItemName != null
    public boolean isMetered();               // measureItemName != null
    public boolean declaresPhases();          // !phases.isEmpty()
    public boolean declaresProtections();     // a protection whose clock is the Item's own history
}
```

- **`priority`: numerically lower is better, in both senses** — served first when power cannot serve
  everyone, and winning when two decisions conflict. **A declaration naming none is placed at 100**
  (`EnergyParticipant.DEFAULT_PRIORITY`), on every declaration path.
- **`PRIORITY_ORDER` is the only sanctioned ordering.** It is `comparingInt(priority).thenComparing(id)`
  — a *total*, stateless order, which is exactly what "the allocation order does not depend on
  incidental iteration order" and "the tie-break carries nothing between cycles" require. Do not sort
  consumers any other way.
- **`handsOff` is the `never` flag, and it lives here rather than on a profile**, so a consumer of
  *any* of the four classes can be marked hands-off. The engine then neither starts, stops, trims nor
  re-modes it *while still reading everything it declares* — marking a device hands-off must never be
  the cheapest way to hide its load from the electrical-limit floor. It is an engine-owned
  prohibition: no contributed algorithm may set it aside.
- **`phases` are the integer indices 1, 2 and 3.** A consumer declaring none is exempt from per-phase
  enforcement and still constrained by the site total — it is never attributed to all three phases
  nor to a guessed one — and is reported as a declaration gap wherever the site declares per-phase
  budgets.
- **`sinkId` names the actuation adapter this participant is written through**, overriding the
  site-wide one. The write side is chosen by naming it and never by ranking; that is the one
  deliberate exception to the precedence chain.
- `measureItemName` is the measured draw. Commands are envelopes, never orders, so anything that
  reasons about delivered energy must read this rather than the commanded value.
- `readyItemName` is the readiness interlock ("startklar"). A closed interlock is a **normal** skip,
  not an error — do not log it at `warn`.

## 5. `PowerProfile` — the four classes

```java
public sealed interface org.openhab.core.energy.PowerProfile
        permits SimpleProfile, ControllableProfile, ModeControllableProfile, BatchProfile {

    Kind kind();

    enum Kind {
        SIMPLE, CONTROLLABLE, MODE_CONTROLLABLE, BATCH;
        public List<String> names();               // canonical name first, then aliases
        public static Optional<Kind> parse(String); // case-insensitive, trims
    }
}
```

| `Kind` | Canonical name | Aliases |
|---|---|---|
| `SIMPLE` | `simple` | — |
| `CONTROLLABLE` | `controllable` | — |
| `MODE_CONTROLLABLE` | `mode` | `modecontrollable` |
| `BATCH` | `batch` | — |

Sealed to exactly four variants — a fifth would be a spec change, not an implementation detail. Use
pattern-matching `switch` over `PowerProfile` (exhaustive, no `default`) rather than branching on
`kind()`; `kind()` exists for declaration mechanisms and log lines.

### 5.1 `SimpleProfile` — ON/OFF plus the full protection set

```java
public record org.openhab.core.energy.SimpleProfile(
        @Nullable QuantityType<Power> onThreshold,
        @Nullable QuantityType<Power> ratedPower,
        @Nullable Duration minOn,
        @Nullable Duration maxOn,
        @Nullable Duration minOff,
        @Nullable Duration maxOff,
        LevelGate levelGate) implements PowerProfile {

    public static SimpleProfile plain();                       // no protections, LevelGate.always()
    public static SimpleProfile withGate(LevelGate levelGate);  // no protections, given gate
    public static SimpleProfile switchingAt(double thresholdWatts);

    public Optional<QuantityType<Power>> powerFigure();  // ratedPower, else onThreshold
    public boolean ratingIsInferred();                   // ratedPower == null
    public boolean declaresProtections();
}
```

Semantics (each parameter is optional; `null` means "not declared", which is *not* the same as zero):

| Component | Meaning |
|---|---|
| `onThreshold` | a **switching** figure: the surplus above which the engine may switch the device on ("Schwellwert"), typically carrying margin |
| `ratedPower` | the **booking** figure the limit floor and budget-constrained scheduling charge against this consumer |
| `minOn` | shortest run the engine must allow once started; **also the catch-up time after a forced restart** |
| `maxOn` | longest uninterrupted run the engine may allow |
| `minOff` | cooldown; the engine must not switch on again before it elapses |
| `maxOff` | duty-cycle guarantee; once exceeded the engine switches back ON *regardless of price or surplus* |

**`ratedPower` is optional on purpose**, so that declaring it never becomes a condition of an existing
declaration being read at all. When it is absent the on-threshold is booked instead and the
participant carries a **declaration gap** the engine reports — a gap is never a rejection.

All four protection times are measured from the steered Item's own **last state change**; the engine
keeps no timers of its own, which is what lets a compressor's cooldown survive a restart wherever
that Item is persisted, and what makes an uncommanded OFF→ON transition start the minimum runtime
exactly as an engine start would.

Invariants: `onThreshold` and `ratedPower` convertible to `Units.WATT` and `>= 0`; no negative
durations; `minOn <= maxOn` and `minOff <= maxOff` when both of a pair are present. `levelGate` is
never null, and it never means "never" — that is `EnergyConsumer.handsOff()`.

### 5.2 `ControllableProfile` — a continuous setpoint

```java
public record org.openhab.core.energy.ControllableProfile(
        QuantityType<?> min,
        QuantityType<?> max) implements PowerProfile {

    public static ControllableProfile watts(double minWatts, double maxWatts);
    public static ControllableProfile amperes(double minAmperes, double maxAmperes);

    public boolean isPowerBased();    // min/max compatible with Units.WATT
    public boolean isCurrentBased();  // min/max compatible with Units.AMPERE
}
```

**The bounds are `QuantityType<?>`, not `QuantityType<Power>`,** because the requirement's own
scenario declares a wallbox as "min 6 A and max 32 A". Both bounds must be *the same* dimension —
mixing amperes and watts throws. Invariant: `min <= max` after unit conversion.

`min` is a hard floor *while running*: an engine either stays at or above it, or stops the device.

### 5.3 `ModeControllableProfile` — discrete ordered modes

```java
public record org.openhab.core.energy.ModeControllableProfile(
        List<String> modes,
        Map<String, QuantityType<Power>> modeDraws) implements PowerProfile {

    public static ModeControllableProfile of(String... modes);          // no per-mode draw

    public ModeControllableProfile withModeDraws(Map<String, QuantityType<Power>>);
    public int size();
    public String mostRestricted();          // modes.getFirst()
    public String leastRestricted();         // modes.getLast()
    public OptionalInt indexOf(String mode);
    public Optional<QuantityType<Power>> drawOf(String mode);
    public boolean declaresModeDraws();
}
```

The list is **ordered, most-restricted first** (index 0 consumes least). Invariants: at least two
modes, each non-blank (stored trimmed), no duplicates; a declared draw must name a mode the profile
carries and be a non-negative power; both collections are stored immutable.

**A mode change carries no power figure and is exempt from the planner's budget**, because an
SG-ready mode 3 draws whatever the heat pump decides it needs and a number declared for it would be
fiction. `modeDraws` is the optional, partial escape hatch for a site that does know: declaring a
figure for one mode says nothing about the others, and the consequence of an undeclared one is caught
by the runtime floor through measurement on a later cycle.

Mapping the four `EnergyLevel`s onto *n* modes is stated by the requirement and applied **above** this
model, where the site level is known.

### 5.4 `BatchProfile` — a fixed uninterruptible program

```java
public record org.openhab.core.energy.BatchProfile(
        QuantityType<Power> ratedPower,
        Duration runtime,
        @Nullable LoadCurve loadCurve) implements PowerProfile {

    public static BatchProfile flat(double watts, Duration runtime);

    public double meanFraction();              // loadCurve == null ? 1.0 : loadCurve.meanFraction()
    public double peakFraction();              // loadCurve == null ? 1.0 : loadCurve.peakFraction()
    public QuantityType<Power> meanPower();     // ratedPower * meanFraction — what the programme costs
    public QuantityType<Power> admissionPower(); // ratedPower * peakFraction — what the site must carry
    public QuantityType<Energy> energy();       // ratedPower[W] * runtime[h] * meanFraction, in Wh
}
```

Invariants: `ratedPower` convertible to `Units.WATT` and `> 0`; `runtime > 0`.

The floor books the **mean** for what a running programme costs and the **peak** while deciding
whether to admit one: admitting a dishwasher on its mean would let its heating phase break the very
limit the booking exists to protect.

The engine's only degree of freedom is the **start moment**; once started, the program runs to
completion.

### 5.5 `LoadCurve`

```java
public record org.openhab.core.energy.LoadCurve(List<Double> samples) {

    public static LoadCurve of(double... samples);

    public int size();
    public double sampleAt(int index);
    public double meanFraction();                       // arithmetic mean = LEFT-Riemann integral / runtime
    public double peakFraction();                       // maximum of the samples
    public Duration sampleInterval(Duration runtime);    // runtime.dividedBy(size())
}
```

Samples are fractions of rated power, **program start first**, **evenly spaced over the owning
`BatchProfile.runtime()`** — so the curve is resolution-independent (`0.1, 1.0, 0.2` describes a 2 h
and a 3 h program alike). This is deliberately a *relative-time* shape, not a `TimeSeries`: it
projects onto absolute time only once a program is scheduled.

Invariants: non-empty, every sample finite and `>= 0`, not all zero. Values are **not** capped at 1.0
(ambiguity A8). A flat program is `LoadCurve.of(1.0)` or simply `loadCurve == null`.

## 6. Demand, deadlines, levels, gates

### 6.1 `Demand`

```java
public record org.openhab.core.energy.Demand(
        QuantityType<Energy> energy,
        Deadline deadline,
        boolean consecutive) {

    public static Demand of(QuantityType<Energy> energy, Deadline deadline);   // consecutive = false
    public static Demand kilowattHoursBy(double kilowattHours, LocalTime dailyDeadline);
    public double kilowattHours();
}
```

`consecutive = true` is the "must not be interrupted" case — a scheduler must place it in contiguous
slots. Invariants: `energy` convertible to `Units.WATT_HOUR` and `>= 0`.

The Batch load curve lives on `BatchProfile`, **not** on `Demand` (ambiguity A7).

### 6.2 `Deadline`

```java
public sealed interface org.openhab.core.energy.Deadline permits Deadline.At, Deadline.Daily {

    Instant resolve(ZonedDateTime reference);

    record At(Instant instant) implements Deadline { }        // one-off, absolute
    record Daily(LocalTime localTime) implements Deadline { }  // recurring, wraps past midnight

    static Deadline at(Instant instant);
    static Deadline daily(LocalTime localTime);
}
```

`Daily.resolve` returns the **next** occurrence at or after `reference` in `reference`'s zone; if the
local time has already passed today, it returns tomorrow's. `At.resolve` ignores the reference and
returns its instant — including one already in the past, which callers must handle.

Two variants because the requirement's examples read both ways; neither reading is picked here
(ambiguity A2).

### 6.3 `EnergyLevel`

```java
public enum org.openhab.core.energy.EnergyLevel {
    BLOCKED, NORMAL, ENCOURAGED, OVERCAPACITY;   // declaration order = ascending availability

    public int code();                                   // 0, 1, 2, 3 respectively
    public boolean atLeast(EnergyLevel other);
    public static Optional<EnergyLevel> fromCode(int code);
}
```

**The codes are fixture-normative:** `fixtures/expected-planned-levels.csv` encodes the most
expensive slots as `0` and the cheapest as `3`. A classifier emitting `EnergyLevel` values whose
`code()` does not match that file is wrong.

Derivation (price ranking, PV escalation, seasonal windows, planned series vs. current level) is
**owned by the level classifier**, not by this enum.

### 6.4 `LevelGate`

```java
public record org.openhab.core.energy.LevelGate(EnergyLevel minimumLevel) {

    public static LevelGate always();                      // minimumLevel == BLOCKED
    public static LevelGate atLeast(EnergyLevel minimumLevel);

    public boolean permits(EnergyLevel currentLevel);
}
```

**A gate always names a level; there is no "never" gate any more.** "Leave this device alone" is
`EnergyConsumer.handsOff()`, which every profile class carries — the gate itself stayed scoped to
Simple consumers, because "run at level ≥ N" has no crisp meaning for a Batch programme with a
deadline or for a ModeControllable device with a mode-per-level mapping of its own.

`permits` answers the *gate* question only, and the gate is an **engine-owned prohibition**: the
engine enforces it for every algorithm rather than offering it as advice a contributed one may set
aside. Protections, readiness and the electrical-limit floor are separate engine checks, ordered by
the fixed ladder *electrical limits > device protections > level gates > optimization* — fixed in the
engine, not encoded in this model.

## 6a. The declaration plane — how a participant is said

Two mechanisms, **one SPI** (`org.openhab.core.energy.spi.EnergyParticipantSource`), so nothing above
it depends on which one produced a declaration:

| Source | Mechanism | Source id | Origin |
|---|---|---|---|
| `MetadataParticipantSource` | the `energy` item-metadata namespace | `metadata` | `EXPLICIT` |
| `ProgrammaticParticipantSource` | `EnergyParticipantContributor`, for add-ons and scripts | `programmatic` | `CONTRIBUTED` |

**Identity is the name of the Item carrying the declaration**, overridable by an explicit `id`. A
second declaration of an identity already present is **a further statement about that participant,
never a second participant and never an error** — every statement is kept (the registry key is
`sourceId::participantId`) and resolved on read.

**The precedence chain is fixed, not configured:** explicit metadata > contributed > discovered, ties
between contributed statements broken by `service.ranking`, with the source id as a final total
tie-break so the outcome never depends on registration order. Within the one programmatic source,
where every contribution shares that source's single ranking and a script has no ranking of its own,
the stand-in is **contributor id ascending**. The only site configuration left on
`org.openhab.core.energy.declaration` is `sources` — which mechanisms take part at all.

> Collapsed seam: the prototype carried a `precedence` configuration parameter so a maintainer could
> try each answer. It is gone, and with it the ability of a site to decide that a contributed
> declaration outranks its own metadata.

**The chain does not promote past a broken link.** A source that has a declaration for an identity and
**cannot read it** reports that identity through `EnergyParticipantSource.getBlockedParticipants()`
rather than simply going quiet, and the registry then leaves the participant out of the resolved view
entirely. Source: owner decision **D26** (2026-08-03, `openhab-ems-spec/docs/OWNER_DECISIONS.md`).
Before it, a typo in a user's own metadata withdrew the explicit statement and silently promoted the
add-on's contribution for the same identity: the device stayed managed, on terms its owner never
chose, and nothing about its behaviour said which declaration was in force. **Intent to control
survives a wrong text**, so a typo now degrades to "nothing happens" — diagnosable — instead of to
"something else happens", which is not. A block reaches only statements that are *not strictly more
authoritative* than it, so an add-on that cannot read its own declaration can never disable a site's;
and it lifts by itself the moment the declaration parses again.

> Alternatives preserved: let the contributed declaration take over (what wave 1 shipped — keeps a
> device managed through a configuration error, at the cost of the silent transfer); take over only
> after the user acknowledges the error (serves both cases, needs an acknowledgement mechanism the
> corpus does not have).

The **actuation sink is the deliberate exception**: it is chosen by explicit configuration naming it —
one site-wide sink, with a per-participant override in `sinkId` — and never by ranking, because
getting a ranked selection wrong moves hardware.

### 6a.1 The `energy` metadata keys

`{ energy="provider" [ … ] }` or `{ energy="consumer" [ … ] }`. A key that does not apply is
*reported* and the declaration is kept; a value that cannot be read skips the **whole** participant.

A consumer **must** declare a `profile`. An absent class and an unreadable one are the same
configuration error and neither is read as `simple`: `profil="controllable"` is one keystroke from
`profile="controllable"` and leaves the key absent, so a parser that refused only what it could read
would let exactly the typo it exists to catch through — and the device it let through would be one
the engine believes it may switch on and off at will. Both refusals reach
`ConfigStatusProvider` as well as the log (§10.6).

| Scope | Keys |
|---|---|
| both | `id`, `priority`, `sink`, `ackWindow`, `ackTolerance`, `maxAge` |
| provider | `role`, `control`, `min`, `max`, `soc`, `invert`, `phase1`, `phase2`, `phase3`, and the later-wave `price` / `schedule` |
| consumer | `profile`, `measure`, `ready`, `handsOff`, `phases`, `demandKwh`, `deadlineHour`, `consecutive` |
| Simple | `level`, `onThreshold`, `ratedPower`, `minOn`, `maxOn`, `minOff`, `maxOff` |
| Controllable | `min`, `max` |
| ModeControllable | `modes`, `modeDraws` |
| Batch | `ratedPower` (or the legacy `ratedW`), `runtimeHours`, `shape` |

Two spellings are read for compatibility: Batch's `ratedW`, and `level="never"` — which now means
`handsOff=true`, is applied on **any** profile class, and is reported so a site can move it.

`ackWindow` and `ackTolerance` override the engine's acknowledgement defaults for one participant —
the band being an **absolute** quantity in the control Item's own dimension (`0.01 A`), never a
fraction of the commanded value, and a band in a dimension the command does not use falls back to an
exact comparison rather than widening anything. `maxAge` is how old a reading may be before it counts
as stale; it is optional because unreadable, `UNDEF` and `NULL` trip staleness on their own. All
three are durations or quantities, and a declared duration of zero is refused rather than read as
"not declared".

**`phases` is declared on the device**, not assigned centrally. The engine configuration parameter
that used to do it is gone: a phase is a property of the wallbox, and a consumer names the indices
while a provider names a reading Item per phase.

**How a declared quantity says which dimension it is remains unreconciled in the corpus**, and the
parser does not settle it: `energy-participants` *Declared bounds in power or current* keeps the
"min 6 A" scenario while `extension-surface` *Expressive declaration surface* says a declared value is
a bare number taking its unit from the published config description. Both spellings are therefore
accepted — a bare number is watts (the canonical internal unit), a value carrying its own unit is
honoured as written — so both requirements' scenarios pass and neither reading is closed off.

## 7. Deliberate non-features (do not add them here without a spec change)

- **No registry, no provider SPI, no metadata parser _in the `org.openhab.core.energy` package_.**
  The model does not know how a participant was declared. The bundle as a whole does declare them —
  see §6a — behind one SPI, so the model stays free of the mechanism.
- **No price, forecast, objective or grid-constraint types.** Waves 2–3.
- **No `TimeSeries` usage.** `LoadCurve` is relative-time on purpose.
- **No actuation, no `Item` reads, no `ItemRegistry` dependency — *in the `org.openhab.core.energy`
  package*.** The model does not know Items exist beyond their names. The bundle as a whole does read
  Items: `EnergyEngine` takes an `ItemRegistry` reference and `RegistryItemStateReader` calls
  `getState()` on it. That is the only Item access anywhere in the bundle, and it is read-only —
  `ShadowModeDemonstrationTest` asserts structurally that no main source contains a call that could
  write to an Item.
- **No `@NonNullByDefault` on `package-info.java`.** openhab-core has zero `package-info.java` files
  in the whole tree and annotates every type instead; adding the package-level default makes the
  compiler emit "Nullness default is redundant" for all 14 types, which violates the
  compile-without-warnings rule. The file exists and carries the package JavaDoc, without the
  annotation.

## 8. Ambiguities found in the specification (recorded, not resolved)

| # | Where | What is unclear | What this model did |
|---|---|---|---|
| A1 | `energy-participants` "Level-gated operation" vs. the source taxonomy | The requirement scoped the gate to **Simple** consumers; the taxonomy scopes it **per consumer regardless of class**. The "never" setting ("devices the engine must leave alone") is the one that most obviously wants to apply to all four classes. | **Closed by the owner (D13).** `never` moved off the profile onto `EnergyConsumer.handsOff()`, so all four classes carry it; the *level gate* stayed on `SimpleProfile` deliberately, and `EnergyConsumer.levelGate()` still returns `Optional.empty()` for the other three. |
| A2 | "Demand declaration" | "4 kWh ready by 07:00" reads as a recurring daily deadline; a one-off reads as an absolute instant. Not stated which. | Both, as `Deadline.Daily` / `Deadline.At`. |
| A3 | "Demand declaration" | The requirement says demand is "an energy amount with a deadline", but two of its own three examples are not energy amounts: *"charged to 80 % by noon"* is a state-of-charge target and *"run 5 h within the next 12 h"* is a runtime demand in a window. Neither is expressible. | Modelled only the stated shape (energy + deadline). The other two need a spec decision. |
| A4 | "Priority" | The requirement did not state the direction of the scale, the default, or the tie-break. `engine-contract`'s source note ("lower runs first, higher wins on conflict") points both ways in one sentence. | **Closed by the owner (D4).** **Lower = better in both senses**; default **100** on every declaration path; total, stateless order via `PRIORITY_ORDER` with an `id` tie-break. The reference binding's live `Controller` contract states the opposite direction for conflicts and is the known follow-on to reconcile. |
| A5 | "Controllable providers" | Says a battery is "the primary case" but never says whether a non-battery provider (e.g. a curtailable PV inverter) may be controllable, nor whether a controllable provider must declare a clamp. | Still allowed for any role. **The clamp half is closed by the owner (D16 · pack A10): `[min, max]` is mandatory for a controllable provider**, and a declaration without one is skipped whole and reported. |
| A6 | "Four consumer profile classes" / `energy-levels` "SG-ready mapping" | The level→mode mapping must exist ("without translation logic in user rules") but is not specified for mode counts other than four. | Not modelled. Engine policy. |
| A7 | "Demand declaration" vs. the four-classes requirement | The load curve is described as part of the **demand** ("Batch-class demand additionally carrying a load curve") while the taxonomy treats it as a property of the **program**. | Placed on `BatchProfile`. A demand can then be declared or omitted independently of the shape. |
| A8 | "Demand declaration" | Nothing bounds a normalized load curve's values, its sample spacing, or how it relates to `runtimeHours`. | Samples evenly spaced over `runtime`; values `>= 0`, finite, not all zero; **no upper bound**. |
| A9 | "Controllable providers" vs. "Four consumer profile classes" | The provider clamp is specified as *power*; the Controllable consumer scenario is in *amperes*. Same concept, two dimensions. | Provider clamp is `QuantityType<Power>`; consumer bounds are `QuantityType<?>` restricted to Power **or** Current. The asymmetry is faithful to the text, not intentional design. |
| A10 | naming (see `PROTOTYPE_REPORT.md` §5) | The name `EnergyConsumer` was questioned in review in favour of `DemandDescription`, and `EnergyProvider` here (a *participant*) collides with the `EnergyProvider` of the specification's data-contributor role. | Kept the task-assigned names; flagged. The rename is cheap while this is a prototype. |
| A11 | `energy-levels` "Four-level scale" | The level names differ between the spec (`blocked / normal / encouraged / overcapacity`) and the source taxonomy (`restricted / normal / encouraged / maximum`). | Used the spec's names. |
| A12 | Whole change | Nothing says whether a participant id is user-supplied, derived from the Item name, or generated; nor what happens on a duplicate id. | `id` is an opaque non-blank string. Uniqueness is a registry concern, not enforced here. |

## 9. Test entry point

`src/test/java/org/openhab/core/energy/ParticipantModelTest.java` pins the invariants above (level
codes vs. fixtures, gate collapse, deterministic ordering, unit validation, batch energy math,
daily-deadline wrap). Per-scenario requirement tests belong with the components that implement those
scenarios, not here.

---

## 10. Integration — how the assembled bundle is wired

> **Note.** This section describes the bundle as it is now, after the owner's decisions replaced a
> number of seams with definite answers. `ParticipantSnapshotSource` lives in
> `org.openhab.core.energy.spi` and the level function in `org.openhab.core.energy.level`, so both
> are reachable from outside the bundle; `EnergyAlgorithmRegistry` and `PlannedLevelPublisher` are
> exported so scripts and price components can reach the engine and the level plane. The
> `ParticipantGuard` seam is **gone**: who enforces the hands-off flag, the level gate and the
> readiness interlock is no longer a question a site answers — the engine does, always. The JavaDoc
> on each type is the authority on that type's own contract; `STAGE1_REPORT.md` records what changed
> and why.

Sections 0–9 describe the participant model alone. This section describes the bundle after the four
components (model, declaration + registry, level classifier, engine) were joined into one
system. Nothing here resolves an open question; every join either preserved an existing seam or
added a configurable one.

### 10.1 The ten OSGi components

All wiring is Declarative Services. `bnd` generates one descriptor per component under
`OSGI-INF/`; the list below is that directory.

| Component | Provides | References | Config PID |
|---|---|---|---|
| `EnergyEngine` | `EnergyAlgorithmRegistry` | `ItemRegistry` (1), `ParticipantSnapshotSource` (0..1), `CurrentLevelFunction` (0..1), `EnergyConfigStatus` (0..1), `ActuationSink` (0..n), `EnergyAlgorithm` (0..n) | `org.openhab.core.energy` |
| `EnergyParticipantRegistryImpl` | `EnergyParticipantRegistry` | `EnergyParticipantSource` (0..n) | `org.openhab.core.energy.declaration` |
| `MetadataParticipantSource` | `EnergyParticipantSource` | `MetadataRegistry` (1), `EnergyConfigStatus` (1) | — |
| `ProgrammaticParticipantSource` | `EnergyParticipantSource`, `EnergyParticipantContributor` | — | — |
| `RegistryParticipantSnapshotSource` | `ParticipantSnapshotSource` | `EnergyParticipantRegistry` | — |
| `EnergyLevelPlane` | `CurrentLevelFunction`, `PlannedLevelPublisher` | — | `org.openhab.core.energy.level` |
| `EnergyConfigStatus` | `ConfigStatusProvider`, `EnergyConfigStatus` | — | — |
| `DefaultSurplusAlgorithm` | `EnergyAlgorithm` | — | — |
| `DeviceProtectionAlgorithm` | `EnergyAlgorithm` | — | — |
| `LoggingActuationSink` | `ActuationSink` | — | — |

The two adapters (`RegistryParticipantSnapshotSource`, `EnergyLevelPlane`) exist so that the two
biggest open questions stay one class away from the engine: the engine never names a declaration
mechanism and never names a price series.

```
 metadata source ─┐
                  ├─► EnergyParticipantRegistry ─► RegistryParticipantSnapshotSource ─┐
 programmatic ────┘                                                                   │
                                                                                      ├─► EnergyEngine ─► ActuationSink (logs only)
 PriceSeries ─► LevelDerivation ─► PlannedLevelSchedule ─► EnergyLevelPlane ──────────┘        ▲
                                                                                               │
                                              EnergyAlgorithm (whiteboard + registerAlgorithm) ┘
```

### 10.2 The level plane's seam — `EnergyLevelPlane`

`org.openhab.core.energy.internal.EnergyLevelPlane` holds the plan and answers the engine. It
does **not** fetch prices: price handling is a later capability, so the plan arrives through
`setPlan(PlannedLevelSchedule)` / `derivePlan(PriceSeries)` — the entry point a future price
component, an add-on or a script uses. Until something supplies a plan the schedule is empty, the
fallback applies, and binding the component changes nothing about engine behaviour.

Three configuration parameters keep three open questions open, each defaulting to the safest or the
already-observable behaviour rather than to a verdict:

| Parameter | Values | Open question it belongs to |
|---|---|---|
| `derivation` | `fixed-counts` (+ `overcapacitySlots`/`encouragedSlots`/`blockedSlots`), `percentiles` (+ the three fractions) | `define-energy-levels` task 2.1 — percentile vs. fixed-count |
| `encouragedFrom` | a watt threshold, **no shipped default** | answered: escalation is graded, and a site that sets no threshold does not escalate and is told so |
| `overcapacityFrom` | a watt threshold, defaulting to `2 x encouragedFrom` | answered: the second graded step |

`escalation` and `levelOutsidePlan` are **gone**. Graded is the only shape shipped (the
any-surplus-jumps-to-maximum reading and the `none` non-policy were both removed), and the level
outside the plan is fixed at `NORMAL` with the plan's absence reported through
`LevelPlaneCondition.PLAN_ABSENT` rather than being a number a site could set to `BLOCKED`.

The **seasonal** derivation is reachable only through `setDerivation(LevelDerivation)`, not through
configuration — the specification defines no configuration grammar for season boundaries, and this
component does not invent one. Recorded as ambiguity I3 below.

### 10.3 The level is a function the engine calls, not a source it reads

The dependency runs one way, and the owner fixed which:

```java
EnergyLevel levelAt(Instant moment, OptionalDouble surplusWatts);   // CurrentLevelFunction
```

`EnergyContextFactory.createSnapshot` takes a `LevelResolver`
(`EnergyLevel resolve(Instant moment, OptionalDouble surplusWatts)`) and calls it with *this*
snapshot's own instant and *this* snapshot's own surplus, before the snapshot is sealed.

**Why.** The level escalates on live surplus, and the surplus is part of the snapshot. The earlier
`CurrentLevelSource` was a service the engine *read*, and its implementation held its own `Clock` —
so the plan could be looked up at a different moment from the readings the cycle acted on. Both
clock-reading entry points (`getCurrentLevel()` and `getCurrentLevel(OptionalDouble)`) are gone and
the plane has no `Clock` field at all, so no third party can supply a level by a route that does not
take the caller's instant.

**Publication is an output.** The current level as an Item and the plan as a `TimeSeries` are
things the engine *drives*, never things it reads back — and neither can be implemented in this
bundle, which writes to no Item. See §12.

### 10.4 Where the two required proofs live

| Artifact | File | What it demonstrates |
|---|---|---|
| **Script-algorithm proof** | `src/test/java/org/openhab/core/energy/internal/ScriptAlgorithmProofTest.java` (8 tests) | An algorithm supplied without being compiled into the bundle. Two strengths: a **lambda** through `registerAlgorithm(id, priority, …)`, and a **`java.lang.reflect.Proxy`** whose implementing class is generated while the test runs (`Proxy.isProxyClass` is asserted) — the same mechanism GraalJS uses to bind a script object to a Java interface. Both then pass through the limit floor, shadow gate and master stop unchanged. A structural test asserts `EnergyAlgorithm` stays implementable from outside: one abstract method, no OSGi type and no `.internal.` type in its signature. |
| **Shadow demonstration** | `src/test/java/org/openhab/core/energy/internal/ShadowModeDemonstrationTest.java` (12 tests) | A synthetic site (grid + PV + controllable battery, Simple boiler, Controllable wallbox) declared through the **real** wiring — contributor → source → registry → snapshot source → engine — run over six cycles of a rising and falling day. Four independent witnesses: **behavioural** (decisions computed, every outcome `SHADOWED`, sink never handed anything), **observable** (the `Shadow mode: would have applied …` lines captured from the engine's own SLF4J logger), **structural** (a comment-stripped scan of every main source for `sendCommand`/`postUpdate`/`ItemEventFactory`/the four Item-event class names/`setState(`/`send(`, that exactly three files touch `org.openhab.core.items.*`, all read-only, that exactly two files may name an `EventPublisher` and one may post, and that persistence is read as configuration only), and **compiled** (`theCompiledBundleCannotEvenResolveAnItemEventType` reads the emitted `.class` constant pools and shows no reference to `org.openhab.core.items.events` at all — which is what makes the package absent from the bundle's `Import-Package` and therefore unresolvable at runtime). |
| Level-plane wiring | `src/test/java/org/openhab/core/energy/internal/LevelPlaneWiringTest.java` (10 tests) | The fixture price series → classifier → `EnergyLevelPlane` → engine, gating a real consumer. Asserts the plan the engine acts on **is** `expected-planned-levels.csv`, that escalation and surplus of one cycle come from one reading, that the plan is looked up at the snapshot's instant and not at a clock of its own, and that the level outside the plan is `NORMAL` with the absence reported. |

### 10.5 Ambiguities that only appeared at integration time

| # | Where | What is unclear | What integration did |
|---|---|---|---|
| I1 | `define-energy-levels` *PV escalation* vs. `define-engine-contract` *Central periodic evaluation* | Is the site level an **input** the engine is handed, or a **product** of the same snapshot the engine builds? | **Answered (D6): a product.** The level plane is a pure function the engine calls with its own snapshot's instant and surplus. The no-argument accessor is gone, so the other direction is no longer reachable — a reduction in flexibility recorded deliberately. |
| I2 | level publication versus engine consumption | The level plane is specified as *publishing* Items (a current-level Item, a future-timestamped `TimeSeries`), while the engine *consumes* a level. | **Half answered.** D6 settles the direction: publication is an output, so the engine never reads the published Item and `EnergyLevelPlane` stays an in-process function. What is *not* resolvable here is the publication itself — this bundle is structurally incapable of writing to an Item, so the current-level Item and the plan `TimeSeries` need a companion bundle. See §12. |
| I3 | seasonal window defaults | The requirement makes seasonal parameters user-configurable but the specification defines no configuration grammar for a season (boundaries, time zone, which local date names a delivery day that straddles two). A flat OSGi config map cannot express it without inventing one. | `derivation=seasonal` is **not** offered as a configuration value; the seasonal derivation is reachable only programmatically. Nothing was invented. |
| I4 | `define-extension-points` + `define-engine-contract` | Nothing says at which **level of granularity** a level applies. `EnergyLevelPlane` is a single site-global service, but `define-energy-levels` task 1.2 explicitly leaves site-global vs. per-domain open. A per-domain answer makes `CurrentLevelFunction` a keyed lookup. | Kept the seam single-valued because the engine snapshot carries exactly one `level`. Still open: this is the first place the undecided task 1.2 becomes a signature, not a preference. |
| I5 | openHAB coding guidelines vs. this build | The task instruction (and openHAB's written convention) asks for a `package-info.java` per package carrying `@NonNullByDefault`. Empirically that is impossible here: the reactor compiles with `-warn:+nullAnnotRedundant` and openhab-core annotates every **type** instead, so adding the package default emits one "Nullness default is redundant" warning per class (24 measured in `org.openhab.core.energy` alone). openhab-core contains **zero** `package-info.java` files across all bundles. | Every package now has a `package-info.java` carrying the package JavaDoc — including the previously missing `org.openhab.core.energy.internal.metadata` — but none carries the annotation; every type carries it instead. This is the arrangement the real tree uses. Reversible with one `sed` if a maintainer prefers the other reading. |
| I6 | `define-engine-contract` *Replaceable algorithm* | The requirement makes scripts first-class contributors but says nothing about **algorithm identity collisions** — two scripts registering the same id, or a script shadowing an add-on's algorithm. The participant plane worked this out carefully (contributor ownership, precedence); the algorithm plane has no equivalent statement. | `registerAlgorithm(id, …)` replaces silently (last writer wins), which is what a reloaded script needs, but means one script can take over another's id unnoticed. Asymmetric with the declaration plane's rules on purpose, and flagged rather than fixed. |

### 10.6 What a contributor may and may not assert about its own decision

Three engine-owned rules meet a contributed algorithm, and all three exist because the fields a
decision carries are public API that nothing corroborates.

**A rung is a property of what the engine can check, not of what was claimed.** `DecisionKind` is a
field on a public record, so an algorithm could otherwise write
`new Decision(…, DecisionKind.DEVICE_PROTECTION, …)` and thereby step around the user's level gate,
walk through the stale-measurement freeze and outrank the engine's own protection for the same
device. An algorithm carrying `EngineOwnedAlgorithm` — a marker interface in a package the bundle
does **not** export (`Private-Package`), so it is unforgeable rather than merely discouraged, which
an id would not have been — keeps whatever rung it claims. Anything else is put to the corroboration
test.

**Corroboration (owner decision D25, 2026-08-03, `openhab-ems-spec/docs/OWNER_DECISIONS.md`).** A
contributed claim to the device-protection rung is honoured exactly when the engine can see the same
thing for itself, from the same cycle's snapshot. Three conjuncts, all in
`DeclaredProtections.corroborate`:

1. the participant's own **effective declaration** carries the protection being claimed — the one
   that survived precedence, never one the contributor supplied with the decision;
2. that protection is **due at this cycle**, measured through `ParticipantState.protectionElapsed`,
   the way every other enforcement point measures it;
3. the **rendered action is the action that protection requires** — an elapsed `maxOff` requires
   `ON`, a running `minOff` requires staying off, and a claim pointing the other way is not
   corroborated by it.

Anything failing one is demoted to `LEVEL_GATE`, judged there on its merits, and **carries the reason
it was demoted in its own `Decision.reason()`**, so a contributor learns why on every surface a
decision reaches rather than in a debug log it would have to be tailing. **Protection-unknown does
not corroborate**: an unreadable history is the absence of evidence, and reading it as corroboration
would put the strongest reachable rung within reach exactly on the sites least able to check it.

> Still closed, and stated rather than left to be discovered: the **electrical-limit rung**. A site's
> limits are the engine's own inputs, not a participant's declaration, so there is nothing to
> corroborate a claim against and a contributed peak-shaver still cannot reach it. D25 names the
> protection rung and nothing else; the peak-shaver half of the question wave 1 raised is recorded as
> open rather than read into an answer that did not mention it.

> Alternatives preserved: the **strict cap** wave 1 shipped — no contributed decision above the level
> gate, full stop; one rule, unforgeable, nothing to get subtly wrong, at the cost of making a
> duty-cycle-aware binding unshippable as a contribution. And **trusting the claim as made**, which
> re-opens exactly the escalation the engine-owned-prohibition rule closed.

**A running load is booked whether or not anybody decided about it.** The floor's ledger pre-books
every consumer reporting itself on, at its measured draw, and releases that booking when a decision
for it is committed or when the floor sheds it. Without that, the ordinary case — a metered device
already in the state it would be commanded to, which therefore gets no decision at all — was counted
in neither the uncontrolled figure (which subtracts it) nor the ledger (which only booked
participants it was forbidden to shed).

**A gap in an accepted declaration is machine-readable.** `EnergyConfigStatus` is a
`ConfigStatusProvider` for the `org.openhab.core.energy` PID carrying five conditions: a declaration
that was refused whole (error), one accepted with a remark (warning), a Simple consumer with no
declared rating, a protected participant whose Item history cannot be read, and a participant naming
an actuation sink that is not installed. It is a **pull**, so it is neither an event nor an Item
write. It is the **pull** half of the participant-condition report; the push half is the cycle event,
which carries the same set in the same words — see §12.

---

## 12. What this bundle publishes, what it does not, and why

The observability requirement asks for an outcome and a reason on every decision, the current cycle
readable, decisions published as deduplicated events, and one engine status Item. D6 adds a fifth
surface in passing: a published current level and a planned `TimeSeries`.

Building wave 1 showed those were blocked for **three different reasons**, which is worth separating
rather than blurring into one "structurally impossible". The owner answered the three separately as
**D23** (2026-08-03, `openhab-ems-spec/docs/OWNER_DECISIONS.md`):

| Surface | The blocker | Where it lives |
|---|---|---|
| **Events** | Nothing in the invariant. Posting an `Event` is *not* an Item write. What blocked it was this bundle's own structural test, whose forbidden-token list was deliberately wider than the rule it stood for | **here** — the list was widened by exactly one token, `EventPublisher`, with the argument recorded on `ShadowModeDemonstrationTest.theBundleContainsNoCodePathThatWritesToAnItem` |
| Engine **status Item**, current-level Item, planned `TimeSeries` | A genuine conflict with the no-write invariant. Writing an Item is exactly what this bundle may not do | `org.openhab.core.energy.publish`, which subscribes to those events |
| **REST** view of the current cycle | Needs JAX-RS, outside the default-library set | `org.openhab.core.energy.publish` in principle — **not built**, see that bundle's README |

### 12.1 The two event types

Exported from `org.openhab.core.energy.events`, built and parsed by `EnergyEventFactory`, posted by
`CycleEventReporter` — the only class here that holds an `EventPublisher`.

| Type | Topic | Carries |
|---|---|---|
| `EnergyDecisionEvent` | `openhab/energy/{participantId}/decision` | One decision: participant, algorithm, priority, kind, outcome, rendered action, what it was trimmed from, reason, cycle timestamp, shadow flag |
| `EnergyCycleEvent` | `openhab/energy/engine/cycle` | One cycle: timestamp, level, shadow, stopped, measurements-stale, participant count, outcome counts, and the participant conditions `EnergyConfigStatus` also carries |

Both are **deduplicated**. A decision is re-emitted only when what the engine concluded about that
participant, from that algorithm, changed; the cycle summary follows the same rule and is also
emitted whenever a decision was, so the two can never describe different cycles. Without that, an
unchanged site would emit one event per decision per tick — around fourteen hundred a day per device
in the mode a fresh installation starts in, which is the log line the requirement exists to replace.

The cost is stated rather than hidden: a subscriber that starts *after* the engine sees nothing until
something changes. A heartbeat re-emit would restore exactly the flood the deduplication prevents,
so the publishing bundle handles it by declaring its Items "awaiting the first cycle" instead.

### 12.2 The invariant did not move

Removing `EventPublisher` from the structural test's list is the one deliberate relaxation in this
bundle, and it is compensated rather than merely argued:

- the list still forbids `sendCommand`, `postUpdate`, `setState(`, `send(` and — newly — the Item
  event classes and `ItemEventFactory`, whose protected constructors make it the *only* way to build
  an `ItemCommandEvent` or an `ItemStateEvent`;
- `theOnlyItemAccessInTheBundleIsReading` independently pins the whole `org.openhab.core.items.`
  prefix to three named read-only files, none of which may hold a publisher;
- `theOnlyPlaceThatPostsAnEventIsTheReporter` pins the publisher to two files and `.post(` to one;
- `everyEventThisBundlePostsIsItsOwn` runs a live site and checks that every event that actually came
  out is one of this bundle's own two types, with the actuation sink still empty.

Four tests where there were two. What also ships: `CycleOutcome` with a `DecisionStatus` and a
free-text reason per decision, `EnergyEngine.lastCycle()`, a rejected-decision counter, deduplicated
`INFO` lines through `RepeatedLineFilter`, and `EnergyConfigStatus` (§10.6) for the declaration
half — a pull, and therefore neither an event nor a write.

---

## 13. The three data planes (wave 2)

Sections 0–12 describe the participant model, the engine and the level plane. Wave 2 added three
data planes and the component that joins them to the level plane. `STAGE2_REPORT.md` records what
was built and what argued back; this section is the type contract.

### 13.1 `org.openhab.core.energy.price`

Prices are **future-timestamped series of currency per unit of energy**, never per-slot channels.

| Type | Contract |
|---|---|
| `EnergyPriceSeries` | slots + currency + energy unit + market zone + direction. Carries `deliveryDay()` (named in the **market's** zone and never inferred), `overwriteWith` (newer publication laid over older, stated in *covered time* rather than timestamps) and `toTimeSeries(Policy)` — the policy is a required argument and has no default |
| `PriceRole` | `SPOT`, `GRID_TARIFF`, `TAXES_AND_FEES`, `FEED_IN`. **Names summands** — this is why it is a different enum from `ForecastRole` |
| `PriceComposition` | the effective consumption price as a user-ordered sum. Refuses a currency, market-zone or **direction** mismatch rather than composing it |
| `PriceDirection` | `CONSUMPTION` / `FEED_IN`, carrying the sign the same kilowatt-hour is valued at in each direction |
| `PriceAdjustment` | `Vat`, `FixedFee`, `Scale`, `Denomination`, `ConditionalTariff`. **Applied in the order the site names**, because the requirement lists four adjustments and never says which order they apply in |
| `SeriesAlignment` | how components of differing geometry are brought onto one geometry. Three implementations, none preferred — an open question the corpus mandates and does not answer |
| `EnergyPriceUnits` | the two places core's currency support does not reach: `ct/kWh` is not expressible, and `toUnit` cannot convert a price even within one currency |

A currency and a market zone are **claims about the data that are never inferred**. Nothing in the
plane guesses either.

### 13.2 `org.openhab.core.energy.forecast`

| Type | Contract |
|---|---|
| `ForecastSeries` | slots + role + source id + unit + run time. Normalises sign at the edge by delegating to the one `SignConvention` rather than carrying a copy |
| `ForecastRole` | `SOLAR_PRODUCTION`, `TEMPERATURE`, `CLOUD_COVER`, `WIND`, `HEATING_DEMAND`. **Names distinct quantities**, which is why it is not `PriceRole`: one enum over both would have halves obeying different arithmetic |
| `ForecastRegistry` | ranked, role-keyed aggregation over contributed sources, tie-broken on the lowest source id |
| `LayeredSeriesResolver` | which layer wins an entry. Ships **nothing selected** — see `LayeredWritePolicy`, an open question |
| `HeatingDemandDerivation` | a pure function of weather and `HeatingDemandParameters`, which ships **no numbers** |
| `ForecastWindows` | window questions over a forecast; pure delegation to `window`, with no second search of its own |

### 13.3 `org.openhab.core.energy.objective`

| Type | Contract |
|---|---|
| `OptimizationObjective` | id, required inputs, `rank(ObjectiveInputs)`. Contributed objectives are first-registration-wins |
| `ObjectiveInputs` | consumption price, feed-in price, carbon series, surplus forecast. `SURPLUS_FORECAST` is **named rather than assumed**: the corpus does not define it |
| `CarbonSeries` | unit-typed, distinguishing an emission intensity from a green share |
| `ExportCarbonCredit` | D20's rule, reversible in exactly one comparison, and reversible by configuration |
| `ExportShare` | how much of a load's energy would otherwise have been exported. Three positions, two evaluation points, because the requirement is not evaluable at one of them alone |
| `AbsentDataPlanePolicy` | what an objective with no data does. Three implementations — an open question |

### 13.4 `org.openhab.core.energy.window`

Promoted in wave 2 to carry the corpus's `cost(window, weights)`.

- **The search runs under the caller's weights**, not only the costing:
  `SelectionStrategy.select(series, request, excluded, weights)` is the method an implementation
  writes, and the three-argument forms are the flat case.
- **`WindowSelection` states its own allocation.** Which slot a load only partly uses is the
  strategy's decision — the last in *time* for a consecutive run, the **worst-ranked** for a ranked
  one — so the answer carries it and `WindowCost` never re-derives it.
- A curve over a **non-consecutive** selection is undefined by the corpus; the reading used here is
  recorded on `WindowCost` rather than assumed.

### 13.5 The join

`internal.EnergyPlanCoordinator` is the only component that turns the data planes into a level plan:
price → `ObjectiveInputs` → objective resolution → `levelDerivationInput` → `derivePlan`. It lives
in **this** bundle because it is arithmetic plus in-process calls, it holds no `EventPublisher`,
names no Item type and touches no persistence, and it hands the schedule to `PlannedLevelPublisher`
rather than publishing it. It creates no thread.

`PlanDerivationCondition` is its own enum rather than part of `LevelPlaneCondition`: "what is true
about the plan now" and "what happened when it was last derived" are true at different times.

### 13.6 The three companion bundles, and the boundary each one crosses

| Bundle | Crosses | Karaf feature |
|---|---|---|
| `org.openhab.core.energy.series` | **reads** an Item's future series through `QueryablePersistenceService`; ships core's generic grid-price provider | `openhab-core-energy-series` |
| `org.openhab.core.energy.forecast.store` | **reads and writes** Item history through `ModifiablePersistenceService`; publishes a derived demand forecast as a future `TimeSeries` | `openhab-core-energy-forecast-store` |
| `org.openhab.core.energy.publish` | **writes** the engine's status, current level and planned schedule to Items | `openhab-core-energy-publish` |

Each depends on `openhab-core-energy` and never the reverse; installing energy management installs
none of them. That is not packaging taste — it is what keeps "the engine writes nothing" a fact a
reader can check from the feature file, and each bundle carries its own bound test asserting both
its own limits and that this bundle does not know it exists.
