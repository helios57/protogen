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
| `buildAndEncode` | **92.1** | 184.0 | **2.0× faster** |
| `decode` | **159.8** | 211.7 | 1.32× faster |
| `encode` (reused instance) | 71.1 | 66.5 | 1.07× slower |
| `protoSize` alone | 32.3 | 0.48 | protobuf-java returns a cached value |

The `protoSize` row is not a real workload, it isolates the memoisation: protobuf-java is not computing
anything there, it is reading a field.

### Nested tree, 2 children per level (`NestedBenchmark`, ns/op)

| depth | nodes | | protogen | protobuf-java | |
|---|---|---|---|---|---|
| 1 | 3 | `encode` | **365** | 649 | 1.78× faster |
| 3 | 15 | `encode` | **2 131** | 3 291 | 1.54× faster |
| 5 | 63 | `encode` | **9 321** | 13 492 | 1.45× faster |
| 1 | 3 | `decode` | **477** | 778 | 1.63× faster |
| 3 | 15 | `decode` | **2 497** | 4 004 | 1.60× faster |
| 5 | 63 | `decode` | **10 330** | 18 290 | 1.77× faster |
| 1 | 3 | `buildAndEncode` | **715** | 1 375 | 1.92× faster |
| 3 | 15 | `buildAndEncode` | **3 613** | 6 938 | 1.92× faster |
| 5 | 63 | `buildAndEncode` | **15 445** | 29 454 | 1.91× faster |

### Realistic batch of OpenMetrics KPIs (`KpiBenchmark`, µs/op)

`KpiV1.key` carries a `@Pattern` annotation, so **every protogen message checks it** on construction and
on parse. protobuf-java has no notion of the constraint and does no such check — it will happily produce
and accept an invalid metric name. protogen still wins these, having stopped paying a regex engine for it.

| items | | protogen | protobuf-java | |
|---|---|---|---|---|
| 10 | `encode` | 1.22 | 0.99 | 1.24× slower |
| 100 | `encode` | **10.88** | 11.74 | 1.08× faster |
| 10 | `decode` | **1.28** | 2.04 | 1.59× faster |
| 100 | `decode` | **13.61** | 20.35 | 1.49× faster |
| 10 | `buildAndEncode` | **1.61** | 2.69 | 1.67× faster |
| 100 | `buildAndEncode` | **18.04** | 31.48 | 1.75× faster |

Allocation, same schema, `-prof gc`: **73.5 kB/op** to decode a batch of 100 against protobuf-java's
98.1 kB/op, and **71.7 kB/op** to build and encode one against 105.1 kB/op.

### What the constraint costs, isolated

Running the same benchmark with `-Dprotogen.validation=false` says what the `@Pattern` is worth, which is
the only honest way to compare against a library that does not offer the feature:

| decode, 100 items | time | allocation |
|---|---|---|
| validation on, as a regex | 23.3 µs | 86.3 kB |
| validation on, as a scan | **13.6 µs** | 73.5 kB |
| validation off | 12.2 µs | 73.5 kB |

The regex was **48% of decoding**. Written out as a scan the same guarantee costs about 10%, and allocates
nothing — the remaining gap to "off" is the character comparisons themselves.

## What this tells us

1. **The codec is faster than protobuf-java nearly everywhere**, and by the widest margin on the shape most
   services actually run: build a message, serialize it once, throw it away. Precomputed tag constants,
   direct byte stores and no descriptor bootstrap do their job.
2. **Sizing each nested payload once was worth more than any micro-optimisation.** A length-delimited field
   has to know how long its payload is before writing it, and a record has nowhere to cache one, so the
   write used to re-measure every subtree the sizing pass had just measured — a full re-descent per level.
   The sizing pass now records those sizes in the order the write reads them back. Encoding a tree five
   deep went from 24.1 µs to 9.3 µs, which turned a 2.1× loss into a 1.45× win.
3. **A constraint no longer costs a regex engine.** An anchored `@Pattern` built from character classes is
   compiled into a scan over the string, so the check that used to allocate a `Matcher` per message now
   allocates nothing. Patterns needing real backtracking keep the regex.
4. **protobuf-java's builder is expensive.** It loses build-then-encode on flat messages by 2.0× and on
   trees by ~1.9× despite winning the isolated `protoSize` comparison; its memoisation is paid for at
   construction time.
5. **`protoSize()` on its own is still ~67× slower**, and always will be: it computes, protobuf-java reads a
   field. It only matters if you call `protoSize()` repeatedly on an instance you never serialize.

## What was tried and rejected

Kept here so they are not re-attempted.

**Encoding strings character by character straight into the output buffer**, to avoid the array
`String.getBytes(UTF_8)` allocates. It measured 1.7× slower on a flat message (72.5 ns → 123.2 ns) and 1.5×
slower on a batch of KPIs. On a compact (Latin-1) string `getBytes` is an intrinsic that moves bytes in
bulk; a hand-rolled loop cannot compete. The allocation stays.

**Handing a caller's list to the immutable wrapper** instead of to `List.copyOf`, for symmetry with the
map. Building a tree five deep went from 15.4 µs to 18.7 µs: a caller's list has to be copied either way,
and `List.copyOf` makes one compact copy where the wrapper adds an `ArrayList` and an object around it.
Only `parse`, whose list nobody else can reach, hands its list over.

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
