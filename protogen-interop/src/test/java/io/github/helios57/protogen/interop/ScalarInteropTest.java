package io.github.helios57.protogen.interop;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.github.helios57.protogen.it.Scalars;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import protogen.it.model.ScalarsV1;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same schema, compiled by protogen and by protoc. Every value is encoded by each implementation and
 * decoded by the other, and the two encodings are compared byte for byte.
 */
class ScalarInteropTest {

    private static protogen.it.official.ScalarsV1 official(ScalarsV1 message)
            throws InvalidProtocolBufferException {
        return protogen.it.official.ScalarsV1.parseFrom(message.toByteArray());
    }

    private static ScalarsV1 protogen(protogen.it.official.ScalarsV1 message) {
        return ScalarsV1.parseFrom(message.toByteArray());
    }

    private static void assertBytesMatch(ScalarsV1 mine, protogen.it.official.ScalarsV1 theirs) {
        assertThat(mine.toByteArray())
                .as("protogen and protoc must produce identical bytes")
                .isEqualTo(theirs.toByteArray());
    }

    @Test
    void emptyMessagesAgree() throws Exception {
        ScalarsV1 mine = Scalars.empty().build();
        protogen.it.official.ScalarsV1 theirs = protogen.it.official.ScalarsV1.newBuilder().build();

        assertBytesMatch(mine, theirs);
        assertThat(official(mine).toByteArray()).isEmpty();
        assertThat(protogen(theirs)).isEqualTo(mine);
    }

    @Test
    void everyScalarAgreesInBothDirections() throws Exception {
        ScalarsV1 mine = Scalars.empty()
                .text("hello").flag(true).i32(42).i64(43L).u32(44).u64(45L).s32(46).s64(47L)
                .f32(48).f64(49L).sf32(50).sf64(51L).real32(1.5F).real64(2.5D)
                .blob(new byte[]{1, 2, 3}).optionalText("opt").optionalNumber(7).optionalFlag(true)
                .optionalReal(0.5D).optionalBlob(new byte[]{9}).wideTagText("wide").wideTagNumber(12345)
                .build();

        protogen.it.official.ScalarsV1 theirs = protogen.it.official.ScalarsV1.newBuilder()
                .setText("hello").setFlag(true).setI32(42).setI64(43L).setU32(44).setU64(45L)
                .setS32(46).setS64(47L).setF32(48).setF64(49L).setSf32(50).setSf64(51L)
                .setReal32(1.5F).setReal64(2.5D).setBlob(ByteString.copyFrom(new byte[]{1, 2, 3}))
                .setOptionalText("opt").setOptionalNumber(7).setOptionalFlag(true)
                .setOptionalReal(0.5D).setOptionalBlob(ByteString.copyFrom(new byte[]{9}))
                .setWideTagText("wide").setWideTagNumber(12345)
                .build();

        assertBytesMatch(mine, theirs);
        assertThat(protogen(theirs)).isEqualTo(mine);

        protogen.it.official.ScalarsV1 decoded = official(mine);
        assertThat(decoded.getText()).isEqualTo("hello");
        assertThat(decoded.getI64()).isEqualTo(43L);
        assertThat(decoded.getBlob().toByteArray()).containsExactly(1, 2, 3);
        assertThat(decoded.getWideTagNumber()).isEqualTo(12345);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, -1, 127, 128, -128, 16383, 16384, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void int32EncodingAgrees(int value) throws Exception {
        ScalarsV1 mine = Scalars.empty().i32(value).build();
        protogen.it.official.ScalarsV1 theirs =
                protogen.it.official.ScalarsV1.newBuilder().setI32(value).build();

        assertBytesMatch(mine, theirs);
        assertThat(official(mine).getI32()).isEqualTo(value);
        assertThat(protogen(theirs).i32()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 4294967296L, -4294967296L})
    void int64EncodingAgrees(long value) throws Exception {
        ScalarsV1 mine = Scalars.empty().i64(value).build();
        protogen.it.official.ScalarsV1 theirs =
                protogen.it.official.ScalarsV1.newBuilder().setI64(value).build();

        assertBytesMatch(mine, theirs);
        assertThat(official(mine).getI64()).isEqualTo(value);
        assertThat(protogen(theirs).i64()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void zigZagEncodingAgrees(int value) throws Exception {
        ScalarsV1 mine = Scalars.empty().s32(value).build();
        protogen.it.official.ScalarsV1 theirs =
                protogen.it.official.ScalarsV1.newBuilder().setS32(value).build();

        assertBytesMatch(mine, theirs);
        assertThat(official(mine).getS32()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE})
    void zigZag64EncodingAgrees(long value) throws Exception {
        ScalarsV1 mine = Scalars.empty().s64(value).build();
        protogen.it.official.ScalarsV1 theirs =
                protogen.it.official.ScalarsV1.newBuilder().setS64(value).build();

        assertBytesMatch(mine, theirs);
        assertThat(official(mine).getS64()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void unsignedAndFixedEncodingsAgree(int value) throws Exception {
        ScalarsV1 mine = Scalars.empty().u32(value).f32(value).sf32(value).build();
        protogen.it.official.ScalarsV1 theirs = protogen.it.official.ScalarsV1.newBuilder()
                .setU32(value).setF32(value).setSf32(value).build();

        assertBytesMatch(mine, theirs);
        assertThat(official(mine).getU32()).isEqualTo(value);
        assertThat(official(mine).getF32()).isEqualTo(value);
        assertThat(official(mine).getSf32()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE})
    void unsigned64AndFixed64EncodingsAgree(long value) throws Exception {
        ScalarsV1 mine = Scalars.empty().u64(value).f64(value).sf64(value).build();
        protogen.it.official.ScalarsV1 theirs = protogen.it.official.ScalarsV1.newBuilder()
                .setU64(value).setF64(value).setSf64(value).build();

        assertBytesMatch(mine, theirs);
        assertThat(official(mine).getU64()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(doubles = {1.0, -1.0, -0.0, Double.MIN_VALUE, Double.MAX_VALUE, Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void doubleEncodingAgreesIncludingNegativeZero(double value) throws Exception {
        ScalarsV1 mine = Scalars.empty().real64(value).build();
        protogen.it.official.ScalarsV1 theirs =
                protogen.it.official.ScalarsV1.newBuilder().setReal64(value).build();

        assertBytesMatch(mine, theirs);
        assertThat(Double.doubleToRawLongBits(official(mine).getReal64()))
                .isEqualTo(Double.doubleToRawLongBits(value));
    }

    @ParameterizedTest
    @ValueSource(floats = {1.0F, -1.0F, -0.0F, Float.MIN_VALUE, Float.MAX_VALUE, Float.NaN,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
    void floatEncodingAgreesIncludingNegativeZero(float value) throws Exception {
        ScalarsV1 mine = Scalars.empty().real32(value).build();
        protogen.it.official.ScalarsV1 theirs =
                protogen.it.official.ScalarsV1.newBuilder().setReal32(value).build();

        assertBytesMatch(mine, theirs);
        assertThat(Float.floatToRawIntBits(official(mine).getReal32()))
                .isEqualTo(Float.floatToRawIntBits(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "grüezi", "日本語", "🇨🇭 flag with surrogate pairs",
            "a string long enough that its length prefix needs two varint bytes ........................"
                    + "........................................................................."})
    void utf8EncodingAgrees(String value) throws Exception {
        ScalarsV1 mine = Scalars.empty().text(value).build();
        protogen.it.official.ScalarsV1 theirs =
                protogen.it.official.ScalarsV1.newBuilder().setText(value).build();

        assertBytesMatch(mine, theirs);
        assertThat(official(mine).getText()).isEqualTo(value);
        assertThat(protogen(theirs).text()).isEqualTo(value);
    }

    @Test
    void bytesEncodingAgrees() throws Exception {
        byte[] value = {0, -1, 127, -128, 0};
        ScalarsV1 mine = Scalars.empty().blob(value).build();
        protogen.it.official.ScalarsV1 theirs = protogen.it.official.ScalarsV1.newBuilder()
                .setBlob(ByteString.copyFrom(value)).build();

        assertBytesMatch(mine, theirs);
        assertThat(official(mine).getBlob().toByteArray()).containsExactly(value);
    }

    @Test
    void explicitPresenceAgrees() throws Exception {
        ScalarsV1 mine = Scalars.empty().optionalText("").optionalNumber(0).build();
        protogen.it.official.ScalarsV1 theirs = protogen.it.official.ScalarsV1.newBuilder()
                .setOptionalText("").setOptionalNumber(0).build();

        assertBytesMatch(mine, theirs);

        protogen.it.official.ScalarsV1 decoded = official(mine);
        assertThat(decoded.hasOptionalText()).isTrue();
        assertThat(decoded.hasOptionalNumber()).isTrue();
        assertThat(decoded.hasOptionalFlag()).isFalse();
    }

    @Test
    void absentOptionalFieldsAgree() throws Exception {
        ScalarsV1 mine = Scalars.empty().build();

        assertThat(official(mine).hasOptionalText()).isFalse();
        assertThat(protogen(protogen.it.official.ScalarsV1.newBuilder().build()).optionalText()).isNull();
    }

    @Test
    void multiByteTagsAgree() throws Exception {
        ScalarsV1 mine = Scalars.empty().wideTagText("wide").wideTagNumber(-5).build();
        protogen.it.official.ScalarsV1 theirs = protogen.it.official.ScalarsV1.newBuilder()
                .setWideTagText("wide").setWideTagNumber(-5).build();

        assertBytesMatch(mine, theirs);
        assertThat(official(mine).getWideTagNumber()).isEqualTo(-5);
    }
}
