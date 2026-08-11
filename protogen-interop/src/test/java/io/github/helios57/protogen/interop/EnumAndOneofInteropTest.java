package io.github.helios57.protogen.interop;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import protogen.it.model.AliasedEnumV1;
import protogen.it.model.EnumHolderV1;
import protogen.it.model.NodeV1;
import protogen.it.model.PayloadV1;
import protogen.it.model.StageEnumV1;
import protogen.it.model.StatusEnumV1;
import protogen.it.model.Wrapped;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Enums, oneofs and the outer-class wrapper, against the reference implementation. */
class EnumAndOneofInteropTest {

    @Test
    void enumsAgreeByteForByte() throws Exception {
        EnumHolderV1 mine = new EnumHolderV1(StageEnumV1.PROD, StatusEnumV1.active_released,
                AliasedEnumV1.ORIGINAL, StageEnumV1.DEV, List.of(StageEnumV1.DEV, StageEnumV1.PROD));
        protogen.it.official.EnumHolderV1 theirs = protogen.it.official.EnumHolderV1.newBuilder()
                .setStage(protogen.it.official.StageEnumV1.PROD)
                .setStatus(protogen.it.official.StatusEnumV1.active_released)
                .setAliased(protogen.it.official.AliasedEnumV1.ORIGINAL)
                .setOptionalStage(protogen.it.official.StageEnumV1.DEV)
                .addStages(protogen.it.official.StageEnumV1.DEV)
                .addStages(protogen.it.official.StageEnumV1.PROD)
                .build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(EnumHolderV1.parseFrom(theirs.toByteArray())).isEqualTo(mine);
    }

    @Test
    void zeroValuedEnumsAgree() throws Exception {
        EnumHolderV1 mine = new EnumHolderV1(StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED, StatusEnumV1.passive,
                AliasedEnumV1.ALIAS_UNSPECIFIED, null, List.of());

        assertThat(mine.toByteArray())
                .isEqualTo(protogen.it.official.EnumHolderV1.newBuilder().build().toByteArray());
    }

    @Test
    void repeatedEnumsArePackedTheSameWay() throws Exception {
        EnumHolderV1 mine = new EnumHolderV1(StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED, StatusEnumV1.passive,
                AliasedEnumV1.ALIAS_UNSPECIFIED, null,
                List.of(StageEnumV1.DEV, StageEnumV1.TEST, StageEnumV1.PROD));
        protogen.it.official.EnumHolderV1 theirs = protogen.it.official.EnumHolderV1.newBuilder()
                .addStages(protogen.it.official.StageEnumV1.DEV)
                .addStages(protogen.it.official.StageEnumV1.TEST)
                .addStages(protogen.it.official.StageEnumV1.PROD)
                .build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
    }

    @Test
    void anEnumValueFromANewerSchemaIsToleratedByBothSides() throws Exception {
        // protoc keeps the raw number; protogen reports UNRECOGNIZED. Neither may fail to parse.
        byte[] fromTheFuture = {0x08, 0x63};

        assertThat(EnumHolderV1.parseFrom(fromTheFuture).stage()).isEqualTo(StageEnumV1.UNRECOGNIZED);
        assertThat(protogen.it.official.EnumHolderV1.parseFrom(fromTheFuture).getStageValue())
                .isEqualTo(99);
    }

    @Test
    void aliasedEnumsAgree() throws Exception {
        EnumHolderV1 viaAlias = new EnumHolderV1(StageEnumV1.STAGE_ENUM_V1_UNSPECIFIED,
                StatusEnumV1.passive, AliasedEnumV1.SYNONYM, null, List.of());
        protogen.it.official.EnumHolderV1 theirs = protogen.it.official.EnumHolderV1.newBuilder()
                .setAliased(protogen.it.official.AliasedEnumV1.ORIGINAL).build();

        assertThat(viaAlias.toByteArray()).isEqualTo(theirs.toByteArray());
    }

    @Test
    void oneofMembersAgreeByteForByte() throws Exception {
        PayloadV1 text = new PayloadV1("s", "hello", null, null, null, "tail");
        protogen.it.official.PayloadV1 theirText = protogen.it.official.PayloadV1.newBuilder()
                .setSubject("s").setMessage("hello").setTrailer("tail").build();

        assertThat(text.toByteArray()).isEqualTo(theirText.toByteArray());
        assertThat(PayloadV1.parseFrom(theirText.toByteArray())).isEqualTo(text);
    }

    @Test
    void oneofMemberHoldingItsDefaultValueIsStillTransmitted() throws Exception {
        PayloadV1 mine = new PayloadV1("", "", null, null, null, "");
        protogen.it.official.PayloadV1 theirs = protogen.it.official.PayloadV1.newBuilder()
                .setMessage("").build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(protogen.it.official.PayloadV1.parseFrom(mine.toByteArray()).getBodyCase())
                .isEqualTo(protogen.it.official.PayloadV1.BodyCase.MESSAGE);
    }

    @Test
    void oneofMessageMemberAgrees() throws Exception {
        NodeV1 node = new NodeV1("n", StageEnumV1.PROD, null, List.of(), List.of(), List.of(),
                Map.of(), null, NodeV1.KindV1.BROKER, Map.of(), Map.of());
        PayloadV1 mine = new PayloadV1("s", null, null, node, null, "");

        protogen.it.official.PayloadV1 theirs = protogen.it.official.PayloadV1.newBuilder()
                .setSubject("s")
                .setNode(protogen.it.official.NodeV1.newBuilder()
                        .setName("n")
                        .setStage(protogen.it.official.StageEnumV1.PROD)
                        .setKind(protogen.it.official.NodeV1.KindV1.BROKER)
                        .build())
                .build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(PayloadV1.parseFrom(theirs.toByteArray())).isEqualTo(mine);
    }

    @Test
    void oneofBytesMemberAgrees() throws Exception {
        PayloadV1 mine = new PayloadV1("", null, null, null, new byte[]{1, 2, 3}, "");
        protogen.it.official.PayloadV1 theirs = protogen.it.official.PayloadV1.newBuilder()
                .setRaw(ByteString.copyFrom(new byte[]{1, 2, 3})).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
    }

    @Test
    void bothSidesAgreeThatTheLastOneofMemberOnTheWireWins() throws Exception {
        byte[] twoMembers = {0x12, 0x01, 'a', 0x18, 0x07};

        assertThat(PayloadV1.parseFrom(twoMembers).counter()).isEqualTo(7L);
        assertThat(PayloadV1.parseFrom(twoMembers).message()).isNull();
        assertThat(protogen.it.official.PayloadV1.parseFrom(twoMembers).getBodyCase())
                .isEqualTo(protogen.it.official.PayloadV1.BodyCase.COUNTER);
    }

    @Test
    void wrapperClassTypesAgree() throws Exception {
        Wrapped.WrappedHolderV1 mine = new Wrapped.WrappedHolderV1(
                Wrapped.OuterApiEnumV1.InnerStatusV1.RUNNING,
                new Wrapped.OuterApiEnumV1(Wrapped.OuterApiEnumV1.InnerStatusV1.STOPPED));

        protogen.it.official.Wrapped.WrappedHolderV1 theirs =
                protogen.it.official.Wrapped.WrappedHolderV1.newBuilder()
                        .setStatus(protogen.it.official.Wrapped.OuterApiEnumV1.InnerStatusV1.RUNNING)
                        .setWrapped(protogen.it.official.Wrapped.OuterApiEnumV1.newBuilder()
                                .setStatus(protogen.it.official.Wrapped.OuterApiEnumV1.InnerStatusV1.STOPPED)
                                .build())
                        .build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(Wrapped.WrappedHolderV1.parseFrom(theirs.toByteArray())).isEqualTo(mine);
    }
}
