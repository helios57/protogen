package io.github.helios57.protogen.compiler.parser;

import io.github.helios57.protogen.compiler.ProtoCompileException;
import io.github.helios57.protogen.compiler.model.ProtoFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtoParserTest {

    private static final String SAMPLE = """
            syntax = "proto3";

            package ch.sbb.tms.ssp;

            option java_multiple_files = true;
            option java_package = "ch.sbb.tms.ssp.model";

            import "EnumV1.proto";

            enum StageEnum {
              DEV = 0;
              PROD = 1;
            }

            /**
             * A monitored broker.
             * @Example tms
             */
            message BrokerMonitoringV1 {
              string tmsAbbl1 = 1;
              map<string, string> sempUrls = 6;
              optional string podNamePrefix = 10;
              message Nested { bool flag = 1; }
            }
            """;

    @Test
    void parsesHeaderAndTopLevelTypes() {
        ProtoFile file = new ProtoParser("BrokerMonitoringsV1.proto", SAMPLE).parse();

        assertThat(file.syntax()).isEqualTo("proto3");
        assertThat(file.protoPackage()).isEqualTo("ch.sbb.tms.ssp");
        assertThat(file.imports()).containsExactly("EnumV1.proto");
        assertThat(file.javaPackage()).isEqualTo("ch.sbb.tms.ssp.model");
        assertThat(file.javaMultipleFiles()).isTrue();
        assertThat(file.types())
                .extracting(ProtoFile.TypeDecl::name)
                .containsExactly("StageEnum", "BrokerMonitoringV1");
    }

    @Test
    void retainsLeadingCommentsForJavadoc() {
        ProtoFile file = new ProtoParser("x.proto", SAMPLE).parse();

        ProtoFile.TypeDecl message = file.types().get(1);
        assertThat(message.comment())
                .contains("A monitored broker.")
                .contains("@Example tms");
    }

    @Test
    void fallsBackToProtoPackageWhenJavaPackageAbsent() {
        ProtoFile file = new ProtoParser("x.proto", "syntax = \"proto3\";\npackage a.b;\n").parse();

        assertThat(file.javaPackage()).isEqualTo("a.b");
        assertThat(file.javaMultipleFiles()).isFalse();
    }

    @Test
    void rejectsUnsupportedConstructsWithLocation() {
        String withService = "syntax = \"proto3\";\npackage a;\nservice Foo { }\n";

        assertThatThrownBy(() -> new ProtoParser("svc.proto", withService).parse())
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("svc.proto:3:1")
                .hasMessageContaining("not supported");
    }

    @Test
    void reportsUnterminatedBodyInsteadOfLooping() {
        String truncated = "syntax = \"proto3\";\nmessage A { string s = 1;\n";

        assertThatThrownBy(() -> new ProtoParser("t.proto", truncated).parse())
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("unterminated");
    }
}
