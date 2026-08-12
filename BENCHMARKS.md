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

JDK 21, Linux x86-64, 1 fork, 3×1s warmup, 4×1s measurement. **Indicative, not publication grade** — treat
the ratios as the signal, not the absolute nanoseconds.

### Flat message, every scalar type (`ScalarsBenchmark`, ns/op — lower is better)

| Benchmark | protogen | protobuf-java | |
|---|---|---|---|
| `buildAndEncode` | **88.3** | 183.2 | **2.1× faster** |
| `decode` | **160.0** | 213.5 | 1.33× faster |
| `encode` (reused instance) | 72.5 | 66.3 | 1.09× slower |
| `protoSize` alone | 32.1 | 0.44 | protobuf-java returns a cached value |

The `protoSize` row is not a real workload, it isolates the memoisation: protobuf-java is not computing
anything there, it is reading a field.

### Nested tree, 2 children per level (`NestedBenchmark`, ns/op)

| depth | nodes | | protogen | protobuf-java | |
|---|---|---|---|---|---|
| 1 | 3 | `encode` | **419** | 626 | 1.49× faster |
| 3 | 15 | `encode` | **2 207** | 3 205 | 1.45× faster |
| 5 | 63 | `encode` | **9 997** | 13 432 | 1.34× faster |
| 1 | 3 | `decode` | **543** | 783 | 1.44× faster |
| 3 | 15 | `decode` | **2 759** | 4 018 | 1.46× faster |
| 5 | 63 | `decode` | **11 855** | 16 893 | 1.43× faster |
| 1 | 3 | `buildAndEncode` | **675** | 1 347 | 2.00× faster |
| 3 | 15 | `buildAndEncode` | **3 680** | 6 913 | 1.88× faster |
| 5 | 63 | `buildAndEncode` | **15 681** | 29 864 | 1.90× faster |

### Realistic batch of OpenMetrics KPIs (`KpiBenchmark`, µs/op)

`KpiV1.key` carries a `@Pattern` annotation, so **every protogen message runs the regex** on construction
and on parse. protobuf-java has no notion of the constraint and does no such check — it will happily
produce and accept an invalid metric name. The remaining decode gap is largely the price of that guarantee.

| items | | protogen | protobuf-java | |
|---|---|---|---|---|
| 10 | `encode` | **1.13** | 1.46 | 1.29× faster |
| 100 | `encode` | **10.82** | 11.89 | 1.10× faster |
| 10 | `decode` | 2.26 | 2.11 | 1.07× slower |
| 100 | `decode` | 24.15 | 20.75 | 1.16× slower |
| 10 | `buildAndEncode` | 3.16 | 2.70 | 1.17× slower |
| 100 | `buildAndEncode` | **30.45** | 30.96 | parity |

Allocation, same schema, `-prof gc`: **86.3 kB/op** to decode a batch of 100 against protobuf-java's
98.1 kB/op.

## What this tells us

1. **The codec is faster than protobuf-java nearly everywhere**, and by the widest margin on the shape most
   services actually run: build a message, serialize it once, throw it away. Precomputed tag constants,
   direct byte stores and no descriptor bootstrap do their job.
2. **Sizing each nested payload once was worth more than any micro-optimisation.** A length-delimited field
   has to know how long its payload is before writing it, and a record has nowhere to cache one, so the
   write used to re-measure every subtree the sizing pass had just measured — a full re-descent per level.
   The sizing pass now records those sizes in the order the write reads them back. Encoding a tree five
   deep went from 24.1 µs to 10.0 µs, which turned a 2.1× loss into a 1.34× win.
3. **Validation is not free.** The Kpi decode gap is a regex per message. That is the deliberate trade for
   "an invalid message cannot be constructed" — and it only exists where the schema declares a constraint.
   `ScalarsBenchmark` shows the codec without it.
4. **protobuf-java's builder is expensive.** It loses build-then-encode on flat messages by 2.1× and on
   trees by ~1.9× despite winning the isolated `protoSize` comparison; its memoisation is paid for at
   construction time.
5. **`protoSize()` on its own is still 70× slower**, and always will be: it computes, protobuf-java reads a
   field. It only matters if you call `protoSize()` repeatedly on an instance you never serialize.

## What was tried and rejected

Kept here so it is not re-attempted: **encoding strings character by character straight into the output
buffer**, to avoid the array `String.getBytes(UTF_8)` allocates. It measured 1.7× slower on a flat message
(72.5 ns → 123.2 ns) and 1.5× slower on a batch of KPIs. On a compact (Latin-1) string `getBytes` is an
intrinsic that moves bytes in bulk; a hand-rolled loop cannot compete. The allocation stays.

## Caveats

* Single machine, single JVM, short runs. Re-run with more forks and iterations before quoting these.
* `encode` reuses one instance, which is the best case for protobuf-java and the worst for protogen. Both
  ends of the range are reported on purpose.
* protobuf-java carries features protogen does not — unknown-field retention by default, reflection,
  descriptors, JSON. Some of its cost buys capability protogen deliberately omits.
* Nothing here measures the actual headline: protogen's output needs **no dependency at all**, while every
  number in the protobuf-java column requires a 1.8 MB jar on the runtime classpath.

## Generation time

Not part of the JMH suite — measured directly, since it is build cost rather than runtime cost. A 95 kB
schema (200 messages of 20 fields, plus one message of 1 000 fields) producing 205 Java files:

| stage | warm |
|---|---|
| parse | 1.1 ms |
| link | 0.2 ms |
| generate | 11.2 ms |

Small enough to disappear next to `javac` compiling the output. Two things did show up while profiling and
were fixed: checking field numbers for duplicates scanned a list per field, quadratic in field count and
most of the link time for a wide message (0.31 ms → 0.07 ms for 1 000 fields), and the source writer split
every line it emitted looking for embedded newlines that almost none have.
