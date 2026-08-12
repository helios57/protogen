package io.github.helios57.protogen.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import protogen.it.model.Any;
import protogen.it.model.Empty;
import protogen.it.model.FieldMask;
import protogen.it.model.ListValue;
import protogen.it.model.NullValue;
import protogen.it.model.Struct;
import protogen.it.model.Value;
import protogen.it.model.WellKnownV1;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The well-known types as they look from Java.
 * <p>
 * That they match {@code protoc} byte for byte is {@code protogen-interop}'s job. This is the other half:
 * a wrapper is the nullable value it exists to carry, a {@code Duration} is a {@link Duration}, and the
 * ones with no JDK counterpart are ordinary records with the usual value semantics.
 */
class WellKnownTest {

    private static WellKnownV1 empty() {
        return new WellKnownV1(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private static WellKnownV1 roundTrip(WellKnownV1 message) {
        assertThat(message.toByteArray()).hasSize(message.protoSize());
        return WellKnownV1.parseFrom(message.toByteArray());
    }

    @Test
    void everythingAbsentEncodesToNothing() {
        assertThat(empty().toByteArray()).isEmpty();
        assertThat(roundTrip(empty())).isEqualTo(empty());
    }

    @Test
    void aWrapperDistinguishesAbsentFromItsDefault() {
        WellKnownV1 absent = empty();
        WellKnownV1 present = new WellKnownV1(null, "", 0, 0L, false, 0.0D, 0.0F, 0, 0L, new byte[0],
                null, null, null, null, null, null);

        assertThat(absent.note()).isNull();
        assertThat(present.note()).isEmpty();
        assertThat(present.toByteArray()).isNotEmpty();

        WellKnownV1 back = roundTrip(present);
        assertThat(back.note()).isEmpty();
        assertThat(back.attempts()).isZero();
        assertThat(back.verified()).isFalse();
        assertThat(back.digest()).isEmpty();
        assertThat(back).isEqualTo(present);
    }

    @Test
    void aBytesWrapperIsCopiedInAndOut() {
        byte[] mutable = {1, 2, 3};
        WellKnownV1 message = new WellKnownV1(null, null, null, null, null, null, null, null, null,
                mutable, null, null, null, null, null, null);

        mutable[0] = 9;
        message.digest()[1] = 9;

        assertThat(message.digest()).containsExactly(1, 2, 3);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, -1L, 60L, -60L, 315_576_000_000L, -315_576_000_000L})
    void durationsRoundTrip(long seconds) {
        for (int nanos : new int[]{0, 1, 999_999_999}) {
            Duration took = Duration.ofSeconds(seconds, seconds < 0 ? -nanos : nanos);
            WellKnownV1 message = new WellKnownV1(took, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);

            assertThat(roundTrip(message).took()).as("%ss %sns", seconds, nanos).isEqualTo(took);
        }
    }

    @Test
    void aSubSecondNegativeDurationRoundTrips() {
        // java.time and protobuf disagree about how to sign this one, so it is worth its own check
        for (Duration took : List.of(Duration.ofMillis(-500), Duration.ofNanos(-1),
                Duration.ofSeconds(-1, 1), Duration.ZERO)) {
            WellKnownV1 message = new WellKnownV1(took, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);

            assertThat(roundTrip(message).took()).isEqualTo(took);
        }
    }

    @Test
    void theRecordGeneratedOnesBehaveLikeAnyOtherMessage() {
        Any any = new Any("type.googleapis.com/x.Y", new byte[]{1, 2});
        FieldMask mask = new FieldMask(List.of("a", "b.c"));
        WellKnownV1 message = new WellKnownV1(null, null, null, null, null, null, null, null, null,
                null, any, new Empty(), mask, null, null, null);

        WellKnownV1 back = roundTrip(message);

        assertThat(back.payload()).isEqualTo(any);
        assertThat(back.payload().typeUrl()).isEqualTo("type.googleapis.com/x.Y");
        assertThat(back.updateMask().paths()).containsExactly("a", "b.c");
        assertThat(back.nothing()).isEqualTo(new Empty());
        assertThat(back).isEqualTo(message);
    }

    @Test
    void anEmptyIsStillPresentWhenItIsThere() {
        WellKnownV1 message = new WellKnownV1(null, null, null, null, null, null, null, null, null,
                null, null, new Empty(), null, null, null, null);

        assertThat(message.toByteArray()).isNotEmpty();
        assertThat(roundTrip(message).nothing()).isNotNull();
        assertThat(empty().nothing()).isNull();
    }

    @Test
    void aStructCarriesEveryValueKind() {
        Map<String, Value> fields = new LinkedHashMap<>();
        fields.put("null", new Value(NullValue.NULL_VALUE, null, null, null, null, null));
        fields.put("number", new Value(null, 1.5D, null, null, null, null));
        fields.put("string", new Value(null, null, "text", null, null, null));
        fields.put("bool", new Value(null, null, null, true, null, null));
        fields.put("list", new Value(null, null, null, null, null,
                new ListValue(List.of(new Value(null, 1.0D, null, null, null, null)))));
        Struct struct = new Struct(fields);
        WellKnownV1 message = new WellKnownV1(null, null, null, null, null, null, null, null, null,
                null, null, null, null, struct, null, null);

        WellKnownV1 back = roundTrip(message);

        assertThat(back.attributes()).isEqualTo(struct);
        assertThat(back.attributes().fields().get("string").stringValue()).isEqualTo("text");
        assertThat(back.attributes().fields().get("list").listValue().values()).hasSize(1);
    }

    @Test
    void aValueIsAOneofAndRefusesTwoAtOnce() {
        assertThatThrownBy(() -> new Value(null, 1.0D, "both", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aStructCanNestItself() {
        Struct inner = new Struct(new LinkedHashMap<>(Map.of("k",
                new Value(null, null, "v", null, null, null))));
        Struct outer = new Struct(new LinkedHashMap<>(Map.of("nested",
                new Value(null, null, null, null, inner, null))));
        WellKnownV1 message = new WellKnownV1(null, null, null, null, null, null, null, null, null,
                null, null, null, null, outer, null, null);

        assertThat(roundTrip(message).attributes().fields().get("nested").structValue()).isEqualTo(inner);
    }

    @Test
    void repeatedWellKnownFieldsRoundTrip() {
        List<Duration> backoff = List.of(Duration.ofSeconds(1), Duration.ZERO, Duration.ofMillis(-1));
        List<String> tags = List.of("a", "", "c");
        WellKnownV1 message = new WellKnownV1(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, backoff, tags);

        WellKnownV1 back = roundTrip(message);

        assertThat(back.backoff()).isEqualTo(backoff);
        assertThat(back.tags()).isEqualTo(tags);
    }

    @Test
    void theGeneratedRecordsCarryNoRuntimeDependency() {
        // they are generated into this package like everything else, not imported from protobuf-java
        assertThat(Struct.class.getPackageName()).isEqualTo("protogen.it.model");
        assertThat(Any.class.getPackageName()).isEqualTo("protogen.it.model");
    }
}
