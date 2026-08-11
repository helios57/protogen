package io.github.helios57.protogen.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import protogen.it.model.NodeV1;
import protogen.it.model.ScalarsV1;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bad input must fail fast with a clear error, never loop, hang or read past the buffer. */
class MalformedInputTest {

    private static byte[] wellFormed() {
        return Scalars.empty().text("hello").i32(300).blob(new byte[]{1, 2, 3}).build().toByteArray();
    }

    static Stream<byte[]> truncations() {
        byte[] full = wellFormed();
        return IntStream.range(1, full.length)
                .mapToObj(i -> java.util.Arrays.copyOf(full, i));
    }

    /**
     * A cut at a field boundary is still well-formed protobuf, so a truncation may legitimately parse.
     * What must never happen is any other outcome: an index out of bounds, a stack overflow or a hang.
     */
    @ParameterizedTest
    @MethodSource("truncations")
    void everyTruncationEitherParsesOrFailsCleanly(byte[] truncated) {
        try {
            ScalarsV1.parseFrom(truncated);
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("malformed protobuf input");
        } catch (Throwable unexpected) {
            throw new AssertionError("truncated input produced " + unexpected.getClass().getName()
                    + " instead of a clean IllegalArgumentException", unexpected);
        }
    }

    @Test
    void aTruncationInTheMiddleOfAFieldIsRejected() {
        byte[] full = wellFormed();
        byte[] midString = java.util.Arrays.copyOf(full, 3); // inside the "hello" payload

        assertThatThrownBy(() -> ScalarsV1.parseFrom(midString))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void truncatedLengthPrefixIsRejected() {
        byte[] lengthLies = {0x0a, 0x7f, 'a'}; // string field claiming 127 bytes, one byte present

        assertThatThrownBy(() -> ScalarsV1.parseFrom(lengthLies))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void fieldNumberZeroIsRejected() {
        byte[] zeroField = {0x00, 0x01};

        assertThatThrownBy(() -> ScalarsV1.parseFrom(zeroField))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field number 0");
    }

    @Test
    void groupWireTypesAreRejected() {
        byte[] startGroup = {0x0b}; // field 1, wire type 3

        assertThatThrownBy(() -> ScalarsV1.parseFrom(startGroup))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported wire type");
    }

    @Test
    void overlongVarintIsRejected() {
        byte[] elevenContinuationBytes = {
                0x08, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80};

        assertThatThrownBy(() -> ScalarsV1.parseFrom(elevenContinuationBytes))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeLengthPrefixIsRejected() {
        // 0xffffffff as a varint decodes to -1
        byte[] negativeLength = {0x0a, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x0f};

        assertThatThrownBy(() -> ScalarsV1.parseFrom(negativeLength))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownFieldsAreSkippedRatherThanRejected() {
        // forward compatibility: a field this build has never heard of must not break parsing
        byte[] withUnknown = {
                0x0a, 0x01, 'x',                      // field 1, string "x"
                (byte) 0xf8, 0x7f, 0x2a,              // field 2047, varint 42 - unknown here
                (byte) 0xfa, 0x7f, 0x02, 'h', 'i',    // field 2047, length delimited - unknown here
        };

        ScalarsV1 parsed = ScalarsV1.parseFrom(withUnknown);

        assertThat(parsed.text()).isEqualTo("x");
    }

    @Test
    void unknownFieldsAreNotPreservedOnReEncoding() {
        // a documented v1 limitation: records carry only their declared components
        byte[] withUnknown = {0x0a, 0x01, 'x', (byte) 0xf8, 0x7f, 0x2a};

        byte[] reEncoded = ScalarsV1.parseFrom(withUnknown).toByteArray();

        assertThat(reEncoded).containsExactly(0x0a, 0x01, 'x');
    }

    @Test
    void trailingGarbageInsideANestedMessageIsRejected() {
        byte[] childClaimsMoreThanItHas = {0x22, 0x05, 0x0a, 0x01, 'a'}; // field 4 length 5, only 3 bytes

        assertThatThrownBy(() -> NodeV1.parseFrom(childClaimsMoreThanItHas))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyInputParsesToAnEmptyMessage() {
        assertThatCode(() -> ScalarsV1.parseFrom(new byte[0])).doesNotThrowAnyException();
        assertThat(ScalarsV1.parseFrom(new byte[0])).isEqualTo(Scalars.empty().build());
    }
}
