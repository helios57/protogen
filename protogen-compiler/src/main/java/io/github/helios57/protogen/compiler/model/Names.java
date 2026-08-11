package io.github.helios57.protogen.compiler.model;

import java.util.Locale;
import java.util.Set;

/** Name mangling shared by the parser, the linker and the emitters. */
public final class Names {

    /** Java keywords and literals that cannot be used as identifiers. */
    private static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "record", "var", "yield");

    private Names() {
    }

    /** {@code broker_monitoring} / {@code brokerMonitoring} -&gt; {@code BrokerMonitoring} */
    public static String toUpperCamel(String name) {
        String camel = toLowerCamel(name);
        return camel.isEmpty() ? camel : Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
    }

    /** {@code operational_day} -&gt; {@code operationalDay}; already-camel names are left alone. */
    public static String toLowerCamel(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        boolean upperNext = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_' || c == '-' || c == '.') {
                upperNext = true;
            } else if (upperNext) {
                sb.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                sb.append(sb.isEmpty() ? Character.toLowerCase(c) : c);
            }
        }
        return sb.toString();
    }

    /** Appends an underscore to names that clash with a Java keyword, as protoc does. */
    public static String escape(String identifier) {
        return RESERVED.contains(identifier.toLowerCase(Locale.ROOT)) && RESERVED.contains(identifier)
                ? identifier + "_"
                : identifier;
    }

    /** The Java name of a record component for a proto field. */
    public static String fieldName(String protoName) {
        return escape(toLowerCamel(protoName));
    }
}
