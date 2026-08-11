package io.github.helios57.protogen.it;

import org.junit.jupiter.api.Test;
import protogen.it.model.ConstrainedV1;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The runtime half of the validation controls.
 * <p>
 * The generated switch is a {@code static final boolean} read once at class initialisation, so the JIT can
 * fold the checks away entirely when it is off - which also means the property has to be set before the
 * class loads. This test therefore runs in its own JVM: see the {@code validation-disabled} surefire
 * execution in this module's pom, which is where the {@code -Dprotogen.validation=false} lives.
 * <p>
 * The point of the switch is reading legacy data that predates a constraint, without regenerating and
 * without dropping the constraint for everyone else.
 */
class ValidationRuntimeSwitchTest {

    @Test
    void theSwitchIsActuallyOffInThisJvm() {
        assertThat(System.getProperty("protogen.validation"))
                .as("this test is meaningless unless the surefire execution set the property")
                .isEqualTo("false");
    }

    @Test
    void constraintsAreNotEnforcedWhenTheSwitchIsOff() {
        // every one of these violates a constraint that ValidationTest proves is enforced by default
        assertThatCode(() -> new ConstrainedV1("ab", "not-a-code", 0, 0L, 7, List.of(), "", "x"))
                .doesNotThrowAnyException();
    }

    @Test
    void legacyDataThatViolatesAConstraintCanBeRead() {
        byte[] tooShort = {0x0a, 0x02, 'a', 'b'}; // instanceId = "ab", below @MinLength 3

        ConstrainedV1 parsed = ConstrainedV1.parseFrom(tooShort);

        assertThat(parsed.instanceId()).isEqualTo("ab");
    }

    @Test
    void normalisationStillHappensWithValidationOff() {
        // the switch turns off the checks, not the proto3 default handling
        ConstrainedV1 message = new ConstrainedV1(null, null, 0, 0L, 0, null, null, null);

        assertThat(message.instanceId()).isEmpty();
        assertThat(message.labels()).isEmpty();
    }

    @Test
    void serializationIsUnaffected() {
        ConstrainedV1 message = new ConstrainedV1("ab", "x", 1, 1L, 5, List.of("a"), "set", null);

        assertThat(ConstrainedV1.parseFrom(message.toByteArray())).isEqualTo(message);
    }
}
