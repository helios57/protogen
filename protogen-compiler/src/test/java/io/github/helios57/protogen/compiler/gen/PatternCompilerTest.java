package io.github.helios57.protogen.compiler.gen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the pattern compiler will and will not take.
 * <p>
 * That the accepted ones give the same answer as the regex is
 * {@link PatternScanDifferentialTest}'s job. This one pins the boundary: taking a pattern it cannot decide
 * correctly is a wrong answer shipped into every constructor, so the refusals matter more than the
 * acceptances.
 */
class PatternCompilerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "^[a-z]+$",
            "^[a-zA-Z_:][a-zA-Z0-9_:]*$",
            "^\\d{2,4}$",
            "^.$",
            "^\\w?\\d*$",
            "^a$"})
    void takesAnAnchoredSequenceOfCharacterClasses(String pattern) {
        assertThat(PatternCompiler.compile(pattern)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[a-z]+$",          // not anchored at the start, so it is a search
            "^[a-z]+",          // not anchored at the end
            "[a-z]+",
            "^abc\\$",          // the trailing dollar is escaped: nothing anchors the end
            "^(a|b)$",          // alternation
            "^(ab)+$",          // groups
            "^(?:ab)$",
            "^(?=a)b$",         // lookaround
            "^a\\1$",           // backreference
            "^\\p{Alpha}$",     // named classes
            "^\\bx\\b$",        // word boundaries
            "^a*?$",            // reluctant
            "^a*+$",            // possessive
            "^$"})              // nothing between the anchors
    void refusesWhatItCannotDecide(String pattern) {
        assertThat(PatternCompiler.compile(pattern)).isNull();
    }

    /**
     * The heart of it: a greedy scan without backtracking is only right when a variable-length term cannot
     * eat something the rest of the pattern needs.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "^[a-z]*a$",            // the star eats the a the pattern still needs
            "^[a-z]*[a-z0-9]+$",    // overlapping sets, and the second must match something
            "^\\w+\\d$",            // \d is inside \w
            "^.*a$",
            "^[a-z]{1,3}[a-z]$"})
    void refusesWhenGreedyWouldStealFromWhatFollows(String pattern) {
        assertThat(PatternCompiler.compile(pattern)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "^[a-z]*[0-9]*$",       // disjoint, so greedy is safe even though both are variable
            "^[a-z]*-[0-9]+$",
            "^[a-z]*[a-z]?$",       // overlapping, but nothing after it has to match
            "^\\d+[a-z]$"})
    void takesOverlapFreeOrOptionalTails(String pattern) {
        assertThat(PatternCompiler.compile(pattern)).isNotNull();
    }

    @Test
    void readsTheQuantifierBounds() {
        assertThat(PatternCompiler.compile("^a{2,5}$"))
                .singleElement()
                .satisfies(term -> {
                    assertThat(term.min()).isEqualTo(2);
                    assertThat(term.max()).isEqualTo(5);
                });
        assertThat(PatternCompiler.compile("^a{2,}$").get(0).max()).isEqualTo(Integer.MAX_VALUE);
        assertThat(PatternCompiler.compile("^a{3}$").get(0))
                .satisfies(term -> assertThat(term.min()).isEqualTo(term.max()));
        assertThat(PatternCompiler.compile("^a?$").get(0).min()).isZero();
    }

    @Test
    void mergesAdjacentRangesAndSortsThem() {
        List<PatternCompiler.Term> terms = PatternCompiler.compile("^[c-df-ga-b]$");

        // a-b, c-d and f-g: the first two touch and become one range, f-g stays separate
        assertThat(terms.get(0).chars().ranges()).hasSize(2);
        assertThat(terms.get(0).chars().condition("c"))
                .isEqualTo("(c >= 'a' && c <= 'd') || (c >= 'f' && c <= 'g')");
    }

    @Test
    void aNegatedClassTestsForWhatItExcludes() {
        String condition = PatternCompiler.compile("^[^ab]$").get(0).chars().condition("x");

        assertThat(condition).isEqualTo("!((x >= 'a' && x <= 'b'))");
    }

    @Test
    void aNegatedClassCoversEverythingElseIncludingAstralCharacters() {
        List<int[]> ranges = PatternCompiler.compile("^[^a]$").get(0).chars().ranges();

        assertThat(ranges.get(ranges.size() - 1)[1]).isEqualTo(Character.MAX_CODE_POINT);
    }

    @Test
    void escapedMetacharactersAreLiterals() {
        assertThat(PatternCompiler.compile("^\\.\\*\\+\\?\\[\\]\\{\\}\\(\\)\\|\\^\\$\\\\$")).hasSize(14);
    }

    @Test
    void aDotExcludesTheLineTerminators() {
        String condition = PatternCompiler.compile("^.$").get(0).chars().condition("c");

        assertThat(condition).contains("'\\n'").contains("'\\r'").contains("\\u0085").startsWith("!(");
    }
}
