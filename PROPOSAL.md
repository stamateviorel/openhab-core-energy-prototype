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

These bundles are written against `openhab-core` and live inside its reactor; this repository holds
them on their own so they can be read without one. So the first step is putting them back, and **the
step that is easy to miss is registering them as modules** - without it Maven reports that the paths
do not exist.

```
# 1. a core checkout to build them in
git clone --depth 1 https://github.com/openhab/openhab-core.git
cp -r <this repo>/bundles/org.openhab.core.energy*     openhab-core/bundles/
cp -r <this repo>/itests/org.openhab.core.energy.tests openhab-core/itests/

# 2. register the four bundles in openhab-core/bundles/pom.xml as <module> entries:
#      org.openhab.core.energy
#      org.openhab.core.energy.series
#      org.openhab.core.energy.forecast.store
#      org.openhab.core.energy.publish

# 3. the bundles themselves, from the openhab-core root
cd openhab-core
mvn -pl bundles/org.openhab.core.energy,bundles/org.openhab.core.energy.series,\
bundles/org.openhab.core.energy.forecast.store,bundles/org.openhab.core.energy.publish clean install

# 4. the bnd indexes, AFTER the jars exist - they record each jar's checksum
mvn -pl bundles/org.openhab.core.persistence install
mvn -pl bom/openhab-core-index,bom/runtime-index,bom/test-index install

# 5. the integration tests
mvn -pl itests/org.openhab.core.energy.tests -Pwith-bnd-resolver-resolve verify
```

The order matters from step 3 onwards. The indexes record a checksum per jar and only ever contain
bundles **actually built in that checkout**, so generating them before the bundles - or rebuilding a
bundle afterwards - fails with `Invalid content checksum` or a bare `missing requirement`.

Steps 1 to 3 were re-run from nothing on 2026-10-04 against `openhab-core` at `04764dc`, which is
current `main` rather than the branch these were written on: **628 unit tests, 0 failures**, with
checkstyle, PMD, SpotBugs and spotless clean. They were 608 at the August freeze; the capacity-tariff
package added twenty.

**An earlier version of this section gave commands that could not work against this repository.**
They assumed the core reactor was already around them - referring to `bom/` and to
`bundles/org.openhab.core.persistence`, neither of which is here - and omitted the module
registration entirely. Anyone who tried it hit an error inside the first minute. That is fixed
above, and the sequence is the one actually run on the date given.

| | |
|---|---|
| Bundles | 4, all opt-in Karaf features; none in `openhab-core-base` |
| Unit tests | 628, 0 failures |
| OSGi integration tests | 7, 0 failures |
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
   `org.openhab.core.items.events` at all — no write path exists to remove, a structural test fails
   if one is added, and an integration test confirms against the **running framework** that OSGi has
   wired it no route to that package, while the publishing companion is wired to exactly that
   package. Writing lives in companions that are each their own opt-in feature.
2. **It fetches nothing.** Sources push in; there is no HTTP client in the tree. An existing price
   binding becomes a source without changing shape.
3. **It invents no defaults where a guess would be acted on.** On a fresh install with a price
   source present it derives *nothing* and reports `PRICE_COMPOSITION_FAILED`, because core ships no
   composition. It will not assume a currency or a market zone either — a guessed denomination gets
   printed onto a page somebody acts on. Where a default *is* shipped it is a recorded decision with
   its alternatives kept (D32, D33), never a convenience.

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
