# Research: Java code generators for `.proto` — is there a *fully self-contained* one?

**Date:** 2026-08-11
**Question:** Does a Maven plugin exist that generates **optimized, fully self-contained Java 17+ sources** from `.proto`
files — i.e. generated code that compiles and runs with **zero external runtime libraries** (only the JDK)?

**Answer: No. Nothing meets the bar.**

Every existing generator ships a runtime jar that the generated code links against. The closest candidate
(LightProto) claims "no runtime dependencies", but its generated code imports `io.netty.buffer.ByteBuf` and
therefore requires `io.netty:netty-buffer`. That justifies building `protogen`.

---

## 1. Evaluation criteria

| # | Criterion                                                                       |
|---|---------------------------------------------------------------------------------|
| C1 | Generated Java compiles against the **JDK only** — no jar on the runtime classpath |
| C2 | Delivered as (or usable from) a **Maven plugin**                                 |
| C3 | Targets **Java 17+**                                                             |
| C4 | Wire-format compatible with `protoc` (proto3)                                    |
| C5 | Optimized (low allocation, no reflection, no descriptor bootstrap)               |
| C6 | Does not require a native `protoc` binary at build time                          |

## 2. Candidates evaluated

### 2.1 `protobuf-java` (Google, reference implementation)

* Generated classes extend `com.google.protobuf.GeneratedMessage` and import `Descriptors.FileDescriptor`,
  `ExtensionRegistry`, `InvalidProtocolBufferException`, … from `com.google.protobuf`.
* **C1 fails hard.** `protobuf-java` is ~1.8 MB and pulls a descriptor/reflection bootstrap into every class.
* Requires the native `protoc` binary (via `os-maven-plugin` classifier download) → **C6 fails**.
* Verdict: **rejected** — this is exactly the dependency we want to delete.

### 2.2 `protobuf-javalite`

* Same generator, "lite" runtime: classes implement `MessageLite`, link against `libprotobuf-lite.jar`.
* Smaller (~200 KB) and reflection-free, but **still a mandatory jar** → **C1 fails**.
* Verdict: **rejected** — reduces the dependency, does not remove it.

### 2.3 `javanano` (`--javanano_out`)

* Google's ultra-light Android generator; the design goal was minimal code + minimal runtime.
* **Removed from protoc in 3.6** and unsupported since; grpc-java formally dropped it (proposal L51).
  Google's guidance is "use protobuf-lite instead".
* Even when alive, it required the `protobuf-javanano` runtime jar.
* Verdict: **dead**, and never satisfied C1 anyway. Still useful as *prior art* for a minimal generated API.

### 2.4 LightProto (`streamnative/lightproto`, orig. `merlimat/lightproto`) — **closest match**

* Actively maintained (v0.8.0, 2026-07-31), Apache-2.0, `<release>17</release>`, **has a Maven plugin**
  (`lightproto-maven-plugin`), proto2 + proto3, maps, oneof, repeated/packed, nested, enums, imports.
* README says: *"No runtime dependencies — generated code is self-contained."*
* **Verified false in the strict sense.** The generator emits two support classes,
  `code-generator/src/main/resources/.../LightProtoCodec.java` and `LightProtoByteBufAccess.java`, whose imports are:
  ```java
  import io.netty.buffer.AbstractByteBuf;
  import io.netty.buffer.ByteBuf;
  import io.netty.buffer.ByteBufUtil;
  ```
  Its own `tests/pom.xml` therefore declares `netty-buffer` as a dependency.
  "Self-contained" means *no LightProto jar* — Netty is still required.
* It is also a **protoc plugin**: it consumes a `CodeGeneratorRequest`, so the build needs `protoc` → **C6 fails**.
* Verdict: **rejected on C1 and C6** — but the **best architectural reference we have**. The "emit the codec as
  generated source instead of shipping a jar" trick is exactly right; we just have to replace `ByteBuf` with
  `byte[]` / `ByteBuffer`.

### 2.5 QuickBuffers (`HebiRobotics/QuickBuffers`)

* Excellent engineering: zero-allocation, real-time friendly, Java 6–20, Apache-2.0, protoc-compatible wire format.
* "No external dependencies" refers to **the runtime jar itself** having no transitive deps. Generated messages
  extend `ProtoMessage` and use `ProtoSource`/`ProtoSink` from `us.hebi.quickbuf:quickbuf-runtime` → **C1 fails**.
* Maven integration only via `protoc-jar-maven-plugin` (it is a protoc plugin) → **C6 fails**.
* proto3 is "wire-compatible" but proto3 *behaviours* are not fully implemented; no services.
* Verdict: **rejected on C1** — best reference for the *optimized* half of the goal (mutable, reusable messages).

### 2.6 PBJ (`hashgraph/pbj`)

* Performance-optimized generator, minimal garbage, protoc-identical binary encoding, Apache-2.0, active.
* Generated code depends on **`pbj-runtime`** (codecs, IO, types) → **C1 fails**.
* **Gradle-only** (Gradle module + wrappers, no Maven plugin) → **C2 fails**.
* Verdict: **rejected**.

### 2.7 Protostuff (`protostuff/protostuff`, `protostuff/protostuff-compiler`)

* ANTLR4 proto2/proto3 parser + StringTemplate-based, extensible generator, has `protostuff-maven-plugin`.
* By design generates code **"for the protostuff runtime library"** (`protostuff-core`, `protostuff-api`) → **C1 fails**.
* Verdict: **rejected** as a generator. Its **parser** (`protostuff-parser`) is a viable off-the-shelf front-end
  if we decide not to write our own (see PLAN.md, "Option B").

### 2.8 Square Wire (`square/wire`)

* Mature, Java + Kotlin output, proto3, gRPC. Generated code uses `ProtoAdapter` etc. from **`wire-runtime`**,
  which the Gradle plugin adds automatically → **C1 fails**.
* Gradle-first; Maven support is not first-class → **C2 weak**.
* Verdict: **rejected**. Its `wire-schema` module is, like protostuff-parser, a usable pure-JVM `.proto` front-end
  (it superseded the deprecated `square/protoparser`).

### 2.9 ProtoStream (`infinispan/protostream`)

* Annotation-driven: you write Java, it derives the schema. Inverted direction, and generated/annotated code
  depends on the `protostream` runtime → **C1 fails**, wrong direction for our use case.
* Verdict: **rejected**.

### 2.10 jprotobuf (Baidu)

* Annotation-based convenience layer that ultimately **delegates to `protobuf-java`** → **C1 fails**.
* Verdict: **rejected**.

### 2.11 Kotlin-targeting generators (`protokt`, `pbandk`, `kotlinx-protobuf-gen`)

* All emit Kotlin and all require their own runtime (+ the Kotlin stdlib, which is itself a dependency) → **C1, C3 fail**.
* Verdict: **out of scope**.

### 2.12 Maven plugins that merely *wrap* protoc

`xolstice/protobuf-maven-plugin`, `ascopes/protobuf-maven-plugin`, `protoc-jar-maven-plugin`,
`google/protobuf-gradle-plugin`.

* These are **build integrations, not generators** — they invoke `protoc` and inherit whatever runtime it targets.
* `ascopes` is the most modern and is the right **UX reference** for our Mojo's parameter surface
  (source roots, includes/excludes, classpath proto discovery, incremental behaviour).
* Verdict: **not competitors**, but a good UX benchmark.

## 3. Summary matrix

| Project | C1 no-runtime-jar | C2 Maven plugin | C3 Java 17+ | C4 wire-compatible | C5 optimized | C6 no protoc |
|---|---|---|---|---|---|---|
| protobuf-java      | ✗ (1.8 MB)          | ✓ (wrappers) | ✓ | ✓ | ✗ reflection | ✗ |
| protobuf-javalite  | ✗ (~200 KB)         | ✓ (wrappers) | ✓ | ✓ | ~ | ✗ |
| javanano           | ✗ + **removed**     | ✗            | ✗ | ✓ | ✓ | ✗ |
| **LightProto**     | ✗ **needs Netty**   | **✓**        | **✓** | ✓ | ✓ | ✗ |
| QuickBuffers       | ✗ quickbuf-runtime  | ~ protoc-jar | ✓ | ✓ | **✓✓** | ✗ |
| PBJ                | ✗ pbj-runtime       | ✗ Gradle     | ✓ | ✓ | ✓ | ✗ |
| Protostuff         | ✗ protostuff-core   | ✓            | ✓ | ✓ | ~ | ✓ |
| Wire               | ✗ wire-runtime      | ✗ Gradle     | ✓ | ✓ | ~ | ✓ |
| ProtoStream        | ✗ protostream       | ✓            | ✓ | ✓ | ~ | n/a |
| jprotobuf          | ✗ protobuf-java     | ✓            | ✓ | ✓ | ✗ | ✗ |
| **protogen (goal)**| **✓ JDK only**      | **✓**        | **✓** | **✓** | **✓** | **✓** |

## 4. Conclusion

**The gap is real.** No project satisfies C1. The state of the art stops at "small runtime jar"
(javalite, quickbuf, pbj, wire) or "self-contained except Netty" (LightProto). Nobody emits Java that runs on
the JDK alone, and nobody does it as a Maven plugin without `protoc`.

### What we take from the field

1. **LightProto's core idea** — emit the wire codec *as generated source* rather than shipping it as a jar.
   We adopt it and go further: `byte[]` / `ByteBuffer` instead of Netty `ByteBuf`, so the only imports are `java.*`.
2. **QuickBuffers' performance model** — no reflection, no descriptor bootstrap, no lazy `UnknownFieldSet`
   machinery; direct field access and pre-computed tags.
3. **javanano's minimalism** — proof that a tiny generated API is sufficient for the 95 % use case.
4. **ascopes' plugin UX** — the parameter surface to copy for the Mojo.
5. **A pure-Java parser is mandatory.** Every rejected generator except Protostuff/Wire is a *protoc plugin*.
   Depending on a native binary contradicts "fully independent", so `protogen` parses `.proto` itself.
   (Bonus: our own parser can retain leading comments, which the SBB protos use for `@Example` / `@MinLength`
   documentation annotations — protoc-based generators discard or awkwardly encode these.)

---

## Sources

- [Java Generated Code Guide — protobuf.dev](https://protobuf.dev/reference/java/java-generated/)
- [streamnative/lightproto](https://github.com/streamnative/lightproto)
- [HebiRobotics/QuickBuffers](https://github.com/HebiRobotics/QuickBuffers/)
- [hashgraph/pbj](https://github.com/hashgraph/pbj)
- [protostuff/protostuff-compiler](https://github.com/protostuff/protostuff-compiler)
- [protostuff/protostuff](https://github.com/protostuff/protostuff)
- [Protostuff maven plugin](https://protostuff.github.io/documentation/maven-plugin/)
- [square/wire](https://square.github.io/wire/) · [wire_compiler docs](https://square.github.io/wire/wire_compiler/)
- [square/protoparser (deprecated)](https://github.com/square/protoparser)
- [infinispan/protostream](https://github.com/infinispan/protostream)
- [grpc proposal L51 — remove nano proto](https://github.com/grpc/proposal/blob/master/L51-java-rm-nano-proto.md)
- [protoc 3.6.1 no longer supports javanano (issue #5288)](https://github.com/protocolbuffers/protobuf/issues/5288)
- [javanano README (android.googlesource.com)](https://android.googlesource.com/platform/external/protobuf/+/HEAD/javanano)
- [ascopes/protobuf-maven-plugin](https://github.com/ascopes/protobuf-maven-plugin) · [generate-mojo docs](https://ascopes.github.io/protobuf-maven-plugin/generate-mojo.html)
- [Maven Protocol Buffers Plugin (xolstice)](https://www.xolstice.org/protobuf-maven-plugin/usage.html)
- [Third-party tools and libraries — Protocol Buffers](https://chromium.googlesource.com/external/github.com/google/protobuf/+/HEAD/docs/third_party.md)
- [mwillema/protobuf-parser (ANTLR4 proto3 grammar)](https://github.com/mwillema/protobuf-parser/blob/master/src/main/antlr4/com/marcowillemart/protobuf/parser/Protobuf.g4)
- [us.hebi.quickbuf:quickbuf-runtime — Maven Central](https://central.sonatype.com/artifact/us.hebi.quickbuf/quickbuf-runtime)
