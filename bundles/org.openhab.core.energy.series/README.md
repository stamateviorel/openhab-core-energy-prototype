# Energy Management Series

The Item and persistence edge of the energy data planes.

This bundle exists because `org.openhab.core.energy` provably cannot touch openHAB's data surfaces.
The engine bundle's own tests forbid `.query(`, `FilterCriteria`, `HistoricItem` and `PersistenceService` in its sources, and its compiled classes carry no reference to `org.openhab.core.items.events` at all, so an Item write cannot even resolve there at runtime.
That is deliberate, and it is what makes "the engine writes nothing" a structural fact rather than a promise.
A data plane still has to read a future price series from somewhere, so the reading lives here — the same split owner decision D23 drew for writing, applied to the input side.

## What it does

- Reads a future-timestamped series out of an Item through a queryable persistence service, which is how openHAB 4.1's `forecast` strategy stores what a binding publishes as a `TimeSeries`.
- Registers openHAB's own generic, configurable grid-price provider as an `EnergyPriceSource`, applying the VAT, unit conversion, fixed fee and conditional tariff a site configured.

Every number it produces is computed by pure functions in `org.openhab.core.energy.price`.
Nothing here does arithmetic that is not parsing.

## Its write bound

**This bundle reads. It does not write.**

- It never commands an Item, never updates an Item's state and never mutates the Item registry.
- It never stores to a persistence service and holds no `ModifiablePersistenceService`.
- It posts no event.

`SeriesBundleBoundTest` asserts all three over the comment-stripped sources, and asserts that the engine bundle does not depend on this one.
The forecast plane's layered predictions will need a writer; adding it is a deliberate widening of that test, with the argument written beside it, and not something to discover has already happened.

## Installation

Its own Karaf feature, `openhab-core-energy-series`, which depends on `openhab-core-energy` and never the other way round.
Installing energy management does not install this bundle.

## Configuration

The generic grid-price provider reads `org.openhab.core.energy.gridprice`.
See `OH-INF/config/energy-gridprice.xml` for every parameter; the ones worth knowing before you start:

| Parameter | What it is |
|---|---|
| `item` | The Item carrying the raw future price series. Nothing is read until this is set. |
| `marketZone` | The time zone of the market that produced the prices, which names their delivery day. It is not your own zone and it is never inferred from it. |
| `pipeline` | The adjustments, **in the order your own bill applies them**. There is no fixed order: VAT on a price that already carries a transfer fee is a different number from a fee added afterwards, and the requirement this implements does not say which is meant. |
| `tariffPeriods` | Conditional tariff windows, read in **your** zone rather than the market's. |

The provider registers at `service.ranking = -2`, the number core's own `DefaultStateDescriptionFragmentProvider` uses.
A core-shipped default is a floor, not a privilege: any add-on publishing a better series for the same role outranks it with no configuration at all.

## What is not here

Prices are never fetched.
Core makes no HTTP request for energy data — sources are add-ons, and the one provider core ships reads an Item.
So no `HttpClientFactory`, no `WebSocketClientFactory` and no library outside openHAB's default set appears anywhere in this bundle.
