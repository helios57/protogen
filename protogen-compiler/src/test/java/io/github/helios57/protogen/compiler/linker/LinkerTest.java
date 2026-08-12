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
                "syntax = \"proto3\";\nimport \"file0.proto\";\npackage a;\nmessage Use { Holder.Status status = 1; }\n");

        Defs.FieldDef field = schema.files().get(1).messages().get(0).fields().get(0);
        assertThat(field.kind()).isEqualTo(Defs.Kind.ENUM);
        assertThat(field.resolved().fullName()).isEqualTo("a.Holder.Status");
    }

    @Test
    void resolvesFullyQualifiedNameWithLeadingDot() {
        Schema schema = link(
                "syntax = \"proto3\";\npackage a.b;\nmessage T { string s = 1; }\n",
                "syntax = \"proto3\";\nimport \"file0.proto\";\npackage c;\nmessage U { .a.b.T t = 1; }\n");

        assertThat(schema.files().get(1).messages().get(0).fields().get(0).resolved().fullName())
                .isEqualTo("a.b.T");
    }

    @Test
    void rejectsATypeFromAFileThatWasNeverImported() {
        assertThatThrownBy(() -> link(
                "syntax = \"proto3\";\npackage a;\nmessage T { string s = 1; }\n",
                "syntax = \"proto3\";\npackage a;\nmessage U { T t = 1; }\n"))
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("declared in file0.proto, which file1.proto does not import");
    }

    @Test
    void anImportIsNotTransitiveUnlessItIsPublic() {
        // file2 imports file1, which imports file0 - so file0's types stay out of reach
        assertThatThrownBy(() -> link(
                "syntax = \"proto3\";\npackage a;\nmessage T { string s = 1; }\n",
                "syntax = \"proto3\";\nimport \"file0.proto\";\npackage a;\nmessage U { T t = 1; }\n",
                "syntax = \"proto3\";\nimport \"file1.proto\";\npackage a;\nmessage V { T t = 1; }\n"))
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("file2.proto does not import");
    }

    @Test
    void importPublicReExportsWhatItImports() {
        Schema schema = link(
                "syntax = \"proto3\";\npackage a;\nmessage T { string s = 1; }\n",
                "syntax = \"proto3\";\nimport public \"file0.proto\";\npackage a;\nmessage U { T t = 1; }\n",
                "syntax = \"proto3\";\nimport \"file1.proto\";\npackage a;\nmessage V { T t = 1; }\n");

        assertThat(schema.files().get(2).messages().get(0).fields().get(0).resolved().fullName())
                .isEqualTo("a.T");
    }

    @Test
    void anImportPathIsMatchedByItsFileName() {
        // imports are written relative to the proto root, parsed files carry the name they were read under
        Schema schema = link(
                "syntax = \"proto3\";\npackage a;\nmessage T { string s = 1; }\n",
                "syntax = \"proto3\";\nimport \"model/nested/file0.proto\";\npackage a;\nmessage U { T t = 1; }\n");

        assertThat(schema.files().get(1).messages().get(0).fields().get(0).resolved().fullName())
                .isEqualTo("a.T");
    }

    @Test
    void mapsTimestampOntoInstantRatherThanAGeneratedType() {
        Schema schema = link("""
                syntax = "proto3";
                package a;
                import "google/protobuf/timestamp.proto";
                message M { google.protobuf.Timestamp at = 1; }
                """);

        Defs.FieldDef field = schema.files().get(0).messages().get(0).fields().get(0);
        assertThat(field.kind()).isEqualTo(Defs.Kind.WELL_KNOWN);
        assertThat(field.wellKnown()).isEqualTo(io.github.helios57.protogen.compiler.model.WellKnown.TIMESTAMP);
    }

    @Test
    void aWellKnownTypeNeedsNoImport() {
        // their definitions are fixed and public, so protogen knows them without being handed the file
        Schema schema = link("""
                syntax = "proto3";
                package a;
                message M {
                  google.protobuf.Timestamp at = 1;
                  google.protobuf.Duration took = 2;
                  google.protobuf.StringValue note = 3;
                }
                """);

        assertThat(schema.files().get(0).messages().get(0).fields())
                .extracting(Defs.FieldDef::wellKnown)
                .containsExactly(io.github.helios57.protogen.compiler.model.WellKnown.TIMESTAMP,
                        io.github.helios57.protogen.compiler.model.WellKnown.DURATION,
                        io.github.helios57.protogen.compiler.model.WellKnown.STRING_VALUE);
    }

    @Test
    void aTypeOfTheUsersOwnIsNotMistakenForAWellKnownOne() {
        Schema schema = link("""
                syntax = "proto3";
                package a;
                message Timestamp { int64 whatever = 1; }
                message M { Timestamp at = 1; }
                """);

        Defs.FieldDef field = schema.files().get(0).messages().get(1).fields().get(0);
        assertThat(field.kind()).isEqualTo(Defs.Kind.MESSAGE);
        assertThat(field.resolved().fullName()).isEqualTo("a.Timestamp");
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
    void aWellKnownTypeWithNoJdkCounterpartIsGeneratedFromTheBundledDefinition() {
        Schema schema = link("""
                syntax = "proto3";
                package a;
                option java_package = "x";
                message M { google.protobuf.Any a = 1; }
                """);

        // pulled in without an import, and generated into the schema's own java package
        assertThat(schema.files()).extracting(ProtoFile::fileName).contains("any.proto");
        Defs.FieldDef field = schema.files().get(schema.files().size() - 1)
                .messages().get(0).fields().get(0);
        assertThat(field.kind()).isEqualTo(Defs.Kind.MESSAGE);
        assertThat(field.resolved().fullName()).isEqualTo("google.protobuf.Any");
        assertThat(field.resolved().file().javaPackage()).isEqualTo("x");
    }

    @Test
    void aBundledDefinitionBringsWhatItNeedsWithIt() {
        Schema schema = link("""
                syntax = "proto3";
                package a;
                option java_package = "x";
                message M { google.protobuf.Struct payload = 1; }
                """);

        // Struct is a map of Value, Value is a oneof over Struct, ListValue and NullValue
        assertThat(schema.symbols().keySet())
                .contains("google.protobuf.Struct", "google.protobuf.Value",
                        "google.protobuf.ListValue", "google.protobuf.NullValue");
    }

    @Test
    void aBundledDefinitionIsNotLoadedWhenNothingNamesIt() {
        Schema schema = link("syntax = \"proto3\";\npackage a;\nmessage M { string s = 1; }\n");

        assertThat(schema.files()).hasSize(1);
    }

    @Test
    void aSchemaThatDeclaresItsOwnWinsOverTheBundledOne() {
        Schema schema = link("""
                syntax = "proto3";
                package google.protobuf;
                message Empty { string mine = 1; }
                message M { Empty e = 1; }
                """);

        assertThat(schema.files()).hasSize(1);
        assertThat(schema.symbols().get("google.protobuf.Empty").file().fileName()).isEqualTo("file0.proto");
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
