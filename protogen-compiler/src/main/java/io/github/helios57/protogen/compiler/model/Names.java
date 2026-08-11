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

    /**
     * Converts a schema name to upper camel case: {@code broker_monitoring} and
     * {@code brokerMonitoring} both become {@code BrokerMonitoring}.
     *
     * @param name the name as written in the schema
     * @return the upper camel case form
     */
    public static String toUpperCamel(String name) {
        String camel = toLowerCamel(name);
        return camel.isEmpty() ? camel : Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
    }

    /**
     * Converts a schema name to lower camel case: {@code operational_day} becomes
     * {@code operationalDay}. Already-camel names are left alone.
     *
     * @param name the name as written in the schema
     * @return the lower camel case form
     */
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

    /**
     * Appends an underscore to names that clash with a Java keyword, as protoc does.
     *
     * @param identifier a candidate Java identifier
     * @return the identifier, made safe to use
     */
    public static String escape(String identifier) {
        return RESERVED.contains(identifier.toLowerCase(Locale.ROOT)) && RESERVED.contains(identifier)
                ? identifier + "_"
                : identifier;
    }

    /**
     * The Java name of the record component generated for a proto field.
     *
     * @param protoName the field name as written in the schema
     * @return the component name, camel cased and keyword-safe
     */
    public static String fieldName(String protoName) {
        return escape(toLowerCamel(protoName));
    }
}
