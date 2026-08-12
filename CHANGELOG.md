# Changelog

Notable changes per release. Dates are release dates; versions follow [semver](https://semver.org/).

## 0.3.0 — 2026-08-12

The well-known types, on protoc's exact bytes and without an import.

### ⚠️ Breaking: `google.protobuf.Timestamp` changed on the wire

It travelled as an `int64` of epoch milliseconds. It now travels as
`{int64 seconds = 1; int32 nanos = 2;}` — byte for byte what `protoc` writes.

* **A 0.2.0 peer and a 0.3.0 peer do not understand each other's timestamps.** Upgrade both ends together,
  or keep the old encoding by declaring the field `optional int64` and mapping it yourself.
* A `protoc` peer no longer has to declare `optional int64` to interoperate: it declares
  `google.protobuf.Timestamp`, which is what the schema said all along.
* **Nanosecond precision now survives.** The old encoding truncated to milliseconds.
* The Java surface is unchanged: still `java.time.Instant`.

### Added

- **Every well-known type, with no `import` needed.** Their definitions are fixed and public, so protogen
  knows them; an `import` is still accepted because the same schema usually has to compile with `protoc`.
  - `Duration` → `java.time.Duration`, including the sign convention (protobuf gives both parts the same
    sign, `java.time` floors the seconds and keeps the nanos positive).
  - The nine wrappers → the nullable value each exists to carry: `StringValue` is a `String` that may be
    `null`, `Int32Value` an `Integer`, and so on. Absent and default stay distinguishable, which is the
    entire point of a wrapper.
  - `Any`, `Empty`, `FieldMask`, `Struct`, `Value`, `ListValue`, `NullValue`, and the descriptor-shaped
    `Api` / `Type` / `Field` / `Option` family → generated as ordinary records from definitions bundled
    with the compiler. They land in **your** schema's Java package, never `com.google.protobuf`, which
    would collide with `protobuf-java` for anyone who has both on the classpath.
  - Naming one pulls in what it needs and nothing else; a type you declare yourself always wins.

### Changed

- `protogen-interop` no longer rewrites the shared schemas before handing them to `protoc` — both sides
  now compile the same text, including the well-known types.

### Notes

- Still no runtime dependency. The bundled definitions are compiler resources; what reaches your artifact
  is generated source like everything else.

## 0.2.0 — 2026-08-12

The first release to read something other than `.proto`, and the one where the generated code got
substantially faster than `protobuf-java` on every shape that matters.

### Added

- **AsyncAPI as a second input** (`asyncApiSourceRoot`), 2.x or 3.x, YAML or JSON. Both versions are
  normalised into one model, so everything downstream behaves the same.
  - The **models** a document's payloads `$ref` are generated from the document itself, along with
    everything they import — a spec-first project needs no proto source root at all.
  - **Scaffolding** for what the document implies: a typed address record per channel (with the
    parameter constraints the document declares), a `java.util.function` stub per operation binding
    `byte[]`, and the Spring Cloud Stream binding configuration for the Solace binder. Written to a
    throwaway directory and **never compiled** — it is help, not build output.
  - Six switches: `scaffoldOutputDirectory`, `scaffoldPackage`, `scaffoldChannels`, `scaffoldStubs`,
    `scaffoldBinderConfig`, `scaffoldNotes`.
- **proto2**: `required` / `optional` / `repeated`, `[default = ...]` exposed as `<field>OrDefault()`, no
  zero-enum rule, and `extensions` ranges parsed so a schema declaring them compiles.
- Field options are parsed and kept; `[packed = ...]` and `[default = ...]` are acted on.
- `reserved` numbers, ranges, `to max` and names are **enforced**.

### Changed

- **`@Pattern` is compiled into a scan over the string** where a greedy left-to-right pass provably gives
  the same answer as `java.util.regex` — no `Matcher` allocated per message. On a batch of a hundred KPIs
  the regex engine was 48% of decode time. Patterns needing backtracking keep the regex.
- **Each nested payload is measured once per encode.** The sizing pass records nested sizes in the order
  the write reads them back, instead of the write re-measuring every subtree. Encoding a tree five deep
  went from 24.1 µs to 9.3 µs.
- **Collections are immutable in their own right**, not `Collections.unmodifiable*` views: a map handed
  out rejects `Entry.setValue`, and a collection `parseFrom` built is handed over rather than copied a
  second time.
- A submessage in the same Java package is parsed off the shared reader rather than through a second one.
- Single-byte fast paths on the varint reader and writer.

### Fixed

- **Imports are enforced.** A file may only name types from itself, its imports, and what those re-export
  with `import public`; the linker previously built one symbol table over everything, so a file could
  reference a type from a file it never imported and `protoc` would reject what protogen accepted.
- A malformed `@Pattern` now fails the build with a `file:line:col` instead of throwing
  `PatternSyntaxException` in whichever service loads the generated class first.
- Duplicate field-number checking was quadratic in field count.

### Performance

Against `protobuf-java` on identical schemas — full numbers in [BENCHMARKS.md](BENCHMARKS.md):

| | protogen | protobuf-java | |
|---|---|---|---|
| decode 100 KPIs | 13.6 µs | 20.3 µs | 1.49× faster |
| build + encode 100 KPIs | 18.0 µs | 31.5 µs | 1.75× faster |
| decode a tree 5 deep | 10.3 µs | 18.3 µs | 1.77× faster |
| encode a tree 5 deep | 9.3 µs | 13.5 µs | 1.45× faster |
| build + encode a flat message | 92 ns | 184 ns | 2.0× faster |

### Notes

- The generated code still needs **no dependency at all** — that has not changed and is the point.
- `protogen-compiler` gained one *generation-time* dependency, a YAML parser for AsyncAPI. Nothing
  reaches generated code, and `protogen-it` asserts that against the built artifact.

## 0.1.0 — 2026-08-11

First release. `.proto` in, fully self-contained Java 17+ records out: no `protobuf-java`, no runtime jar
of any kind, and no native `protoc` needed by the build.

- proto3 messages as immutable records, with a package-private `ProtoWire` codec emitted next to them and
  pruned to the helpers the schema actually uses.
- All 15 scalar types, `bytes`, enums, nested and recursive messages, `repeated` (packed and unpacked),
  `map`, `oneof`, `optional` presence, cross-file and cross-package references.
- `google.protobuf.Timestamp` as `java.time.Instant`, travelling as an `int64` of epoch millis.
- Validation from schema comments (`@MinLength`, `@Pattern`, `@Minimum`, …), with two independent
  switches: `<emitValidation>` at build time and `-Dprotogen.validation=false` at runtime.
- Opt-in unknown-field preservation, and a JSON metadata sidecar for documentation pipelines.
- Wire compatibility with `protoc` verified by a differential suite that compiles the same schemas both
  ways and compares the bytes.
