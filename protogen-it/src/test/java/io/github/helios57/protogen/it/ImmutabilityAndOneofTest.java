package io.github.helios57.protogen.it;

import org.junit.jupiter.api.Test;
import protogen.it.model.EmptyV1;
import protogen.it.model.NodeV1;
import protogen.it.model.PayloadV1;
import protogen.it.model.ScalarsV1;
import protogen.it.model.StageEnumV1;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Value semantics of the generated records, oneof handling and the degenerate empty message. */
class ImmutabilityAndOneofTest {

    @Test
    void bytesComponentsCompareByValueNotIdentity() {
        ScalarsV1 a = Scalars.empty().blob(new byte[]{1, 2, 3}).build();
        ScalarsV1 b = Scalars.empty().blob(new byte[]{1, 2, 3}).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void differentBytesAreNotEqual() {
        ScalarsV1 a = Scalars.empty().blob(new byte[]{1, 2, 3}).build();
        ScalarsV1 b = Scalars.empty().blob(new byte[]{1, 2, 4}).build();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void toStringRendersBytesReadably() {
        ScalarsV1 message = Scalars.empty().blob(new byte[]{1, 2}).build();

        assertThat(message.toString()).contains("blob=[1, 2]");
    }

    @Test
    void mutatingTheSourceArrayDoesNotAffectTheMessage() {
        byte[] source = {1, 2, 3};
        ScalarsV1 message = Scalars.empty().blob(source).build();

        source[0] = 99;

        assertThat(message.blob()).containsExactly(1, 2, 3);
    }

    @Test
    void mutatingTheReturnedArrayDoesNotAffectTheMessage() {
        ScalarsV1 message = Scalars.empty().blob(new byte[]{1, 2, 3}).build();

        message.blob()[0] = 99;

        assertThat(message.blob()).containsExactly(1, 2, 3);
    }

    @Test
    void recordsWithoutBytesUseTheDefaultValueSemantics() {
        NodeV1 a = new NodeV1("n", StageEnumV1.DEV, null, List.of(), List.of(1), List.of(), Map.of(),
                null, NodeV1.KindV1.BROKER, Map.of(), Map.of());
        NodeV1 b = new NodeV1("n", StageEnumV1.DEV, null, List.of(), List.of(1), List.of(), Map.of(),
                null, NodeV1.KindV1.BROKER, Map.of(), Map.of());

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void emptyMessageIsGeneratedAndRoundTrips() {
        EmptyV1 message = new EmptyV1();

        assertThat(message.protoSize()).isZero();
        assertThat(EmptyV1.parseFrom(message.toByteArray())).isEqualTo(message);
    }

    @Test
    void oneofMembersAreNullableAndReportTheirCase() {
        PayloadV1 unset = new PayloadV1("s", null, null, null, null, "");
        PayloadV1 text = new PayloadV1("s", "hello", null, null, null, "");
        PayloadV1 counter = new PayloadV1("s", null, 7L, null, null, "");

        assertThat(unset.bodyCase()).isEqualTo(PayloadV1.BodyCase.NOT_SET);
        assertThat(text.bodyCase()).isEqualTo(PayloadV1.BodyCase.MESSAGE);
        assertThat(counter.bodyCase()).isEqualTo(PayloadV1.BodyCase.COUNTER);
    }

    @Test
    void oneofMembersRoundTripIncludingTheirDefaultValues() {
        PayloadV1 emptyString = new PayloadV1("s", "", null, null, null, "");

        PayloadV1 parsed = PayloadV1.parseFrom(emptyString.toByteArray());

        assertThat(parsed.message()).isEmpty();
        assertThat(parsed.bodyCase()).isEqualTo(PayloadV1.BodyCase.MESSAGE);
    }

    @Test
    void oneofMessageMemberFromAnotherFileRoundTrips() {
        NodeV1 node = new NodeV1("n", StageEnumV1.PROD, null, List.of(), List.of(), List.of(),
                Map.of(), null, NodeV1.KindV1.BROKER, Map.of(), Map.of());
        PayloadV1 message = new PayloadV1("s", null, null, node, null, "tail");

        PayloadV1 parsed = PayloadV1.parseFrom(message.toByteArray());

        assertThat(parsed.node()).isEqualTo(node);
        assertThat(parsed.trailer()).isEqualTo("tail");
        assertThat(parsed.bodyCase()).isEqualTo(PayloadV1.BodyCase.NODE);
    }

    @Test
    void lastOneofMemberOnTheWireWins() {
        // protobuf semantics: a stream setting two members leaves the last one set
        byte[] twoMembers = {0x12, 0x01, 'a', 0x18, 0x07}; // message = "a", then counter = 7

        PayloadV1 parsed = PayloadV1.parseFrom(twoMembers);

        assertThat(parsed.counter()).isEqualTo(7L);
    }
}
