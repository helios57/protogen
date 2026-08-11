# Benchmarks — protogen vs protobuf-java

JMH, comparing protogen's generated records against `protobuf-java` **on the same schemas**, byte-identical
encodings verified in `@Setup` before any measurement runs.

## Running them

```bash
mvn -Pbenchmark package -DskipTests
java -jar protogen-benchmark/target/benchmarks.jar                 # everything, ~20 min
java -jar protogen-benchmark/target/benchmarks.jar ScalarsBenchmark
java -jar protogen-benchmark/target/benchmarks.jar -prof gc        # allocation per operation
```

## How to read the shapes

Three shapes are measured because they answer different questions, and reporting only one would flatter
whichever side that shape suits:

| Shape | What it does | Who it favours |
|---|---|---|
| `encode` | serializes **one prepared instance** repeatedly | **protobuf-java** — it memoises `getSerializedSize()` on the instance, so after the first call serialization is a single pass. protogen is an immutable record with nowhere to cache, so it sizes and then writes, every time. |
| `buildAndEncode` | constructs a fresh message, then serializes it | neither — this is the single-shot path most services actually take, and no cache can help with it |
| `decode` | parses a fixed `byte[]` | neither |

## Results

JDK 21, Linux x86-64, 1 fork, 3×1s warmup, 3×1s measurement. **Indicative, not publication grade** — treat
the ratios as the signal, not the absolute nanoseconds.

### Flat message, every scalar type (`ScalarsBenchmark`, ns/op — lower is better)

| Benchmark | protogen | protobuf-java | |
|---|---|---|---|
| `buildAndEncode` | **88.1** | 214.7 | **2.4× faster** |
| `decode` | **172.8** | 195.7 | 1.13× faster |
| `encode` (reused instance) | 74.0 | 73.0 | parity |
| `protoSize` alone | 31.8 | 1.1 | protobuf-java returns a cached value |

The `protoSize` row is not a real workload, it isolates the memoisation: protobuf-java is not computing
anything there, it is reading a field.

### Nested tree, 2 children per level (`NestedBenchmark`, ns/op)

| depth | nodes | | protogen | protobuf-java | |
|---|---|---|---|---|---|
| 1 | 3 | `encode` | 543 | 511 | parity |
| 3 | 15 | `encode` | 4 250 | 2 798 | 1.5× slower |
| 5 | 63 | `encode` | 24 079 | 11 346 | **2.1× slower** |
| 1 | 3 | `decode` | **657** | 815 | 1.24× faster |
| 3 | 15 | `decode` | **3 470** | 4 046 | 1.17× faster |
| 5 | 63 | `decode` | **14 730** | 17 736 | 1.20× faster |
| 1 | 3 | `buildAndEncode` | **905** | 1 349 | 1.49× faster |
| 3 | 15 | `buildAndEncode` | **5 939** | 7 035 | 1.18× faster |
| 5 | 63 | `buildAndEncode` | 30 582 | 29 333 | parity |

### Realistic batch of OpenMetrics KPIs (`KpiBenchmark`, µs/op)

`KpiV1.key` carries a `@Pattern` annotation, so **every protogen message runs the regex** on construction
and on parse. protobuf-java has no notion of the constraint and does no such check — it will happily
produce and accept an invalid metric name. The gap here is largely the price of that guarantee.

| items | | protogen | protobuf-java | |
|---|---|---|---|---|
| 10 | `encode` | 1.68 | 1.40 | 1.20× slower |
| 100 | `encode` | 17.55 | 13.14 | 1.34× slower |
| 10 | `decode` | 3.10 | 2.05 | 1.51× slower |
| 100 | `decode` | 28.64 | 20.49 | 1.40× slower |
| 10 | `buildAndEncode` | 4.06 | 2.74 | 1.48× slower |
| 100 | `buildAndEncode` | 36.80 | 31.72 | 1.16× slower |

## What this tells us

1. **The codec itself is competitive.** On a flat message protogen decodes faster and builds-and-encodes
   2.4× faster; on trees it decodes ~20% faster at every depth. Precomputed tag constants, direct byte
   stores and no descriptor bootstrap do their job.
2. **The missing size cache is the one real cost, and it scales with nesting.** Parity at depth 1, 1.5× at
   depth 3, 2.1× at depth 5 — exactly the repeated `protoSize()` walk. This settles PLAN.md's open question:
   memoisation is worth doing for deep trees.
   * It only bites when the **same instance is serialized more than once**. Serialize-once is the
     `buildAndEncode` row, where the two are at parity even at depth 5.
   * The fix without giving up records: have `toByteArray()` compute child sizes once into a scratch array
     and thread it through `writeTo`, rather than caching on the instance. Phase 6 work.
3. **Validation is not free.** The Kpi decode gap is a regex per message. That is the deliberate trade for
   "an invalid message cannot be constructed" — and it is opt-in per field, since it only exists where the
   schema declares a constraint. `ScalarsBenchmark` shows the codec without it.
4. **protobuf-java's builder is expensive.** It loses the build-then-encode comparison on flat messages by
   2.4× despite winning on the reused-instance one; its memoisation is paid for at construction time.

## Caveats

* Single machine, single JVM, short runs. Re-run with more forks and iterations before quoting these.
* `encode` reuses one instance, which is the best case for protobuf-java and the worst for protogen. Both
  ends of the range are reported on purpose.
* protobuf-java carries features protogen does not — unknown-field retention, reflection, descriptors, JSON.
  Some of its cost buys capability protogen deliberately omits.
* Nothing here measures the actual headline: protogen's output needs **no dependency at all**, while every
  number in the protobuf-java column requires a 1.8 MB jar on the runtime classpath.
