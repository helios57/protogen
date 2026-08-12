# protogen

[![build](https://github.com/helios57/protogen/actions/workflows/build.yml/badge.svg)](https://github.com/helios57/protogen/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.helios57.protogen/protogen-maven-plugin)](https://central.sonatype.com/artifact/io.github.helios57.protogen/protogen-maven-plugin)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**A Maven plugin that turns `.proto` files into optimized, fully self-contained Java 17+ records.**

The generated code compiles and runs against the **JDK alone** — no `protobuf-java`, no Netty, no runtime
jar of any kind. Your build needs no native `protoc` binary either. Messages interoperate with `protoc`
both ways, verified by a differential suite that compiles the same schemas with both — see
[Compatibility and deviations](#compatibility-and-deviations) for the handful of places the bytes differ
and why.

```xml
<plugin>
    <groupId>io.github.helios57.protogen</groupId>
    <artifactId>protogen-maven-plugin</artifactId>
    <version>0.1.0</version>
    <executions>
        <execution><goals><goal>generate</goal></goals></execution>
    </executions>
</plugin>
```

Drop `.proto` files in `src/main/proto` and build. That is the whole setup — **no dependency is added to
your project**, which is the entire point.

It also reads **[AsyncAPI](#asyncapi-as-input)**, 2.x or 3.x: the models a document's payloads point at are
generated from the document itself, and the channel addresses, function stubs and Spring Cloud Stream
configuration it implies are scaffolded next to them, to read and adapt rather than to compile.

---

## Contents

- [Why](#why) · [Quick start](#quick-start) · [The generated API](#the-generated-api)
- [Configuration](#configuration) · [Examples](#examples)
- [Validation](#validation-from-the-schema) · [Unknown fields](#unknown-fields) ·
  [Timestamps](#timestamps) · [Documentation metadata](#documentation-metadata)
- [AsyncAPI as input](#asyncapi-as-input)
- [Supported schema features](#supported-schema-features) · [Compatibility and deviations](#compatibility-and-deviations) · [Performance](#performance)

## Why

Every other Java generator makes you ship a runtime library:

| Generator | Runtime the generated code needs |
|---|---|
| `protobuf-java` | `protobuf-java` (~1.8 MB, reflection + descriptors) |
| `protobuf-javalite` | `protobuf-javalite` (~200 KB) |
| Square Wire | `wire-runtime` |
| QuickBuffers | `quickbuf-runtime` |
| PBJ | `pbj-runtime` (Gradle only) |
| Protostuff | `protostuff-core` |
| **LightProto** | **`netty-buffer`** — despite its "no runtime dependencies" claim |

Full evaluation with the evidence: **[RESEARCH.md](RESEARCH.md)**.

protogen closes the gap by **emitting the wire codec as source** next to your messages instead of shipping
it as a jar — written against `byte[]` and `int`, and pruned to the helpers your schema actually uses.

## Quick start

```
src/main/proto/order.proto
```

```proto
syntax = "proto3";
package com.example;

option java_multiple_files = true;
option java_package = "com.example.model";

message OrderV1 {
  string id = 1;
  repeated string items = 2;
  int64 totalCents = 3;
}
```

```java
OrderV1 order = new OrderV1("A-4711", List.of("widget", "gasket"), 1999L);

byte[] wire = order.toByteArray();
OrderV1 parsed = OrderV1.parseFrom(wire);

assert parsed.equals(order);
```

Sources are written to `target/generated-sources/protogen` during `generate-sources` and registered as a
compile source root automatically. IDEs pick them up with no extra configuration.

## The generated API

Every message becomes an immutable `record`:

```java
public record NodeV1(
        String name,                       // implicit presence -> "" default, never null
        StageEnumV1 stage,                 // enum default is the constant numbered 0
        String stageSuffix,                // `optional` -> nullable
        List<NodeV1> children,             // unmodifiable, never null
        List<Integer> ports,
        Map<String, String> endpoints,     // unmodifiable, insertion ordered
        CoordinatesV1 location,            // message presence -> nullable
        Instant createdAt) {               // google.protobuf.Timestamp -> Instant

    // parsing
    public static NodeV1 parseFrom(byte[] data);
    public static NodeV1 parseFrom(byte[] data, int offset, int length);

    // writing
    public byte[] toByteArray();
    public int writeTo(byte[] target, int offset);   // returns the new position
    public int protoSize();                          // bytes toByteArray() will produce
}
```

**What the compact constructor does for you:** normalises `null` to the proto3 default, copies collections
into unmodifiable views, defensively copies `byte[]` in *and* out, enforces the constraints declared in the
schema, and rejects two members of the same `oneof` being set at once.

Enums get `number()` and `forNumber(int)`, plus an `UNRECOGNIZED` constant so a value from a newer schema
never breaks an older reader. A `oneof` adds a `<name>Case()` accessor and a matching enum.

Every Java package gets its own **package-private** `ProtoWire` codec, and a message's public surface is
`byte[]` and `int` only — so generated packages never depend on one another, and nothing is shared.

## Configuration

All parameters, with their defaults. Each also has a `-D` property, shown in the last column.

| Parameter | Default | What it does | Property |
|---|---|---|---|
| `protoSourceRoot` | `${basedir}/src/main/proto` | directory scanned for `.proto` files | `protogen.protoSourceRoot` |
| `outputDirectory` | `${project.build.directory}/generated-sources/protogen` | where Java lands; added as a compile source root | `protogen.outputDirectory` |
| `includes` | `**/*.proto` | glob patterns to generate, relative to the source root. A leading `**/` is optional, so a file in the root still matches | – |
| `excludes` | *(none)* | glob patterns to skip | – |
| `javaPackage` | from `option java_package` | override the target package for every file | `protogen.javaPackage` |
| `emitJavadoc` | `true` | carry schema comments into the generated Javadoc | `protogen.emitJavadoc` |
| `emitValidation` | `true` | generate the checks declared by `@Minimum` / `@Pattern` style annotations | `protogen.emitValidation` |
| `preserveUnknownFields` | `false` | keep fields this build does not know in a trailing component | `protogen.preserveUnknownFields` |
| `emitSchemaMetadata` | `true` | write the JSON documentation sidecar | `protogen.emitSchemaMetadata` |
| `resourceOutputDirectory` | `${project.build.directory}/generated-resources/protogen` | where the sidecars land; added as a project resource | `protogen.resourceOutputDirectory` |
| `failOnUnsupported` | `true` | fail the build on unsupported constructs rather than skipping them | `protogen.failOnUnsupported` |
| `skip` | `false` | skip the execution entirely | `protogen.skip` |

And the [AsyncAPI](#asyncapi-as-input) parameters, all inert until `asyncApiSourceRoot` is set:

| Parameter | Default | What it does | Property |
|---|---|---|---|
| `asyncApiSourceRoot` | *(unset — feature off)* | directory scanned for AsyncAPI documents, 2.x or 3.x, YAML or JSON | `protogen.asyncApiSourceRoot` |
| `scaffoldOutputDirectory` | `${project.build.directory}/protogen-scaffold` | where the scaffolding lands. **Never compiled and never a source root** — point it at `src/main/java` only if you want it in your sources, and know it overwrites | `protogen.scaffoldOutputDirectory` |
| `scaffoldPackage` | the package of the generated messages | package for the scaffolded Java | `protogen.scaffoldPackage` |
| `scaffoldChannels` | `true` | a typed address record per channel | `protogen.scaffoldChannels` |
| `scaffoldStubs` | `true` | a `java.util.function` stub per operation | `protogen.scaffoldStubs` |
| `scaffoldBinderConfig` | `true` | Spring Cloud Stream bindings for the Solace binder | `protogen.scaffoldBinderConfig` |
| `scaffoldNotes` | `true` | a README explaining what each scaffolded file is | `protogen.scaffoldNotes` |

There is one **runtime** switch, read by the generated code rather than the plugin:

| Property | Default | Effect |
|---|---|---|
| `-Dprotogen.validation=false` | validation on | disables the generated constraint checks for the whole JVM |

## Examples

### Minimal

```xml
<plugin>
    <groupId>io.github.helios57.protogen</groupId>
    <artifactId>protogen-maven-plugin</artifactId>
    <version>0.1.0</version>
    <executions>
        <execution><goals><goal>generate</goal></goals></execution>
    </executions>
</plugin>
```

### Filtering which schemas are generated

```xml
<configuration>
    <protoSourceRoot>${project.basedir}/src/main/schemas</protoSourceRoot>
    <includes>
        <include>**/*V1.proto</include>
    </includes>
    <excludes>
        <exclude>internal/**</exclude>
    </excludes>
</configuration>
```

### A relay that must not drop what it does not understand

```xml
<configuration>
    <preserveUnknownFields>true</preserveUnknownFields>
</configuration>
```

```java
// fields written against a newer schema pass through untouched
RelayedV1 in = RelayedV1.parseFrom(wire);
byte[] out = in.toByteArray();
assert Arrays.equals(out, wire);
```

### Two source roots with different settings

A schema cannot be generated two ways into the same package, so give each its own execution:

```xml
<executions>
    <execution>
        <id>generate</id>
        <goals><goal>generate</goal></goals>
    </execution>
    <execution>
        <id>generate-relay</id>
        <goals><goal>generate</goal></goals>
        <configuration>
            <protoSourceRoot>${project.basedir}/src/main/proto-relay</protoSourceRoot>
            <preserveUnknownFields>true</preserveUnknownFields>
            <emitValidation>false</emitValidation>
        </configuration>
    </execution>
</executions>
```

### Generating test fixtures

```xml
<execution>
    <id>generate-test-schemas</id>
    <phase>generate-test-sources</phase>
    <goals><goal>generate</goal></goals>
    <configuration>
        <protoSourceRoot>${project.basedir}/src/test/proto</protoSourceRoot>
        <outputDirectory>${project.build.directory}/generated-test-sources/protogen</outputDirectory>
    </configuration>
</execution>
```

### Spec-first: models from the AsyncAPI document, scaffolding into the source tree

```xml
<configuration>
    <!-- no proto source root: every model comes from a payload ref -->
    <asyncApiSourceRoot>${project.build.directory}/filtered/asyncapi</asyncApiSourceRoot>
    <scaffoldOutputDirectory>${project.basedir}/src/main/java</scaffoldOutputDirectory>
    <scaffoldPackage>com.example.messaging</scaffoldPackage>
    <scaffoldStubs>false</scaffoldStubs>
    <scaffoldBinderConfig>false</scaffoldBinderConfig>
</configuration>
```

Keeps the channel address records, which are worth having under version control, and drops the stubs and
the binder configuration, which are a starting point rather than something to keep regenerating.

### Reading legacy data that predates a constraint

```bash
java -Dprotogen.validation=false -jar migration-tool.jar
```

### From the command line

```bash
mvn protogen:generate -Dprotogen.preserveUnknownFields=true
mvn verify -Dprotogen.skip=true
```

## Validation from the schema

Annotations in a field's leading comment become checks in the generated constructor, so an invalid message
cannot be built — by hand *or* by parsing:

```proto
message ConstrainedV1 {
  /*
   * @MinLength 3
   * @MaxLength 10
   * @Example abcdef
   */
  string instanceId = 1;

  // @Pattern ^[A-Z]{2}\d+$
  string code = 2;

  // @Minimum 1
  // @Maximum 100
  int32 msgIndex = 3;

  // @Required
  string tenant = 4;
}
```

```java
new ConstrainedV1("ab", "CH42", 5, "acme");
// IllegalArgumentException: ConstrainedV1.instanceId violates @MinLength 3, was: 2
```

| Annotation | Applies to | Check |
|---|---|---|
| `@MinLength n` / `@MaxLength n` | `string` | length bounds |
| `@Pattern regex` | `string` | compiled once into a `static final Pattern` |
| `@Minimum n` / `@Min n` | numeric | inclusive lower bound |
| `@Maximum n` / `@Max n` | numeric | inclusive upper bound |
| `@ExclusiveMinimum n` / `@ExclusiveMaximum n` | numeric | exclusive bounds |
| `@MultipleOf n` | integral | must be an exact multiple |
| `@MinItems n` / `@MaxItems n` | `repeated`, `map` | size bounds |
| `@Required` | any | must be present, i.e. non-default |
| `@Example v`, `@RootNode` | any | documentation only — Javadoc and the metadata sidecar |

Two independent switches control it:

| Switch | Where | Effect |
|---|---|---|
| `<emitValidation>false</emitValidation>` | plugin config | the checks are never generated |
| `-Dprotogen.validation=false` | JVM, runtime | a `static final boolean` the JIT folds away, so carrying the capability costs nothing |

> **Worth knowing:** proto3 cannot distinguish *absent* from *default*, so a constraint on an
> implicit-presence field is enforced on **every** instance — it effectively becomes required. Put the
> constraint on an `optional` field if it should only apply when the value is set.

`oneof` invariants are structural rather than schema validation, and stay enforced under both switches.

## Unknown fields

Off by default, because the extra component shows up in every constructor call, `equals` and `toString`.
Turn it on for a service that relays messages it does not fully own:

```xml
<configuration>
    <preserveUnknownFields>true</preserveUnknownFields>
</configuration>
```

Each record then gains a trailing `byte[] unknownFields`. Unrecognised tags are copied verbatim,
re-emitted after the known fields, and included in `equals`/`hashCode` — so a message written against a
newer schema survives a round trip byte for byte instead of being silently truncated.

## Timestamps

`google.protobuf.Timestamp` surfaces as `java.time.Instant` and travels as an **`int64` of epoch
milliseconds** — not the standard seconds+nanos submessage.

A peer built with `protoc` must therefore declare the field as **`optional int64`**. The `optional` matters:
a `Timestamp` field has message presence, so an instant at the epoch is a real value that must go on the
wire, where a bare `int64` would treat zero as absent and drop it. That equivalence is asserted byte for
byte in `protogen-interop`, not assumed. Sub-millisecond precision is not transmitted.

## Documentation metadata

`@Example` and `@RootNode` are documentation rather than behaviour, so they stay out of the runtime. Each
`.proto` gets a JSON sidecar at `META-INF/protogen/<file>.json`, on the classpath for a docs pipeline to
read without re-parsing the schema or reflecting over the classes:

```json
{
  "schemaVersion": 1,
  "file": "kpiV1.proto",
  "javaPackage": "com.example.model",
  "messages": [
    {
      "name": "KpiV1", "javaType": "com.example.model.KpiV1", "rootNode": true,
      "fields": [
        { "name": "key", "number": 1, "type": "string", "label": "singular",
          "examples": ["jvm_memory_committed_bytes"],
          "constraints": { "pattern": "^[a-zA-Z_:][a-zA-Z0-9_:]*$" } }
      ]
    }
  ]
}
```

Disable with `<emitSchemaMetadata>false</emitSchemaMetadata>`.

## AsyncAPI as input

Point `asyncApiSourceRoot` at a directory of AsyncAPI documents — **2.x or 3.x, YAML or JSON** — and
protogen reads them too. Two different things come out, and the difference matters:

| | What | Where it goes |
|---|---|---|
| **Models** | the `.proto` a payload names, and everything it imports | the normal output, compiled into your artifact |
| **Scaffolding** | channel records, function stubs, binder configuration | a throwaway directory, **never compiled** |

```xml
<configuration>
    <asyncApiSourceRoot>${project.basedir}/src/main/asyncapi</asyncApiSourceRoot>
</configuration>
```

> Specs are often filtered for `@project.version@` and friends. If yours are, point this at the **filtered**
> output rather than the source tree.

### The models

A document that names a schema is the contract for it:

```yaml
components:
  messages:
    OrderCreated:
      contentType: application/protobuf
      schemaFormat: 'application/vnd.google.protobuf;version=3'
      payload:
        schema:
          $ref: schemas/orderEventV1.proto
```

That `.proto` is generated exactly as if it had been under `protoSourceRoot`, and whatever it imports comes
along. A **spec-first project needs no proto source root at all** — leave it unset (or pointing at nothing)
and every model comes from the documents. The ref is resolved next to the document first, then against
`protoSourceRoot`, then by file name under either; a ref that matches nothing is a warning naming the
channel, never a silently missing model. Payloads that are not protobuf are left alone.

### The scaffolding

Everything below is **help, not build output**. It is written to `target/protogen-scaffold`, is never added
as a source root, and nothing in a normal build compiles it — a generator cannot know how your application
is wired, and code it guessed at has no business in a live source tree. Read it, take what is useful,
delete the rest. Each of the four kinds has its own switch, and `scaffoldOutputDirectory` will put it
wherever you want, including straight into `src/main/java`.

**A record per channel**, whose components are the address parameters in address order, validated against
what the document actually says about them:

```java
new IncrementalChannel("acme", "prod").address();
// example/metric/incremental/acme/prod

new IncrementalChannel("acme", "staging");
// IllegalArgumentException: stage must be one of [dev, int, prod], was: staging
```

**A `java.util.function` stub per operation** — `Supplier<byte[]>` to send, `Consumer<byte[]>` to receive:

```java
public class ReadControlListener implements Consumer<byte[]> {
    @Override
    public void accept(byte[] message) {
        // the content type says gzip, so decompress 'message' first
        // KpiCollectionV1 payload = KpiCollectionV1.parseFrom(message);
        throw new UnsupportedOperationException("scaffold: handle the message");
    }
}
```

The stubs bind **`byte[]`, not the generated records**, because a `byte[]` is what actually crosses the
binder. Turning a payload into those bytes — and compressing it when the content type says gzip — is
application logic, and where that belongs is a decision the generator deliberately does not make. It points
at the step and leaves it to you.

**The Spring Cloud Stream configuration** for the Solace binder, with the destinations taken from the
channel addresses — the part that is tedious and easy to get wrong by hand:

```yaml
spring:
  cloud:
    function:
      definition: |
        publishIncremental;
        readControl
    stream:
      default:
        binder: solace
      bindings:
        publishIncremental-out-0:
          destination: example/metric/incremental/{tenant}/{stage}
          contentType: "application/gzip-protobuf"
        readControl-in-0:
          destination: example/metric/control
      solace:
        bindings:
          readControl-in-0:
            consumer:
              queueNameExpression: "destination.trim().replaceAll('[*>]', '_')"
```

`send` becomes an `-out-0` binding and `receive` an `-in-0` one; only consumers get a queue. Connection
details, credentials and tuning are absent on purpose — they belong to the environment, not to an API
description. A destination containing `{parameters}` has to be resolved before use, which is exactly what
the channel records do.

### What is read from which version

2.x keys channels by their address and hangs `publish`/`subscribe` off them; 3.0 gives a channel an id plus
an `address` and moves the direction into a separate `operations` section. Both are read into one model, so
everything downstream behaves the same. For a 2.x document the channel id is derived from the literal
segments of its address. `$ref`s into `components` are resolved; parameter constraints are read from the
parameter itself (3.0) or from its nested `schema` (2.x).

## Supported schema features

| Feature | | Notes |
|---|---|---|
| all 15 scalar types | ✅ | including `sint*` zig-zag and `fixed*` |
| `bytes` | ✅ | defensively copied in and out |
| `enum`, nested, `allow_alias`, lower-case constants | ✅ | proto3's zero-value rule enforced; unknown values become `UNRECOGNIZED` |
| nested and recursive messages | ✅ | |
| cross-file and cross-package references | ✅ | proto scoping: innermost scope outward, leading dot forces absolute |
| `repeated` | ✅ | packed per the syntax default, `[packed = ...]` honoured; packed **and** unpacked accepted on read |
| `map<K,V>` | ✅ | including message values |
| `oneof` | ✅ | siblings cleared on parse, at most one enforced on construction |
| `optional` explicit presence | ✅ | |
| `reserved` | ✅ | numbers, ranges, `to max` and names — **enforced** |
| `import`, `import public` | ✅ | **enforced**: a file may only name types from itself, its imports, and what those re-export with `import public` |
| `java_package`, `java_multiple_files`, `java_outer_classname` | ✅ | both file layouts, incl. protoc's `OuterClass` collision suffix |
| `google.protobuf.Timestamp` | ✅ | as `Instant`, see above |
| comment → Javadoc, comment → validation | ✅ | |
| **proto2**: `required` / `optional` / `repeated`, `[default = ...]`, no zero-enum rule, `extensions` ranges | ✅ | a declared default is exposed as `<field>OrDefault()`, so presence is not lost |
| field options: `packed`, `default`, `deprecated`, `json_name`, custom `(...)` | ✅ | parsed and kept; `packed` and `default` are acted on |
| see [Compatibility and deviations](#compatibility-and-deviations) | | for what is not supported and why |

Anything unsupported **fails the build with a located error**, never a silently wrong result.

## Performance

Measured against `protobuf-java` on identical schemas — full numbers and caveats in
**[BENCHMARKS.md](BENCHMARKS.md)**.

| | protogen vs protobuf-java |
|---|---|
| Build a message and encode it | **1.9–2.1× faster**, flat or nested |
| Decode | **1.3–1.5× faster** on trees and flat messages, and allocates less |
| Encode the *same instance* repeatedly, nested 5 deep | **1.34× faster** |
| `protoSize()` on its own, nothing else | **70× slower** — protobuf-java returns a memoised field, protogen computes |
| Schemas with `@Pattern` | slower by the cost of the regex, which buys a guarantee protobuf-java does not offer |

A length-delimited field has to know its payload size before writing it, and an immutable record has
nowhere to cache one. Rather than give up records, the sizing pass records each nested size in the order
the write reads it back, so nothing is measured twice — which is what turned encoding a deep tree from
2.1× slower into 1.34× faster.

```bash
mvn -Pbenchmark package -DskipTests
java -jar protogen-benchmark/target/benchmarks.jar
```

## Compatibility and deviations

protogen aims at **mutual readability with `protoc`, not byte-identity**. In practice the encodings are
identical for everything except the cases below, and where they differ both sides still read each other.
Every deviation is listed here with the reason.

### Deliberate deviations from protoc

| Deviation | Why | Consequence |
|---|---|---|
| **`google.protobuf.Timestamp` travels as an `int64` of epoch millis**, not a seconds+nanos submessage | the submessage costs a nested length-delimited frame per timestamp for precision nobody in practice uses | a protoc peer must declare the field `optional int64`. Sub-millisecond precision is lost. Asserted byte-for-byte in `protogen-interop` |
| **Map entry order is insertion order** | `LinkedHashMap`, so output is deterministic | protoc's order is unspecified, so bytes may differ for multi-entry maps. Both sides parse either |
| **Unknown enum values become `UNRECOGNIZED`** and are dropped on re-encode | keeping the raw number needs a second component per enum field | protoc round-trips them. Do not use protogen for a relay that must preserve enum values it does not know |
| **Unknown fields are dropped** unless `preserveUnknownFields` is on | the extra component shows up in every constructor, `equals` and `toString` | turn the flag on for relays |

### Known limitations

- **Deep nesting overflows the stack.** Parsing recurses one Java frame per nesting level, with no depth
  limit; roughly 20 000 levels overflows. `protobuf-java` caps recursion at 100 and rejects beyond it.
  This is fine for schemas you control and **not** fine for untrusted input — if you parse bytes from
  outside your trust boundary, don't use protogen for it. Removing the recursion means a two-phase
  span-scan-then-build parser, which is a redesign rather than a patch.
- **`protoSize()` on its own is recomputed, not memoised** — a record has nowhere to cache. Encoding is
  unaffected: `toByteArray()` measures each nested payload once and the write reads those sizes back. It
  costs only if you call `protoSize()` repeatedly on an instance you never serialize. See
  [BENCHMARKS.md](BENCHMARKS.md).
- **An import is matched by file name when the path does not match.** Imports are written relative to the
  proto root while protogen knows each file by the name it was read under, so `model/common.proto` matches
  a file read as `common.proto`. Two files with the same base name in different directories are therefore
  indistinguishable to the import check.

### Not supported, by design

| | Why |
|---|---|
| **Extensions** (`extend`, and custom options beyond being recorded) | extensions are inherently open and dynamic; supporting them means an extension registry, which is exactly the shared runtime protogen exists to remove. `extensions` **ranges** are parsed so a proto2 schema declaring them still compiles |
| **Groups** | the deprecated proto2 nested encoding. Rejected with a hint to use a nested message |
| **Services / gRPC** | protogen generates models, not transports |
| **JSON mapping** | needs a JSON library or a hand-rolled one in *generated* code; out of scope for a wire-format generator |
| **Well-known types other than `Timestamp`** | `Any` and `Struct` need dynamic typing; `Duration`, `FieldMask` and friends would be easy but nobody has needed them |
| **Editions (2023/2024)** | watching, not implementing |
| **Non-protobuf AsyncAPI payloads** (JSON Schema, Avro) | a document's protobuf payloads are generated, the rest are read and left alone — protogen is a protobuf generator that happens to read AsyncAPI, not the other way round |

Anything in this table is **rejected with a `file:line:col` diagnostic**, never silently mis-generated.

## Modules

| Module | Purpose |
|---|---|
| `protogen-compiler` | `.proto` and AsyncAPI → Java source text. Build-tool agnostic. Its one dependency is a YAML parser, used at generation time only — nothing reaches the generated code, which stays JDK-only. |
| `protogen-maven-plugin` | The `protogen:generate` Mojo. |
| `protogen-it` | The zero-dependency proof: no compile-scope dependencies, generated code compiled and exercised. |
| `protogen-interop` | The differential proof: the same schemas compiled by `protoc`, encodings compared byte for byte. |
| `protogen-benchmark` | JMH benchmarks against `protobuf-java`. |

## Building

```bash
mvn verify
```

Requires JDK 17+ and Maven 3.9+. CI runs the same build on JDK 17, 21 and 25 and asserts that
`protogen-it` still has no compile-scope dependency — the headline claim is checked against the artifact,
not merely trusted. Dependency and action versions are kept current by Dependabot.

Design notes and the roadmap are in [PLAN.md](PLAN.md); releasing is documented in
[RELEASING.md](RELEASING.md).

## License

MIT — see [LICENSE](LICENSE).
