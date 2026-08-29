# What this is, for someone who would have to maintain it

A reference implementation of native energy management for openHAB, built against a real
`openhab-core` checkout, running in a real OSGi framework. It exists to make
[openhab-core#3478](https://github.com/openhab/openhab-core/issues/3478) concrete enough to argue
with. It is not a pull request and nothing here has been proposed for merge.

The requirements it was built from are in
[openhab-ems-spec](https://github.com/stamateviorel/openhab-ems-spec), collected from that thread and
from three production EMS implementations by its participants, every requirement credited to whoever
first stated it.

## What can be checked in ten minutes

```
mvn -pl bom/openhab-core-index,bom/runtime-index,bom/test-index install
mvn -pl bundles/org.openhab.core.persistence install
mvn -pl bundles/org.openhab.core.energy,bundles/org.openhab.core.energy.series,\
bundles/org.openhab.core.energy.forecast.store,bundles/org.openhab.core.energy.publish clean install
mvn -pl itests/org.openhab.core.energy.tests -Pwith-bnd-resolver-resolve verify
```

| | |
|---|---|
| Bundles | 4, all opt-in Karaf features; none in `openhab-core-base` |
| Unit tests | 601, 0 failures |
| OSGi integration tests | 5, 0 failures |
| Checkstyle / PMD / SpotBugs | 0 findings on all four |
| Javadoc | 0 warnings |
| Dependencies | every one an `openhab-core` artifact; nothing outside the default set |
| HTTP or WebSocket clients | none anywhere; core fetches nothing |

The integration test starts all four bundles in a framework and runs
`Item → persistence → provider → registry → coordinator → level plan` end to end, with nothing wired
by hand. That is the part worth checking first, because it is the part that was pure assumption
until it ran.

## Three things it deliberately will not do

1. **The engine cannot write to an Item.** `org.openhab.core.energy` does not import
   `org.openhab.core.items.events` at all — no write path exists to remove, and a structural test
   fails if one is added. Writing lives in companion bundles that are each their own opt-in feature.
2. **It fetches nothing.** Sources push in; there is no HTTP client in the tree. An existing price
   binding becomes a source without changing shape.
3. **It invents no defaults.** On a fresh install with a price source present it derives *nothing*
   and reports `PRICE_COMPOSITION_FAILED`, because core ships no composition. Installing a source is
   not by itself a working configuration, and that is on purpose.

## What is not done, stated plainly

- **Never verified against a real market feed.** Every price in every test is a fixture, a hand-built
  series, or the integration test's store. No live ENTSO-E or Tibber binding has driven it.
- **Price and solar do not join.** The framework answers "given tomorrow's prices, plan a wallbox"
  end to end. It does not combine a price plan and a solar forecast into one schedule, because the
  quantity that would join them — how much surplus a *future* slot will have — is not defined
  anywhere in the corpus. What ships is an approximation, off by default, that reports itself as
  production rather than surplus.
- **No REST view and no UI.** JAX-RS is outside the default-library set for this bundle; the UI is
  unbuilt.
- **The layer ledger does not survive a restart.**
- **Roughly 90 places** where the requirements were ambiguous, contradictory or silent were found by
  building this, plus 17 more in wave 2. They are listed with what became of each, in
  `PROTOTYPE_REPORT.md` §5 and `STAGE2_REPORT.md` §5. That list, not the code, is the useful output.

## The decisions that are not mine to make

Thirty-one design questions had to be answered before any of this could be typed out. They were
answered by one person — me — so that the corpus could be built from at all, and they are recorded
as **my** decisions in `openhab-ems-spec/docs/OWNER_DECISIONS.md`, never as consensus. Every option
that was rejected is written next to the one that was taken, so overturning any of them means
pointing at an alternative that is already there: one requirement to rewrite, not a re-run of the
analysis.

The architectural question underneath them is genuinely open and is a maintainer's to settle:
**does energy management belong in core at all**, or as an add-on, or as an application on top? This
code does not assume the answer. It is arranged so the engine is separable from everything that
touches openHAB's data surfaces, which is the shape that survives either verdict.

## The ask

Nothing is being proposed for merge. What would be useful, in order:

1. Whether the approach is wrong in a way that makes the rest pointless — better heard now than
   after another wave.
2. Whether the corpus should move somewhere the project owns, rather than sitting in a personal
   repository. The offer to transfer it stands.
3. If neither, that is an answer too, and a fair one. It would just be better said than left.
