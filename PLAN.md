# protogen — implementation plan

**Goal:** a Maven plugin that turns `.proto` files into **optimized, fully self-contained Java 17+ sources**.
Generated code compiles and runs against the **JDK only** — no `protobuf-java`, no Netty, no `protogen` jar.
The build itself needs no native `protoc`.

See [RESEARCH.md](RESEARCH.md) for why this does not exist yet.

---

## 1. Non-negotiable invariants

| # | Invariant | Enforced by |
|---|---|---|
| I1 | Generated sources import **only** `java.*` | `ImportPurityTest` — scans every generated `.java` for non-`java.` imports and FQNs |
| I2 | Wire format is byte-identical to `protoc` | differential tests vs `protobuf-java` (**test scope only**) |
| I3 | The IT module has **zero compile-scope dependencies** | `maven-enforcer-plugin` `banTransitiveDependencies` + explicit check |
| I4 | No reflection, no `Class.forName`, no descriptor bootstrap in generated code | `ImportPurityTest` + review |
| I5 | Build requires no `protoc` binary and no network-fetched toolchain | own parser |

I1 and I3 are the whole point of the project. They get automated tests in Phase 1, before there is anything to test.

## 2. Module layout

```
protogen/
├── pom.xml                     parent — Java 17, dependency & plugin management
├── protogen-compiler/          pure library: .proto  ->  Java source text
│   ├── lexer/  parser/  ast/   front-end (no deps)
│   ├── model/                  linked/resolved schema model
│   └── gen/                    emitters (message, enum, wire-codec, builder)
├── protogen-maven-plugin/      the Mojo — thin wrapper over protogen-compiler
└── protogen-it/                integration tests: sample .proto -> generate -> compile -> assert
```

`protogen-compiler` is deliberately free of Maven types so it can later be driven from a CLI, Gradle, or a test
harness. `protogen-maven-plugin` only does file discovery, staleness checks and `addCompileSourceRoot`.

## 3. Generated API contract

For `message BrokerMonitoringV1` with `java_package = ch.sbb.tms.ssp.model`, `java_multiple_files = true`:

```java
package ch.sbb.tms.ssp.model;

public final class BrokerMonitoringV1 {

    // ---- construction -------------------------------------------------
    public static Builder newBuilder();
    public static Builder newBuilder(BrokerMonitoringV1 prototype);
    public Builder toBuilder();

    // ---- accessors ----------------------------------------------------
    public String getTmsAbbl1();              // implicit presence -> "" default, never null
    public boolean hasStageSuffix();          // explicit `optional` -> presence tracked
    public String getStageSuffix();
    public StageEnum getStage();              // enum -> real Java enum + UNRECOGNIZED
    public int getStageValue();               // raw wire value, for forward compatibility
    public List<BrokerMonitoringV1> getMonitoredBrokersList();   // unmodifiable
    public Map<String, String> getSempUrlsMap();                 // unmodifiable

    // ---- serialization ------------------------------------------------
    public static BrokerMonitoringV1 parseFrom(byte[] data);
    public static BrokerMonitoringV1 parseFrom(byte[] data, int off, int len);
    public static BrokerMonitoringV1 parseFrom(ByteBuffer buf);
    public static BrokerMonitoringV1 parseFrom(InputStream in) throws IOException;
    public byte[] toByteArray();
    public int writeTo(byte[] target, int offset);
    public void writeTo(OutputStream out) throws IOException;
    public int getSerializedSize();           // cached

    // ---- value semantics ----------------------------------------------
    public boolean equals(Object o);          // deep, Arrays.equals for bytes
    public int hashCode();                    // cached
    public String toString();                 // protobuf-ish text form, for logs

    public static final class Builder { /* fluent setters, addX, putX, build() */ }
}
```

**Decisions:**

* **Immutable message + nested `Builder`.** Matches `protobuf-java` ergonomics so migrating an existing SBB service
  is a package rename, not a rewrite. A mutable/reusable "zero-allocation" mode (QuickBuffers-style) is a later
  opt-in flag, not the default.
* **Not a `record`.** `bytes` fields are `byte[]`; record-generated `equals`/`hashCode` would compare by identity.
  We emit explicit `equals`/`hashCode`/`toString`.
* **proto3 semantics are honoured, not approximated:** implicit-presence scalars are omitted from the wire when
  equal to the default; fields declared `optional` get a real presence bit.
* **Unknown fields are preserved** (raw `byte[]` tail, re-emitted in tag order) — required for round-tripping
  through a service that has an older schema. Opt-out flag `preserveUnknownFields=false` for minimum footprint.
* **Enums** become Java enums plus an `UNRECOGNIZED` constant and `forNumber(int)` / `getNumber()`, so an unknown
  wire value never throws.

### The self-containment trick

The wire codec cannot live in a jar, so it is **emitted as source**: one `ProtoWire.java` per generated tree
(package configurable, default `<commonJavaPackage>.protogen`). It contains varint/zigzag/fixed32/fixed64 read+write,
UTF-8 encode/decode, tag handling, and a growable output buffer. Its only imports are
`java.nio.*`, `java.io.*`, `java.util.*`, `java.lang.invoke.VarHandle`.

This is LightProto's idea with the Netty removed: `byte[]` + explicit offset, plus `ByteBuffer` overloads.
`MethodHandles.byteArrayViewVarHandle` gives us `fixed32`/`fixed64` at `Unsafe` speed with a JDK-only import.

## 4. Front-end: parse `.proto` ourselves

Every rejected generator except Protostuff and Wire is a **protoc plugin**. Shelling out to a native binary
contradicts "fully independent", so we own the front-end.

**Option A (chosen): hand-written lexer + recursive-descent parser.** ~1500 LOC, no ANTLR runtime in the plugin,
full control over **comment retention** — the SBB protos carry `@Example` / `@MinLength` annotations in leading
comments and we want them in the generated Javadoc. protoc-based generators throw these away.

**Option B (fallback): `protostuff-parser` or `wire-schema`.** Saves the parser work at the cost of an ANTLR/Kotlin
dependency *in the plugin* (never in generated code, so I1 survives). Revisit only if Option A slips.

Grammar scope for v0.1 — driven by what the SBB `.proto` files actually use:

| Feature | v0.1 | Notes |
|---|---|---|
| `syntax = "proto3"` | ✅ | |
| `package`, `option java_package` / `java_multiple_files` / `java_outer_classname` | ✅ | all present in SBB protos |
| `import`, `import public` | ✅ | 12 files use it |
| scalars (`string bool int32 int64 uint32 uint64 double float fixed* sfixed* sint* bytes`) | ✅ | |
| `enum` incl. nested + `allow_alias` | ✅ | 14 files |
| nested `message` | ✅ | |
| `repeated` (packed + unpacked decode) | ✅ | 19 files |
| `optional` (explicit presence) | ✅ | 8 files |
| `map<K,V>` | ✅ | 3 files |
| `oneof` | ✅ | not used by SBB protos yet, cheap to add, high risk to retrofit |
| `reserved` | ✅ | parse + enforce |
| comment retention → Javadoc | ✅ | the differentiator |
| well-known types (`Timestamp`, `Any`, …) | ❌ v0.2 | would be generated as plain messages, no JSON mapping |
| `service` / gRPC | ❌ | out of scope |
| `extend` / groups / proto2 | ❌ | proto2 in v0.3 if ever needed |
| editions (2023/2026) | ❌ | watch, do not implement |
| JSON mapping | ❌ | explicitly out of scope for v1 |

Anything outside the scope must fail with a **clear, located diagnostic** (`file:line:col: message`), never a
silent wrong result.

## 5. Maven plugin surface

Goal `protogen:generate`, default phase `generate-sources` (plus `generate-test-sources` for `generate-test`).

| Parameter | Default | Purpose |
|---|---|---|
| `protoSourceRoot` | `${basedir}/src/main/proto` | input tree |
| `includes` / `excludes` | `**/*.proto` / – | filtering |
| `outputDirectory` | `${project.build.directory}/generated-sources/protogen` | output, auto-added as compile source root |
| `javaPackage` | from `option java_package` | override |
| `runtimePackage` | `<commonJavaPackage>.protogen` | where `ProtoWire.java` lands |
| `preserveUnknownFields` | `true` | footprint vs. round-trip fidelity |
| `emitJavadoc` | `true` | comment retention |
| `checkStaleMillis` | `0` | incremental regeneration |
| `failOnUnsupported` | `true` | I5 discipline |

UX benchmark is `ascopes/protobuf-maven-plugin`. The Mojo must be **incremental** (skip when no `.proto` is newer
than its output) and must not break `mvn -o` offline builds.

## 6. Verification strategy

`protobuf-java` is our **oracle, in test scope only** — it never touches the generated code's classpath.

1. **Round-trip:** `protogen.encode(msg)` → `protobuf-java.parse` → assert field-equal, and the reverse.
2. **Byte-identity:** for canonical messages, assert `protogen` bytes equal `protoc` bytes exactly
   (field order, packed-ness, default omission).
3. **Differential fuzz:** random schema-conforming instances, both directions, ~10k cases in CI.
4. **Truncated / malformed input:** must throw a defined exception, never loop or over-read.
5. **`ImportPurityTest`:** greps every generated file — any non-`java.` import fails the build (I1).
6. **Zero-dependency compile:** `protogen-it` compiles the generated sources with an **empty compile classpath**
   and runs them (I3). This is the acceptance test for the entire project.

Sample `.proto` files live in `protogen-it/src/main/proto`. The seeded ones are neutral fixtures that exercise the
exact feature set of the SBB protos (see §4 table); drop the real test files in alongside them.

## 7. Phases

| Phase | Deliverable | Done when |
|---|---|---|
| **0** | Scaffolding: parent pom, 3 modules, Mojo stub wired into `generate-sources`, sample protos, CI | `mvn verify` green; plugin runs and logs discovered `.proto` files |
| **1** | Front-end: lexer, parser, AST, import resolution, type linking, diagnostics | parses all sample protos + a hostile-syntax corpus; errors carry `file:line:col` |
| **2** | `ProtoWire.java` emitter + codegen for scalars, strings, bytes, enums, nested messages, presence | round-trip vs `protobuf-java` green for a flat message |
| **3** | `repeated` (packed & unpacked), `map`, `oneof` | round-trip green for all sample protos |
| **4** | Mojo hardening: includes/excludes, incremental, offline, multi-module, `addCompileSourceRoot` | `protogen-it` builds from a clean `~/.m2` with `-o` |
| **5** | Verification suite (§6 items 1–6), CI matrix on JDK 17/21/25 | zero-dependency compile+run test green — **project goal met** |
| **6** | Optimization: precomputed tag constants, `VarHandle` fixed paths, UTF-8 fast path, cached size/hash, JMH harness | benchmarked ≥ `protobuf-java` on encode/decode, materially less allocation |
| **7** | Release: Javadoc from comments, `README` usage, semantic versioning, publish to GitHub Packages / Maven Central | a consumer project compiles with **no** protobuf dependency |

Phases 0–5 are the MVP. Phase 6 is where "optimized" gets earned — deliberately *after* correctness.

## 8. Risks

| Risk | Mitigation |
|---|---|
| proto3 default/presence semantics are subtle — easy to be subtly wrong | differential testing against `protobuf-java` from Phase 2, not Phase 5 |
| Hand-written parser under-covers real-world `.proto` | hostile corpus in Phase 1; `failOnUnsupported=true` so gaps are loud |
| "Self-contained" erodes one convenience import at a time | `ImportPurityTest` in CI from Phase 0 |
| Generated code bloat (a full codec per tree) | one shared `ProtoWire` per tree, not per message; measure with `-Xlint` + jar size assertion |
| Scope creep into gRPC/JSON/editions | §4 table is the contract; anything else is a new milestone |

## 9. Open questions

1. **Mutable/reusable message mode** (QuickBuffers-style) — worth a `messageStyle=immutable|mutable` flag, or does
   immutable-only keep the generator honest?
2. **`ProtoWire` placement** when one build produces several unrelated Java packages — one shared public class, or
   one package-private copy per package (bigger, but zero cross-package coupling)?
3. **Well-known types** — is `Timestamp` needed for the SBB protos, or is `int64 epochMillis` the house style?
4. **Comment annotations** (`@Example`, `@MinLength`) — pass through to Javadoc only, or also emit validation code?
   The latter overlaps with the existing `api-documentation-maven-plugin` / `api_validator` pipeline.
