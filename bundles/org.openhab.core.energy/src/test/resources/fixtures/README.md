# Conformance fixtures

These four CSV files are **copied verbatim** from the `fixtures/` directory of the `openhab-ems-spec`
OpenSpec corpus. They are not illustrations and not test data invented here: the corpus declares them
acceptance vectors, so a classifier that does not reproduce them exactly is wrong, not "close".

| File | Role |
|---|---|
| `dayahead-prices.csv` | input - 24 hourly day-ahead prices |
| `expected-planned-levels.csv` | expected level code per slot (3 = cheapest, 0 = dearest) |
| `expected-heating-control.csv` | expected ON/OFF per slot for the 8 cheapest hours |
| `expected-boiler-control.csv` | expected ON/OFF per slot for the next 3, excluding the heating hours |

Do not edit them to make a test pass. If one of them is wrong, that is a finding for the corpus.
Verify they are still identical with `diff` against the corpus copy before trusting a green run.
