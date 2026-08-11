package io.github.helios57.protogen.compiler.gen;

import io.github.helios57.protogen.compiler.model.Constraints;
import io.github.helios57.protogen.compiler.model.Defs;
import io.github.helios57.protogen.compiler.model.Names;
import io.github.helios57.protogen.compiler.model.ProtoFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Emits a JSON sidecar describing what the schema's documentation annotations say.
 * <p>
 * {@code @Example} and {@code @RootNode} are documentation, not behaviour - they belong in the Javadoc and
 * in whatever renders the API docs, not in the generated runtime. This writes them, together with the
 * constraints and the Java names they ended up with, to
 * {@code META-INF/protogen/<file>.json} in the generated resources, so a documentation pipeline can read
 * them without re-parsing the {@code .proto} or reflecting over the classes.
 * <p>
 * The JSON is written by hand rather than with a library, because protogen-compiler has no dependencies.
 */
final class MetadataEmitter {

    /** Bumped when the shape of the document changes, so consumers can tell. */
    private static final int SCHEMA_VERSION = 1;

    private MetadataEmitter() {
    }

    /** @return the sidecar path and content for one {@code .proto} file */
    static JavaGenerator.GeneratedFile emit(ProtoFile file) {
        Json json = new Json();
        json.objectStart();
        json.field("schemaVersion", SCHEMA_VERSION);
        json.field("file", file.fileName());
        json.field("protoPackage", file.protoPackage());
        json.field("javaPackage", file.javaPackage());
        json.field("javaMultipleFiles", file.javaMultipleFiles());
        if (!file.javaMultipleFiles()) {
            json.field("javaOuterClassName", file.javaOuterClassName());
        }

        json.key("enums");
        json.arrayStart();
        for (Defs.EnumDef def : file.enums()) {
            emitEnum(json, def);
        }
        json.arrayEnd();

        json.key("messages");
        json.arrayStart();
        for (Defs.MessageDef def : file.messages()) {
            emitMessage(json, def);
        }
        json.arrayEnd();
        json.objectEnd();

        String base = file.fileName().endsWith(".proto")
                ? file.fileName().substring(0, file.fileName().length() - ".proto".length())
                : file.fileName();
        return new JavaGenerator.GeneratedFile("META-INF/protogen/" + base + ".json", json.toString(),
                JavaGenerator.Kind.RESOURCE);
    }

    private static void emitEnum(Json json, Defs.EnumDef def) {
        json.objectStart();
        json.field("name", def.name());
        json.field("fullName", def.fullName());
        json.field("javaType", Types.javaName(def));
        json.key("values");
        json.arrayStart();
        for (Defs.EnumValueDef v : def.values()) {
            json.objectStart();
            json.field("name", v.name());
            json.field("number", v.number());
            json.objectEnd();
        }
        json.arrayEnd();
        json.objectEnd();
    }

    private static void emitMessage(Json json, Defs.MessageDef message) {
        Constraints messageConstraints = Constraints.parse(message.comment());
        json.objectStart();
        json.field("name", message.name());
        json.field("fullName", message.fullName());
        json.field("javaType", Types.javaName(message));
        // @RootNode marks a message that is an API entry point rather than a nested detail
        json.field("rootNode", messageConstraints.rootNode());
        json.field("documentation", prose(message.comment()));

        json.key("fields");
        json.arrayStart();
        for (Defs.FieldDef f : message.fields()) {
            emitField(json, f);
        }
        json.arrayEnd();

        json.key("nestedEnums");
        json.arrayStart();
        for (Defs.EnumDef nested : message.nestedEnums()) {
            emitEnum(json, nested);
        }
        json.arrayEnd();

        json.key("nestedMessages");
        json.arrayStart();
        for (Defs.MessageDef nested : message.nestedMessages()) {
            emitMessage(json, nested);
        }
        json.arrayEnd();
        json.objectEnd();
    }

    private static void emitField(Json json, Defs.FieldDef f) {
        Constraints c = f.constraints();
        json.objectStart();
        json.field("name", f.name());
        json.field("javaName", Names.fieldName(f.name()));
        json.field("number", f.number());
        json.field("kind", f.kind().name().toLowerCase(java.util.Locale.ROOT));
        json.field("type", describeType(f));
        json.field("label", f.label().name().toLowerCase(java.util.Locale.ROOT));
        json.field("documentation", prose(f.comment()));

        json.key("examples");
        json.arrayStart();
        for (String example : c.examples()) {
            json.value(example);
        }
        json.arrayEnd();

        json.key("constraints");
        json.objectStart();
        json.optional("minLength", c.minLength());
        json.optional("maxLength", c.maxLength());
        json.optional("minimum", c.minimum());
        json.optional("maximum", c.maximum());
        json.optional("exclusiveMinimum", c.exclusiveMinimum());
        json.optional("exclusiveMaximum", c.exclusiveMaximum());
        json.optional("multipleOf", c.multipleOf());
        json.optional("minItems", c.minItems());
        json.optional("maxItems", c.maxItems());
        json.optional("pattern", c.pattern());
        if (c.required()) {
            json.field("required", true);
        }
        json.objectEnd();
        json.objectEnd();
    }

    private static String describeType(Defs.FieldDef f) {
        return switch (f.kind()) {
            case SCALAR -> f.scalar().protoName();
            case ENUM, MESSAGE -> f.resolved().fullName();
            case TIMESTAMP -> "google.protobuf.Timestamp";
            case MAP -> "map<" + describeType(f.mapKey()) + ", " + describeType(f.mapValue()) + ">";
        };
    }

    /** The comment with the annotation lines removed, i.e. the human-readable part. */
    private static String prose(String comment) {
        if (comment == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (String line : comment.split("\n")) {
            String stripped = line.strip();
            if (!stripped.startsWith("@") && !stripped.isEmpty()) {
                lines.add(stripped);
            }
        }
        return String.join(" ", lines);
    }

    /** A minimal JSON writer: enough for this document, no dependency. */
    private static final class Json {

        private final StringBuilder sb = new StringBuilder(1024);
        private int depth;
        private boolean needComma;
        /** A value that directly follows a key stays on the key's line. */
        private boolean afterKey;

        void objectStart() {
            separator();
            sb.append("{");
            depth++;
            needComma = false;
        }

        void objectEnd() {
            depth--;
            newline();
            sb.append("}");
            needComma = true;
        }

        void arrayStart() {
            separator();
            sb.append("[");
            depth++;
            needComma = false;
        }

        void arrayEnd() {
            depth--;
            newline();
            sb.append("]");
            needComma = true;
        }

        void key(String name) {
            separator();
            sb.append(quote(name)).append(": ");
            needComma = false;
            afterKey = true;
        }

        void field(String name, String value) {
            key(name);
            sb.append(quote(value));
            written();
        }

        void field(String name, int value) {
            key(name);
            sb.append(value);
            written();
        }

        void field(String name, boolean value) {
            key(name);
            sb.append(value);
            written();
        }

        /** A scalar written straight after a key ends the key, so the next one gets its comma. */
        private void written() {
            needComma = true;
            afterKey = false;
        }

        void optional(String name, Integer value) {
            if (value != null) {
                field(name, value.intValue());
            }
        }

        void optional(String name, BigDecimal value) {
            if (value != null) {
                key(name);
                sb.append(value.toPlainString());
                written();
            }
        }

        void optional(String name, String value) {
            if (value != null) {
                field(name, value);
            }
        }

        void value(String value) {
            separator();
            sb.append(quote(value));
            needComma = true;
        }

        private void separator() {
            if (afterKey) {
                afterKey = false;
                return;
            }
            if (needComma) {
                sb.append(',');
            }
            if (!sb.isEmpty()) {
                newline();
            }
            needComma = false;
        }

        private void newline() {
            if (!sb.isEmpty()) {
                sb.append('\n').append("  ".repeat(Math.max(0, depth)));
            }
        }

        private static String quote(String value) {
            StringBuilder out = new StringBuilder(value.length() + 8);
            out.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            return out.append('"').toString();
        }

        @Override
        public String toString() {
            return sb.append('\n').toString();
        }
    }
}
