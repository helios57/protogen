package io.github.helios57.protogen.it;

import org.junit.jupiter.api.Test;
import protogen.it.model.ScalarsV1;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * proto3 presence rules: implicit-presence fields vanish from the wire at their default, while a field
 * declared {@code optional} keeps the difference between unset and default.
 */
class PresenceTest {

    @Test
    void implicitPresenceFieldsAtTheirDefaultAreNotWritten() {
        ScalarsV1 message = Scalars.empty().text("").i32(0).flag(false).blob(new byte[0]).build();

        assertThat(message.toByteArray()).isEmpty();
    }

    @Test
    void implicitPresenceFieldsAboveTheirDefaultAreWritten() {
        assertThat(Scalars.empty().text("x").build().toByteArray()).isNotEmpty();
        assertThat(Scalars.empty().i32(1).build().toByteArray()).isNotEmpty();
        assertThat(Scalars.empty().flag(true).build().toByteArray()).isNotEmpty();
        assertThat(Scalars.empty().blob(new byte[]{0}).build().toByteArray()).isNotEmpty();
    }

    @Test
    void unsetOptionalFieldIsNull() {
        ScalarsV1 parsed = ScalarsV1.parseFrom(Scalars.empty().build().toByteArray());

        assertThat(parsed.optionalText()).isNull();
        assertThat(parsed.optionalNumber()).isNull();
        assertThat(parsed.optionalFlag()).isNull();
        assertThat(parsed.optionalBlob()).isNull();
    }

    @Test
    void optionalFieldSetToItsDefaultIsStillTransmitted() {
        ScalarsV1 message = Scalars.empty().optionalText("").optionalNumber(0).optionalFlag(false).build();

        assertThat(message.toByteArray()).isNotEmpty();

        ScalarsV1 parsed = ScalarsV1.parseFrom(message.toByteArray());
        assertThat(parsed.optionalText()).isEmpty();
        assertThat(parsed.optionalNumber()).isZero();
        assertThat(parsed.optionalFlag()).isFalse();
    }

    @Test
    void unsetAndDefaultOptionalAreDistinguishable() {
        ScalarsV1 unset = Scalars.empty().build();
        ScalarsV1 defaulted = Scalars.empty().optionalText("").build();

        assertThat(unset).isNotEqualTo(defaulted);
        assertThat(unset.toByteArray()).isNotEqualTo(defaulted.toByteArray());
    }

    @Test
    void nullSingularStringIsNormalisedToTheProto3Default() {
        ScalarsV1 message = Scalars.empty().text(null).build();

        assertThat(message.text()).isEmpty();
        assertThat(message.toByteArray()).isEmpty();
    }

    @Test
    void nullSingularBytesIsNormalisedToAnEmptyArray() {
        ScalarsV1 message = Scalars.empty().blob(null).build();

        assertThat(message.blob()).isEmpty();
    }
}
