package io.github.helios57.protogen.compiler.parser;

import io.github.helios57.protogen.compiler.ProtoCompileException;
import io.github.helios57.protogen.compiler.model.Defs;
import io.github.helios57.protogen.compiler.model.ProtoFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtoParserTest {

    private static final String SAMPLE = """
            syntax = "proto3";

            package protogen.it;

            option java_multiple_files = true;
            option java_package = "protogen.it.model";

            import "enums.proto";

            enum StageEnum {
              STAGE_UNSPECIFIED = 0;
              PROD = 1;
            }

            /**
             * A monitored broker.
             * @Example tms
             */
            message BrokerV1 {
              string abbl = 1;
              map<string, string> endpoints = 6;
              optional string suffix = 10;
              repeated int32 ports = 11;
              reserved 12, 13;
              message NestedV1 {
                bool flag = 1;
              }
              oneof body {
                string text = 20;
                int64 counter = 21;
              }
            }
            """;

    private static ProtoFile parse(String source) {
        return new ProtoParser("broker.proto", source).parse();
    }

    @Test
    void parsesHeader() {
        ProtoFile file = parse(SAMPLE);

        assertThat(file.syntax()).isEqualTo("proto3");
        assertThat(file.protoPackage()).isEqualTo("protogen.it");
        assertThat(file.imports()).containsExactly("enums.proto");
        assertThat(file.javaPackage()).isEqualTo("protogen.it.model");
        assertThat(file.javaMultipleFiles()).isTrue();
    }

    @Test
    void parsesTopLevelTypes() {
        ProtoFile file = parse(SAMPLE);

        assertThat(file.enums()).extracting(Defs.EnumDef::name).containsExactly("StageEnum");
        assertThat(file.messages()).extracting(Defs.MessageDef::name).containsExactly("BrokerV1");
    }

    @Test
    void parsesFieldsLabelsAndNestedTypes() {
        Defs.MessageDef broker = parse(SAMPLE).messages().get(0);

        assertThat(broker.fields())
                .extracting(Defs.FieldDef::name)
                .containsExactly("abbl", "endpoints", "suffix", "ports", "text", "counter");
        assertThat(broker.fields())
                .extracting(Defs.FieldDef::number)
                .containsExactly(1, 6, 10, 11, 20, 21);
        assertThat(field(broker, "suffix").label()).isEqualTo(Defs.Label.OPTIONAL);
        assertThat(field(broker, "ports").label()).isEqualTo(Defs.Label.REPEATED);
        assertThat(field(broker, "endpoints").kind()).isEqualTo(Defs.Kind.MAP);
        assertThat(broker.nestedMessages()).extracting(Defs.MessageDef::name).containsExactly("NestedV1");
    }

    @Test
    void parsesOneofGrouping() {
        Defs.MessageDef broker = parse(SAMPLE).messages().get(0);

        assertThat(broker.oneofs()).extracting(Defs.OneofDef::name).containsExactly("body");
        assertThat(field(broker, "text").oneofIndex()).isZero();
        assertThat(field(broker, "counter").oneofIndex()).isZero();
        assertThat(field(broker, "abbl").inOneof()).isFalse();
    }

    @Test
    void retainsLeadingCommentsForJavadoc() {
        Defs.MessageDef broker = parse(SAMPLE).messages().get(0);

        assertThat(broker.comment())
                .contains("A monitored broker.")
                .contains("@Example tms");
    }

    @Test
    void attachesCommentsAcrossBlankLines() {
        // real schemas are often written double-spaced; the annotations must still reach the field
        Defs.MessageDef message = parse("""
                syntax = "proto3";

                package a;

                /*

                 * @Pattern ^[a-zA-Z_:][a-zA-Z0-9_:]*$

                 * @Example jvm_memory_committed_bytes

                 */

                message M {

                  /*

                   * @MinLength 3

                   */

                  string key = 1;

                }
                """).messages().get(0);

        assertThat(message.comment()).contains("@Pattern");
        assertThat(message.fields().get(0).constraints().minLength()).isEqualTo(3);
    }

    @Test
    void toleratesTrailingCommentsAtEndOfFile() {
        ProtoFile file = parse("""
                syntax = "proto3";
                package a;
                message M { string s = 1; }

                // @AI hint to generate demo data
                // s is mandatory
                """);

        assertThat(file.messages()).hasSize(1);
    }

    @Test
    void derivesOuterClassNameWhenNotUsingMultipleFiles() {
        ProtoFile file = new ProtoParser("outer_api_enum.proto",
                "syntax = \"proto3\";\npackage a;\nmessage Foo { string s = 1; }\n").parse();

        assertThat(file.javaMultipleFiles()).isFalse();
        assertThat(file.javaOuterClassName()).isEqualTo("OuterApiEnum");
    }

    @Test
    void appendsOuterClassSuffixOnCollision() {
        ProtoFile file = new ProtoParser("status.proto",
                "syntax = \"proto3\";\npackage a;\nmessage Status { string s = 1; }\n").parse();

        assertThat(file.javaOuterClassName()).isEqualTo("StatusOuterClass");
    }

    @Test
    void fallsBackToProtoPackageWhenJavaPackageAbsent() {
        ProtoFile file = new ProtoParser("x.proto", "syntax = \"proto3\";\npackage a.b;\n").parse();

        assertThat(file.javaPackage()).isEqualTo("a.b");
    }

    @Test
    void rejectsServicesWithLocation() {
        assertThatThrownBy(() -> parse("syntax = \"proto3\";\npackage a;\nservice Foo { }\n"))
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("broker.proto:3:1")
                .hasMessageContaining("not supported");
    }

    @Test
    void rejectsProto2() {
        assertThatThrownBy(() -> parse("syntax = \"proto2\";\n"))
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("only proto3 is supported");
    }

    @Test
    void rejectsMissingSyntax() {
        assertThatThrownBy(() -> parse("package a;\nmessage A { string s = 1; }\n"))
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("missing 'syntax");
    }

    @Test
    void rejectsEnumWithoutZeroValue() {
        assertThatThrownBy(() -> parse("syntax = \"proto3\";\nenum E { A = 1; }\n"))
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("must define a constant with value 0");
    }

    @Test
    void rejectsDuplicateEnumValueWithoutAllowAlias() {
        assertThatThrownBy(() -> parse("syntax = \"proto3\";\nenum E { A = 0; B = 1; C = 1; }\n"))
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("allow_alias");
    }

    @Test
    void acceptsAliasesWhenAllowed() {
        ProtoFile file = parse("syntax = \"proto3\";\nenum E { option allow_alias = true; A = 0; B = 1; C = 1; }\n");

        assertThat(file.enums().get(0).allowAlias()).isTrue();
        assertThat(file.enums().get(0).values()).hasSize(3);
    }

    @Test
    void rejectsReservedFieldNumberRange() {
        assertThatThrownBy(() -> parse("syntax = \"proto3\";\nmessage A { string s = 19500; }\n"))
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("reserved for protobuf internals");
    }

    @Test
    void rejectsOutOfRangeFieldNumber() {
        assertThatThrownBy(() -> parse("syntax = \"proto3\";\nmessage A { string s = 0; }\n"))
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void rejectsRepeatedOneofMember() {
        assertThatThrownBy(() -> parse("syntax = \"proto3\";\nmessage A { oneof b { repeated string s = 1; } }\n"))
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("cannot be repeated");
    }

    @Test
    void reportsUnterminatedBodyInsteadOfLooping() {
        assertThatThrownBy(() -> parse("syntax = \"proto3\";\nmessage A { string s = 1;\n"))
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("unterminated");
    }

    private static Defs.FieldDef field(Defs.MessageDef message, String name) {
        return message.fields().stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field " + name));
    }
}
