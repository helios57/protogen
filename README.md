# protogen

[![build](https://github.com/helios57/protogen/actions/workflows/build.yml/badge.svg)](https://github.com/helios57/protogen/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.helios57.protogen/protogen-maven-plugin)](https://central.sonatype.com/artifact/io.github.helios57.protogen/protogen-maven-plugin)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**A Maven plugin that turns `.proto` files into optimized, fully self-contained Java 17+ records.**

The generated code compiles and runs against the **JDK alone** — no `protobuf-java`, no Netty, no runtime
jar of any kind. Your build needs no native `protoc` binary either. The wire format is byte-identical to
`protoc`, verified by a differential test suite that compiles the same schemas with both.

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

---

## Contents

- [Why](#why) · [Quick start](#quick-start) · [The generated API](#the-generated-api)
- [Configuration](#configuration) · [Examples](#examples)
- [Validation](#validation-from-the-schema) · [Unknown fields](#unknown-fields) ·
  [Timestamps](#timestamps) · [Documentation metadata](#documentation-metadata)
- [Supported proto3](#supported-proto3) · [Performance](#performance) · [Limitations](#limitations)

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

## Supported proto3

| Feature | | Notes |
|---|---|---|
| all 15 scalar types | ✅ | including `sint*` zig-zag and `fixed*` |
| `bytes` | ✅ | defensively copied in and out |
| `enum`, nested, `allow_alias`, lower-case constants | ✅ | proto3's zero-value rule enforced; unknown values become `UNRECOGNIZED` |
| nested and recursive messages | ✅ | |
| cross-file and cross-package references | ✅ | proto scoping: innermost scope outward, leading dot forces absolute |
| `repeated` | ✅ | packed on write, packed **and** unpacked accepted on read |
| `map<K,V>` | ✅ | including message values |
| `oneof` | ✅ | siblings cleared on parse, at most one enforced on construction |
| `optional` explicit presence | ✅ | |
| `reserved` | ✅ | parsed |
| `import`, `import public` | ✅ | |
| `java_package`, `java_multiple_files`, `java_outer_classname` | ✅ | both file layouts, incl. protoc's `OuterClass` collision suffix |
| `google.protobuf.Timestamp` | ✅ | as `Instant`, see above |
| comment → Javadoc, comment → validation | ✅ | |
| other well-known types, `service`/gRPC, `extend`, groups, proto2, editions, JSON mapping | ❌ | rejected with a `file:line:col` diagnostic |

Anything unsupported **fails the build with a located error**, never a silently wrong result.

## Performance

Measured against `protobuf-java` on identical schemas — full numbers and caveats in
**[BENCHMARKS.md](BENCHMARKS.md)**.

| | protogen vs protobuf-java |
|---|---|
| Build a flat message and encode it | **2.4× faster** |
| Decode | **1.1–1.2× faster**, at every nesting depth |
| Encode the *same instance* repeatedly, nested 5 deep | **2.1× slower** — protobuf-java memoises its size, a record has nowhere to cache |
| Schemas with `@Pattern` | slower by the cost of the regex, which buys a guarantee protobuf-java does not offer |

```bash
mvn -Pbenchmark package -DskipTests
java -jar protogen-benchmark/target/benchmarks.jar
```

## Limitations

- **Unknown fields are dropped** unless `preserveUnknownFields` is on.
- **An unknown enum value becomes `UNRECOGNIZED`** and is not re-encoded; `protoc` keeps the raw number.
- **`protoSize()` is recomputed rather than memoised** — a record has no mutable field to cache in. This
  costs nothing when a message is serialized once, and up to 2.1× when the same deep instance is
  serialized repeatedly.
- **No gRPC, no JSON mapping, no proto2, no editions.**

## Modules

| Module | Purpose |
|---|---|
| `protogen-compiler` | `.proto` → Java source text. Build-tool agnostic, zero dependencies. |
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
