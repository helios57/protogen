package io.github.helios57.protogen.it;

import org.junit.jupiter.api.Test;
import protogen.it.model.ScalarsV1;
import protogen.it.optin.model.EnvelopeV1;
import protogen.it.optin.model.RelayedMessageV1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unknown-field preservation, generated from {@code src/main/proto-optin} with
 * {@code preserveUnknownFields = true} (see the {@code generate-opt-ins} execution in this module's pom).
 * <p>
 * The scenario it exists for: a relay reads the fields it owns and forwards the message on. Without
 * preservation, everything the relay's build has never heard of is silently dropped in transit.
 */
class UnknownFieldTest {

    /** field 9 varint 42, then field 10 length-delimited "hi" - neither is declared in relay.proto */
    private static final byte[] UNKNOWN_TAIL = {0x48, 0x2a, 0x52, 0x02, 'h', 'i'};

    private static byte[] withUnknownTail(byte[] known) {
        byte[] out = new byte[known.length + UNKNOWN_TAIL.length];
        System.arraycopy(known, 0, out, 0, known.length);
        System.arraycopy(UNKNOWN_TAIL, 0, out, known.length, UNKNOWN_TAIL.length);
        return out;
    }

    private static RelayedMessageV1 relayed() {
        return new RelayedMessageV1("order-4711", "tms.orders.v1", "tenant-a", 7L, null);
    }

    @Test
    void theRecordCarriesATrailingUnknownFieldsComponent() {
        RelayedMessageV1 message = relayed();

        assertThat(message.unknownFields()).isEmpty();
        assertThat(RelayedMessageV1.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("correlationId", "topic", "tenant", "sequence", "unknownFields");
    }

    @Test
    void unknownFieldsAreCapturedOnParse() {
        byte[] wire = withUnknownTail(relayed().toByteArray());

        RelayedMessageV1 parsed = RelayedMessageV1.parseFrom(wire);

        assertThat(parsed.correlationId()).isEqualTo("order-4711");
        assertThat(parsed.unknownFields()).containsExactly(UNKNOWN_TAIL);
    }

    @Test
    void unknownFieldsSurviveARoundTripUnchanged() {
        byte[] wire = withUnknownTail(relayed().toByteArray());

        byte[] relayedOn = RelayedMessageV1.parseFrom(wire).toByteArray();

        // known fields first, then the untouched tail - the same bytes went in and came out
        assertThat(relayedOn).isEqualTo(wire);
    }

    @Test
    void aSecondRoundTripIsStable() {
        byte[] wire = withUnknownTail(relayed().toByteArray());

        byte[] once = RelayedMessageV1.parseFrom(wire).toByteArray();
        byte[] twice = RelayedMessageV1.parseFrom(once).toByteArray();

        assertThat(twice).isEqualTo(once);
    }

    @Test
    void protoSizeAccountsForTheUnknownBytes() {
        RelayedMessageV1 parsed = RelayedMessageV1.parseFrom(withUnknownTail(relayed().toByteArray()));

        assertThat(parsed.toByteArray()).hasSize(parsed.protoSize());
        assertThat(parsed.protoSize()).isEqualTo(relayed().protoSize() + UNKNOWN_TAIL.length);
    }

    @Test
    void everyWireTypeCanBeCaptured() {
        byte[] allWireTypes = {
                0x48, 0x2a,                                     // field 9, varint
                0x51, 1, 2, 3, 4, 5, 6, 7, 8,                    // field 10, fixed64
                0x5a, 0x02, 'h', 'i',                            // field 11, length delimited
                0x65, 1, 2, 3, 4,                                // field 12, fixed32
        };

        EnvelopeV1 parsed = EnvelopeV1.parseFrom(allWireTypes);

        assertThat(parsed.unknownFields()).containsExactly(allWireTypes);
        assertThat(parsed.toByteArray()).isEqualTo(allWireTypes);
    }

    @Test
    void unknownFieldsParticipateInValueSemantics() {
        RelayedMessageV1 withTail = RelayedMessageV1.parseFrom(withUnknownTail(relayed().toByteArray()));
        RelayedMessageV1 withoutTail = relayed();
        RelayedMessageV1 sameTail = RelayedMessageV1.parseFrom(withUnknownTail(relayed().toByteArray()));

        assertThat(withTail).isNotEqualTo(withoutTail);
        assertThat(withTail).isEqualTo(sameTail).hasSameHashCodeAs(sameTail);
        assertThat(withTail.toString()).contains("unknownFields=");
    }

    @Test
    void theUnknownBytesAreDefensivelyCopied() {
        byte[] source = UNKNOWN_TAIL.clone();
        RelayedMessageV1 message = new RelayedMessageV1("a", "b", "c", 1L, source);

        source[0] = 0x7f;
        message.unknownFields()[1] = 0x7f;

        assertThat(message.unknownFields()).containsExactly(UNKNOWN_TAIL);
    }

    @Test
    void aMalformedUnknownFieldIsStillRejected() {
        byte[] truncated = {0x5a, 0x7f, 'h'}; // field 11 claims 127 bytes, one present

        assertThatThrownBy(() -> EnvelopeV1.parseFrom(truncated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void withoutTheOptInUnknownFieldsAreStillDropped() {
        // the default source root is generated without preservation; this pins that contrast
        byte[] wire = {0x0a, 0x01, 'x', (byte) 0xf8, 0x7f, 0x2a};

        assertThat(ScalarsV1.parseFrom(wire).toByteArray()).containsExactly(0x0a, 0x01, 'x');
        assertThat(ScalarsV1.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("unknownFields");
    }
}
