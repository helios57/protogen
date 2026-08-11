package io.github.helios57.protogen.it;

import org.junit.jupiter.api.Test;
import protogen.it.model.ConstrainedV1;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code @Minimum} / {@code @Pattern} style annotations in the schema comments become checks in the
 * generated record's compact constructor, so they hold for hand-built and for parsed messages alike.
 * <p>
 * Note the consequence for implicit-presence fields: proto3 cannot tell absent from default, so a
 * constraint on such a field is enforced on every instance. Put the constraint on an {@code optional}
 * field when it should apply only if the value is set.
 */
class ValidationTest {

    private static ConstrainedV1 valid() {
        return new ConstrainedV1("abcdef", "CH42", 5, 10L, 15, List.of("a"), "set", null);
    }

    @Test
    void aValidMessageIsAccepted() {
        assertThatCode(ValidationTest::valid).doesNotThrowAnyException();
    }

    @Test
    void minLengthIsEnforced() {
        assertThatThrownBy(() -> new ConstrainedV1("ab", "CH42", 5, 10L, 15, List.of("a"), "set", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ConstrainedV1.instanceId")
                .hasMessageContaining("@MinLength 3")
                .hasMessageContaining("was: 2");
    }

    @Test
    void maxLengthIsEnforced() {
        assertThatThrownBy(() -> new ConstrainedV1("a".repeat(11), "CH42", 5, 10L, 15, List.of("a"), "set", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@MaxLength 10");
    }

    @Test
    void patternIsEnforced() {
        assertThatThrownBy(() -> new ConstrainedV1("abcdef", "ch42", 5, 10L, 15, List.of("a"), "set", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@Pattern");
    }

    @Test
    void inclusiveBoundsAreEnforced() {
        assertThatThrownBy(() -> new ConstrainedV1("abcdef", "CH42", 0, 10L, 15, List.of("a"), "set", null))
                .hasMessageContaining("@Minimum 1");
        assertThatThrownBy(() -> new ConstrainedV1("abcdef", "CH42", 101, 10L, 15, List.of("a"), "set", null))
                .hasMessageContaining("@Maximum 100");
        assertThatCode(() -> new ConstrainedV1("abcdef", "CH42", 1, 10L, 15, List.of("a"), "set", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> new ConstrainedV1("abcdef", "CH42", 100, 10L, 15, List.of("a"), "set", null))
                .doesNotThrowAnyException();
    }

    @Test
    void exclusiveBoundsAreEnforced() {
        assertThatThrownBy(() -> new ConstrainedV1("abcdef", "CH42", 5, 0L, 15, List.of("a"), "set", null))
                .hasMessageContaining("@ExclusiveMinimum 0");
        assertThatThrownBy(() -> new ConstrainedV1("abcdef", "CH42", 5, 1000L, 15, List.of("a"), "set", null))
                .hasMessageContaining("@ExclusiveMaximum 1000");
        assertThatCode(() -> new ConstrainedV1("abcdef", "CH42", 5, 1L, 15, List.of("a"), "set", null))
                .doesNotThrowAnyException();
    }

    @Test
    void multipleOfIsEnforced() {
        assertThatThrownBy(() -> new ConstrainedV1("abcdef", "CH42", 5, 10L, 7, List.of("a"), "set", null))
                .hasMessageContaining("@MultipleOf 5");
        assertThatCode(() -> new ConstrainedV1("abcdef", "CH42", 5, 10L, 0, List.of("a"), "set", null))
                .doesNotThrowAnyException();
    }

    @Test
    void itemCountsAreEnforced() {
        assertThatThrownBy(() -> new ConstrainedV1("abcdef", "CH42", 5, 10L, 15, List.of(), "set", null))
                .hasMessageContaining("@MinItems 1");
        assertThatThrownBy(() -> new ConstrainedV1("abcdef", "CH42", 5, 10L, 15,
                List.of("a", "b", "c", "d", "e", "f"), "set", null))
                .hasMessageContaining("@MaxItems 5");
    }

    @Test
    void requiredIsEnforced() {
        assertThatThrownBy(() -> new ConstrainedV1("abcdef", "CH42", 5, 10L, 15, List.of("a"), "", null))
                .hasMessageContaining("ConstrainedV1.mandatory is @Required but was not set");
    }

    @Test
    void constraintsOnOptionalFieldsApplyOnlyWhenSet() {
        assertThatCode(() -> new ConstrainedV1("abcdef", "CH42", 5, 10L, 15, List.of("a"), "set", null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new ConstrainedV1("abcdef", "CH42", 5, 10L, 15, List.of("a"), "set", "ab"))
                .hasMessageContaining("ConstrainedV1.nickname")
                .hasMessageContaining("@MinLength 4");
        assertThatCode(() -> new ConstrainedV1("abcdef", "CH42", 5, 10L, 15, List.of("a"), "set", "abcd"))
                .doesNotThrowAnyException();
    }

    @Test
    void parsingAlsoValidates() {
        byte[] encoded = valid().toByteArray();

        assertThat(ConstrainedV1.parseFrom(encoded)).isEqualTo(valid());
    }

    @Test
    void parsingAMessageThatViolatesAConstraintIsRejected() {
        // a peer that does not honour the schema must not be able to smuggle an invalid value in
        byte[] tooShort = {0x0a, 0x02, 'a', 'b'}; // instanceId = "ab", below @MinLength 3

        assertThatThrownBy(() -> ConstrainedV1.parseFrom(tooShort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@MinLength 3");
    }
}
