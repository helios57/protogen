package io.github.helios57.protogen.interop;

import com.google.protobuf.ByteString;
import io.github.helios57.protogen.it.Scalars;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import protogen.it.model.NodeV1;
import protogen.it.model.ScalarsV1;
import protogen.it.model.StageEnumV1;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fuzzes random messages through both implementations.
 * <p>
 * Seeds are fixed so a failure is reproducible: the seed appears in the test name, and re-running that
 * case replays exactly the same messages.
 */
class RandomizedDifferentialTest {

    private static final int CASES_PER_SEED = 250;

    @DisplayName("random scalar messages agree with protoc")
    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1L, 2L, 3L, 20260811L, -7L})
    void randomScalarMessagesAgree(long seed) throws Exception {
        Random random = new Random(seed);

        for (int i = 0; i < CASES_PER_SEED; i++) {
            ScalarsV1 mine = randomScalars(random);
            protogen.it.official.ScalarsV1 theirs = equivalent(mine);

            assertThat(mine.toByteArray())
                    .as("case %d of seed %d: %s", i, seed, mine)
                    .isEqualTo(theirs.toByteArray());

            // protoc parses what protogen wrote
            assertThat(protogen.it.official.ScalarsV1.parseFrom(mine.toByteArray())).isEqualTo(theirs);
            // and protogen parses what protoc wrote
            assertThat(ScalarsV1.parseFrom(theirs.toByteArray())).isEqualTo(mine);
        }
    }

    @DisplayName("random nested trees agree with protoc")
    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {11L, 12L, 13L})
    void randomNestedMessagesAgree(long seed) throws Exception {
        Random random = new Random(seed);

        for (int i = 0; i < 100; i++) {
            NodeV1 mine = randomNode(random, 3);
            protogen.it.official.NodeV1 theirs = equivalent(mine);

            assertThat(mine.toByteArray())
                    .as("case %d of seed %d", i, seed)
                    .isEqualTo(theirs.toByteArray());
            assertThat(NodeV1.parseFrom(theirs.toByteArray())).isEqualTo(mine);
        }
    }

    // ------------------------------------------------------------ generation

    private static ScalarsV1 randomScalars(Random r) {
        Scalars s = Scalars.empty()
                .text(randomString(r))
                .flag(r.nextBoolean())
                .i32(randomInt(r))
                .i64(randomLong(r))
                .u32(randomInt(r))
                .u64(randomLong(r))
                .s32(randomInt(r))
                .s64(randomLong(r))
                .f32(randomInt(r))
                .f64(randomLong(r))
                .sf32(randomInt(r))
                .sf64(randomLong(r))
                .real32(r.nextBoolean() ? 0F : r.nextFloat())
                .real64(r.nextBoolean() ? 0D : r.nextDouble())
                .blob(randomBytes(r))
                .wideTagText(randomString(r))
                .wideTagNumber(randomInt(r));
        if (r.nextBoolean()) {
            s.optionalText(randomString(r));
        }
        if (r.nextBoolean()) {
            s.optionalNumber(randomInt(r));
        }
        if (r.nextBoolean()) {
            s.optionalFlag(r.nextBoolean());
        }
        if (r.nextBoolean()) {
            s.optionalReal(r.nextDouble());
        }
        if (r.nextBoolean()) {
            s.optionalBlob(randomBytes(r));
        }
        return s.build();
    }

    private static protogen.it.official.ScalarsV1 equivalent(ScalarsV1 m) {
        protogen.it.official.ScalarsV1.Builder b = protogen.it.official.ScalarsV1.newBuilder()
                .setText(m.text()).setFlag(m.flag()).setI32(m.i32()).setI64(m.i64())
                .setU32(m.u32()).setU64(m.u64()).setS32(m.s32()).setS64(m.s64())
                .setF32(m.f32()).setF64(m.f64()).setSf32(m.sf32()).setSf64(m.sf64())
                .setReal32(m.real32()).setReal64(m.real64())
                .setBlob(ByteString.copyFrom(m.blob()))
                .setWideTagText(m.wideTagText()).setWideTagNumber(m.wideTagNumber());
        if (m.optionalText() != null) {
            b.setOptionalText(m.optionalText());
        }
        if (m.optionalNumber() != null) {
            b.setOptionalNumber(m.optionalNumber());
        }
        if (m.optionalFlag() != null) {
            b.setOptionalFlag(m.optionalFlag());
        }
        if (m.optionalReal() != null) {
            b.setOptionalReal(m.optionalReal());
        }
        if (m.optionalBlob() != null) {
            b.setOptionalBlob(ByteString.copyFrom(m.optionalBlob()));
        }
        return b.build();
    }

    private static NodeV1 randomNode(Random r, int depth) {
        List<NodeV1> children = new ArrayList<>();
        if (depth > 0) {
            for (int i = r.nextInt(3); i > 0; i--) {
                children.add(randomNode(r, depth - 1));
            }
        }
        List<Integer> ports = new ArrayList<>();
        for (int i = r.nextInt(4); i > 0; i--) {
            ports.add(randomInt(r));
        }
        List<String> tags = new ArrayList<>();
        for (int i = r.nextInt(3); i > 0; i--) {
            tags.add(randomString(r));
        }
        // a single entry keeps the encoding order deterministic on both sides
        Map<String, String> endpoints = new LinkedHashMap<>();
        if (r.nextBoolean()) {
            endpoints.put(randomString(r), randomString(r));
        }
        NodeV1.CoordinatesV1 location = r.nextBoolean()
                ? new NodeV1.CoordinatesV1(r.nextDouble(), r.nextDouble())
                : null;
        StageEnumV1 stage = StageEnumV1.forNumber(r.nextInt(5));
        NodeV1.KindV1 kind = NodeV1.KindV1.forNumber(r.nextInt(3));
        String suffix = r.nextBoolean() ? randomString(r) : null;

        return new NodeV1(randomString(r), stage, suffix, children, ports, tags, endpoints, location,
                kind, Map.of(), Map.of());
    }

    private static protogen.it.official.NodeV1 equivalent(NodeV1 m) {
        protogen.it.official.NodeV1.Builder b = protogen.it.official.NodeV1.newBuilder()
                .setName(m.name())
                .setStage(protogen.it.official.StageEnumV1.forNumber(m.stage().number()))
                .setKind(protogen.it.official.NodeV1.KindV1.forNumber(m.kind().number()))
                .addAllPorts(m.ports())
                .addAllTags(m.tags())
                .putAllEndpoints(m.endpoints());
        if (m.stageSuffix() != null) {
            b.setStageSuffix(m.stageSuffix());
        }
        if (m.location() != null) {
            b.setLocation(protogen.it.official.NodeV1.CoordinatesV1.newBuilder()
                    .setLatitude(m.location().latitude())
                    .setLongitude(m.location().longitude())
                    .build());
        }
        for (NodeV1 child : m.children()) {
            b.addChildren(equivalent(child));
        }
        return b.build();
    }

    private static int randomInt(Random r) {
        return switch (r.nextInt(5)) {
            case 0 -> 0;
            case 1 -> r.nextInt(128);
            case 2 -> -r.nextInt(128) - 1;
            case 3 -> r.nextInt();
            default -> r.nextBoolean() ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        };
    }

    private static long randomLong(Random r) {
        return switch (r.nextInt(5)) {
            case 0 -> 0L;
            case 1 -> r.nextInt(128);
            case 2 -> -r.nextInt(128) - 1;
            case 3 -> r.nextLong();
            default -> r.nextBoolean() ? Long.MAX_VALUE : Long.MIN_VALUE;
        };
    }

    private static String randomString(Random r) {
        int length = r.nextInt(40);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(switch (r.nextInt(4)) {
                case 0 -> (char) ('a' + r.nextInt(26));
                case 1 -> 'ä';
                case 2 -> '語';
                default -> ' ';
            });
        }
        return sb.toString();
    }

    private static byte[] randomBytes(Random r) {
        byte[] bytes = new byte[r.nextInt(20)];
        r.nextBytes(bytes);
        return bytes;
    }
}
