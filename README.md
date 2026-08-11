# protogen

[![build](https://github.com/helios57/protogen/actions/workflows/build.yml/badge.svg)](https://github.com/helios57/protogen/actions/workflows/build.yml)

**A Maven plugin that generates optimized, fully self-contained Java 17+ sources from `.proto` files.**

Generated code compiles and runs against the **JDK alone** — no `protobuf-java`, no Netty, no runtime jar
of any kind. The build needs no native `protoc` binary either.

> **Status:** proto3 works end to end. 310 tests, including a differential suite that compiles the same
> schemas with `protoc` and compares the encodings byte for byte. See [PLAN.md](PLAN.md).

## Why

Every existing Java generator makes you ship a runtime library:

| Generator | Runtime the generated code needs |
|---|---|
| `protobuf-java` | `protobuf-java` (~1.8 MB, reflection + descriptors) |
| `protobuf-javalite` | `protobuf-javalite` (~200 KB) |
| Square Wire | `wire-runtime` |
| QuickBuffers | `quickbuf-runtime` |
| PBJ | `pbj-runtime` (Gradle only) |
| Protostuff | `protostuff-core` |
| **LightProto** | **`netty-buffer`** — despite the "no runtime dependencies" claim |

Full evaluation, with the criteria and the evidence: **[RESEARCH.md](RESEARCH.md)**.

protogen closes that gap. The wire codec is **emitted as source** next to your messages instead of shipped
as a jar, written against `byte[]` and `ByteBuffer` rather than a third-party buffer type — and pruned to
the helpers your schema actually uses.

## Usage

```xml
<plugin>
    <groupId>io.github.helios57.protogen</groupId>
    <artifactId>protogen-maven-plugin</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <executions>
        <execution>
            <goals><goal>generate</goal></goals>
        </execution>
    </executions>
</plugin>
```

Drop `.proto` files in `src/main/proto`. Sources land in `target/generated-sources/protogen` during
`generate-sources` and are added to the compile source roots automatically. **No dependency is added to
your project** — that is the point.

## What the generated code looks like

```java
public record NodeV1(
        String name,                       // implicit presence -> "" default, never null
        StageEnumV1 stage,
        String stageSuffix,                // `optional` -> nullable
        List<NodeV1> children,             // unmodifiable, never null
        Map<String, String> endpoints,
        Instant createdAt) {               // google.protobuf.Timestamp -> Instant

    public static NodeV1 parseFrom(byte[] data);
    public byte[] toByteArray();
    public int writeTo(byte[] target, int offset);
    public int protoSize();
}
```

Messages are **immutable records**. The compact constructor normalises absent values to their proto3
defaults, copies collections into unmodifiable views, and enforces the constraints declared in the schema.

Every Java package gets its own package-private `ProtoWire` codec, and the public surface of a message is
`byte[]` and `int` only — so packages never depend on one another and nothing is shared.

## Validation from the schema

Annotations in a field's leading comment become checks in the generated constructor, so an invalid message
cannot be built — by hand or by parsing:

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
}
```

Supported: `@MinLength`, `@MaxLength`, `@Pattern`, `@Minimum`/`@Min`, `@Maximum`/`@Max`,
`@ExclusiveMinimum`, `@ExclusiveMaximum`, `@MultipleOf`, `@MinItems`, `@MaxItems`, `@Required`.

Validation has **two independent switches**:

| Switch | Where | Default |
|---|---|---|
| `<emitValidation>` | plugin config, generation time | `true` — omit the checks from the bytecode entirely |
| `-Dprotogen.validation=false` | JVM, runtime | on — folds the checks away without regenerating |

The runtime one is a `static final boolean`, so switching it off costs nothing at all. It is also how you
get a **lenient parse** for legacy data that predates a constraint, without weakening the constraint for
everyone else.

## Unknown fields

Off by default; turn it on for a service that relays messages it does not fully own:

```xml
<configuration>
    <preserveUnknownFields>true</preserveUnknownFields>
</configuration>
```

Each record then gains a trailing `byte[] unknownFields`. Fields this build has never heard of are copied
verbatim, re-emitted after the known ones, and included in `equals`/`hashCode` — so a message written
against a newer schema survives a round trip byte for byte instead of being silently truncated.

## Documentation metadata

`@Example` and `@RootNode` are documentation rather than behaviour, so they stay out of the runtime. Each
`.proto` gets a JSON sidecar at `META-INF/protogen/<file>.json` describing root nodes, examples,
constraints, field numbers and the Java names everything ended up with — for a docs pipeline to read off
the classpath without re-parsing the schema:

```json
{
  "name": "KpiV1", "javaType": "com.java.proto.model.proto.KpiV1", "rootNode": true,
  "fields": [ { "name": "key", "number": 1, "type": "string",
                "examples": ["jvm_memory_committed_bytes"],
                "constraints": { "pattern": "^[a-zA-Z_:][a-zA-Z0-9_:]*$" } } ]
}
```

Switch it off with `<emitSchemaMetadata>false</emitSchemaMetadata>`.

## Timestamps

`google.protobuf.Timestamp` surfaces as `java.time.Instant` and travels as an **`int64` of epoch
milliseconds**. A peer built with `protoc` must declare the field as `optional int64` — `optional` because
a `Timestamp` field has message presence, so an instant at the epoch must still go on the wire. That
equivalence is asserted byte for byte in `protogen-interop`, not assumed.

## Modules

| Module | Purpose |
|---|---|
| `protogen-compiler` | `.proto` → Java source text. Build-tool agnostic, zero dependencies. |
| `protogen-maven-plugin` | The `protogen:generate` Mojo. |
| `protogen-it` | The zero-dependency proof: no compile-scope dependencies, generated code compiled and exercised. |
| `protogen-interop` | The differential proof: the same schemas compiled by `protoc`, encodings compared byte for byte. |
| `protogen-benchmark` | JMH benchmarks against `protobuf-java` on the same schemas. |

## Building

```bash
mvn verify
```

Requires JDK 17+ and Maven 3.9+. CI runs the same build on JDK 17, 21 and 25, and asserts that
`protogen-it` still has no compile-scope dependencies. Dependency and action versions are kept current by
Dependabot, grouped into one PR per fleet so an update either passes the full suite or says which bump
broke it.

## Releasing

Not on Maven Central yet — the build is wired for it and waits only on the publishing credentials. See
**[RELEASING.md](RELEASING.md)**: create a Central Portal account with the GitHub login (which verifies the
`io.github.helios57` namespace automatically), add four repository secrets, then push a `v*` tag.

## Performance

Measured against `protobuf-java` on identical schemas — full numbers and caveats in
**[BENCHMARKS.md](BENCHMARKS.md)**.

| | protogen vs protobuf-java |
|---|---|
| Build a flat message and encode it | **2.4× faster** |
| Decode | **1.1–1.2× faster**, at every nesting depth |
| Encode the *same instance* repeatedly, nested 5 deep | **2.1× slower** — protobuf-java memoises its size, a record has nowhere to cache |
| Schemas with `@Pattern` constraints | slower by the cost of the regex, which buys a guarantee protobuf-java does not offer |

```bash
mvn -Pbenchmark package -DskipTests
java -jar protogen-benchmark/target/benchmarks.jar
```

## Scope

proto3: all 15 scalar types, `bytes`, enums (incl. nested, aliases, unknown values), nested and recursive
messages, `repeated` (packed and unpacked), `map` (incl. message values), `oneof`, `optional` presence,
`reserved`, imports, cross-file and cross-package references, `java_multiple_files` in both modes, and
comment → Javadoc/validation retention.

Out of scope for v1: gRPC services, `extend`/groups, proto2, editions, JSON mapping, and well-known types
other than `Timestamp`. Unknown fields are dropped rather than preserved. Anything unsupported fails the
build with a `file:line:col` diagnostic rather than generating something wrong.

## License

MIT — see [LICENSE](LICENSE).
