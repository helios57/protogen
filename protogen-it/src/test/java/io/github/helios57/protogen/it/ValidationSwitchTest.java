package io.github.helios57.protogen.it;

import org.junit.jupiter.api.Test;
import protogen.it.model.ConstrainedV1;
import protogen.it.optin.model.RelayedMessageV1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The compile-time half of the validation controls.
 * <p>
 * {@code src/main/proto-optin} is generated with {@code emitValidation = false}, so its records carry no
 * checks at all - not disabled at runtime, absent from the bytecode. The default source root keeps them.
 * The runtime half is covered by {@link ValidationRuntimeSwitchTest}, which needs its own JVM.
 */
class ValidationSwitchTest {

    private static final Path GENERATED = Path.of("target", "generated-sources", "protogen");

    @Test
    void withValidationOnTheConstraintIsEnforced() {
        assertThatThrownBy(() -> new ConstrainedV1("ab", "CH42", 5, 10L, 15, List.of("a"), "set", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@MinLength 3");
    }

    @Test
    void withValidationOffTheConstraintIsNotEvenGenerated() {
        // relay.proto declares @MinLength 8 on tenant; generated with emitValidation = false it is ignored
        assertThatCode(() -> new RelayedMessageV1("id", "topic", "x", 1L, null))
                .doesNotThrowAnyException();
    }

    @Test
    void theGeneratedSourceWithValidationOffContainsNoChecks() throws IOException {
        String source = Files.readString(GENERATED.resolve("protogen/it/optin/model/RelayedMessageV1.java"));

        assertThat(source)
                .doesNotContain("PROTOGEN_VALIDATION")
                .doesNotContain("throw new IllegalArgumentException")
                .doesNotContain("@MinLength");
        // and the Javadoc must not promise enforcement that is not there
        assertThat(source).doesNotContain("violates a");
    }

    @Test
    void theGeneratedSourceWithValidationOnCarriesTheRuntimeSwitch() throws IOException {
        String source = Files.readString(GENERATED.resolve("protogen/it/model/ConstrainedV1.java"));

        assertThat(source)
                .contains("private static final boolean PROTOGEN_VALIDATION")
                .contains("System.getProperty(\"protogen.validation\", \"true\")")
                .contains("if (PROTOGEN_VALIDATION) {");
    }

    @Test
    void onlyAnExplicitFalseDisablesValidation() throws IOException {
        // a typo in the property must leave the checks running rather than silently switch them off
        String source = Files.readString(GENERATED.resolve("protogen/it/model/ConstrainedV1.java"));

        assertThat(source).contains("!\"false\".equalsIgnoreCase(");
    }

    @Test
    void structuralOneofChecksAreNotAffectedByTheValidationSwitch() throws IOException {
        // a oneof invariant is correctness, not schema validation, so it is never switched off
        String source = Files.readString(GENERATED.resolve("protogen/it/model/PayloadV1.java"));

        int oneofCheck = source.indexOf("at most one member of oneof");
        assertThat(oneofCheck).isPositive();
        assertThat(source.substring(0, oneofCheck)).doesNotContain("if (PROTOGEN_VALIDATION) {");
    }
}
