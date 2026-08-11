# protogen

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
`@Example` and `@RootNode` are carried into the Javadoc.

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

## Building

```bash
mvn verify
```

Requires JDK 17+ and Maven 3.9+.

## Scope

proto3: all 15 scalar types, `bytes`, enums (incl. nested, aliases, unknown values), nested and recursive
messages, `repeated` (packed and unpacked), `map` (incl. message values), `oneof`, `optional` presence,
`reserved`, imports, cross-file and cross-package references, `java_multiple_files` in both modes, and
comment → Javadoc/validation retention.

Out of scope for v1: gRPC services, `extend`/groups, proto2, editions, JSON mapping, and well-known types
other than `Timestamp`. Unknown fields are dropped rather than preserved. Anything unsupported fails the
build with a `file:line:col` diagnostic rather than generating something wrong.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
