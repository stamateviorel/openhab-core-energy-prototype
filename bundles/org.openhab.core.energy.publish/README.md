# `org.openhab.core.energy.publish` — the energy framework's publishing component

> **⚠ THIS BUNDLE WRITES ITEMS. THAT IS WHAT IT IS FOR.**
> Its neighbour `org.openhab.core.energy` **must never write an Item** — that is a hard invariant,
> proved there by five structural and behavioural tests, the strongest of which reads the emitted
> class files and shows that the Item-event types are not even resolvable in that bundle. The two
> bundles have different rules on purpose. If a surface needs a write, it belongs **here**. If it does
> not, it belongs **there**, where it can be reasoned about without asking what it might touch.

## Why there are two bundles

Requirement pack A8 of the energy corpus asks for four observability surfaces: an outcome and a
reason on every decision, the current cycle readable, decisions published as deduplicated events,
and one engine status Item. D6 adds a fifth in passing — a published current level.

Building the wave-1 slice showed that three of those were blocked inside a framework that writes
nothing, **for three different reasons**:

| Surface | What actually blocked it | Where it lives now |
|---|---|---|
| Decision + cycle events | Nothing in the invariant. Posting an `Event` is not an Item write; what blocked it was the framework's own structural test, whose forbidden-token list was deliberately wider than the rule it stood for | `org.openhab.core.energy` — the list was widened on purpose, with the argument recorded beside it |
| Engine status Item, current level | A genuine conflict with the no-write invariant | **here** |
| REST view | A dependency outside the default-library set (JAX-RS) | here in principle — **not built**, see below |

The owner answered that as **D23** (2026-08-03,
`openhab-ems-spec/docs/OWNER_DECISIONS.md`): events stay with the framework, the status Item and the
REST view move out. Alternatives preserved in `define-engine-contract` design.md §23 — (a) relax A8
for the framework and drop all three surfaces; (b) move *everything*, events included, into this
bundle.

## What it does

It subscribes to the two events the framework publishes and maintains two Items.

| Item | Type | Carries |
|---|---|---|
| `EnergyEngineStatus` | `String` | One sentence about the last reported cycle: level, mode, participant count, stale-measurement and participant-condition caveats, outcome counts, and the most recent decision |
| `EnergyCurrentLevel` | `String` | The energy level the engine derived from that cycle's own snapshot — `BLOCKED`, `NORMAL`, `ENCOURAGED`, `OVERCAPACITY` |

Both are **provided** by this bundle (`EnergyItemProvider`), not created by the user: installing the
component is the whole of the setup, and removing it removes them — an energy status Item with no
energy framework behind it is a stale number somebody will read. Their names are constants on the
public `org.openhab.core.energy.publish.EnergyItems` class, so a rule or a sitemap can refer to them
without guessing.

Example status line:

```
OVERCAPACITY · shadow · 5 participants · 1 participant condition · 2 shadowed · last: SHADOWED boiler ON
```

While the framework's master stop is engaged both Items say so, rather than repeating the last level
they saw — a stopped engine takes no snapshot, so it has no level, and a rule keyed on a stale
verdict would go on acting.

## The bound on what it writes

"May write Items" is a licence that grows if nobody writes the limits down. They are:

- **two Items**, both provided by this bundle, so it can never scribble on something a user declared;
- **state updates only, never commands** — nothing it does moves a device;
- **on change only**, mirroring the framework's own deduplication, so an unchanged site produces no
  bus traffic.

All three are asserted in `EnergyStatusPublisherTest`.

## Installation

```
feature:install openhab-core-energy-publish
```

It is a **second opt-in feature** rather than part of `openhab-core-energy`, deliberately: installing
the framework must not silently install something that creates and writes Items. That is the
distinction the split exists to make visible. The framework alone still reports — on the event bus.

## Known limitation: a cold start is quiet

The framework's events are **deduplicated**, so a publishing component that starts *after* the engine
hears nothing until something changes. Both Items therefore begin on
`EnergyItems.AWAITING_FIRST_CYCLE` — a real state rather than `NULL`, because "nothing has been
reported yet" and "this is broken" are different conditions and an operator has to be able to tell
them apart.

The alternative — a periodic heartbeat re-emit from the framework — was not taken: it restores
exactly the per-tick flood that the deduplication exists to prevent, which at the default cadence is
around fourteen hundred identical events a day.

## Not built: the REST view

A8's fourth surface, "the current cycle readable over REST", is **not implemented here**, and that is
recorded rather than forgotten:

- it needs JAX-RS (`javax.ws.rs.*`), which is outside the default-library set the openHAB coding
  guidelines allow without maintainer discussion, and the wave-1 rules forbid the framework bundle a
  new dependency;
- D23 settled *where* it would go if it is built — here, beside the status Item, never in the
  framework — but not *that* it must be;
- everything a REST resource would serve already leaves the framework on the event bus, so a UI can
  subscribe over SSE today without any of it. What REST would add is a poll for the current state
  rather than a wait for the next change, which is the same cold-start gap described above.

Building it is a maintainer decision about a new dependency in core, not a design question this
corpus can settle on its own.

## Dependencies

Three, all of them core:

- `org.openhab.core` — Items, the event bus, the string library type
- `org.openhab.core.energy` — the two event types and their payloads
- `org.openhab.core.test` — test scope only
