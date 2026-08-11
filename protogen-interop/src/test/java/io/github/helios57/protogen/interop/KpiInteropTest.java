package io.github.helios57.protogen.interop;

import com.java.proto.model.proto.KpiCollectionV1;
import com.java.proto.model.proto.KpiV1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The real-world Kpi schema, encoded and decoded across both implementations. */
class KpiInteropTest {

    @Test
    void kpiAgreesByteForByte() throws Exception {
        KpiV1 mine = new KpiV1("jvm_memory_committed_bytes", Map.of("area", "heap"), 42.1,
                Map.of("TYPE", "seconds"));
        com.java.proto.official.KpiV1 theirs = com.java.proto.official.KpiV1.newBuilder()
                .setKey("jvm_memory_committed_bytes")
                .putLabel("area", "heap")
                .setValue(42.1)
                .putMeta("TYPE", "seconds")
                .build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(KpiV1.parseFrom(theirs.toByteArray())).isEqualTo(mine);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 1.0, -1.0, 42.1, -12.5, 1e308, -1e308, Double.MIN_VALUE})
    void everyMetricValueAgrees(double value) throws Exception {
        KpiV1 mine = new KpiV1("up", Map.of(), value, Map.of());
        com.java.proto.official.KpiV1 theirs = com.java.proto.official.KpiV1.newBuilder()
                .setKey("up").setValue(value).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(com.java.proto.official.KpiV1.parseFrom(mine.toByteArray()).getValue())
                .isEqualTo(value);
    }

    @Test
    void emptyMapsAgree() throws Exception {
        KpiV1 mine = new KpiV1("up", Map.of(), 1.0, Map.of());
        com.java.proto.official.KpiV1 theirs = com.java.proto.official.KpiV1.newBuilder()
                .setKey("up").setValue(1.0).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
    }

    @Test
    void collectionsAgreeAcrossFiles() throws Exception {
        KpiCollectionV1 mine = new KpiCollectionV1(List.of(
                new KpiV1("up", Map.of(), 1.0, Map.of()),
                new KpiV1("errors_total", Map.of("job", "api"), 7.0, Map.of())));

        com.java.proto.official.KpiCollectionV1 theirs =
                com.java.proto.official.KpiCollectionV1.newBuilder()
                        .addItems(com.java.proto.official.KpiV1.newBuilder()
                                .setKey("up").setValue(1.0).build())
                        .addItems(com.java.proto.official.KpiV1.newBuilder()
                                .setKey("errors_total").putLabel("job", "api").setValue(7.0).build())
                        .build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(KpiCollectionV1.parseFrom(theirs.toByteArray())).isEqualTo(mine);
    }

    @Test
    void protogenRejectsAKeyThatProtocHappilyProduces() throws Exception {
        // the reference implementation has no notion of @Pattern, so it will emit an invalid key.
        // protogen refusing to parse it is the point of generating validation.
        byte[] fromProtoc = com.java.proto.official.KpiV1.newBuilder()
                .setKey("0-not-a-valid-metric-name").setValue(1.0).build().toByteArray();

        assertThatThrownBy(() -> KpiV1.parseFrom(fromProtoc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@Pattern");
    }

    @Test
    void randomKpisAgreeInBothDirections() throws Exception {
        Random random = new Random(20260811L);
        String[] keys = {"up", "jvm_memory_committed_bytes", "_x", ":y", "errors_total", "a0:_Z"};

        for (int i = 0; i < 300; i++) {
            String key = keys[random.nextInt(keys.length)];
            double value = switch (random.nextInt(4)) {
                case 0 -> 0.0;
                case 1 -> random.nextDouble();
                case 2 -> -random.nextDouble() * 1e6;
                default -> random.nextLong();
            };
            // one entry per map keeps the encoding order deterministic on both sides
            Map<String, String> label = random.nextBoolean() ? Map.of("area", "heap") : Map.of();
            Map<String, String> meta = random.nextBoolean() ? Map.of("TYPE", "seconds") : Map.of();

            KpiV1 mine = new KpiV1(key, label, value, meta);
            com.java.proto.official.KpiV1.Builder b = com.java.proto.official.KpiV1.newBuilder()
                    .setKey(key).setValue(value).putAllLabel(label).putAllMeta(meta);
            com.java.proto.official.KpiV1 theirs = b.build();

            assertThat(mine.toByteArray()).as("case %d", i).isEqualTo(theirs.toByteArray());
            assertThat(KpiV1.parseFrom(theirs.toByteArray())).isEqualTo(mine);
        }
    }
}
