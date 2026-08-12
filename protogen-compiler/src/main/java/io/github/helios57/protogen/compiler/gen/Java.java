package io.github.helios57.protogen.compiler.gen;

/** A tiny indentation-aware source writer. Keeps the emitters free of manual whitespace bookkeeping. */
final class Java {

    private static final String INDENT = "    ";

    private final StringBuilder sb = new StringBuilder(4096);
    private int depth;

    void indent() {
        depth++;
    }

    void outdent() {
        depth--;
    }

    void blank() {
        sb.append('\n');
    }

    /** Appends one line at the current depth. Embedded newlines are indented too. */
    void line(String text) {
        if (text.isEmpty()) {
            sb.append('\n');
            return;
        }
        // by far the common case, and splitting every line was the emitter's largest single cost
        if (text.indexOf('\n') < 0) {
            appendIndent();
            sb.append(text).append('\n');
            return;
        }
        for (String part : text.split("\n", -1)) {
            appendIndent();
            sb.append(part).append('\n');
        }
    }

    private void appendIndent() {
        for (int i = 0; i < depth; i++) {
            sb.append(INDENT);
        }
    }

    /** Appends a Javadoc block, wrapping each line in the usual decoration. */
    void javadoc(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String[] lines = text.split("\n", -1);
        if (lines.length == 1) {
            line("/** " + lines[0].strip() + " */");
            return;
        }
        line("/**");
        for (String l : lines) {
            String stripped = l.strip();
            line(stripped.isEmpty() ? " *" : " * " + stripped);
        }
        line(" */");
    }

    /**
     * Makes schema text safe to drop into a {@code //} comment.
     *
     * @param text the text as the schema wrote it
     * @return the text with anything that would end the comment, or the line, neutralised
     */
    static String comment(String text) {
        return text.replace("*/", "* /").replace('\n', ' ').replace('\r', ' ');
    }

    /** Escapes a value for use inside a Java string literal. */
    static String literal(String value) {
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
        return sb.toString();
    }
}
