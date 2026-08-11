package io.github.helios57.protogen.it;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader for the tests.
 * <p>
 * protogen-it must acquire no dependencies, not even in test scope, or the zero-dependency proof stops
 * being airtight. Sixty lines of recursive descent is the price of checking that the generated metadata is
 * actually well-formed JSON rather than merely containing the right substrings - a distinction that
 * matters, because a missing comma passes every substring assertion ever written.
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    /**
     * Parses a JSON document.
     *
     * @return a {@link Map}, {@link List}, {@link String}, {@link Double}, {@link Boolean} or {@code null}
     * @throws IllegalArgumentException if the input is not well-formed JSON
     */
    public static Object parse(String text) {
        Json json = new Json(text);
        Object value = json.value();
        json.skipWhitespace();
        if (json.pos != text.length()) {
            throw new IllegalArgumentException("trailing content at offset " + json.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("expected an object but found " + value);
        }
        return (Map<String, Object>) value;
    }

    private Object value() {
        skipWhitespace();
        if (pos >= src.length()) {
            throw new IllegalArgumentException("unexpected end of input");
        }
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipWhitespace();
            String key = string();
            skipWhitespace();
            expect(':');
            out.put(key, value());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or '}' at offset " + (pos - 1)
                        + " but found '" + c + "'");
            }
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            out.add(value());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or ']' at offset " + (pos - 1)
                        + " but found '" + c + "'");
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char escape = next();
                switch (escape) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> sb.append(escape);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Double number() {
        int start = pos;
        while (pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        if (start == pos) {
            throw new IllegalArgumentException("unexpected character '" + src.charAt(pos)
                    + "' at offset " + pos);
        }
        return Double.valueOf(src.substring(start, pos));
    }

    private Object literal(String text, Object value) {
        if (!src.startsWith(text, pos)) {
            throw new IllegalArgumentException("unexpected literal at offset " + pos);
        }
        pos += text.length();
        return value;
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private char next() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("unexpected end of input");
        }
        return src.charAt(pos++);
    }

    private void expect(char c) {
        skipWhitespace();
        char found = next();
        if (found != c) {
            throw new IllegalArgumentException("expected '" + c + "' at offset " + (pos - 1)
                    + " but found '" + found + "'");
        }
    }
}
