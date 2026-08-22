# openhab-core energy prototype

A reference implementation of the energy-management requirements collected in
[openhab-ems-spec](https://github.com/stamateviorel/openhab-ems-spec), built against a real
`openhab-core` checkout. It exists to answer one question: do those requirements survive contact
with a compiler?

They mostly did not, and **that is the point of this repository**. Building it surfaced around
ninety places where the corpus was ambiguous, contradictory or simply silent — priority had no
stated direction, "unacknowledged" was never made operational, the level scale was named in prose
but numbered only inside a test file. Wave 2 turned up seventeen more, listed in
`bundles/org.openhab.core.energy/STAGE2_REPORT.md` §5. The code is the instrument; the defect
lists are the finding.

Context and discussion: [openhab-core#3478](https://github.com/openhab/openhab-core/issues/3478).

## What is here

| Bundle | What it is | Tests |
|---|---|---|
| `org.openhab.core.energy` | participant model, engine loop, level classifier, extension seams, price plane, forecast plane, objectives | 535 |
| `org.openhab.core.energy.series` | grid price series | 26 |
| `org.openhab.core.energy.forecast.store` | persistence-layered forecast store | 24 |
| `org.openhab.core.energy.publish` | Item and status publication | 16 |

**601 tests, 0 failures.** Checkstyle, PMD and SpotBugs report nothing on any of the four;
javadoc is warning-free. Every dependency is an `openhab-core` artifact — nothing outside
openHAB's default set, and no HTTP or WebSocket client anywhere, because the source SPI is
pull-only and core fetches no energy data itself.

## It cannot write to an Item

The engine is shadow-only, and that is structural rather than a matter of discipline:
`org.openhab.core.energy` does not import `org.openhab.core.items.events` **at all**, so there
is no write path to remove — a structural test fails if someone adds one.

Writing to openHAB's own data surfaces is deliberately pushed out of the engine and into
companion bundles that are each their own opt-in feature. Those are the only two places the
Item-event package appears:

| Bundle | imports `items.events` |
|---|---|
| `org.openhab.core.energy` (engine) | **0** |
| `org.openhab.core.energy.series` | 0 |
| `org.openhab.core.energy.forecast.store` | 1 |
| `org.openhab.core.energy.publish` | 1 |

The engine does hold an `EventPublisher`, in exactly one class, and it can post exactly two
things — both of them this bundle's own cycle events. Neither is an Item event. Posting an
event is not a write.

## Building

openHAB bundles do not build standalone — the parent's `directory-maven-plugin` needs the
reactor root. Copy the four directories into an `openhab-addons`-style `openhab-core` checkout
under `bundles/`, register them in `bundles/pom.xml` and `bom/openhab-core`, then:

```
JAVA_HOME=/path/to/temurin-21 mvn clean install
```

Build with `clean`. A warm rebuild trips three false checkstyle findings from the incremental
cache, and that reproduces on a pristine `org.openhab.core` too.

## Status

This is a reference implementation, not a proposal and not anything anyone has endorsed. The
thirty-one design decisions it encodes are recorded as **the author's** decisions in
`openhab-ems-spec/docs/OWNER_DECISIONS.md`, each one keeping the rejected alternatives written
next to the option that was taken — so disagreeing means pointing at an alternative that is
already there, which is one requirement to rewrite rather than a re-run of the analysis.

The implementation is largely Claude's work; the reviewing, testing and deciding are the
author's.

Licensed EPL-2.0, matching `openhab-core`.
