# `org.openhab.core.energy.forecast.store` — the forecast plane's storage edge

> **⚠ THIS BUNDLE WRITES ITEM HISTORY AND, WHEN A SITE ASKS IT TO, ONE ITEM'S TIME SERIES.
> THAT IS WHAT IT IS FOR.**
> Its neighbour `org.openhab.core.energy` **must never write an Item**, may not query persistence,
> and may not so much as name `.store(`, `.query(` or `FilterCriteria` — a hard invariant proved
> there by five structural tests, the strongest of which reads the emitted class files. The two
> bundles have different rules on purpose. If a surface needs storage, it belongs **here**. If it
> does not, it belongs **there**, where it can be reasoned about without asking what it might touch.

## Why this bundle exists

`define-forecast-providers` _Layered prediction series_ asks for a prediction series that is
"read-write and updatable at any time over any of its entries — past, present or future", so that a
coarse year-long baseline can be pre-filled and progressively overwritten by better sources, and a
known cap (an inverter limit, a §14a dimming limit) can be written straight onto the entries it
applies to.

Every word of that is a write, and two of them are writes into the *past*. In openHAB that is
`ModifiablePersistenceService.store(item, timestamp, state)` — which is precisely the call the
framework bundle is built to be incapable of making.

So the plane is split along the line D23 already drew for the engine's status Item:

| Piece | Where | Why |
|---|---|---|
| What a layered write should produce — which entries land, which are refused, what collided | `org.openhab.core.energy` (`LayeredSeriesResolver` → `LayeredWritePlan`) | arithmetic over timestamps; no Item, no persistence, no event, fully unit-testable |
| Carrying that plan out | **here** (`PersistenceLayeredStore`) | `store(...)`, `query(...)`, `FilterCriteria` — forbidden next door |
| Reading an Item's stored future series as a forecast source | **here** (`ItemForecastSource`) | `query(...)`, same reason |
| Deriving heating demand from temperature and solar | `org.openhab.core.energy` (`HeatingDemandDerivation`) | a pure function, which is what the change's own task 3.2 asks for |
| Publishing that derived series onto an Item | **here** (`DerivedHeatingDemandSource`) | an `ItemTimeSeriesEvent` is an Item write |

**The value is computed where nothing can be written; the write happens where writing is the point.**

## What it does

### The layered prediction series

`LayeredPredictionStore.apply(item, layer, series)` applies one writer's values — `BASELINE`,
`FORECAST`, `CAP` or (later) `LEARNED` — to the prediction series an Item carries, and answers with a
`LayeredWriteReport`: what was written, what was refused, where two layers met, and what the plane
has to report about it.

**It requires a persistence service that can modify what it stored.** Not as a preference: core's own
`PersistenceManagerImpl` filters on `instanceof ModifiablePersistenceService` before storing any time
series at all, so a non-modifiable service would not even receive the future half of a prediction.
A site pointed at one is refused and told, with the qualifying services named in the log
(influxdb, inmemory, jdbc, mongodb). Silently appending would meet none of the requirement and look
like it did.

### The collision the specification leaves open — and how it is handled here

_Layered prediction series_ says a refresh "replaces the old ones for those timestamps", and its very
next scenario says the capped entries hold the capped value. A refresh arriving after a cap therefore
erases it, and `design.md` §2 records this as **undecided**, framing three ways out.

This bundle does not decide it. It makes it **visible and configurable**:

| `writePolicy` | What happens |
|---|---|
| *(unset — what a fresh installation has)* | The requirement's own words: the newest write wins. The erased cap is reported as `CAP_OVERWRITTEN_BY_REFRESH`, alongside `WRITE_POLICY_UNCONFIGURED`, and logged as a warning |
| `reapply-caps` | design §2 option 1 — the refresh is applied and the cap is written back on top of it |
| `writer-precedence` | design §2 option 2 — each layer has a rank **the site declares**; no rank ships, and the policy refuses to write until one does |
| `cap-at-read-time` | design §2 option 3 — caps never enter the prediction; they live in their own series and are composed with `min` when the series is read |

**What building all four surfaced** is on `LayeredWritePolicy`'s JavaDoc and is worth repeating here:
the two write-time options are implementable — the framework knows which layer is calling because the
caller says so — but they are **not durable**. Persistence stores a number at a timestamp and nothing
about who wrote it, so the layer ledger lives in memory and a restart forgets it. The only option that
still works after a restart is the one that never writes the cap into the prediction at all.

### The Item-backed forecast source

Any Item carrying a stored future series becomes a forecast source by being named in `series`. That
is what makes _Forecast source fails_ ordinary rather than special: the stored baseline is simply a
source registered at `service.ranking = -2`, so a contributed live service outranks it with no
configuration, and when that service goes dark the plane falls through to the baseline and keeps
planning. Nothing anywhere has a branch for "the forecast is missing".

### The derived heating demand

`DerivedHeatingDemandSource` reads temperature and solar through the framework's `ForecastRegistry`,
runs the framework's pure derivation, and answers as an ordinary forecast source — so a consumer reads
a *derived* demand exactly as it reads a fetched one. If, and only if, the site names an Item in
`demandItem`, the series is also published to it as a time series.

**No building constant ships with a value.** Heat loss, base temperature, solar gain and the four
pre-heating parameters are all undeclared by default, the derivation stays inert until a site supplies
them, and the absence is reported as a named condition rather than looking like a building that needs
no heat. That is D22's pattern — ship the shape, ship no number, report unconfigured — applied to a
plane for which the decision record decided no parameters at all.

## The bound on what it writes

"May write" is a licence that grows if nobody writes the limits down. They are:

- **only Items a site named** — in `series`, `capSeries` or `demandItem`. It has no other way to reach
  an Item name;
- **history and time series, never a command and never a state update** — nothing it does moves a
  device or changes what an Item currently reads;
- **one publishing class and one storing class** — `DerivedHeatingDemandSource` is the only file that
  may call `.post(`, `PersistenceLayeredStore` the only one that may call `.store(`;
- **`TimeSeries.Policy.ADD`, deliberately** — `REPLACE` deletes every stored entry between a series'
  own first and last timestamps, including the baseline entries in any gap it happens to have, which
  is the very series the layered-prediction requirement exists to protect.

All four are asserted in `WriteBoundTest`, which also pins the direction of the dependency: nothing in
`org.openhab.core.energy` names this bundle's package, so a checkout with this directory deleted still
builds and still passes.

## Installation

```
feature:install openhab-core-energy-forecast-store
```

A **third** opt-in feature, after `openhab-core-energy` and `openhab-core-energy-publish`, and for the
same reason as the second: installing the framework must not silently install something that writes.
The framework alone still plans on whatever forecast sources are contributed to it in process.

## Configuration

Two pages, because they are two jobs.

`system:energy-forecast-store` — where the prediction series live and how their writers get on:
`persistenceService`, `series`, `capSeries`, `writePolicy`, `layerPrecedence`.

`system:energy-demand` — the building the heating demand is derived for: `demandItem`, `heatLoss`,
`baseTemperature`, `solarGain`, `preheatHorizon`, `preheatDrop`, `preheatShare`, `preheatModel`.

Not one parameter in either page ships a default.

## Known limitations, stated rather than discovered later

- **The layer ledger does not survive a restart** (above). `cap-at-read-time` is the only policy
  unaffected.
- **A stored series carries no run time.** Persistence records when a value *applies*, never when it
  was *computed*, so `generatedAt` on a series read back out of storage is the moment this framework
  last wrote it — and the epoch for a series it has not written since it started. Staleness reporting
  on a stored series is therefore weaker than on a live source, which carries its own run time.
- **The width of the last stored entry is unknowable.** A series' final value has no successor to end
  it, so a read takes the width of the entry before it. The same gap exists in core's own
  `TimeSeries` transport and is reported as a corpus finding rather than papered over.
- **No cadence.** The derived demand is recomputed when it is read and when its configuration changes;
  nothing polls, because no requirement or decided parameter in the corpus states a refresh interval,
  and inventing one would put a number nobody chose on the load of every site.

## Dependencies

Four, all of them core: `org.openhab.core` (Items, events, library types), `org.openhab.core.config.core`
(configuration parsing), `org.openhab.core.energy` (the plane's types and pure functions),
`org.openhab.core.persistence` (the modifiable service), plus `org.openhab.core.test` in test scope.
Nothing outside the default-library set; no HTTP client anywhere, because core fetches nothing —
sources are add-ons, and the one source shipped here reads an Item.
