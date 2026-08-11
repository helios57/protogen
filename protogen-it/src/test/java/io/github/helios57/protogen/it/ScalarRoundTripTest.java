package io.github.helios57.protogen.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import protogen.it.model.ScalarsV1;

import static org.assertj.core.api.Assertions.assertThat;

/** protogen encodes, protogen decodes: every scalar type and the values most likely to break a codec. */
class ScalarRoundTripTest {

    private static ScalarsV1 roundTrip(ScalarsV1 message) {
        byte[] encoded = message.toByteArray();
        assertThat(encoded).as("toByteArray must agree with protoSize").hasSize(message.protoSize());
        return ScalarsV1.parseFrom(encoded);
    }

    @Test
    void emptyMessageEncodesToZeroBytes() {
        ScalarsV1 empty = Scalars.empty().build();

        assertThat(empty.protoSize()).isZero();
        assertThat(empty.toByteArray()).isEmpty();
        assertThat(roundTrip(empty)).isEqualTo(empty);
    }

    @Test
    void allScalarsSurviveARoundTrip() {
        ScalarsV1 original = Scalars.empty()
                .text("hello").flag(true).i32(42).i64(43L).u32(44).u64(45L).s32(46).s64(47L)
                .f32(48).f64(49L).sf32(50).sf64(51L).real32(1.5F).real64(2.5D).blob(new byte[]{1, 2, 3})
                .optionalText("opt").optionalNumber(7).optionalFlag(true).optionalReal(0.5D)
                .optionalBlob(new byte[]{9}).wideTagText("wide").wideTagNumber(12345)
                .build();

        assertThat(roundTrip(original)).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, -1, 127, 128, -128, 16383, 16384, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void int32BoundariesRoundTrip(int value) {
        assertThat(roundTrip(Scalars.empty().i32(value).build()).i32()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 4294967296L, -4294967296L})
    void int64BoundariesRoundTrip(long value) {
        assertThat(roundTrip(Scalars.empty().i64(value).build()).i64()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void zigZagInt32RoundTrips(int value) {
        assertThat(roundTrip(Scalars.empty().s32(value).build()).s32()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE})
    void zigZagInt64RoundTrips(long value) {
        assertThat(roundTrip(Scalars.empty().s64(value).build()).s64()).isEqualTo(value);
    }

    @Test
    void zigZagEncodingIsCompactForSmallNegatives() {
        // -1 as sint32 must take one byte, where int32 would take ten
        ScalarsV1 zigZag = Scalars.empty().s32(-1).build();
        ScalarsV1 plain = Scalars.empty().i32(-1).build();

        assertThat(zigZag.protoSize()).isEqualTo(2);
        assertThat(plain.protoSize()).isEqualTo(11);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void fixedWidthRoundTrips(int value) {
        ScalarsV1 parsed = roundTrip(Scalars.empty().f32(value).sf32(value).build());

        assertThat(parsed.f32()).isEqualTo(value);
        assertThat(parsed.sf32()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE})
    void fixed64WidthRoundTrips(long value) {
        ScalarsV1 parsed = roundTrip(Scalars.empty().f64(value).sf64(value).build());

        assertThat(parsed.f64()).isEqualTo(value);
        assertThat(parsed.sf64()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(doubles = {1.0, -1.0, Double.MIN_VALUE, Double.MAX_VALUE, Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void doubleSpecialValuesRoundTrip(double value) {
        double parsed = roundTrip(Scalars.empty().real64(value).build()).real64();

        if (Double.isNaN(value)) {
            assertThat(parsed).isNaN();
        } else {
            assertThat(parsed).isEqualTo(value);
        }
    }

    @ParameterizedTest
    @ValueSource(floats = {1.0F, -1.0F, Float.MIN_VALUE, Float.MAX_VALUE, Float.NaN,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
    void floatSpecialValuesRoundTrip(float value) {
        float parsed = roundTrip(Scalars.empty().real32(value).build()).real32();

        if (Float.isNaN(value)) {
            assertThat(parsed).isNaN();
        } else {
            assertThat(parsed).isEqualTo(value);
        }
    }

    @Test
    void negativeZeroIsWrittenBecauseItDiffersFromTheDefault() {
        ScalarsV1 message = Scalars.empty().real64(-0.0D).build();

        assertThat(message.protoSize()).isPositive();
        assertThat(Double.doubleToRawLongBits(roundTrip(message).real64()))
                .isEqualTo(Double.doubleToRawLongBits(-0.0D));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "grüezi", "日本語", "🇨🇭 flag with surrogate pairs", "tab\tnewline\n"})
    void utf8StringsRoundTripAndSizeMatches(String value) {
        ScalarsV1 message = Scalars.empty().text(value).build();

        assertThat(message.toByteArray()).hasSize(message.protoSize());
        assertThat(roundTrip(message).text()).isEqualTo(value);
    }

    @Test
    void loneSurrogateDoesNotCorruptTheLengthPrefix() {
        // an unpaired surrogate encodes as the three byte replacement character
        ScalarsV1 message = Scalars.empty().text("before\uD83Cafter").build();

        assertThat(message.toByteArray()).hasSize(message.protoSize());
        assertThat(roundTrip(message).text()).hasSize("before?after".length());
    }

    @Test
    void longStringsCrossTheMultiByteLengthPrefixBoundary() {
        String value = "x".repeat(200);

        assertThat(roundTrip(Scalars.empty().text(value).build()).text()).isEqualTo(value);
    }

    @Test
    void bytesRoundTripIncludingZeroBytes() {
        byte[] value = {0, -1, 127, -128, 0};

        assertThat(roundTrip(Scalars.empty().blob(value).build()).blob()).containsExactly(value);
    }

    @Test
    void fieldNumbersNeedingMultiByteTagsRoundTrip() {
        ScalarsV1 parsed = roundTrip(Scalars.empty().wideTagText("wide").wideTagNumber(-5).build());

        assertThat(parsed.wideTagText()).isEqualTo("wide");
        assertThat(parsed.wideTagNumber()).isEqualTo(-5);
    }

    @Test
    void writeToHonoursTheGivenOffset() {
        ScalarsV1 message = Scalars.empty().text("offset").build();
        byte[] target = new byte[message.protoSize() + 8];

        int end = message.writeTo(target, 4);

        assertThat(end).isEqualTo(4 + message.protoSize());
        assertThat(ScalarsV1.parseFrom(target, 4, message.protoSize())).isEqualTo(message);
    }
}
