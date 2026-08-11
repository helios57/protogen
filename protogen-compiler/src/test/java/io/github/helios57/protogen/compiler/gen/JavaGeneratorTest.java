package io.github.helios57.protogen.compiler.gen;

import io.github.helios57.protogen.compiler.ProtoCompiler;
import io.github.helios57.protogen.compiler.model.ProtoFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shape of the generated sources. The behaviour of the emitted code is covered end to end by
 * protogen-it and protogen-interop; this pins the decisions that are hard to see from there - the file
 * layout, the record shape and, above all, that the codec carries nothing the schema does not use.
 */
class JavaGeneratorTest {

    private static final ProtoCompiler COMPILER = new ProtoCompiler(ProtoCompiler.Options.defaults());

    private static Map<String, String> generate(String... sources) {
        return generate(ProtoCompiler.Options.defaults(), sources);
    }

    private static Map<String, String> generate(ProtoCompiler.Options options, String... sources) {
        ProtoCompiler compiler = new ProtoCompiler(options);
        List<ProtoFile> files = new java.util.ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            files.add(compiler.parse("file" + i + ".proto", sources[i]));
        }
        return compiler.generate(compiler.link(files)).stream()
                .collect(Collectors.toMap(JavaGenerator.GeneratedFile::relativePath,
                        JavaGenerator.GeneratedFile::content));
    }

    private static ProtoCompiler.Options with(boolean unknownFields, boolean validation, boolean metadata) {
        return new ProtoCompiler.Options(null, true, true, unknownFields, validation, metadata);
    }

    private static final String CONSTRAINED = """
            syntax = "proto3";
            option java_multiple_files = true;
            option java_package = "x";
            /**
             * @RootNode
             */
            message M {
              /*
               * @MinLength 3
               * @Example abcdef
               */
              string s = 1;
            }
            """;

    // ------------------------------------------------------ the three opt-ins

    @Test
    void unknownFieldPreservationIsOffByDefault() {
        String source = generate(CONSTRAINED).get("x/M.java");

        assertThat(source).doesNotContain("unknownFields");
        assertThat(generate(CONSTRAINED).get("x/ProtoWire.java"))
                .doesNotContain("copyField").doesNotContain("static final class U");
    }

    @Test
    void unknownFieldPreservationAddsATrailingComponentAndTheCaptureHelpers() {
        Map<String, String> out = generate(with(true, true, true), CONSTRAINED);

        assertThat(out.get("x/M.java"))
                .contains("byte[] unknownFields)")
                .contains("ProtoWire.U unknownFields = null;")
                .contains("copyField(tag, unknownFields)");
        assertThat(out.get("x/ProtoWire.java"))
                .contains("U copyField(int tag, U sink)")
                .contains("static final class U");
    }

    @Test
    void validationIsGeneratedByDefaultBehindARuntimeSwitch() {
        String source = generate(CONSTRAINED).get("x/M.java");

        assertThat(source)
                .contains("private static final boolean PROTOGEN_VALIDATION")
                .contains("if (PROTOGEN_VALIDATION) {")
                .contains("@MinLength 3");
    }

    @Test
    void validationCanBeLeftOutEntirelyAtGenerationTime() {
        String source = generate(with(false, false, true), CONSTRAINED).get("x/M.java");

        assertThat(source)
                .doesNotContain("PROTOGEN_VALIDATION")
                .doesNotContain("throw new IllegalArgumentException")
                .doesNotContain("@MinLength");
    }

    @Test
    void metadataSidecarCarriesExamplesAndRootNode() {
        Map<String, String> out = generate(CONSTRAINED);

        String json = out.get("META-INF/protogen/file0.json");
        assertThat(json).isNotNull();
        assertThat(json)
                .contains("\"rootNode\": true")
                .contains("\"abcdef\"")
                .contains("\"minLength\": 3")
                .contains("\"javaType\": \"x.M\"");
    }

    @Test
    void theMetadataSidecarIsAResourceNotASource() {
        ProtoCompiler compiler = new ProtoCompiler(ProtoCompiler.Options.defaults());
        List<ProtoFile> files = List.of(compiler.parse("file0.proto", CONSTRAINED));

        List<JavaGenerator.GeneratedFile> generated = compiler.generate(compiler.link(files));

        assertThat(generated)
                .filteredOn(f -> f.relativePath().endsWith(".json"))
                .singleElement()
                .extracting(JavaGenerator.GeneratedFile::kind)
                .isEqualTo(JavaGenerator.Kind.RESOURCE);
        assertThat(generated)
                .filteredOn(f -> f.relativePath().endsWith(".java"))
                .allSatisfy(f -> assertThat(f.kind()).isEqualTo(JavaGenerator.Kind.SOURCE));
    }

    @Test
    void metadataCanBeSwitchedOff() {
        Map<String, String> out = generate(with(false, true, false), CONSTRAINED);

        assertThat(out).doesNotContainKey("META-INF/protogen/file0.json");
    }

    @Test
    void multipleFilesOptionGivesEachTypeItsOwnFile() {
        Map<String, String> out = generate("""
                syntax = "proto3";
                package a;
                option java_multiple_files = true;
                option java_package = "x.y";
                enum E { Z = 0; }
                message M { string s = 1; }
                """);

        assertThat(out).containsKeys("x/y/E.java", "x/y/M.java", "x/y/ProtoWire.java");
    }

    @Test
    void withoutMultipleFilesEverythingNestsInAnOuterClass() {
        Map<String, String> out = generate("""
                syntax = "proto3";
                package a;
                option java_package = "x.y";
                message M { string s = 1; }
                """);

        assertThat(out).containsKeys("x/y/File0.java", "x/y/ProtoWire.java");
        assertThat(out).doesNotContainKey("x/y/M.java");
        assertThat(out.get("x/y/File0.java"))
                .contains("public final class File0 {")
                .contains("public record M(");
    }

    @Test
    void eachJavaPackageGetsItsOwnCodecSoPackagesStayIndependent() {
        Map<String, String> out = generate(
                "syntax = \"proto3\";\npackage a;\noption java_package = \"x\";\nmessage M { string s = 1; }\n",
                "syntax = \"proto3\";\npackage b;\noption java_package = \"y\";\nmessage N { string s = 1; }\n");

        assertThat(out).containsKeys("x/ProtoWire.java", "y/ProtoWire.java");
        assertThat(out.get("x/ProtoWire.java")).startsWith("package x;");
        assertThat(out.get("y/ProtoWire.java")).startsWith("package y;");
    }

    @Test
    void theCodecIsPackagePrivateSoItNeverLeaksAcrossAPackageBoundary() {
        String codec = generate("syntax = \"proto3\";\noption java_package = \"x\";\nmessage M { string s = 1; }\n")
                .get("x/ProtoWire.java");

        assertThat(codec).contains("final class ProtoWire {").doesNotContain("public final class ProtoWire");
    }

    @Test
    void aStringOnlySchemaCarriesNoNumericHelpers() {
        String codec = generate("syntax = \"proto3\";\noption java_package = \"x\";\nmessage M { string s = 1; }\n")
                .get("x/ProtoWire.java");

        assertThat(codec).contains("wString").contains("utf8Len");
        assertThat(codec)
                .as("nothing numeric is used, so nothing numeric may be emitted")
                .doesNotContain("zz32").doesNotContain("zz64")
                .doesNotContain("wFixed32").doesNotContain("wFixed64")
                .doesNotContain("fixed32()").doesNotContain("fixed64()")
                .doesNotContain("wBytes").doesNotContain("bytes()");
    }

    @Test
    void aSignedIntegerSchemaPullsInExactlyTheZigZagHelpers() {
        String codec = generate("syntax = \"proto3\";\noption java_package = \"x\";\nmessage M { sint32 v = 1; }\n")
                .get("x/ProtoWire.java");

        assertThat(codec).contains("zz32").contains("unZz32");
        assertThat(codec).doesNotContain("zz64").doesNotContain("wString").doesNotContain("utf8Len");
    }

    @Test
    void aSchemaWithoutSubmessagesNeedsNoSliceOrLimitHelpers() {
        String codec = generate("syntax = \"proto3\";\noption java_package = \"x\";\nmessage M { bool b = 1; }\n")
                .get("x/ProtoWire.java");

        assertThat(codec).doesNotContain("pushLimit").doesNotContain("int slice(");
    }

    @Test
    void aSchemaWithSubmessagesGetsTheZeroCopySliceHelpers() {
        String codec = generate("""
                syntax = "proto3";
                option java_package = "x";
                message Inner { bool b = 1; }
                message Outer { Inner inner = 1; }
                """).get("x/ProtoWire.java");

        assertThat(codec).contains("int slice(").contains("byte[] array()");
    }

    @Test
    void packedRepeatedFieldsPullInTheLimitHelpers() {
        String codec = generate("syntax = \"proto3\";\noption java_package = \"x\";\nmessage M { repeated int32 v = 1; }\n")
                .get("x/ProtoWire.java");

        assertThat(codec).contains("pushLimit").contains("popLimit");
    }

    @Test
    void messagesAreRecordsWithNoBuilderAndNoSharedRuntimeType() {
        String source = generate("""
                syntax = "proto3";
                option java_multiple_files = true;
                option java_package = "x";
                message M { string s = 1; }
                """).get("x/M.java");

        assertThat(source)
                .contains("public record M(")
                .contains("public static M parseFrom(byte[] data)")
                .contains("public int writeTo(byte[] target, int offset)")
                .contains("public int protoSize()")
                .contains("public byte[] toByteArray()");
        assertThat(source)
                .as("the public surface must be expressible in primitives alone")
                .doesNotContain("class Builder")
                .doesNotContain("public static M parse(ProtoWire");
    }

    @Test
    void everyGeneratedFileImportsOnlyTheJdk() {
        Map<String, String> out = generate("""
                syntax = "proto3";
                option java_multiple_files = true;
                option java_package = "x";
                import "google/protobuf/timestamp.proto";
                message M {
                  string s = 1;
                  repeated int32 v = 2;
                  map<string, string> m = 3;
                  google.protobuf.Timestamp at = 4;
                }
                """);

        for (String content : out.values()) {
            assertThat(content.lines().filter(l -> l.startsWith("import ")))
                    .allSatisfy(line -> assertThat(line).startsWith("import java."));
        }
    }
}
