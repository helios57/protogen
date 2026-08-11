package io.github.helios57.protogen.interop;

import org.junit.jupiter.api.Test;
import protogen.it.model.NodeV1;
import protogen.it.model.NodesV1;
import protogen.it.model.StageEnumV1;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Repeated fields, packed encoding, maps and nested messages, against the reference implementation. */
class CollectionsInteropTest {

    private static NodeV1 mine(List<Integer> ports, List<String> tags, List<NodeV1> children,
                               Map<String, String> endpoints) {
        return new NodeV1("n", StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED, null, children, ports, tags,
                endpoints, null, NodeV1.KindV1.KIND_V1_UNSPECIFIED, Map.of(), Map.of());
    }

    private static protogen.it.official.NodeV1.Builder theirs() {
        return protogen.it.official.NodeV1.newBuilder().setName("n");
    }

    @Test
    void packedRepeatedNumbersAgreeByteForByte() throws Exception {
        NodeV1 mine = mine(List.of(1, 2, 3, 300), List.of(), List.of(), Map.of());
        protogen.it.official.NodeV1 theirs = theirs().addAllPorts(List.of(1, 2, 3, 300)).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(protogen.it.official.NodeV1.parseFrom(mine.toByteArray()).getPortsList())
                .containsExactly(1, 2, 3, 300);
        assertThat(NodeV1.parseFrom(theirs.toByteArray()).ports()).containsExactly(1, 2, 3, 300);
    }

    @Test
    void emptyRepeatedFieldsAgree() throws Exception {
        NodeV1 mine = mine(List.of(), List.of(), List.of(), Map.of());
        protogen.it.official.NodeV1 theirs = theirs().build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
    }

    @Test
    void repeatedStringsAgreeByteForByte() throws Exception {
        List<String> tags = List.of("a", "bb", "");
        NodeV1 mine = mine(List.of(), tags, List.of(), Map.of());
        protogen.it.official.NodeV1 theirs = theirs().addAllTags(tags).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(protogen.it.official.NodeV1.parseFrom(mine.toByteArray()).getTagsList())
                .containsExactlyElementsOf(tags);
    }

    @Test
    void protocAcceptsUnpackedInputFromNobodyButStillAgreesOnOurOutput() throws Exception {
        // protogen always packs, exactly as protoc does; the reference parser must accept our bytes
        NodeV1 mine = mine(List.of(1, 2), List.of(), List.of(), Map.of());

        assertThat(protogen.it.official.NodeV1.parseFrom(mine.toByteArray()).getPortsList())
                .containsExactly(1, 2);
    }

    @Test
    void nestedAndRepeatedMessagesAgree() throws Exception {
        NodeV1 child = mine(List.of(9), List.of("c"), List.of(), Map.of());
        NodeV1 mine = mine(List.of(), List.of(), List.of(child, child), Map.of());

        protogen.it.official.NodeV1 theirsChild = theirs().addPorts(9).addTags("c").build();
        protogen.it.official.NodeV1 theirs = theirs().addChildren(theirsChild).addChildren(theirsChild).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(NodeV1.parseFrom(theirs.toByteArray())).isEqualTo(mine);
    }

    @Test
    void deepRecursionAgrees() throws Exception {
        NodeV1 mine = mine(List.of(), List.of(), List.of(), Map.of());
        protogen.it.official.NodeV1 theirs = theirs().build();
        for (int i = 0; i < 15; i++) {
            mine = mine(List.of(), List.of(), List.of(mine), Map.of());
            theirs = theirs().addChildren(theirs).build();
        }

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(NodeV1.parseFrom(theirs.toByteArray())).isEqualTo(mine);
    }

    @Test
    void singleEntryMapAgreesByteForByte() throws Exception {
        NodeV1 mine = mine(List.of(), List.of(), List.of(), Map.of("primary", "https://a.example"));
        protogen.it.official.NodeV1 theirs = theirs().putEndpoints("primary", "https://a.example").build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
    }

    @Test
    void mapEntryWithDefaultValuedKeyAndValueAgrees() throws Exception {
        // protoc writes both key and value of a map entry even at their defaults
        NodeV1 mine = mine(List.of(), List.of(), List.of(), Map.of("", ""));
        protogen.it.official.NodeV1 theirs = theirs().putEndpoints("", "").build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(protogen.it.official.NodeV1.parseFrom(mine.toByteArray()).getEndpointsMap())
                .containsEntry("", "");
    }

    @Test
    void multiEntryMapsAgreeSemantically() throws Exception {
        // map entry order is unspecified in protobuf, so compare contents rather than bytes
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("primary", "https://a.example");
        endpoints.put("backup", "https://b.example");
        NodeV1 mine = mine(List.of(), List.of(), List.of(), endpoints);

        assertThat(protogen.it.official.NodeV1.parseFrom(mine.toByteArray()).getEndpointsMap())
                .containsExactlyInAnyOrderEntriesOf(endpoints);

        protogen.it.official.NodeV1 theirs = theirs().putAllEndpoints(endpoints).build();
        assertThat(NodeV1.parseFrom(theirs.toByteArray()).endpoints())
                .containsExactlyInAnyOrderEntriesOf(endpoints);
    }

    @Test
    void messageValuedMapAgrees() throws Exception {
        NodeV1.CoordinatesV1 bern = new NodeV1.CoordinatesV1(46.95, 7.44);
        NodeV1 mine = new NodeV1("n", StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED, null, List.of(), List.of(),
                List.of(), Map.of(), null, NodeV1.KindV1.KIND_V1_UNSPECIFIED, Map.of("bern", bern), Map.of());

        protogen.it.official.NodeV1 theirs = theirs()
                .putNamedLocations("bern", protogen.it.official.NodeV1.CoordinatesV1.newBuilder()
                        .setLatitude(46.95).setLongitude(7.44).build())
                .build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(NodeV1.parseFrom(theirs.toByteArray())).isEqualTo(mine);
    }

    @Test
    void integerKeyedMapAgrees() throws Exception {
        NodeV1 mine = new NodeV1("n", StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED, null, List.of(), List.of(),
                List.of(), Map.of(), null, NodeV1.KindV1.KIND_V1_UNSPECIFIED, Map.of(), Map.of(7, true));
        protogen.it.official.NodeV1 theirs = theirs().putFlagsByCode(7, true).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
    }

    @Test
    void nestedMessageFieldAgrees() throws Exception {
        NodeV1.CoordinatesV1 location = new NodeV1.CoordinatesV1(46.95, 7.44);
        NodeV1 mine = new NodeV1("n", StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED, null, List.of(), List.of(),
                List.of(), Map.of(), location, NodeV1.KindV1.KIND_V1_UNSPECIFIED, Map.of(), Map.of());
        protogen.it.official.NodeV1 theirs = theirs()
                .setLocation(protogen.it.official.NodeV1.CoordinatesV1.newBuilder()
                        .setLatitude(46.95).setLongitude(7.44).build())
                .build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
    }

    @Test
    void anEmptyNestedMessageIsStillTransmitted() throws Exception {
        NodeV1.CoordinatesV1 origin = new NodeV1.CoordinatesV1(0, 0);
        NodeV1 mine = new NodeV1("", StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED, null, List.of(), List.of(),
                List.of(), Map.of(), origin, NodeV1.KindV1.KIND_V1_UNSPECIFIED, Map.of(), Map.of());
        protogen.it.official.NodeV1 theirs = protogen.it.official.NodeV1.newBuilder()
                .setLocation(protogen.it.official.NodeV1.CoordinatesV1.newBuilder().build())
                .build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(protogen.it.official.NodeV1.parseFrom(mine.toByteArray()).hasLocation()).isTrue();
    }

    @Test
    void topLevelWrapperOfRepeatedMessagesAgrees() throws Exception {
        NodeV1 child = mine(List.of(1), List.of(), List.of(), Map.of());
        NodesV1 mine = new NodesV1(List.of(child));
        protogen.it.official.NodesV1 theirs = protogen.it.official.NodesV1.newBuilder()
                .addNodes(theirs().addPorts(1).build()).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(NodesV1.parseFrom(theirs.toByteArray())).isEqualTo(mine);
    }
}
