# protogen

**A Maven plugin that generates optimized, fully self-contained Java 17+ sources from `.proto` files.**

Generated code compiles and runs against the **JDK alone** — no `protobuf-java`, no Netty, no runtime jar of
any kind. The build needs no native `protoc` binary either.

> **Status: phase 0 — scaffolding.** The plugin discovers and parses `.proto` files today; code emission lands
> in phases 2–3. See [PLAN.md](PLAN.md).

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

protogen closes that gap. The wire codec is **emitted as source** alongside your messages instead of being
shipped as a jar, and it is written against `byte[]` / `ByteBuffer` rather than a third-party buffer type.

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

Drop `.proto` files in `src/main/proto`. Sources are generated into
`target/generated-sources/protogen` during `generate-sources` and added to the compile source roots
automatically. **No dependency is added to your project** — that is the point.

Parameters are documented in [PLAN.md § 5](PLAN.md#5-maven-plugin-surface).

## Modules

| Module | Purpose |
|---|---|
| `protogen-compiler` | `.proto` → Java source text. Build-tool agnostic, zero dependencies. |
| `protogen-maven-plugin` | The `protogen:generate` Mojo — file discovery, staleness, source roots. |
| `protogen-it` | Acceptance tests: generate → compile with an **empty compile classpath** → verify bytes against `protobuf-java` (test scope only). |

## Building

```bash
mvn verify
```

Requires JDK 17+ and Maven 3.9+.

## Scope

proto3: scalars, `bytes`, enums, nested messages, `repeated` (packed + unpacked), `map`, `oneof`,
`optional` presence, `reserved`, imports, and comment→Javadoc retention.

Out of scope for v1: gRPC services, `extend`/groups, proto2, editions, JSON mapping, well-known types.
Anything unsupported fails the build with a `file:line:col` diagnostic rather than generating something wrong.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
