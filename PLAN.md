# protogen — design and plan

**Goal:** a Maven plugin that turns `.proto` files into **optimized, fully self-contained Java 17+ sources**.
Generated code compiles and runs against the **JDK only** — no `protobuf-java`, no Netty, no `protogen` jar.
The build itself needs no native `protoc`.

See [RESEARCH.md](RESEARCH.md) for why this does not exist yet.

**Status:** phases 0–5 are done. proto3 messages generate, round-trip, and are byte-identical to `protoc`
across 310 tests. Phase 6 (benchmarking) and phase 7 (release) are open.

---

## 1. Non-negotiable invariants

| # | Invariant | Enforced by |
|---|---|---|
| I1 | Generated sources import **only** `java.*` | `ImportPurityTest`, plus a check for fully qualified names, which an import-only scan would miss |
| I2 | Wire format is byte-identical to `protoc` | `protogen-interop` — the same schema compiled by both, compared byte for byte |
| I3 | The IT module has **zero compile-scope dependencies** | `protogen-it/pom.xml` declares none; the generated code compiles and runs there |
| I4 | No reflection, no `Class.forName`, no descriptor bootstrap | `ImportPurityTest` |
| I5 | No `protoc` binary and no network toolchain in the plugin | protogen owns its parser |
| I6 | Messages are **immutable** | records, defensive copies, unmodifiable collections |
| I7 | The codec carries **only what the schema uses** | `JavaGeneratorTest` asserts absent helpers, not just present ones |

## 2. Module layout

```
protogen/
├── protogen-compiler/          pure library: .proto -> Java source text, no dependencies
│   ├── lexer/ parser/          hand-written front-end, comment-retaining
│   ├── model/ linker/          declaration tree, symbol table, type resolution
│   └── gen/                    emitters: codec, message, enum
├── protogen-maven-plugin/      the protogen:generate Mojo, a thin wrapper
├── protogen-it/                zero-dependency proof: generate -> compile -> run
└── protogen-interop/           differential tests against protoc + protobuf-java
```

`protogen-compiler` holds no Maven types, so the same compiler can drive a CLI or a Gradle plugin later.

## 3. The generated API

For `message NodeV1` with `java_package = protogen.it.model`, `java_multiple_files = true`:

```java
public record NodeV1(
        String name,                          // implicit presence -> "" default, never null
        StageEnumV1 stage,                    // enum default is the constant numbered 0
        String stageSuffix,                   // `optional` -> nullable
        List<NodeV1> children,                // unmodifiable, never null
        List<Integer> ports,
        Map<String, String> endpoints,        // unmodifiable, insertion ordered
        CoordinatesV1 location,               // message presence -> nullable
        Instant createdAt) {                  // google.protobuf.Timestamp -> Instant

    public NodeV1 { /* normalise nulls, copy collections, enforce schema constraints */ }

    public static NodeV1 parseFrom(byte[] data);
    public static NodeV1 parseFrom(byte[] data, int offset, int length);
    public byte[] toByteArray();
    public int writeTo(byte[] target, int offset);   // returns the new position
    public int protoSize();
}
```

### Why these choices

* **Records, always immutable.** No mutable or reusable message mode. The compact constructor normalises
  absent values to their proto3 defaults, copies collections into unmodifiable views and copies `byte[]`
  on the way in *and* on the way out, so a message cannot be mutated after construction.
* **No builder.** Records plus the canonical constructor are the whole surface. Test-side builders live in
  the tests that need them, not in every generated class.
* **`byte[]` components** force explicit `equals`/`hashCode`/`toString`, which the generator emits only for
  the messages that have them — the record defaults would compare arrays by identity.
* **Nothing shared between packages.** Every Java package gets its own package-private `ProtoWire`. The
  public surface of a message is expressed in `byte[]` and `int` alone, so a message in one package can
  nest a message from another without the two packages sharing a single type.
* **The codec is pruned.** `Feature` collects what a package's fields actually need and closes over the
  dependencies; `CodecEmitter` emits only those methods. A string-only schema gets no zig-zag, no
  fixed-width helpers, no `slice`, no `pushLimit`.
* **Tags are compile-time constants.** Single-byte tags become a direct `target[offset++] = ...` store
  rather than a varint call, and tag sizes fold into the size arithmetic as literals.
* **Malformed input raises `IllegalArgumentException`.** A dedicated exception type would be a shared type;
  `java.lang` keeps the packages independent.

### Known v1 limitations

* **Unknown fields are dropped by default**, because preserving them puts an extra component into every
  constructor and every `equals`. Turn on `preserveUnknownFields` where it matters — see §6.
* **An unknown enum value becomes `UNRECOGNIZED`** and is not re-encoded. `protoc` keeps the raw number.
* **`protoSize()` is recomputed** rather than memoised, because a record has no mutable field to cache in.
  Serializing a tree of depth *d* walks it *d* times. Phase 6 revisits this if benchmarks justify it.

## 4. Timestamp and Instant

`google.protobuf.Timestamp` maps to `java.time.Instant` and travels as an **`int64` of epoch
milliseconds**, not as the standard seconds+nanos submessage.

This is a deliberate deviation, so the contract it creates is pinned by test rather than assumed:

> **A protogen `Timestamp` field is byte-identical to a protoc `optional int64` field of epoch millis.**

`optional` is the part that is easy to get wrong. A `Timestamp` field has message presence, so an instant
at the epoch is a real value that must go on the wire; a bare `int64` would treat zero as absent and drop
it. `protogen-interop` derives its reference schema from the shared one by exactly this substitution, so
the two sides cannot drift.

Sub-millisecond precision is not transmitted. A peer built with `protoc` must declare the field as
`optional int64`.

## 5. Validation from schema annotations

The `@Annotation` vocabulary already used in the surveyed schemas becomes runtime checks in the record's
compact constructor, so an invalid message cannot be constructed — by hand or by parsing.

| Annotation | Applies to | Check |
|---|---|---|
| `@MinLength n` / `@MaxLength n` | `string` | length bounds |
| `@Pattern regex` | `string` | compiled once into a `static final Pattern` |
| `@Minimum n` / `@Maximum n` (aliases `@Min` / `@Max`) | numeric | inclusive bounds |
| `@ExclusiveMinimum n` / `@ExclusiveMaximum n` | numeric | exclusive bounds |
| `@MultipleOf n` | integral | exact multiple |
| `@MinItems n` / `@MaxItems n` | `repeated`, `map` | size bounds |
| `@Required` | any | present, i.e. non-default |
| `@Example v`, `@RootNode` | any | documentation only, carried into the Javadoc |

**One consequence worth knowing:** proto3 cannot distinguish absent from default on an implicit-presence
field, so a constraint on such a field is enforced on *every* instance — it effectively becomes required.
Put the constraint on an `optional` field when it should apply only when the value is set. Both behaviours
are pinned in `ValidationTest`.

## 5a. The three opt-ins

### Unknown-field preservation (`preserveUnknownFields`, default off)

A relay reads the fields it owns and forwards the message on; without preservation everything its build
has never heard of is silently dropped in transit. With the flag on, each record gains a trailing
`byte[] unknownFields` component: unrecognised tags are copied **verbatim**, appended after the known
fields on write, and included in `equals`/`hashCode`/`toString`. A round trip is byte-exact and stable.

It stays off by default because that component is visible in every constructor call and every diff — a
real cost to pay only where relaying happens. The codec helpers it needs (`copyField` and a growable
buffer) are emitted only when it is on, per the pruning rule.

`protogen-it/src/main/proto-optin` plus the `generate-opt-ins` execution in that module's pom is the
worked example.

### Validation: two switches

| Switch | Where | Default | Effect |
|---|---|---|---|
| `emitValidation` | Mojo, generation time | `true` | whether the checks exist in the bytecode at all |
| `-Dprotogen.validation=false` | JVM, runtime | on | folds the generated checks away without regenerating |

The runtime switch is a `static final boolean` read once at class initialisation, so when it is off the
JIT removes the checks entirely — there is no per-message cost for carrying the capability. Only an
explicit `false` disables it, so a typo in the property leaves validation running rather than silently
turning it off.

The runtime switch is what makes a **lenient parse** possible: point a migration job at
`-Dprotogen.validation=false` to read legacy data that predates a constraint, without regenerating and
without weakening the constraint for everyone else. Records cannot bypass their canonical constructor, so
parse and construction necessarily share the switch — this is the honest way to offer leniency.

Oneof invariants are **not** covered by either switch: "at most one member set" is structural correctness,
not schema validation, and is always enforced.

### `@Example` / `@RootNode` → documentation metadata (`emitSchemaMetadata`, default on)

These two annotations are documentation, not behaviour, so they do not belong in the generated runtime.
Each `.proto` gets a JSON sidecar at `META-INF/protogen/<file>.json` in the generated **resources**,
recording per message: `rootNode`, prose documentation, Java type name; and per field: number, proto type,
label, examples, and every constraint. A documentation pipeline can read it off the classpath without
re-parsing the schema or reflecting over the classes, while the records stay free of tooling-only members.

The JSON is written by hand — `protogen-compiler` still has no dependencies.

## 6. Front-end

Every generator surveyed in RESEARCH.md except Protostuff and Wire is a **protoc plugin**. Shelling out to
a native binary contradicts "fully independent", so protogen owns a hand-written lexer and recursive-descent
parser — roughly 900 lines, no ANTLR, and full control over **comment retention**, which is what lets the
`@Example` / `@MinLength` annotations reach the generated Javadoc and the validation.

Two lexer decisions matter in practice:

* **A comment attaches to the next declaration even across blank lines.** protoc would call such a comment
  "detached", but here comments carry `@Pattern` and `@Minimum` annotations that become validation, and
  real schemas are often written double-spaced. Dropping the comment would silently drop the validation.
* **Generated locals never collide with schema names.** A schema may declare fields called `key`, `value`,
  `size` or `target` — a generated method using those names would fail to compile or, worse, silently
  shadow the component. `Locals` allocates each generated name around the message's own.

Supported, driven by what the surveyed schemas actually use:

| Feature | | Notes |
|---|---|---|
| `syntax = "proto3"` | ✅ | proto2 is rejected with a diagnostic |
| `java_package`, `java_multiple_files`, `java_outer_classname` | ✅ | including the wrapper-class layout and protoc's `OuterClass` collision suffix |
| `import`, `import public` | ✅ | |
| all 15 scalar types | ✅ | |
| `enum`, nested enums, `allow_alias`, lower-case constants | ✅ | proto3's zero-value rule is enforced |
| nested `message`, recursion, cross-file references | ✅ | proto scoping: innermost scope outward, leading dot forces absolute |
| `repeated`, packed and unpacked decode | ✅ | |
| `optional` explicit presence | ✅ | |
| `map<K,V>`, including message values | ✅ | key/value always written, as protoc does |
| `oneof` | ✅ | siblings cleared on parse, at most one enforced on construction |
| `reserved` | ✅ | parsed |
| `google.protobuf.Timestamp` | ✅ | see §4 |
| comment → Javadoc, comment → validation | ✅ | the differentiator |
| other well-known types, `service`, `extend`, groups, proto2, editions, JSON | ❌ | rejected with `file:line:col` |

Anything unsupported **fails the build with a located diagnostic**, never a silently wrong result.

## 7. Maven plugin surface

Goal `protogen:generate`, default phase `generate-sources`.

| Parameter | Default | Purpose |
|---|---|---|
| `protoSourceRoot` | `${basedir}/src/main/proto` | input tree |
| `includes` / `excludes` | `**/*.proto` / – | filtering; a leading `**/` is optional, so a file in the root matches |
| `outputDirectory` | `${project.build.directory}/generated-sources/protogen` | output, auto-added as a compile source root |
| `javaPackage` | from `option java_package` | override |
| `emitJavadoc` | `true` | comment retention |
| `preserveUnknownFields` | `false` | keep unknown fields in a trailing component (§5a) |
| `emitValidation` | `true` | generate the schema's constraint checks (§5a) |
| `emitSchemaMetadata` | `true` | write the `@Example` / `@RootNode` JSON sidecar (§5a) |
| `resourceOutputDirectory` | `${project.build.directory}/generated-resources/protogen` | where the sidecars land; added as a project resource |
| `failOnUnsupported` | `true` | |
| `skip` | `false` | |

## 8. Verification

Two modules, deliberately separated:

**`protogen-it` — the zero-dependency proof.** No compile-scope dependencies at all. The generated code is
compiled and exercised there: round-trips, presence rules, collections, enums, `Instant`, oneofs,
validation, immutability, and a malformed-input suite that walks every truncation of a well-formed message
and asserts it either parses or fails cleanly — never an index error, a stack overflow or a hang.

**`protogen-interop` — the differential proof.** The *same* `.proto` files are compiled a second time by
`protoc` into `protobuf-java` classes. The reference schemas are **derived from the shared ones at build
time** (rewriting `java_package`, and `Timestamp` → `optional int64`), so the two sides cannot drift apart.
Every message is then encoded by each implementation and decoded by the other, and the encodings are
compared byte for byte — including a seeded fuzz suite over random messages. `protoc` and `protobuf-java`
exist only in this module.

Three real codec bugs were caught this way and fixed: an unpaired surrogate sized as three bytes but
encoded as one, `-0.0` skipped as if it were the default, and `Timestamp` presence differing from a bare
`int64`.

## 9. Phases

| Phase | Deliverable | State |
|---|---|---|
| **0** | Scaffolding, Mojo wired into `generate-sources` | ✅ |
| **1** | Lexer, parser, linker, located diagnostics | ✅ |
| **2** | Codec emitter, scalars, strings, bytes, enums, presence | ✅ |
| **3** | `repeated` packed and unpacked, `map`, `oneof`, `Instant`, validation | ✅ |
| **4** | Mojo hardening: includes/excludes, offline, multi-module | ✅ |
| **5** | Verification: zero-dependency compile and run, differential vs protoc, fuzz | ✅ |
| **6** | Optimization: JMH harness against protobuf-java, CI | ✅ harness and numbers; size memoisation still to do |
| **7** | Release: Maven Central publishing, signing, CI release workflow, Dependabot | ✅ wired; awaiting the account credentials in [RELEASING.md](RELEASING.md) |

Measured results and how to read them: [BENCHMARKS.md](BENCHMARKS.md).

## 10. Open questions

1. ~~**Unknown-field preservation**~~ — **done**, as an opt-in (§5a). Still open: a differential test that
   has `protoc` produce the unknown fields from a genuine v2 schema, rather than the hand-built wire bytes
   the current test uses.
2. ~~**`protoSize()` memoisation**~~ — **answered by measurement.** It costs nothing at depth 1, 1.5× at
   depth 3 and 2.1× at depth 5 when the same instance is serialized repeatedly, and nothing at all when a
   message is serialized once. Worth fixing, and without giving up records: have `toByteArray()` compute
   child sizes once into a scratch array and thread it through `writeTo`, rather than caching per instance.
   See [BENCHMARKS.md](BENCHMARKS.md).
3. ~~**Validation on parse**~~ — **done**: two switches, one at generation time and one at runtime (§5a).
   The runtime one gives the lenient parse for legacy data.
4. ~~**`@Example` / `@RootNode`**~~ — **done**: a JSON sidecar under `META-INF/protogen/` (§5a). Still
   open: wiring that sidecar into the AsyncAPI generator itself, which lives in another repo.
