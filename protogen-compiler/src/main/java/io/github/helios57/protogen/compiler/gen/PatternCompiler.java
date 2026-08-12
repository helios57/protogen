package io.github.helios57.protogen.compiler.gen;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an {@code @Pattern} regex into a scan over the characters of the string, when it can.
 * <p>
 * A constraint like {@code ^[a-zA-Z_:][a-zA-Z0-9_:]*$} is a loop over characters written in another
 * notation. Handing it to {@code java.util.regex} means allocating a {@code Matcher} - and its group
 * arrays - for every message constructed or parsed, then walking a compiled automaton; the same check
 * written out is a few comparisons per character and allocates nothing. On a batch of a hundred KPIs whose
 * key carries exactly that pattern, the regex was half the cost of decoding.
 * <p>
 * Only a subset is compiled, and only when the result provably matches what the regex would:
 * <ul>
 *   <li>the pattern must be anchored at both ends, so there is no search to do;</li>
 *   <li>every element must be a literal character, a character class, {@code .}, or one of
 *       {@code \d \w \s \D \W \S}, optionally quantified with {@code ? * +} or {@code {n,m\}};</li>
 *   <li>a quantifier that can consume a variable number of characters must not be able to eat a character
 *       the rest of the pattern needs - otherwise matching would require backtracking, and a greedy scan
 *       would give a different answer.</li>
 * </ul>
 * Anything else - alternation, groups, lookaround, reluctant or possessive quantifiers, backreferences -
 * is left to {@code java.util.regex}, which stays correct for every pattern this cannot take.
 */
final class PatternCompiler {

    private PatternCompiler() {
    }

    /**
     * The largest code point, so a complement can be expressed as ranges.
     * <p>
     * Code points, not {@code char}s: {@code java.util.regex} treats a surrogate pair as one character, so
     * {@code ^.$} matches an emoji. A scan over {@code char}s would call that two characters and disagree.
     */
    private static final int MAX_CODE_POINT = Character.MAX_CODE_POINT;

    /**
     * A set of characters, as disjoint ranges in ascending order.
     *
     * @param ranges    the ranges the set contains, each {@code {low, high\}} inclusive, disjoint and sorted
     * @param written   the ranges the generated test names, which for a negated class are the ones it excludes
     * @param negated   whether the test is the negation of {@link #written}
     * @param source    how the set was written in the pattern, for the generated comment
     */
    record CharSet(List<int[]> ranges, List<int[]> written, boolean negated, String source) {

        /**
         * The test for membership.
         *
         * @param variable the name of the {@code char} variable to test
         * @return a Java expression, true exactly for the characters in this set
         */
        String condition(String variable) {
            String test = PatternCompiler.condition(written, variable);
            return negated ? "!(" + test + ")" : test;
        }

        boolean intersects(CharSet other) {
            for (int[] mine : ranges) {
                for (int[] theirs : other.ranges) {
                    if (mine[0] <= theirs[1] && theirs[0] <= mine[1]) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * One element of the pattern: a set of characters and how many of them.
     *
     * @param chars the characters this element accepts
     * @param min   the smallest number accepted
     * @param max   the largest number accepted, {@link Integer#MAX_VALUE} when unbounded
     */
    record Term(CharSet chars, int min, int max) {

        /** Whether this term can consume a variable number of characters, which is what needs analysing. */
        boolean variable() {
            return max > min;
        }
    }

    /**
     * Compiles a regex into the terms a generated scan walks.
     *
     * @param regex the pattern as written in the schema
     * @return the terms, or {@code null} when the pattern is outside the subset and must stay a regex
     */
    static List<Term> compile(String regex) {
        if (regex == null || !regex.startsWith("^") || !regex.endsWith("$") || regex.length() < 3) {
            return null;
        }
        // a trailing '\$' is a literal dollar, not the anchor
        if (regex.charAt(regex.length() - 2) == '\\' && !endsWithEscapedBackslash(regex)) {
            return null;
        }
        List<Term> terms = new Parser(regex.substring(1, regex.length() - 1)).parse();
        if (terms == null || terms.isEmpty() || !unambiguous(terms)) {
            return null;
        }
        return terms;
    }

    private static boolean endsWithEscapedBackslash(String regex) {
        int backslashes = 0;
        for (int i = regex.length() - 2; i >= 0 && regex.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 0;
    }

    /**
     * Whether a greedy left-to-right scan decides this pattern the same way a backtracking engine would.
     * <p>
     * It does unless some variable-length term can consume a character that the rest of the pattern
     * needs: {@code ^[a-z]*a$} against {@code "aaa"} matches, but a greedy scan would eat all three and
     * then find nothing left for the {@code a}.
     *
     * @param terms the parsed terms
     * @return whether the scan is safe to generate
     */
    private static boolean unambiguous(List<Term> terms) {
        for (int i = 0; i < terms.size(); i++) {
            Term term = terms.get(i);
            if (!term.variable()) {
                continue;
            }
            for (int j = i + 1; j < terms.size(); j++) {
                Term next = terms.get(j);
                if (next.min() == 0) {
                    // an optional term can always match nothing, so being eaten into costs it nothing
                    continue;
                }
                if (term.chars().intersects(next.chars())) {
                    return false;
                }
                // the first term that must match something stops the greedy one from reaching any further:
                // a set disjoint from it is exactly where the greedy scan gives up anyway
                break;
            }
        }
        return true;
    }

    // ------------------------------------------------------------- parsing

    /** A recursive-descent-free scan over the regex source; the supported subset needs no recursion. */
    private static final class Parser {

        private final String source;
        private int pos;

        Parser(String source) {
            this.source = source;
        }

        /** @return the terms, or {@code null} at the first construct outside the subset */
        List<Term> parse() {
            List<Term> terms = new ArrayList<>();
            while (pos < source.length()) {
                CharSet atom = atom();
                if (atom == null) {
                    return null;
                }
                int[] bounds = quantifier();
                if (bounds == null) {
                    return null;
                }
                terms.add(new Term(atom, bounds[0], bounds[1]));
            }
            return terms;
        }

        private CharSet atom() {
            char c = source.charAt(pos);
            if (c == '[') {
                return charClass();
            }
            if (c == '.') {
                pos++;
                // Java's '.' matches everything except the line terminators, DOTALL being off
                return complement(List.of(new int[]{'\n', '\n'}, new int[]{'\r', '\r'},
                                new int[]{0x85, 0x85}, new int[]{0x2028, 0x2029}),
                        ".");
            }
            if (c == '\\') {
                return escape();
            }
            if ("|()*+?{}^$".indexOf(c) >= 0) {
                return null;
            }
            pos++;
            return literal(c);
        }

        private CharSet escape() {
            if (pos + 1 >= source.length()) {
                return null;
            }
            char c = source.charAt(pos + 1);
            pos += 2;
            return switch (c) {
                case 'd' -> digits();
                case 'D' -> complement(digits().ranges(), "\\D");
                case 'w' -> word();
                case 'W' -> complement(word().ranges(), "\\W");
                case 's' -> whitespace();
                case 'S' -> complement(whitespace().ranges(), "\\S");
                case 't' -> literal('\t');
                case 'n' -> literal('\n');
                case 'r' -> literal('\r');
                case 'u' -> unicodeEscape();
                case '\\', '.', '[', ']', '(', ')', '{', '}', '*', '+', '?', '|', '^', '$', '-', '/' ->
                        literal(c);
                // anything else is a construct this does not model: \b, \p{...}, \1, ...
                default -> null;
            };
        }

        /** {@code \\uXXXX}: four hex digits, already consumed as far as the {@code u}. */
        private CharSet unicodeEscape() {
            if (pos + 4 > source.length()) {
                return null;
            }
            try {
                char c = (char) Integer.parseInt(source.substring(pos, pos + 4), 16);
                pos += 4;
                return literal(c);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** Parses {@code [...]}, with ranges, escapes and an optional leading negation. */
        private CharSet charClass() {
            int start = pos;
            pos++;
            boolean negated = pos < source.length() && source.charAt(pos) == '^';
            if (negated) {
                pos++;
            }
            List<int[]> ranges = new ArrayList<>();
            boolean closed = false;
            while (pos < source.length()) {
                char c = source.charAt(pos);
                if (c == ']' && !ranges.isEmpty()) {
                    pos++;
                    closed = true;
                    break;
                }
                CharSet member = c == '\\' ? escape() : single();
                if (member == null) {
                    return null;
                }
                int[] range = member.ranges().get(0);
                if (member.ranges().size() != 1 || range[0] != range[1]) {
                    // a shorthand such as \d or \w inside the class contributes all of its ranges
                    ranges.addAll(member.ranges());
                    continue;
                }
                if (pos + 1 < source.length() && source.charAt(pos) == '-' && source.charAt(pos + 1) != ']') {
                    pos++;
                    CharSet upper = source.charAt(pos) == '\\' ? escape() : single();
                    if (upper == null || upper.ranges().size() != 1
                            || upper.ranges().get(0)[0] != upper.ranges().get(0)[1]) {
                        return null;
                    }
                    ranges.add(new int[]{range[0], upper.ranges().get(0)[1]});
                    continue;
                }
                ranges.add(range);
            }
            if (!closed || ranges.isEmpty()) {
                return null;
            }
            String written = source.substring(start, pos);
            List<int[]> merged = merge(ranges);
            return negated ? complement(merged, written) : set(merged, written);
        }

        private CharSet single() {
            char c = source.charAt(pos);
            pos++;
            return literal(c);
        }

        /** @return {@code {min, max\}}, or {@code null} for a quantifier outside the subset */
        private int[] quantifier() {
            if (pos >= source.length()) {
                return new int[]{1, 1};
            }
            char c = source.charAt(pos);
            int[] bounds = switch (c) {
                case '*' -> new int[]{0, Integer.MAX_VALUE};
                case '+' -> new int[]{1, Integer.MAX_VALUE};
                case '?' -> new int[]{0, 1};
                case '{' -> repetition();
                default -> new int[]{1, 1};
            };
            if (bounds == null) {
                return null;
            }
            if (c == '*' || c == '+' || c == '?') {
                pos++;
            }
            // reluctant and possessive quantifiers mean something a plain greedy scan does not
            if (pos < source.length() && (source.charAt(pos) == '?' || source.charAt(pos) == '+')
                    && (c == '*' || c == '+' || c == '?' || c == '{')) {
                return null;
            }
            return bounds;
        }

        private int[] repetition() {
            int close = source.indexOf('}', pos);
            if (close < 0) {
                return null;
            }
            String body = source.substring(pos + 1, close);
            try {
                int comma = body.indexOf(',');
                int min;
                int max;
                if (comma < 0) {
                    min = Integer.parseInt(body.strip());
                    max = min;
                } else {
                    min = Integer.parseInt(body.substring(0, comma).strip());
                    String upper = body.substring(comma + 1).strip();
                    max = upper.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(upper);
                }
                if (min < 0 || max < min) {
                    return null;
                }
                pos = close + 1;
                return new int[]{min, max};
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    // ---------------------------------------------------------- char sets

    private static CharSet literal(char c) {
        return set(List.of(new int[]{c, c}), describe(c));
    }

    private static CharSet digits() {
        return set(List.of(new int[]{'0', '9'}), "\\d");
    }

    private static CharSet word() {
        return set(merge(List.of(new int[]{'a', 'z'}, new int[]{'A', 'Z'},
                new int[]{'0', '9'}, new int[]{'_', '_'})), "\\w");
    }

    private static CharSet whitespace() {
        return set(merge(List.of(new int[]{' ', ' '}, new int[]{'\t', '\t'}, new int[]{'\n', '\n'},
                new int[]{0x0B, 0x0C}, new int[]{'\r', '\r'})), "\\s");
    }

    private static CharSet set(List<int[]> ranges, String source) {
        List<int[]> merged = merge(ranges);
        return new CharSet(merged, merged, false, source);
    }

    private static CharSet complement(List<int[]> ranges, String source) {
        List<int[]> merged = merge(ranges);
        List<int[]> inverted = new ArrayList<>();
        int next = 0;
        for (int[] range : merged) {
            if (range[0] > next) {
                inverted.add(new int[]{next, range[0] - 1});
            }
            next = Math.max(next, range[1] + 1);
        }
        if (next <= MAX_CODE_POINT) {
            inverted.add(new int[]{next, MAX_CODE_POINT});
        }
        // the test reads better as "not one of these" than as the complement's ranges
        return new CharSet(inverted, merged, true, source);
    }

    private static List<int[]> merge(List<int[]> ranges) {
        List<int[]> sorted = new ArrayList<>(ranges);
        sorted.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] range : sorted) {
            if (!merged.isEmpty() && range[0] <= merged.get(merged.size() - 1)[1] + 1) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], range[1]);
                continue;
            }
            merged.add(new int[]{range[0], range[1]});
        }
        return merged;
    }

    private static String condition(List<int[]> ranges, String variable) {
        List<String> parts = new ArrayList<>();
        for (int[] range : ranges) {
            if (range[0] == range[1]) {
                parts.add(variable + " == " + charLiteral(range[0]));
            } else {
                parts.add("(" + variable + " >= " + charLiteral(range[0])
                        + " && " + variable + " <= " + charLiteral(range[1]) + ")");
            }
        }
        return String.join(" || ", parts);
    }

    private static String charLiteral(int c) {
        return switch (c) {
            case '\'' -> "'\\''";
            case '\\' -> "'\\\\'";
            case '\n' -> "'\\n'";
            case '\r' -> "'\\r'";
            case '\t' -> "'\\t'";
            // above the BMP there is no char literal to write, and the scan compares code points anyway
            default -> c > 0xFFFF ? String.format("0x%X", c)
                    : c >= 0x20 && c < 0x7f ? "'" + (char) c + "'" : String.format("'\\u%04x'", c);
        };
    }

    private static String describe(char c) {
        return c >= 0x20 && c < 0x7f ? String.valueOf(c) : String.format("\\u%04x", (int) c);
    }
}
