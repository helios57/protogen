package io.github.helios57.protogen.compiler.gen;

import io.github.helios57.protogen.compiler.model.Constraints;
import io.github.helios57.protogen.compiler.model.Defs;
import io.github.helios57.protogen.compiler.model.Names;
import io.github.helios57.protogen.compiler.model.ScalarType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Emits one message as an immutable Java {@code record}.
 * <p>
 * The record is the value type: its compact constructor normalises absent values to their proto3 defaults,
 * makes collections unmodifiable and enforces the {@code @Minimum} / {@code @Pattern} style constraints
 * declared in the schema comments. Serialization is plain methods over {@code byte[]} and {@code int}, so a
 * message never exposes a codec type across a package boundary.
 */
final class MessageEmitter {

    private final String javaPackage;
    private final GeneratorOptions options;
    private final boolean emitJavadoc;
    /** The message currently being emitted, so field-level helpers can see its oneof groups. */
    private Defs.MessageDef currentMessage;
    /** Names for this message's generated locals, chosen not to collide with its components. */
    private Locals locals;

    MessageEmitter(String javaPackage, GeneratorOptions options) {
        this.javaPackage = javaPackage;
        this.options = options;
        this.emitJavadoc = options.emitJavadoc();
    }

    /**
     * The name of the trailing unknown-fields component, or {@code null} when not preserving.
     *
     * @return the name of the trailing unknown-fields component, or {@code null} when not preserving
     */
    private String unknownComponent() {
        return options.preserveUnknownFields() ? locals.unknown : null;
    }

    void emit(Java out, Defs.MessageDef message, boolean topLevel) {
        Defs.MessageDef enclosingMessage = currentMessage;
        Locals enclosingLocals = locals;
        currentMessage = message;
        locals = new Locals(message.fields());
        try {
            emitRecord(out, message);
        } finally {
            currentMessage = enclosingMessage;
            locals = enclosingLocals;
        }
    }

    private void emitRecord(Java out, Defs.MessageDef message) {
        List<Defs.FieldDef> fields = sorted(message);

        if (emitJavadoc) {
            out.javadoc(javadocFor(message, fields));
        }
        List<String> components = new ArrayList<>();
        for (Defs.FieldDef f : fields) {
            components.add(Types.componentType(f, javaPackage) + " " + Names.fieldName(f.name()));
        }
        if (unknownComponent() != null) {
            components.add("byte[] " + unknownComponent());
        }
        if (components.isEmpty()) {
            out.line("public record " + message.name() + "() {");
        } else {
            out.line("public record " + message.name() + "(");
            out.indent();
            out.indent();
            for (int i = 0; i < components.size(); i++) {
                out.line(components.get(i) + (i < components.size() - 1 ? "," : ") {"));
            }
            out.outdent();
            out.outdent();
        }
        out.indent();

        emitValidationSwitch(out);

        emitPatternConstants(out, fields);
        emitCompactConstructor(out, message, fields);
        emitOneofAccessors(out, message, fields);

        for (Defs.EnumDef nested : message.nestedEnums()) {
            out.blank();
            new EnumEmitter(emitJavadoc).emit(out, nested, false);
        }
        for (Defs.MessageDef nested : message.nestedMessages()) {
            out.blank();
            emit(out, nested, false);
        }

        emitParse(out, message, fields);
        emitProtoSize(out, fields);
        emitWriteTo(out, fields);
        emitToByteArray(out);
        emitValueSemantics(out, message, fields);

        out.outdent();
        out.line("}");
    }

    private static List<Defs.FieldDef> sorted(Defs.MessageDef message) {
        List<Defs.FieldDef> fields = new ArrayList<>(message.fields());
        fields.sort(Comparator.comparingInt(Defs.FieldDef::number));
        return fields;
    }

    // ------------------------------------------------------------- javadoc

    private String javadocFor(Defs.MessageDef message, List<Defs.FieldDef> fields) {
        StringBuilder sb = new StringBuilder();
        String comment = docText(message.comment());
        sb.append(comment.isEmpty() ? "Generated from message {@code " + message.fullName() + "}." : comment);
        for (Defs.FieldDef f : fields) {
            String fieldDoc = docText(f.comment()).replace("\n", " ").strip();
            sb.append("\n\n@param ").append(Names.fieldName(f.name())).append(' ')
                    .append(fieldDoc.isEmpty() ? "field " + f.number() : fieldDoc);
        }
        return sb.toString();
    }

    /** Turns the comment into Javadoc prose; the annotation lines become validation, not doc tags. */
    private static String docText(String comment) {
        if (comment == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : comment.split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("@")) {
                if (stripped.regionMatches(true, 1, "Example", 0, "Example".length())) {
                    sb.append(sb.isEmpty() ? "" : "\n").append("Example: ")
                            .append(stripped.substring("@Example".length()).strip());
                }
                continue;
            }
            // an unescaped @ elsewhere in the text would be read as a Javadoc tag
            sb.append(sb.isEmpty() ? "" : "\n").append(stripped.replace("@", "&#64;"));
        }
        return sb.toString().strip();
    }

    // -------------------------------------------------- construction

    private void emitPatternConstants(Java out, List<Defs.FieldDef> fields) {
        for (Defs.FieldDef f : fields) {
            if (f.constraints().pattern() != null && isStringLike(f)) {
                out.blank();
                out.line("private static final java.util.regex.Pattern " + patternConstant(f)
                        + " = java.util.regex.Pattern.compile(" + Java.literal(f.constraints().pattern()) + ");");
            }
        }
    }

    private static String patternConstant(Defs.FieldDef f) {
        return "PATTERN_" + f.name().toUpperCase(Locale.ROOT);
    }

    private static boolean isStringLike(Defs.FieldDef f) {
        return f.kind() == Defs.Kind.SCALAR && f.scalar() == ScalarType.STRING;
    }

    /**
     * The runtime half of the validation controls: a {@code static final} the JIT folds away, so switching
     * validation off with {@code -Dprotogen.validation=false} costs nothing at all. Only an explicit
     * {@code false} disables it, so a typo in the property leaves the checks running.
     */
    private void emitValidationSwitch(Java out) {
        if (!options.emitValidation()) {
            return;
        }
        out.blank();
        if (emitJavadoc) {
            out.javadoc("""
                    Whether the constraints declared in the schema are enforced.

                    <p>Set {@code -Dprotogen.validation=false} to turn them off for the whole JVM, for
                    instance when reading legacy data that predates a constraint. Any other value, or none,
                    leaves them on.""");
        }
        out.line("private static final boolean PROTOGEN_VALIDATION = !\"false\".equalsIgnoreCase("
                + "System.getProperty(\"protogen.validation\", \"true\"));");
    }

    private void emitCompactConstructor(Java out, Defs.MessageDef message, List<Defs.FieldDef> fields) {
        if (fields.isEmpty() && unknownComponent() == null) {
            return;
        }
        List<String> body = new ArrayList<>();
        for (Defs.FieldDef f : fields) {
            String n = Names.fieldName(f.name());
            if (f.kind() == Defs.Kind.MAP) {
                body.add(n + " = " + n + " == null ? java.util.Map.of()"
                        + " : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(" + n + "));");
            } else if (f.repeated()) {
                body.add(n + " = " + n + " == null ? java.util.List.of() : java.util.List.copyOf(" + n + ");");
            } else if (!Types.nullable(f)) {
                if (isStringLike(f)) {
                    body.add(n + " = " + n + " == null ? \"\" : " + n + ";");
                } else if (isBytes(f)) {
                    body.add(n + " = " + n + " == null ? new byte[0] : " + n + ".clone();");
                } else if (f.kind() == Defs.Kind.ENUM) {
                    body.add(n + " = " + n + " == null ? " + Types.defaultExpr(f, javaPackage) + " : " + n + ";");
                }
            } else if (isBytes(f)) {
                body.add(n + " = " + n + " == null ? null : " + n + ".clone();");
            }
        }
        if (unknownComponent() != null) {
            String u = unknownComponent();
            body.add(u + " = " + u + " == null ? new byte[0] : " + u + ".clone();");
        }
        // a oneof invariant is structural, not schema validation, so it is never switched off
        List<String> checks = new ArrayList<>(oneofChecks(message, fields));
        List<String> schemaChecks = options.emitValidation()
                ? validationChecks(message, fields)
                : List.of();
        if (body.isEmpty() && checks.isEmpty() && schemaChecks.isEmpty()) {
            return;
        }
        out.blank();
        if (emitJavadoc) {
            out.javadoc(checks.isEmpty() && schemaChecks.isEmpty()
                    ? "Normalises absent values to their proto3 defaults and makes collections unmodifiable."
                    : """
                    Normalises absent values to their proto3 defaults, makes collections unmodifiable and
                    enforces the constraints declared in the schema.

                    @throws IllegalArgumentException if a constraint is violated""");
        }
        out.line("public " + message.name() + " {");
        out.indent();
        body.forEach(out::line);
        if (!body.isEmpty() && !(checks.isEmpty() && schemaChecks.isEmpty())) {
            out.blank();
        }
        checks.forEach(out::line);
        if (!schemaChecks.isEmpty()) {
            out.line("if (PROTOGEN_VALIDATION) {");
            out.indent();
            schemaChecks.forEach(out::line);
            out.outdent();
            out.line("}");
        }
        out.outdent();
        out.line("}");
    }

    // ------------------------------------------------------------ validation

    /** At most one member of a oneof may be set, so an invalid combination cannot be constructed. */
    private List<String> oneofChecks(Defs.MessageDef message, List<Defs.FieldDef> fields) {
        List<String> checks = new ArrayList<>();
        for (int i = 0; i < message.oneofs().size(); i++) {
            int index = i;
            List<String> members = fields.stream()
                    .filter(f -> f.oneofIndex() == index)
                    .map(f -> Names.fieldName(f.name()))
                    .toList();
            if (members.size() < 2) {
                continue;
            }
            String count = members.stream()
                    .map(m -> "(" + m + " != null ? 1 : 0)")
                    .reduce((a, b) -> a + " + " + b)
                    .orElseThrow();
            checks.add("if (" + count + " > 1) {\n    throw new IllegalArgumentException(\""
                    + message.name() + ": at most one member of oneof '" + message.oneofs().get(i).name()
                    + "' may be set\");\n}");
        }
        return checks;
    }

    private List<String> validationChecks(Defs.MessageDef message, List<Defs.FieldDef> fields) {
        List<String> checks = new ArrayList<>();
        for (Defs.FieldDef f : fields) {
            Constraints c = f.constraints();
            if (!c.hasValidation()) {
                continue;
            }
            String n = Names.fieldName(f.name());
            String where = message.name() + "." + n;

            if (c.required()) {
                checks.add(requiredCheck(f, n, where));
            }
            if (f.repeated() || f.kind() == Defs.Kind.MAP) {
                if (c.minItems() != null) {
                    checks.add(check(n + ".size() < " + c.minItems(), where, "@MinItems " + c.minItems(),
                            n + ".size()"));
                }
                if (c.maxItems() != null) {
                    checks.add(check(n + ".size() > " + c.maxItems(), where, "@MaxItems " + c.maxItems(),
                            n + ".size()"));
                }
                continue;
            }
            String guard = Types.nullable(f) ? n + " != null && " : "";
            if (isStringLike(f)) {
                if (c.minLength() != null) {
                    checks.add(check(guard + n + ".length() < " + c.minLength(), where,
                            "@MinLength " + c.minLength(), n + ".length()"));
                }
                if (c.maxLength() != null) {
                    checks.add(check(guard + n + ".length() > " + c.maxLength(), where,
                            "@MaxLength " + c.maxLength(), n + ".length()"));
                }
                if (c.pattern() != null) {
                    checks.add(check(guard + "!" + patternConstant(f) + ".matcher(" + n + ").matches()", where,
                            "@Pattern " + c.pattern(), n));
                }
            }
            if (isNumeric(f)) {
                if (c.minimum() != null) {
                    checks.add(check(guard + n + " < " + numberLiteral(f, c.minimum()), where,
                            "@Minimum " + c.minimum(), n));
                }
                if (c.maximum() != null) {
                    checks.add(check(guard + n + " > " + numberLiteral(f, c.maximum()), where,
                            "@Maximum " + c.maximum(), n));
                }
                if (c.exclusiveMinimum() != null) {
                    checks.add(check(guard + n + " <= " + numberLiteral(f, c.exclusiveMinimum()), where,
                            "@ExclusiveMinimum " + c.exclusiveMinimum(), n));
                }
                if (c.exclusiveMaximum() != null) {
                    checks.add(check(guard + n + " >= " + numberLiteral(f, c.exclusiveMaximum()), where,
                            "@ExclusiveMaximum " + c.exclusiveMaximum(), n));
                }
                if (c.multipleOf() != null && isIntegral(f)) {
                    checks.add(check(guard + n + " % " + numberLiteral(f, c.multipleOf()) + " != 0", where,
                            "@MultipleOf " + c.multipleOf(), n));
                }
            }
        }
        return checks;
    }

    private String requiredCheck(Defs.FieldDef f, String n, String where) {
        String condition;
        if (f.repeated() || f.kind() == Defs.Kind.MAP) {
            condition = n + ".isEmpty()";
        } else if (Types.nullable(f)) {
            condition = n + " == null";
        } else if (isStringLike(f)) {
            condition = n + ".isEmpty()";
        } else if (isBytes(f)) {
            condition = n + ".length == 0";
        } else if (f.kind() == Defs.Kind.ENUM) {
            condition = n + ".number() == 0";
        } else if (f.scalar() == ScalarType.BOOL) {
            condition = "!" + n;
        } else {
            condition = n + " == " + f.scalar().defaultLiteral();
        }
        return "if (" + condition + ") {\n    throw new IllegalArgumentException(\"" + where
                + " is @Required but was not set\");\n}";
    }

    private static String check(String condition, String where, String rule, String actualExpr) {
        return "if (" + condition + ") {\n    throw new IllegalArgumentException(\"" + where + " violates "
                + rule.replace("\\", "\\\\").replace("\"", "\\\"") + ", was: \" + " + actualExpr + ");\n}";
    }

    private static boolean isNumeric(Defs.FieldDef f) {
        return f.kind() == Defs.Kind.SCALAR && f.scalar() != ScalarType.STRING
                && f.scalar() != ScalarType.BYTES && f.scalar() != ScalarType.BOOL;
    }

    private static boolean isIntegral(Defs.FieldDef f) {
        return isNumeric(f) && f.scalar() != ScalarType.DOUBLE && f.scalar() != ScalarType.FLOAT;
    }

    private static boolean isBytes(Defs.FieldDef f) {
        return f.kind() == Defs.Kind.SCALAR && f.scalar() == ScalarType.BYTES;
    }

    /**
     * Whether this message will actually carry schema-declared checks.
     *
     * @return whether this message will actually carry schema-declared checks
     */
    private boolean hasValidation(Defs.MessageDef message) {
        return options.emitValidation()
                && message.fields().stream().anyMatch(f -> f.constraints().hasValidation());
    }

    private static String numberLiteral(Defs.FieldDef f, java.math.BigDecimal value) {
        return switch (f.scalar()) {
            case DOUBLE -> value.toPlainString() + "D";
            case FLOAT -> value.toPlainString() + "F";
            case INT64, UINT64, SINT64, FIXED64, SFIXED64 -> value.toBigInteger() + "L";
            default -> value.toBigInteger().toString();
        };
    }

    // ---------------------------------------------------------------- oneof

    private void emitOneofAccessors(Java out, Defs.MessageDef message, List<Defs.FieldDef> fields) {
        for (int i = 0; i < message.oneofs().size(); i++) {
            Defs.OneofDef oneof = message.oneofs().get(i);
            int index = i;
            List<Defs.FieldDef> members = fields.stream().filter(f -> f.oneofIndex() == index).toList();
            if (members.isEmpty()) {
                continue;
            }
            String enumName = Names.toUpperCamel(oneof.name()) + "Case";
            out.blank();
            if (emitJavadoc) {
                out.javadoc("Which member of the {@code " + oneof.name() + "} oneof is set.");
            }
            out.line("public enum " + enumName + " {");
            out.indent();
            StringBuilder constants = new StringBuilder("NOT_SET");
            for (Defs.FieldDef m : members) {
                constants.append(", ").append(m.name().toUpperCase(Locale.ROOT));
            }
            out.line(constants + ";");
            out.outdent();
            out.line("}");

            out.blank();
            if (emitJavadoc) {
                out.javadoc("@return which member of the {@code " + oneof.name() + "} oneof is set");
            }
            out.line("public " + enumName + " " + Names.toLowerCamel(oneof.name()) + "Case() {");
            out.indent();
            for (Defs.FieldDef m : members) {
                out.line("if (" + Names.fieldName(m.name()) + " != null) {");
                out.line("    return " + enumName + "." + m.name().toUpperCase(Locale.ROOT) + ";");
                out.line("}");
            }
            out.line("return " + enumName + ".NOT_SET;");
            out.outdent();
            out.line("}");
        }
    }

    // ------------------------------------------------------------- parsing

    private void emitParse(Java out, Defs.MessageDef message, List<Defs.FieldDef> fields) {
        String name = message.name();
        String r = locals.reader;

        // only promise constraint enforcement where the checks are actually generated
        String throwsClause = hasValidation(message)
                ? "@throws IllegalArgumentException if the input is truncated, malformed, or violates a\n"
                + "constraint declared in the schema"
                : "@throws IllegalArgumentException if the input is truncated or malformed";

        out.blank();
        if (emitJavadoc) {
            out.javadoc("Parses a %s from its protobuf encoding.\n\n%s".formatted(name, throwsClause));
        }
        out.line("public static " + name + " parseFrom(byte[] " + locals.data + ") {");
        out.line("    return parseFrom(" + locals.data + ", 0, " + locals.data + ".length);");
        out.line("}");

        out.blank();
        if (emitJavadoc) {
            out.javadoc("Parses a %s from {@code %s} bytes starting at {@code %s}.\n\n%s"
                    .formatted(name, locals.length, locals.offset, throwsClause));
        }
        out.line("public static " + name + " parseFrom(byte[] " + locals.data + ", int " + locals.offset
                + ", int " + locals.length + ") {");
        out.line("    return parse(new ProtoWire.R(" + locals.data + ", " + locals.offset + ", "
                + locals.offset + " + " + locals.length + "));");
        out.line("}");

        out.blank();
        out.line("static " + name + " parse(ProtoWire.R " + r + ") {");
        out.indent();
        for (Defs.FieldDef f : fields) {
            String type = f.repeated() || f.kind() == Defs.Kind.MAP
                    ? mutableCollectionType(f)
                    : Types.componentType(f, javaPackage);
            String init = f.repeated() || f.kind() == Defs.Kind.MAP ? "null" : Types.defaultExpr(f, javaPackage);
            out.line(type + " " + Names.fieldName(f.name()) + " = " + init + ";");
        }
        if (unknownComponent() != null) {
            out.line("ProtoWire.U " + locals.unknown + " = null;");
        }
        out.line("int " + locals.tag + ";");
        out.line("while ((" + locals.tag + " = " + r + ".tag()) != 0) {");
        out.indent();
        out.line("switch (" + locals.tag + ") {");
        out.indent();
        for (Defs.FieldDef f : fields) {
            emitParseCase(out, f);
        }
        if (unknownComponent() != null) {
            out.line("default -> " + locals.unknown + " = " + r + ".copyField(" + locals.tag + ", "
                    + locals.unknown + ");");
        } else {
            out.line("default -> " + r + ".skip(" + locals.tag + ");");
        }
        out.outdent();
        out.line("}");
        out.outdent();
        out.line("}");

        List<String> arguments = new ArrayList<>();
        for (Defs.FieldDef f : fields) {
            arguments.add(Names.fieldName(f.name()));
        }
        if (unknownComponent() != null) {
            arguments.add(locals.unknown + " == null ? new byte[0] : " + locals.unknown + ".toByteArray()");
        }
        if (arguments.isEmpty()) {
            out.line("return new " + name + "();");
        } else {
            out.line("return new " + name + "(");
            out.indent();
            out.indent();
            for (int i = 0; i < arguments.size(); i++) {
                out.line(arguments.get(i) + (i < arguments.size() - 1 ? "," : ");"));
            }
            out.outdent();
            out.outdent();
        }
        out.outdent();
        out.line("}");
    }

    private String mutableCollectionType(Defs.FieldDef f) {
        if (f.kind() == Defs.Kind.MAP) {
            return "java.util.Map<" + Types.boxedElementType(f.mapKey(), javaPackage) + ", "
                    + Types.boxedElementType(f.mapValue(), javaPackage) + ">";
        }
        return "java.util.List<" + Types.boxedElementType(f, javaPackage) + ">";
    }

    private void emitParseCase(Java out, Defs.FieldDef f) {
        String n = Names.fieldName(f.name());
        String r = locals.reader;

        if (f.kind() == Defs.Kind.MAP) {
            out.line("case " + Types.writtenTag(f) + " -> {");
            out.indent();
            out.line("int " + locals.limit + " = " + r + ".pushLimit(" + r + ".uvarint32());");
            out.line(Types.boxedElementType(f.mapKey(), javaPackage) + " " + locals.key + " = "
                    + Types.defaultExpr(f.mapKey(), javaPackage) + ";");
            out.line(Types.boxedElementType(f.mapValue(), javaPackage) + " " + locals.value + " = "
                    + Types.defaultExpr(f.mapValue(), javaPackage) + ";");
            out.line("int " + locals.entryTag + ";");
            out.line("while ((" + locals.entryTag + " = " + r + ".tag()) != 0) {");
            out.indent();
            out.line("switch (" + locals.entryTag + ") {");
            out.indent();
            out.line("case " + Types.tag(f.mapKey()) + " -> " + locals.key + " = " + readExpr(f.mapKey()) + ";");
            if (f.mapValue().kind() == Defs.Kind.MESSAGE) {
                out.line("case " + Types.tag(f.mapValue()) + " -> {");
                out.indent();
                out.line("int " + locals.len + " = " + r + ".uvarint32();");
                out.line(locals.value + " = " + Types.javaName(f.mapValue().resolved(), javaPackage)
                        + ".parseFrom(" + r + ".array(), " + r + ".slice(" + locals.len + "), " + locals.len + ");");
                out.outdent();
                out.line("}");
            } else {
                out.line("case " + Types.tag(f.mapValue()) + " -> " + locals.value + " = "
                        + readExpr(f.mapValue()) + ";");
            }
            out.line("default -> " + r + ".skip(" + locals.entryTag + ");");
            out.outdent();
            out.line("}");
            out.outdent();
            out.line("}");
            out.line(r + ".popLimit(" + locals.limit + ");");
            out.line("if (" + n + " == null) {");
            out.line("    " + n + " = new java.util.LinkedHashMap<>();");
            out.line("}");
            out.line(n + ".put(" + locals.key + ", " + locals.value + ");");
            out.outdent();
            out.line("}");
            return;
        }

        if (f.repeated()) {
            if (Feature.isPacked(f)) {
                out.line("case " + Types.writtenTag(f) + " -> {");
                out.indent();
                out.line("int " + locals.limit + " = " + r + ".pushLimit(" + r + ".uvarint32());");
                emitLazyList(out, n);
                out.line("while (!" + r + ".isAtEnd()) {");
                out.line("    " + n + ".add(" + readExpr(f) + ");");
                out.line("}");
                out.line(r + ".popLimit(" + locals.limit + ");");
                out.outdent();
                out.line("}");
                // a writer that does not pack still produces valid protobuf
                out.line("case " + Types.tag(f) + " -> {");
                out.indent();
                emitLazyList(out, n);
                out.line(n + ".add(" + readExpr(f) + ");");
                out.outdent();
                out.line("}");
            } else {
                out.line("case " + Types.writtenTag(f) + " -> {");
                out.indent();
                emitLazyList(out, n);
                if (f.kind() == Defs.Kind.MESSAGE) {
                    out.line("int " + locals.len + " = " + r + ".uvarint32();");
                    out.line(n + ".add(" + Types.javaName(f.resolved(), javaPackage) + ".parseFrom(" + r
                            + ".array(), " + r + ".slice(" + locals.len + "), " + locals.len + "));");
                } else {
                    out.line(n + ".add(" + readExpr(f) + ");");
                }
                out.outdent();
                out.line("}");
            }
            return;
        }

        List<String> siblings = oneofSiblings(f);
        if (f.kind() == Defs.Kind.MESSAGE) {
            out.line("case " + Types.writtenTag(f) + " -> {");
            out.indent();
            out.line("int " + locals.len + " = " + r + ".uvarint32();");
            out.line(n + " = " + Types.javaName(f.resolved(), javaPackage) + ".parseFrom(" + r + ".array(), "
                    + r + ".slice(" + locals.len + "), " + locals.len + ");");
            siblings.forEach(s -> out.line(s + " = null;"));
            out.outdent();
            out.line("}");
            return;
        }
        if (siblings.isEmpty()) {
            out.line("case " + Types.writtenTag(f) + " -> " + n + " = " + readExpr(f) + ";");
        } else {
            out.line("case " + Types.writtenTag(f) + " -> {");
            out.indent();
            out.line(n + " = " + readExpr(f) + ";");
            siblings.forEach(s -> out.line(s + " = null;"));
            out.outdent();
            out.line("}");
        }
    }

    private void emitLazyList(Java out, String n) {
        out.line("if (" + n + " == null) {");
        out.line("    " + n + " = new java.util.ArrayList<>();");
        out.line("}");
    }

    /**
     * The other members of {@code field}'s oneof.
     * <p>
     * Setting one member clears the rest: protobuf says a stream that sets two members leaves only the
     * last one set.
     */
    private List<String> oneofSiblings(Defs.FieldDef field) {
        if (!field.inOneof() || currentMessage == null) {
            return List.of();
        }
        return currentMessage.fields().stream()
                .filter(f -> f.oneofIndex() == field.oneofIndex() && f != field)
                .map(f -> Names.fieldName(f.name()))
                .toList();
    }

    private String readExpr(Defs.FieldDef f) {
        String r = locals.reader;
        return switch (f.kind()) {
            case SCALAR -> switch (f.scalar()) {
                case DOUBLE -> "Double.longBitsToDouble(" + r + ".fixed64())";
                case FLOAT -> "Float.intBitsToFloat(" + r + ".fixed32())";
                case INT32, UINT32 -> r + ".uvarint32()";
                case INT64, UINT64 -> r + ".varint64()";
                case SINT32 -> "ProtoWire.unZz32(" + r + ".uvarint32())";
                case SINT64 -> "ProtoWire.unZz64(" + r + ".varint64())";
                case FIXED32, SFIXED32 -> r + ".fixed32()";
                case FIXED64, SFIXED64 -> r + ".fixed64()";
                case BOOL -> r + ".bool()";
                case STRING -> r + ".string()";
                case BYTES -> r + ".bytes()";
            };
            case ENUM -> Types.javaName(f.resolved(), javaPackage) + ".forNumber(" + r + ".uvarint32())";
            case TIMESTAMP -> "java.time.Instant.ofEpochMilli(" + r + ".varint64())";
            case MESSAGE, MAP -> throw new IllegalStateException("handled separately");
        };
    }

    // -------------------------------------------------------------- sizing

    private void emitProtoSize(Java out, List<Defs.FieldDef> fields) {
        out.blank();
        if (emitJavadoc) {
            out.javadoc("@return the number of bytes {@link #toByteArray()} produces");
        }
        out.line("public int protoSize() {");
        out.indent();
        out.line("int " + locals.size + " = 0;");
        for (Defs.FieldDef f : fields) {
            emitSizeFor(out, f);
        }
        if (unknownComponent() != null) {
            out.line(locals.size + " += " + unknownComponent() + ".length;");
        }
        out.line("return " + locals.size + ";");
        out.outdent();
        out.line("}");
    }

    private void emitSizeFor(Java out, Defs.FieldDef f) {
        String n = Names.fieldName(f.name());
        String size = locals.size;
        int tagSize = Types.tagSize(Types.writtenTag(f));

        if (f.kind() == Defs.Kind.MAP) {
            emitMapLoopHeader(out, f, n);
            out.indent();
            emitEntrySize(out, f);
            out.line(size + " += " + tagSize + " + ProtoWire.sUVarint32(" + locals.entrySize + ") + "
                    + locals.entrySize + ";");
            out.outdent();
            out.line("}");
            return;
        }

        if (f.repeated()) {
            if (Feature.isPacked(f)) {
                out.line("if (!" + n + ".isEmpty()) {");
                out.indent();
                out.line("int " + locals.payload + " = 0;");
                out.line("for (" + Types.boxedElementType(f, javaPackage) + " " + locals.element + " : " + n + ") {");
                out.line("    " + locals.payload + " += " + sizeExpr(f, locals.element) + ";");
                out.line("}");
                out.line(size + " += " + tagSize + " + ProtoWire.sUVarint32(" + locals.payload + ") + "
                        + locals.payload + ";");
                out.outdent();
                out.line("}");
            } else if (f.kind() == Defs.Kind.MESSAGE) {
                out.line("for (" + Types.boxedElementType(f, javaPackage) + " " + locals.element + " : " + n + ") {");
                out.indent();
                out.line("int " + locals.nested + " = " + locals.element + ".protoSize();");
                out.line(size + " += " + tagSize + " + ProtoWire.sUVarint32(" + locals.nested + ") + "
                        + locals.nested + ";");
                out.outdent();
                out.line("}");
            } else {
                out.line("for (" + Types.boxedElementType(f, javaPackage) + " " + locals.element + " : " + n + ") {");
                out.line("    " + size + " += " + tagSize + " + " + sizeExpr(f, locals.element) + ";");
                out.line("}");
            }
            return;
        }

        if (f.kind() == Defs.Kind.MESSAGE) {
            out.line("if (" + n + " != null) {");
            out.indent();
            out.line("int " + locals.nested + " = " + n + ".protoSize();");
            out.line(size + " += " + tagSize + " + ProtoWire.sUVarint32(" + locals.nested + ") + "
                    + locals.nested + ";");
            out.outdent();
            out.line("}");
            return;
        }

        out.line("if (" + presenceCondition(f) + ") {");
        out.line("    " + size + " += " + tagSize + " + " + sizeExpr(f, n) + ";");
        out.line("}");
    }

    private void emitMapLoopHeader(Java out, Defs.FieldDef f, String n) {
        out.line("for (java.util.Map.Entry<" + Types.boxedElementType(f.mapKey(), javaPackage) + ", "
                + Types.boxedElementType(f.mapValue(), javaPackage) + "> " + locals.entry + " : "
                + n + ".entrySet()) {");
    }

    /**
     * Declares the entry size for the map entry in scope, and the value size first when the value is a
     * submessage. protoc always writes both key and value of a map entry, even at their defaults, so these
     * sizes are unconditional.
     */
    private void emitEntrySize(Java out, Defs.FieldDef f) {
        String keySize = Types.tagSize(Types.tag(f.mapKey())) + " + "
                + sizeExpr(f.mapKey(), locals.entry + ".getKey()");
        int valueTagSize = Types.tagSize(Types.tag(f.mapValue()));
        if (f.mapValue().kind() == Defs.Kind.MESSAGE) {
            out.line("int " + locals.valueSize + " = " + locals.entry + ".getValue().protoSize();");
            out.line("int " + locals.entrySize + " = " + keySize + " + " + valueTagSize
                    + " + ProtoWire.sUVarint32(" + locals.valueSize + ") + " + locals.valueSize + ";");
        } else {
            out.line("int " + locals.entrySize + " = " + keySize + " + " + valueTagSize + " + "
                    + sizeExpr(f.mapValue(), locals.entry + ".getValue()") + ";");
        }
    }

    /** The condition under which a singular field is written at all. */
    private String presenceCondition(Defs.FieldDef f) {
        String n = Names.fieldName(f.name());
        if (Types.nullable(f)) {
            return n + " != null";
        }
        return switch (f.kind()) {
            case SCALAR -> switch (f.scalar()) {
                case STRING -> "!" + n + ".isEmpty()";
                case BYTES -> n + ".length > 0";
                case BOOL -> n;
                // compare bit patterns, as protoc does, so -0.0 is written rather than mistaken for the
                // default: -0.0 == 0.0 is true, but their encodings differ
                case DOUBLE -> "Double.doubleToRawLongBits(" + n + ") != 0L";
                case FLOAT -> "Float.floatToRawIntBits(" + n + ") != 0";
                case INT64, UINT64, SINT64, FIXED64, SFIXED64 -> n + " != 0L";
                default -> n + " != 0";
            };
            case ENUM -> n + ".number() != 0";
            case TIMESTAMP, MESSAGE -> n + " != null";
            case MAP -> "!" + n + ".isEmpty()";
        };
    }

    private String sizeExpr(Defs.FieldDef f, String v) {
        return switch (f.kind()) {
            case SCALAR -> switch (f.scalar()) {
                case DOUBLE, FIXED64, SFIXED64 -> "8";
                case FLOAT, FIXED32, SFIXED32 -> "4";
                case INT32 -> "ProtoWire.sVarint32(" + v + ")";
                case UINT32 -> "ProtoWire.sUVarint32(" + v + ")";
                case INT64, UINT64 -> "ProtoWire.sVarint64(" + v + ")";
                case SINT32 -> "ProtoWire.sUVarint32(ProtoWire.zz32(" + v + "))";
                case SINT64 -> "ProtoWire.sVarint64(ProtoWire.zz64(" + v + "))";
                case BOOL -> "1";
                case STRING -> "ProtoWire.sString(" + v + ")";
                case BYTES -> "ProtoWire.sBytes(" + v + ")";
            };
            case ENUM -> "ProtoWire.sVarint32(" + v + ".number())";
            case TIMESTAMP -> "ProtoWire.sVarint64(" + v + ".toEpochMilli())";
            case MESSAGE, MAP -> throw new IllegalStateException("handled separately");
        };
    }

    // ------------------------------------------------------------- writing

    private void emitWriteTo(Java out, List<Defs.FieldDef> fields) {
        out.blank();
        if (emitJavadoc) {
            out.javadoc(("""
                    Writes this message into {@code %s} starting at {@code %s}.

                    <p>{@code %s} must have room for {@link #protoSize()} bytes.

                    @return the position just past the last byte written""")
                    .formatted(locals.target, locals.offset, locals.target));
        }
        out.line("public int writeTo(byte[] " + locals.target + ", int " + locals.offset + ") {");
        out.indent();
        for (Defs.FieldDef f : fields) {
            emitWriteFor(out, f);
        }
        if (unknownComponent() != null) {
            // appended verbatim, after the known fields; protobuf places no ordering requirement on tags
            String u = unknownComponent();
            out.line("if (" + u + ".length > 0) {");
            out.indent();
            out.line("System.arraycopy(" + u + ", 0, " + locals.target + ", " + locals.offset + ", "
                    + u + ".length);");
            out.line(locals.offset + " += " + u + ".length;");
            out.outdent();
            out.line("}");
        }
        out.line("return " + locals.offset + ";");
        out.outdent();
        out.line("}");
    }

    private void emitWriteFor(Java out, Defs.FieldDef f) {
        String n = Names.fieldName(f.name());

        if (f.kind() == Defs.Kind.MAP) {
            emitMapLoopHeader(out, f, n);
            out.indent();
            emitTagWrite(out, Types.writtenTag(f));
            emitEntrySize(out, f);
            out.line(locals.offset + " = ProtoWire.wUVarint32(" + locals.target + ", " + locals.offset
                    + ", " + locals.entrySize + ");");
            emitTagWrite(out, Types.tag(f.mapKey()));
            emitValueWrite(out, f.mapKey(), locals.entry + ".getKey()");
            emitTagWrite(out, Types.tag(f.mapValue()));
            if (f.mapValue().kind() == Defs.Kind.MESSAGE) {
                out.line(locals.offset + " = ProtoWire.wUVarint32(" + locals.target + ", " + locals.offset
                        + ", " + locals.valueSize + ");");
                out.line(locals.offset + " = " + locals.entry + ".getValue().writeTo(" + locals.target
                        + ", " + locals.offset + ");");
            } else {
                emitValueWrite(out, f.mapValue(), locals.entry + ".getValue()");
            }
            out.outdent();
            out.line("}");
            return;
        }

        if (f.repeated()) {
            if (Feature.isPacked(f)) {
                out.line("if (!" + n + ".isEmpty()) {");
                out.indent();
                emitTagWrite(out, Types.writtenTag(f));
                out.line("int " + locals.payload + " = 0;");
                out.line("for (" + Types.boxedElementType(f, javaPackage) + " " + locals.element + " : " + n + ") {");
                out.line("    " + locals.payload + " += " + sizeExpr(f, locals.element) + ";");
                out.line("}");
                out.line(locals.offset + " = ProtoWire.wUVarint32(" + locals.target + ", " + locals.offset
                        + ", " + locals.payload + ");");
                out.line("for (" + Types.boxedElementType(f, javaPackage) + " " + locals.element + " : " + n + ") {");
                out.indent();
                emitValueWrite(out, f, locals.element);
                out.outdent();
                out.line("}");
                out.outdent();
                out.line("}");
            } else if (f.kind() == Defs.Kind.MESSAGE) {
                out.line("for (" + Types.boxedElementType(f, javaPackage) + " " + locals.element + " : " + n + ") {");
                out.indent();
                emitTagWrite(out, Types.writtenTag(f));
                out.line(locals.offset + " = ProtoWire.wUVarint32(" + locals.target + ", " + locals.offset
                        + ", " + locals.element + ".protoSize());");
                out.line(locals.offset + " = " + locals.element + ".writeTo(" + locals.target + ", "
                        + locals.offset + ");");
                out.outdent();
                out.line("}");
            } else {
                out.line("for (" + Types.boxedElementType(f, javaPackage) + " " + locals.element + " : " + n + ") {");
                out.indent();
                emitTagWrite(out, Types.writtenTag(f));
                emitValueWrite(out, f, locals.element);
                out.outdent();
                out.line("}");
            }
            return;
        }

        if (f.kind() == Defs.Kind.MESSAGE) {
            out.line("if (" + n + " != null) {");
            out.indent();
            emitTagWrite(out, Types.writtenTag(f));
            out.line(locals.offset + " = ProtoWire.wUVarint32(" + locals.target + ", " + locals.offset
                    + ", " + n + ".protoSize());");
            out.line(locals.offset + " = " + n + ".writeTo(" + locals.target + ", " + locals.offset + ");");
            out.outdent();
            out.line("}");
            return;
        }

        out.line("if (" + presenceCondition(f) + ") {");
        out.indent();
        emitTagWrite(out, Types.writtenTag(f));
        emitValueWrite(out, f, n);
        out.outdent();
        out.line("}");
    }

    /** Tags are compile-time constants, so a single-byte tag becomes a direct store. */
    private void emitTagWrite(Java out, int tag) {
        if (tag < 128) {
            out.line(locals.target + "[" + locals.offset + "++] = (byte) " + tag + ";");
        } else {
            out.line(locals.offset + " = ProtoWire.wUVarint32(" + locals.target + ", " + locals.offset
                    + ", " + tag + ");");
        }
    }

    private void emitValueWrite(Java out, Defs.FieldDef f, String v) {
        String t = locals.target;
        String o = locals.offset;
        switch (f.kind()) {
            case SCALAR -> {
                switch (f.scalar()) {
                    case DOUBLE -> out.line(o + " = ProtoWire.wFixed64(" + t + ", " + o
                            + ", Double.doubleToRawLongBits(" + v + "));");
                    case FLOAT -> out.line(o + " = ProtoWire.wFixed32(" + t + ", " + o
                            + ", Float.floatToRawIntBits(" + v + "));");
                    case INT32 -> out.line(o + " = ProtoWire.wVarint32(" + t + ", " + o + ", " + v + ");");
                    case UINT32 -> out.line(o + " = ProtoWire.wUVarint32(" + t + ", " + o + ", " + v + ");");
                    case INT64, UINT64 -> out.line(o + " = ProtoWire.wVarint64(" + t + ", " + o + ", " + v + ");");
                    case SINT32 -> out.line(o + " = ProtoWire.wUVarint32(" + t + ", " + o
                            + ", ProtoWire.zz32(" + v + "));");
                    case SINT64 -> out.line(o + " = ProtoWire.wVarint64(" + t + ", " + o
                            + ", ProtoWire.zz64(" + v + "));");
                    case FIXED32, SFIXED32 -> out.line(o + " = ProtoWire.wFixed32(" + t + ", " + o + ", " + v + ");");
                    case FIXED64, SFIXED64 -> out.line(o + " = ProtoWire.wFixed64(" + t + ", " + o + ", " + v + ");");
                    case BOOL -> out.line(t + "[" + o + "++] = (byte) (" + v + " ? 1 : 0);");
                    case STRING -> out.line(o + " = ProtoWire.wString(" + t + ", " + o + ", " + v + ");");
                    case BYTES -> out.line(o + " = ProtoWire.wBytes(" + t + ", " + o + ", " + v + ");");
                }
            }
            case ENUM -> out.line(o + " = ProtoWire.wVarint32(" + t + ", " + o + ", " + v + ".number());");
            case TIMESTAMP -> out.line(o + " = ProtoWire.wVarint64(" + t + ", " + o + ", "
                    + v + ".toEpochMilli());");
            case MESSAGE, MAP -> throw new IllegalStateException("handled separately");
        }
    }

    private void emitToByteArray(Java out) {
        out.blank();
        if (emitJavadoc) {
            out.javadoc("@return this message in its protobuf encoding");
        }
        out.line("public byte[] toByteArray() {");
        out.indent();
        out.line("byte[] " + locals.out + " = new byte[protoSize()];");
        out.line("writeTo(" + locals.out + ", 0);");
        out.line("return " + locals.out + ";");
        out.outdent();
        out.line("}");
    }

    /** {@code byte[]} components need explicit value semantics; the record defaults compare by identity. */
    private void emitValueSemantics(Java out, Defs.MessageDef message, List<Defs.FieldDef> fields) {
        List<Defs.FieldDef> byteFields = fields.stream()
                .filter(f -> isBytes(f) && !f.repeated())
                .toList();
        if (byteFields.isEmpty() && unknownComponent() == null) {
            return;
        }

        // every component compared by value, in declaration order, with the unknown bytes last
        List<String> names = new ArrayList<>();
        List<Boolean> isArray = new ArrayList<>();
        for (Defs.FieldDef f : fields) {
            names.add(Names.fieldName(f.name()));
            isArray.add(isBytes(f) && !f.repeated());
        }
        if (unknownComponent() != null) {
            names.add(unknownComponent());
            isArray.add(true);
        }

        for (Defs.FieldDef f : byteFields) {
            String n = Names.fieldName(f.name());
            out.blank();
            if (emitJavadoc) {
                out.javadoc("@return a copy, so the message stays immutable");
            }
            out.line("public byte[] " + n + "() {");
            if (Types.nullable(f)) {
                out.line("    return " + n + " == null ? null : " + n + ".clone();");
            } else {
                out.line("    return " + n + ".clone();");
            }
            out.line("}");
        }
        if (unknownComponent() != null) {
            String u = unknownComponent();
            out.blank();
            if (emitJavadoc) {
                out.javadoc("""
                        The encoded bytes of any field this build does not know, kept so a message written
                        against a newer schema survives a round trip unchanged.

                        @return a copy, so the message stays immutable""");
            }
            out.line("public byte[] " + u + "() {");
            out.line("    return " + u + ".clone();");
            out.line("}");
        }

        out.blank();
        out.line("@Override");
        out.line("public boolean equals(Object " + locals.candidate + ") {");
        out.indent();
        out.line("if (this == " + locals.candidate + ") {");
        out.line("    return true;");
        out.line("}");
        out.line("if (!(" + locals.candidate + " instanceof " + message.name() + " " + locals.other + ")) {");
        out.line("    return false;");
        out.line("}");
        List<String> comparisons = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            comparisons.add(isArray.get(i)
                    ? "java.util.Arrays.equals(this." + n + ", " + locals.other + "." + n + ")"
                    : "java.util.Objects.equals(this." + n + ", " + locals.other + "." + n + ")");
        }
        out.line("return " + String.join("\n        && ", comparisons) + ";");
        out.outdent();
        out.line("}");

        out.blank();
        out.line("@Override");
        out.line("public int hashCode() {");
        out.indent();
        out.line("int " + locals.result + " = 1;");
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            out.line(locals.result + " = 31 * " + locals.result + " + " + (isArray.get(i)
                    ? "java.util.Arrays.hashCode(" + n + ")"
                    : "java.util.Objects.hashCode(" + n + ")") + ";");
        }
        out.line("return " + locals.result + ";");
        out.outdent();
        out.line("}");

        out.blank();
        out.line("@Override");
        out.line("public String toString() {");
        out.indent();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            parts.add("\"" + n + "=\" + " + (isArray.get(i)
                    ? "java.util.Arrays.toString(" + n + ")" : n));
        }
        out.line("return \"" + message.name() + "[\" + " + String.join("\n        + \", \" + ", parts)
                + " + \"]\";");
        out.outdent();
        out.line("}");
    }
}
