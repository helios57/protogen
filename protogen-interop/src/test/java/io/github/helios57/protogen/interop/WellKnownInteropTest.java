package io.github.helios57.protogen.interop;

import com.google.protobuf.BoolValue;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import protogen.it.model.WellKnownV1;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The well-known types, against the ones {@code protobuf-java} ships.
 * <p>
 * protogen needs no import to understand them and generates no dependency to carry them, but the bytes
 * are the reference bytes: a {@code Duration} is {@code {seconds, nanos}}, a wrapper is a submessage with
 * one optional field. The Java surface is the only thing that differs - a wrapper is the nullable value
 * it exists to carry, rather than a one-field message to unwrap.
 */
class WellKnownInteropTest {

    /** Sets a component by index, because the record's canonical constructor takes sixteen of them. */
    private static WellKnownV1 with(java.util.function.Consumer<Object[]> set) {
        Object[] c = new Object[16];
        set.accept(c);
        return new WellKnownV1((Duration) c[0], (String) c[1], (Integer) c[2], (Long) c[3],
                (Boolean) c[4], (Double) c[5], (Float) c[6], (Integer) c[7], (Long) c[8], (byte[]) c[9],
                (protogen.it.model.Any) c[10], (protogen.it.model.Empty) c[11],
                (protogen.it.model.FieldMask) c[12], (protogen.it.model.Struct) c[13],
                (List<Duration>) c[14], (List<String>) c[15]);
    }

    private static protogen.it.official.WellKnownV1.Builder theirs() {
        return protogen.it.official.WellKnownV1.newBuilder();
    }

    // ------------------------------------------------------------- Duration

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, -1L, 315_576_000_000L, -315_576_000_000L})
    void everyDurationAgrees(long seconds) throws Exception {
        for (int nanos : new int[]{0, 1, 999_999_999}) {
            Duration took = Duration.ofSeconds(seconds, seconds < 0 ? -nanos : nanos);
            WellKnownV1 mine = with(c -> c[0] = took);
            com.google.protobuf.Duration reference = com.google.protobuf.Duration.newBuilder()
                    .setSeconds(protobufSeconds(took)).setNanos(protobufNanos(took)).build();

            assertThat(mine.toByteArray()).as("%ss %sns", seconds, nanos)
                    .isEqualTo(theirs().setTook(reference).build().toByteArray());
            assertThat(WellKnownV1.parseFrom(theirs().setTook(reference).build().toByteArray()).took())
                    .isEqualTo(took);
        }
    }

    /** protobuf gives both parts the same sign; java.time floors the seconds and keeps the nanos positive. */
    private static long protobufSeconds(Duration d) {
        return d.getSeconds() < 0 && d.getNano() > 0 ? d.getSeconds() + 1 : d.getSeconds();
    }

    private static int protobufNanos(Duration d) {
        return d.getSeconds() < 0 && d.getNano() > 0 ? d.getNano() - 1_000_000_000 : d.getNano();
    }

    @Test
    void aNegativeSubSecondDurationAgrees() throws Exception {
        // the awkward one: protobuf says 0s -500000000ns, java.time says -1s +500000000ns
        Duration half = Duration.ofMillis(-500);
        WellKnownV1 mine = with(c -> c[0] = half);
        com.google.protobuf.Duration reference = com.google.protobuf.Duration.newBuilder()
                .setSeconds(0).setNanos(-500_000_000).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs().setTook(reference).build().toByteArray());
        assertThat(WellKnownV1.parseFrom(mine.toByteArray()).took()).isEqualTo(half);
    }

    // ------------------------------------------------------------- wrappers

    @Test
    void everyWrapperAgrees() throws Exception {
        WellKnownV1 mine = with(c -> {
            c[1] = "note";
            c[2] = 7;
            c[3] = 8L;
            c[4] = true;
            c[5] = 1.5D;
            c[6] = 2.5F;
            c[7] = 80;
            c[8] = 90L;
            c[9] = new byte[]{1, 2, 3};
        });
        protogen.it.official.WellKnownV1 theirs = theirs()
                .setNote(StringValue.of("note"))
                .setAttempts(Int32Value.of(7))
                .setBytesRead(Int64Value.of(8L))
                .setVerified(BoolValue.of(true))
                .setRatio(DoubleValue.of(1.5D))
                .setWeight(FloatValue.of(2.5F))
                .setPort(UInt32Value.of(80))
                .setOffset(UInt64Value.of(90L))
                .setDigest(BytesValue.of(com.google.protobuf.ByteString.copyFrom(new byte[]{1, 2, 3})))
                .build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());

        WellKnownV1 back = WellKnownV1.parseFrom(theirs.toByteArray());
        assertThat(back.note()).isEqualTo("note");
        assertThat(back.attempts()).isEqualTo(7);
        assertThat(back.bytesRead()).isEqualTo(8L);
        assertThat(back.verified()).isTrue();
        assertThat(back.ratio()).isEqualTo(1.5D);
        assertThat(back.weight()).isEqualTo(2.5F);
        assertThat(back.port()).isEqualTo(80);
        assertThat(back.offset()).isEqualTo(90L);
        assertThat(back.digest()).containsExactly(1, 2, 3);
    }

    @Test
    void aWrapperCarryingItsDefaultIsPresentButEmpty() throws Exception {
        // the whole point of a wrapper: "" is a value, absent is not the same thing
        WellKnownV1 mine = with(c -> c[1] = "");
        protogen.it.official.WellKnownV1 theirs = theirs().setNote(StringValue.of("")).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray()).isNotEmpty();
        assertThat(WellKnownV1.parseFrom(theirs.toByteArray()).note()).isEmpty();
    }

    @Test
    void anAbsentWrapperIsAbsentOnBothSides() {
        assertThat(with(c -> {
        }).toByteArray()).isEqualTo(theirs().build().toByteArray()).isEmpty();
    }

    @Test
    void repeatedWrappersAndDurationsAgree() throws Exception {
        List<Duration> backoff = List.of(Duration.ofSeconds(1), Duration.ZERO);
        List<String> tags = List.of("a", "");
        WellKnownV1 mine = with(c -> {
            c[14] = backoff;
            c[15] = tags;
        });
        protogen.it.official.WellKnownV1 theirs = theirs()
                .addBackoff(com.google.protobuf.Duration.newBuilder().setSeconds(1).build())
                .addBackoff(com.google.protobuf.Duration.getDefaultInstance())
                .addTags(StringValue.of("a"))
                .addTags(StringValue.of(""))
                .build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        WellKnownV1 back = WellKnownV1.parseFrom(theirs.toByteArray());
        assertThat(back.backoff()).isEqualTo(backoff);
        assertThat(back.tags()).isEqualTo(tags);
    }

    // ------------------------------------- the ones generated as records

    @Test
    void anAnyAgrees() throws Exception {
        protogen.it.model.Any any = new protogen.it.model.Any(
                "type.googleapis.com/example.Thing", new byte[]{9, 8, 7});
        WellKnownV1 mine = with(c -> c[10] = any);
        protogen.it.official.WellKnownV1 theirs = theirs().setPayload(com.google.protobuf.Any.newBuilder()
                .setTypeUrl("type.googleapis.com/example.Thing")
                .setValue(com.google.protobuf.ByteString.copyFrom(new byte[]{9, 8, 7}))
                .build()).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(WellKnownV1.parseFrom(theirs.toByteArray()).payload()).isEqualTo(any);
    }

    @Test
    void anEmptyAgrees() throws Exception {
        WellKnownV1 mine = with(c -> c[11] = new protogen.it.model.Empty());
        protogen.it.official.WellKnownV1 theirs = theirs()
                .setNothing(com.google.protobuf.Empty.getDefaultInstance()).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(WellKnownV1.parseFrom(theirs.toByteArray()).nothing()).isNotNull();
    }

    @Test
    void aFieldMaskAgrees() throws Exception {
        protogen.it.model.FieldMask mask = new protogen.it.model.FieldMask(List.of("a.b", "c"));
        WellKnownV1 mine = with(c -> c[12] = mask);
        protogen.it.official.WellKnownV1 theirs = theirs().setUpdateMask(
                com.google.protobuf.FieldMask.newBuilder().addPaths("a.b").addPaths("c").build()).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(WellKnownV1.parseFrom(theirs.toByteArray()).updateMask()).isEqualTo(mask);
    }

    @Test
    void aStructOfEveryValueKindAgrees() throws Exception {
        protogen.it.model.Struct struct = new protogen.it.model.Struct(new java.util.LinkedHashMap<>(
                java.util.Map.of("s", value(v -> v[2] = "text"))));
        WellKnownV1 mine = with(c -> c[13] = struct);
        protogen.it.official.WellKnownV1 theirs = theirs().setAttributes(
                com.google.protobuf.Struct.newBuilder().putFields("s",
                        com.google.protobuf.Value.newBuilder().setStringValue("text").build()).build())
                .build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(WellKnownV1.parseFrom(theirs.toByteArray()).attributes()).isEqualTo(struct);
    }

    @Test
    void aStructOfNestedValuesAgrees() throws Exception {
        // a Value is a oneof over six kinds, two of which are recursive
        protogen.it.model.ListValue list = new protogen.it.model.ListValue(List.of(
                value(v -> v[1] = 1.5D), value(v -> v[3] = true)));
        protogen.it.model.Struct struct = new protogen.it.model.Struct(new java.util.LinkedHashMap<>(
                java.util.Map.of("l", value(v -> v[5] = list))));
        WellKnownV1 mine = with(c -> c[13] = struct);

        protogen.it.official.WellKnownV1 theirs = theirs().setAttributes(
                com.google.protobuf.Struct.newBuilder().putFields("l",
                        com.google.protobuf.Value.newBuilder().setListValue(
                                com.google.protobuf.ListValue.newBuilder()
                                        .addValues(com.google.protobuf.Value.newBuilder()
                                                .setNumberValue(1.5D))
                                        .addValues(com.google.protobuf.Value.newBuilder()
                                                .setBoolValue(true))).build()).build()).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(WellKnownV1.parseFrom(theirs.toByteArray()).attributes()).isEqualTo(struct);
    }

    @Test
    void aNullValueAgrees() throws Exception {
        protogen.it.model.Struct struct = new protogen.it.model.Struct(new java.util.LinkedHashMap<>(
                java.util.Map.of("n", value(v -> v[0] = protogen.it.model.NullValue.NULL_VALUE))));
        WellKnownV1 mine = with(c -> c[13] = struct);
        protogen.it.official.WellKnownV1 theirs = theirs().setAttributes(
                com.google.protobuf.Struct.newBuilder().putFields("n",
                        com.google.protobuf.Value.newBuilder()
                                .setNullValue(com.google.protobuf.NullValue.NULL_VALUE).build()).build())
                .build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(WellKnownV1.parseFrom(theirs.toByteArray()).attributes()).isEqualTo(struct);
    }

    private static protogen.it.model.Value value(java.util.function.Consumer<Object[]> set) {
        Object[] v = new Object[6];
        set.accept(v);
        return new protogen.it.model.Value((protogen.it.model.NullValue) v[0], (Double) v[1],
                (String) v[2], (Boolean) v[3], (protogen.it.model.Struct) v[4],
                (protogen.it.model.ListValue) v[5]);
    }
}
