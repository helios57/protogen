package io.github.helios57.protogen.compiler.gen;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generated scan has to answer exactly what {@code java.util.regex} would, for every input.
 * <p>
 * It replaces the regex engine on the constructor of every message with an {@code @Pattern} constraint, so
 * a disagreement is not a slow path - it is a message wrongly accepted or wrongly rejected. Each pattern
 * here is generated, compiled and run against a corpus built from its own alphabet, and the answer is
 * compared with {@link Pattern#matches}. Patterns outside the compiled subset are in the list too: they
 * fall back to the regex, and must still be right.
 */
class PatternScanDifferentialTest {

    /** Patterns the scan is expected to take, including every construct the subset supports. */
    private static final List<String> COMPILED = List.of(
            "^[a-zA-Z_:][a-zA-Z0-9_:]*$",      // the OpenMetrics metric name, and the reason this exists
            "^[A-Z]{2}\\d+$",
            "^[a-z]+$",
            "^[a-z]*$",
            "^[a-z]?$",
            "^a$",
            "^ab$",
            "^\\d{3}$",
            "^\\d{2,4}$",
            "^\\d{2,}$",
            "^[0-9]+-[0-9]+$",
            "^\\w+$",
            "^\\W$",
            "^\\s$",
            "^\\S+$",
            "^[^0-9]+$",
            "^[a-z][0-9][A-Z]$",
            "^.$",
            "^.{1,3}$",
            "^[-.]+$",
            "^[a-z]+\\.[a-z]+$",
            "^v[0-9]+$",
            "^[\\t]+$",
            "^[a-fA-F0-9]{8}$",
            "^x{0}$",
            "^[a-z]{0,3}$",
            "^[a-z]*/[a-z]+$",              // contains */, which would close the generated comment
            "^[a-]+$",                      // a dash last in a class is a literal dash
            "^[.]$",
            "^\\.$",
            "^-a$",
            "^a{2}b{3}$",
            "^[A-Z][a-z]*$",
            "^[\\u00e0-\\u00ff]+$",
            "^.*$",
            "^\\d+\\.\\d+\\.\\d+$",
            "^[^\\s]+$",
            "^[0-9a-f]{2}(?:)$".replace("(?:)", ""),
            "^\\w{1,64}$",
            "^[a-z]+\\$$",            // a literal dollar before the anchor, which is not the same as \\$
            "^\\w?\\d*$",              // overlapping, but nothing after the star has to match
            "^[a-z]*[a-z]?$",
            "^[a-z]*[a-c]{0,2}$",
            "^[a-z]*[0-9]?[.]$");            // a literal dollar before the anchor, which is not the same as \\$

    /** Patterns outside the subset, which must keep working through {@code java.util.regex}. */
    private static final List<String> FELL_BACK = List.of(
            "^(a|b)+$",
            "^[a-z]+@[a-z]+\\.(com|org)$",
            "^(?=.*[0-9])[a-zA-Z0-9]+$",
            "^[a-z]*a$",
            "^[a-z]*[a-z0-9]+$",
            "^a.*?b$",
            "[a-z]+",
            "^\\p{Alpha}+$",
            "^(abc)$",
            "^abc\\$",                      // the trailing dollar is escaped, so nothing is anchored at the end
            "^\\bword\\b$",
            "^a++$",
            "^.*a.*$",
            "^$");

    private static List<String> patterns() {
        List<String> all = new ArrayList<>(COMPILED);
        all.addAll(FELL_BACK);
        return all;
    }

    private static GeneratedCode generated;

    @BeforeAll
    static void generate() {
        StringBuilder proto = new StringBuilder("""
                syntax = "proto3";
                package p;
                option java_package = "p";
                option java_multiple_files = true;
                """);
        List<String> all = patterns();
        for (int i = 0; i < all.size(); i++) {
            proto.append("message M").append(i).append(" {\n")
                    .append("  // @Pattern ").append(all.get(i)).append('\n')
                    .append("  string s = 1;\n}\n");
        }
        generated = GeneratedCode.of(proto.toString());
    }

    @AfterAll
    static void cleanUp() throws Exception {
        generated.close();
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> everyPattern() {
        List<String> all = patterns();
        return java.util.stream.IntStream.range(0, all.size())
                .mapToObj(i -> org.junit.jupiter.params.provider.Arguments.of(i, all.get(i)));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("everyPattern")
    void theGeneratedCheckAgreesWithTheRegex(int index, String pattern) throws Exception {
        Constructor<?> constructor = generated.load("p.M" + index).getConstructor(String.class);
        Pattern reference = Pattern.compile(pattern);

        List<String> corpus = corpusFor(pattern);
        assertThat(corpus).hasSizeGreaterThan(300);
        for (String candidate : corpus) {
            boolean expected = reference.matcher(candidate).matches();
            assertThat(accepts(constructor, candidate))
                    .as("%s against %s", pattern, render(candidate))
                    .isEqualTo(expected);
        }
    }

    private static boolean accepts(Constructor<?> constructor, String candidate) throws Exception {
        try {
            constructor.newInstance(candidate);
            return true;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IllegalArgumentException) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Strings worth trying against one pattern: the awkward ones, plus random strings over an alphabet
     * taken from the pattern itself so that near-misses actually occur.
     */
    private static List<String> corpusFor(String pattern) {
        Set<String> corpus = new LinkedHashSet<>(List.of(
                "", " ", "a", "A", "0", "9", "_", ":", "-", ".", "\t", "\n", "\r", "/", "@",
                "aa", "a0", "0a", "AB12", "ab.cd", "12-34", "v1", "\u00e9", "\u65e5\u672c",
                "\ud83d\ude00", "a\ud83d\ude00b", "a\nb", "\n\n", "abcdefghijklmnopqrstuvwxyz",
                "A".repeat(64), "0".repeat(64), " a", "a ", "AB1", "ZZ0", "x", "xx"));

        char[] alphabet = alphabetFor(pattern);
        // a fixed seed: a failure has to be reproducible from the test name alone
        Random random = new Random(pattern.hashCode());
        for (int length = 0; length <= 6; length++) {
            for (int i = 0; i < 60; i++) {
                StringBuilder sb = new StringBuilder(length);
                for (int c = 0; c < length; c++) {
                    sb.append(alphabet[random.nextInt(alphabet.length)]);
                }
                corpus.add(sb.toString());
            }
        }
        return List.copyOf(corpus);
    }

    /** The characters the pattern itself mentions, plus a few it does not, so both answers show up. */
    private static char[] alphabetFor(String pattern) {
        Set<Character> alphabet = new LinkedHashSet<>(List.of('a', 'z', 'A', 'Z', '0', '9', '_', ':',
                '-', '.', ' ', '\t', '\n', '\u00e9', 'x', 'b', '@'));
        for (char c : pattern.toCharArray()) {
            if (c != '^' && c != '$' && c != '\\' && c != '[' && c != ']' && c != '{' && c != '}') {
                alphabet.add(c);
            }
        }
        char[] out = new char[alphabet.size()];
        int i = 0;
        for (char c : alphabet) {
            out[i++] = c;
        }
        return out;
    }

    private static String render(String candidate) {
        return "\"" + candidate.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    @Test
    void theExpectedPatternsAreCompiledAndTheRestAreNot() {
        // the split above is the point of the whole class; if a pattern silently changed sides the
        // differential check would still pass while the optimization quietly stopped applying
        for (String pattern : COMPILED) {
            assertThat(PatternCompiler.compile(pattern)).as("should compile: %s", pattern).isNotNull();
        }
        for (String pattern : FELL_BACK) {
            assertThat(PatternCompiler.compile(pattern)).as("should fall back: %s", pattern).isNull();
        }
    }

    @Test
    void aMalformedPatternFailsTheBuildRatherThanTheFirstClassLoad() {
        // it would otherwise be copied verbatim into the generated source and throw
        // PatternSyntaxException at class initialisation, in whatever service touches the message first
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> source("^[a-z]{2,1}$"))
                .isInstanceOf(io.github.helios57.protogen.compiler.ProtoCompileException.class)
                .hasMessageContaining("is not a valid regular expression")
                .hasMessageContaining("^[a-z]{2,1}$");
    }

    @Test
    void aCompiledPatternLeavesNoRegexBehindAndAFallbackKeepsOne() {
        String scanned = source("^[a-zA-Z_:][a-zA-Z0-9_:]*$");
        String regex = source("^(a|b)+$");

        assertThat(scanned).doesNotContain("java.util.regex").contains("matchesSPattern");
        assertThat(regex).contains("java.util.regex.Pattern.compile").contains("PATTERN_S");
    }

    private static String source(String pattern) {
        return new JavaGenerator(new GeneratorOptions(true, false, true, false))
                .generate(new io.github.helios57.protogen.compiler.ProtoCompiler(
                        io.github.helios57.protogen.compiler.ProtoCompiler.Options.defaults())
                        .link(List.of(new io.github.helios57.protogen.compiler.ProtoCompiler(
                                io.github.helios57.protogen.compiler.ProtoCompiler.Options.defaults())
                                .parse("f.proto", """
                                        syntax = "proto3";
                                        option java_package = "p";
                                        option java_multiple_files = true;
                                        message M {
                                          // @Pattern %s
                                          string s = 1;
                                        }
                                        """.formatted(pattern)))))
                .stream()
                .filter(file -> file.relativePath().endsWith("M.java"))
                .findFirst()
                .orElseThrow()
                .content();
    }
}
