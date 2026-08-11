package io.github.helios57.protogen.it;

import org.junit.jupiter.api.Test;
import protogen.it.model.AliasedEnumV1;
import protogen.it.model.EnumHolderV1;
import protogen.it.model.StageEnumV1;
import protogen.it.model.StatusEnumV1;
import protogen.it.model.Wrapped;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Enums, including values from a newer schema and declared aliases. */
class EnumTest {

    private static EnumHolderV1 holder(StageEnumV1 stage) {
        return new EnumHolderV1(stage, StatusEnumV1.passive, AliasedEnumV1.ALIAS_UNSPECIFIED, null, List.of());
    }

    @Test
    void enumsRoundTrip() {
        EnumHolderV1 message = new EnumHolderV1(StageEnumV1.PROD, StatusEnumV1.active_released,
                AliasedEnumV1.ORIGINAL, StageEnumV1.DEV, List.of(StageEnumV1.DEV, StageEnumV1.PROD));

        assertThat(EnumHolderV1.parseFrom(message.toByteArray())).isEqualTo(message);
    }

    @Test
    void zeroValuedEnumIsNotWritten() {
        assertThat(holder(StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED).toByteArray()).isEmpty();
    }

    @Test
    void lowerCaseConstantsKeepTheirDeclaredNames() {
        assertThat(StatusEnumV1.passive.number()).isZero();
        assertThat(StatusEnumV1.passive_candidate.number()).isEqualTo(1);
        assertThat(StatusEnumV1.active.number()).isEqualTo(2);
        assertThat(StatusEnumV1.active_released.number()).isEqualTo(3);
    }

    @Test
    void aliasedConstantsShareAWireValue() {
        assertThat(AliasedEnumV1.ORIGINAL.number()).isEqualTo(AliasedEnumV1.SYNONYM.number());
        assertThat(AliasedEnumV1.forNumber(1)).isEqualTo(AliasedEnumV1.ORIGINAL);
    }

    @Test
    void unknownWireValueBecomesUnrecognizedRatherThanFailing() {
        // a value written by a newer schema must not break an older reader
        byte[] fromTheFuture = {0x08, 0x63}; // field 1, varint, value 99

        EnumHolderV1 parsed = EnumHolderV1.parseFrom(fromTheFuture);

        assertThat(parsed.stage()).isEqualTo(StageEnumV1.UNRECOGNIZED);
        assertThat(StageEnumV1.UNRECOGNIZED.number()).isEqualTo(-1);
    }

    @Test
    void forNumberMapsEveryDeclaredValue() {
        for (StageEnumV1 value : StageEnumV1.values()) {
            if (value != StageEnumV1.UNRECOGNIZED) {
                assertThat(StageEnumV1.forNumber(value.number())).isEqualTo(value);
            }
        }
    }

    @Test
    void repeatedEnumsArePacked() {
        EnumHolderV1 message = new EnumHolderV1(StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED,
                StatusEnumV1.passive, AliasedEnumV1.ALIAS_UNSPECIFIED, null,
                List.of(StageEnumV1.DEV, StageEnumV1.TEST, StageEnumV1.PROD));

        assertThat(message.protoSize()).isEqualTo(1 + 1 + 3);
        assertThat(EnumHolderV1.parseFrom(message.toByteArray()).stages())
                .containsExactly(StageEnumV1.DEV, StageEnumV1.TEST, StageEnumV1.PROD);
    }

    @Test
    void nestedEnumInsideAWrapperClassRoundTrips() {
        Wrapped.WrappedHolderV1 message = new Wrapped.WrappedHolderV1(
                Wrapped.OuterApiEnumV1.InnerStatusV1.RUNNING,
                new Wrapped.OuterApiEnumV1(Wrapped.OuterApiEnumV1.InnerStatusV1.STOPPED));

        assertThat(Wrapped.WrappedHolderV1.parseFrom(message.toByteArray())).isEqualTo(message);
    }
}
