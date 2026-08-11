package io.github.helios57.protogen.compiler.gen;

import io.github.helios57.protogen.compiler.model.Defs;
import io.github.helios57.protogen.compiler.model.Names;

import java.util.ArrayList;
import java.util.List;

/** Emits a proto enum as a Java enum that tolerates values this build has never heard of. */
final class EnumEmitter {

    private final boolean emitJavadoc;

    EnumEmitter(boolean emitJavadoc) {
        this.emitJavadoc = emitJavadoc;
    }

    void emit(Java out, Defs.EnumDef def, boolean topLevel) {
        if (emitJavadoc) {
            String comment = def.comment() == null ? "" : def.comment().strip();
            out.javadoc(comment.isEmpty()
                    ? "Generated from enum {@code " + def.fullName() + "}."
                    : comment);
        }
        out.line("public enum " + def.name() + " {");
        out.indent();

        List<String> constants = new ArrayList<>();
        for (Defs.EnumValueDef v : def.values()) {
            constants.add(Names.escape(v.name()) + "(" + v.number() + ")");
        }
        for (int i = 0; i < constants.size(); i++) {
            Defs.EnumValueDef v = def.values().get(i);
            if (emitJavadoc && v.comment() != null && !v.comment().isBlank()) {
                out.javadoc(v.comment().strip());
            }
            out.line(constants.get(i) + ",");
        }
        if (emitJavadoc) {
            out.javadoc("""
                    A value this build does not know, produced when decoding a message written against a newer
                    schema. It has no wire number and is skipped when re-encoding.""");
        }
        out.line("UNRECOGNIZED(-1);");

        out.blank();
        out.line("private final int number;");
        out.blank();
        out.line(def.name() + "(int number) {");
        out.line("    this.number = number;");
        out.line("}");

        out.blank();
        if (emitJavadoc) {
            out.javadoc("@return the wire value, or {@code -1} for {@link #UNRECOGNIZED}");
        }
        out.line("public int number() {");
        out.line("    return number;");
        out.line("}");

        out.blank();
        if (emitJavadoc) {
            out.javadoc("@return the constant with this wire value, or {@link #UNRECOGNIZED} if unknown");
        }
        out.line("public static " + def.name() + " forNumber(int number) {");
        out.indent();
        out.line("return switch (number) {");
        out.indent();
        List<Integer> emitted = new ArrayList<>();
        for (Defs.EnumValueDef v : def.values()) {
            if (emitted.contains(v.number())) {
                continue; // an alias; the first declaration wins, as protoc does
            }
            emitted.add(v.number());
            out.line("case " + v.number() + " -> " + Names.escape(v.name()) + ";");
        }
        out.line("default -> UNRECOGNIZED;");
        out.outdent();
        out.line("};");
        out.outdent();
        out.line("}");

        out.outdent();
        out.line("}");
    }
}
