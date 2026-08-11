package io.github.helios57.protogen.compiler.linker;

import io.github.helios57.protogen.compiler.ProtoCompileException;
import io.github.helios57.protogen.compiler.ProtoCompiler;
import io.github.helios57.protogen.compiler.model.Defs;
import io.github.helios57.protogen.compiler.model.ProtoFile;
import io.github.helios57.protogen.compiler.model.ScalarType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinkerTest {

    private static final ProtoCompiler COMPILER = new ProtoCompiler(ProtoCompiler.Options.defaults());

    private static Schema link(String... sources) {
        List<ProtoFile> files = new java.util.ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            files.add(COMPILER.parse("file" + i + ".proto", sources[i]));
        }
        return COMPILER.link(files);
    }

    @Test
    void resolvesScalarFields() {
        Schema schema = link("syntax = \"proto3\";\npackage a;\nmessage M { string s = 1; sint64 z = 2; }\n");

        Defs.MessageDef m = schema.files().get(0).messages().get(0);
        assertThat(m.fields().get(0).kind()).isEqualTo(Defs.Kind.SCALAR);
        assertThat(m.fields().get(0).scalar()).isEqualTo(ScalarType.STRING);
        assertThat(m.fields().get(1).scalar()).isEqualTo(ScalarType.SINT64);
    }

    @Test
    void resolvesNestedTypeFromTheInnermostScopeOutward() {
        Schema schema = link("""
                syntax = "proto3";
                package a;
                message Outer {
                  message Inner { string s = 1; }
                  Inner inner = 1;
                }
                """);

        Defs.FieldDef field = schema.files().get(0).messages().get(0).fields().get(0);
        assertThat(field.kind()).isEqualTo(Defs.Kind.MESSAGE);
        assertThat(field.resolved().fullName()).isEqualTo("a.Outer.Inner");
    }

    @Test
    void resolvesQualifiedNestedTypeAcrossFiles() {
        Schema schema = link(
                "syntax = \"proto3\";\npackage a;\nmessage Holder { enum Status { S = 0; } }\n",
                "syntax = \"proto3\";\npackage a;\nmessage Use { Holder.Status status = 1; }\n");

        Defs.FieldDef field = schema.files().get(1).messages().get(0).fields().get(0);
        assertThat(field.kind()).isEqualTo(Defs.Kind.ENUM);
        assertThat(field.resolved().fullName()).isEqualTo("a.Holder.Status");
    }

    @Test
    void resolvesFullyQualifiedNameWithLeadingDot() {
        Schema schema = link(
                "syntax = \"proto3\";\npackage a.b;\nmessage T { string s = 1; }\n",
                "syntax = \"proto3\";\npackage c;\nmessage U { .a.b.T t = 1; }\n");

        assertThat(schema.files().get(1).messages().get(0).fields().get(0).resolved().fullName())
                .isEqualTo("a.b.T");
    }

    @Test
    void mapsTimestampOntoInstantRatherThanAGeneratedType() {
        Schema schema = link("""
                syntax = "proto3";
                package a;
                import "google/protobuf/timestamp.proto";
                message M { google.protobuf.Timestamp at = 1; }
                """);

        assertThat(schema.files().get(0).messages().get(0).fields().get(0).kind())
                .isEqualTo(Defs.Kind.TIMESTAMP);
    }

    @Test
    void resolvesMapKeyAndValue() {
        Schema schema = link("""
                syntax = "proto3";
                package a;
                message V { string s = 1; }
                message M { map<string, V> entries = 1; }
                """);

        Defs.FieldDef field = schema.files().get(0).messages().get(1).fields().get(0);
        assertThat(field.kind()).isEqualTo(Defs.Kind.MAP);
        assertThat(field.mapKey().scalar()).isEqualTo(ScalarType.STRING);
        assertThat(field.mapValue().resolved().fullName()).isEqualTo("a.V");
    }

    @Test
    void rejectsUnresolvableType() {
        assertThatThrownBy(() -> link("syntax = \"proto3\";\npackage a;\nmessage M { Missing m = 1; }\n"))
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("cannot resolve type 'Missing'");
    }

    @Test
    void rejectsDuplicateTypeNames() {
        assertThatThrownBy(() -> link(
                "syntax = \"proto3\";\npackage a;\nmessage T { string s = 1; }\n",
                "syntax = \"proto3\";\npackage a;\nmessage T { string s = 1; }\n"))
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("duplicate type 'a.T'");
    }

    @Test
    void rejectsDuplicateFieldNumbers() {
        assertThatThrownBy(() -> link("syntax = \"proto3\";\nmessage M { string a = 1; string b = 1; }\n"))
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("used twice");
    }

    @Test
    void rejectsUnsupportedWellKnownTypesByName() {
        assertThatThrownBy(() -> link("""
                syntax = "proto3";
                import "google/protobuf/any.proto";
                message M { google.protobuf.Any a = 1; }
                """))
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("google.protobuf.Any")
                .hasMessageContaining("only google.protobuf.Timestamp");
    }

    @Test
    void rejectsInvalidMapKeyTypes() {
        assertThatThrownBy(() -> link("syntax = \"proto3\";\nmessage M { map<double, string> m = 1; }\n"))
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("map key must be");
    }

    @Test
    void groupsFilesByTheirJavaPackage() {
        Schema schema = link(
                "syntax = \"proto3\";\npackage a;\noption java_package = \"x.y\";\nmessage T { string s = 1; }\n",
                "syntax = \"proto3\";\npackage b;\noption java_package = \"x.y\";\nmessage U { string s = 1; }\n",
                "syntax = \"proto3\";\npackage c;\noption java_package = \"z\";\nmessage V { string s = 1; }\n");

        assertThat(schema.javaPackages()).containsExactly("x.y", "z");
        assertThat(schema.filesIn("x.y")).hasSize(2);
    }
}
