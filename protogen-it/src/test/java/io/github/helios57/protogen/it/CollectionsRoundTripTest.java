package io.github.helios57.protogen.it;

import org.junit.jupiter.api.Test;
import protogen.it.model.NodeV1;
import protogen.it.model.StageEnumV1;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Repeated fields, packed encoding and maps. */
class CollectionsRoundTripTest {

    private static NodeV1 node() {
        return new NodeV1("n", StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED, null, List.of(), List.of(),
                List.of(), Map.of(), null, NodeV1.KindV1.KIND_V1_UNSPECIFIED, Map.of(), Map.of());
    }

    private static NodeV1 roundTrip(NodeV1 message) {
        assertThat(message.toByteArray()).hasSize(message.protoSize());
        return NodeV1.parseFrom(message.toByteArray());
    }

    @Test
    void emptyCollectionsAreNotWritten() {
        NodeV1 message = new NodeV1("", StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED, null, List.of(),
                List.of(), List.of(), Map.of(), null, NodeV1.KindV1.KIND_V1_UNSPECIFIED, Map.of(), Map.of());

        assertThat(message.toByteArray()).isEmpty();
    }

    @Test
    void repeatedNumericFieldsArePackedIntoOneLengthDelimitedRun() {
        NodeV1 message = withPorts(List.of(1, 2, 3, 300));

        int name = 1 + 1 + 1;                 // tag, length, one byte of content
        int payload = 1 + 1 + 1 + 2;          // values 1, 2, 3 and 300 as varints
        int ports = 1 + 1 + payload;          // one tag and one length prefix for the whole run
        assertThat(message.protoSize()).isEqualTo(name + ports);
        assertThat(roundTrip(message).ports()).containsExactly(1, 2, 3, 300);
    }

    @Test
    void repeatedStringsGetOneTagEach() {
        NodeV1 message = withTags(List.of("a", "bb", ""));

        assertThat(roundTrip(message).tags()).containsExactly("a", "bb", "");
    }

    @Test
    void repeatedNumericFieldsAcceptUnpackedInput() {
        // a writer that does not pack is still valid protobuf and must be accepted
        byte[] unpacked = {
                0x28, 0x01, // field 5, varint, value 1
                0x28, 0x02, // field 5, varint, value 2
        };

        assertThat(NodeV1.parseFrom(unpacked).ports()).containsExactly(1, 2);
    }

    @Test
    void repeatedNumericFieldsAcceptAMixOfPackedAndUnpacked() {
        byte[] mixed = {
                0x2a, 0x02, 0x01, 0x02, // field 5, packed, values 1 and 2
                0x28, 0x03,             // field 5, unpacked, value 3
        };

        assertThat(NodeV1.parseFrom(mixed).ports()).containsExactly(1, 2, 3);
    }

    @Test
    void repeatedMessagesRoundTrip() {
        NodeV1 child = withTags(List.of("child"));
        NodeV1 parent = withChildren(List.of(child, child));

        assertThat(roundTrip(parent).children()).containsExactly(child, child);
    }

    @Test
    void deeplyNestedRecursionRoundTrips() {
        NodeV1 message = node();
        for (int i = 0; i < 20; i++) {
            message = withChildren(List.of(message));
        }

        assertThat(roundTrip(message)).isEqualTo(message);
    }

    @Test
    void stringMapRoundTripsAndPreservesInsertionOrder() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("primary", "https://a.example");
        endpoints.put("backup", "https://b.example");
        NodeV1 message = withEndpoints(endpoints);

        NodeV1 parsed = roundTrip(message);
        assertThat(parsed.endpoints()).containsExactlyEntriesOf(endpoints);
        assertThat(parsed.endpoints().keySet()).containsExactly("primary", "backup");
    }

    @Test
    void mapEntriesAtTheirDefaultsAreStillTransmitted() {
        // protoc always writes both key and value of a map entry, so an empty value must survive
        NodeV1 message = withEndpoints(Map.of("", ""));

        assertThat(roundTrip(message).endpoints()).containsEntry("", "");
    }

    @Test
    void messageValuedMapRoundTrips() {
        NodeV1.CoordinatesV1 bern = new NodeV1.CoordinatesV1(46.95, 7.44);
        NodeV1 message = new NodeV1("n", StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED, null, List.of(),
                List.of(), List.of(), Map.of(), null, NodeV1.KindV1.KIND_V1_UNSPECIFIED,
                Map.of("bern", bern), Map.of());

        assertThat(roundTrip(message).namedLocations()).containsEntry("bern", bern);
    }

    @Test
    void integerKeyedMapRoundTrips() {
        NodeV1 message = new NodeV1("n", StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED, null, List.of(),
                List.of(), List.of(), Map.of(), null, NodeV1.KindV1.KIND_V1_UNSPECIFIED, Map.of(),
                Map.of(7, true, 9, false));

        assertThat(roundTrip(message).flagsByCode()).containsEntry(7, true).containsEntry(9, false);
    }

    @Test
    void collectionsAreUnmodifiable() {
        NodeV1 message = withPorts(List.of(1, 2));

        assertThatThrownBy(() -> message.ports().add(3)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> message.endpoints().put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aMapEntryCannotBeWrittenThroughEither() {
        // a parsed map is handed over rather than copied, so it has to be unmodifiable the whole way down:
        // Entry.setValue writes straight through an entry set that was only wrapped one level deep
        NodeV1 message = NodeV1.parseFrom(
                new NodeV1("n", null, null, null, null, null, Map.of("k", "v"), null, null, null, null)
                        .toByteArray());

        assertThatThrownBy(() -> message.endpoints().entrySet().iterator().next().setValue("other"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(message.endpoints()).containsExactly(java.util.Map.entry("k", "v"));
    }

    @Test
    void aMapPassedInIsCopiedRatherThanHeld() {
        java.util.Map<String, String> mutable = new java.util.LinkedHashMap<>(Map.of("k", "v"));
        NodeV1 message = new NodeV1("n", null, null, null, null, null, mutable, null, null, null, null);

        mutable.put("added", "later");

        assertThat(message.endpoints()).containsExactly(java.util.Map.entry("k", "v"));
    }

    @Test
    void nullCollectionsAreNormalisedToEmptyOnes() {
        NodeV1 message = new NodeV1("n", null, null, null, null, null, null, null, null, null, null);

        assertThat(message.children()).isEmpty();
        assertThat(message.ports()).isEmpty();
        assertThat(message.tags()).isEmpty();
        assertThat(message.endpoints()).isEmpty();
        assertThat(message.stage()).isEqualTo(StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED);
    }

    @Test
    void mutatingTheSourceCollectionDoesNotAffectTheMessage() {
        List<Integer> ports = new java.util.ArrayList<>(List.of(1, 2));
        NodeV1 message = withPorts(ports);

        ports.add(3);

        assertThat(message.ports()).containsExactly(1, 2);
    }

    // -------------------------------------------------------------- helpers

    private static NodeV1 withPorts(List<Integer> ports) {
        return new NodeV1("n", StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED, null, List.of(), ports,
                List.of(), Map.of(), null, NodeV1.KindV1.KIND_V1_UNSPECIFIED, Map.of(), Map.of());
    }

    private static NodeV1 withTags(List<String> tags) {
        return new NodeV1("n", StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED, null, List.of(), List.of(),
                tags, Map.of(), null, NodeV1.KindV1.KIND_V1_UNSPECIFIED, Map.of(), Map.of());
    }

    private static NodeV1 withChildren(List<NodeV1> children) {
        return new NodeV1("n", StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED, null, children, List.of(),
                List.of(), Map.of(), null, NodeV1.KindV1.KIND_V1_UNSPECIFIED, Map.of(), Map.of());
    }

    private static NodeV1 withEndpoints(Map<String, String> endpoints) {
        return new NodeV1("n", StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED, null, List.of(), List.of(),
                List.of(), endpoints, null, NodeV1.KindV1.KIND_V1_UNSPECIFIED, Map.of(), Map.of());
    }
}
