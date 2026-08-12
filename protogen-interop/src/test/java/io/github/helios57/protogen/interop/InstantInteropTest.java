package io.github.helios57.protogen.interop;

import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import protogen.it.model.EventV1;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * protogen surfaces {@code google.protobuf.Timestamp} as {@link Instant}, and puts it on the wire exactly
 * as {@code protoc} does: a submessage of {@code {int64 seconds = 1; int32 nanos = 2;}}.
 * <p>
 * Both sides compile the very same {@code .proto} text - only {@code java_package} is rewritten - so this
 * is a comparison of two encoders of one schema, not of two schemas that are believed to correspond.
 */
class InstantInteropTest {

    private static Timestamp reference(Instant when) {
        return Timestamp.newBuilder()
                .setSeconds(when.getEpochSecond())
                .setNanos(when.getNano())
                .build();
    }

    @Test
    void aTimestampIsByteIdenticalToProtocs() {
        Instant when = Instant.ofEpochSecond(1_760_000_000L, 123_456_789);
        EventV1 mine = new EventV1("e", when, null, List.of());
        protogen.it.official.EventV1 theirs = protogen.it.official.EventV1.newBuilder()
                .setEventId("e").setOccurredAt(reference(when)).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, -1L, 1_000L, -1_000L, 253_402_300_799L, -62_135_596_800L})
    void everySecondAgrees(long seconds) throws Exception {
        for (int nanos : new int[]{0, 1, 500_000_000, 999_999_999}) {
            Instant when = Instant.ofEpochSecond(seconds, nanos);
            EventV1 mine = new EventV1("", when, null, List.of());
            protogen.it.official.EventV1 theirs = protogen.it.official.EventV1.newBuilder()
                    .setOccurredAt(reference(when)).build();

            assertThat(mine.toByteArray()).as("%ss %sns", seconds, nanos).isEqualTo(theirs.toByteArray());
            assertThat(protogen.it.official.EventV1.parseFrom(mine.toByteArray()).getOccurredAt())
                    .isEqualTo(reference(when));
            assertThat(EventV1.parseFrom(theirs.toByteArray()).occurredAt()).isEqualTo(when);
        }
    }

    @Test
    void nanosecondPrecisionCrossesInBothDirections() throws Exception {
        // the old encoding was epoch millis, which silently dropped this
        Instant precise = Instant.ofEpochSecond(1_760_000_000L, 999_999_999);

        protogen.it.official.EventV1 theirs = protogen.it.official.EventV1
                .parseFrom(new EventV1("", precise, null, List.of()).toByteArray());

        assertThat(theirs.getOccurredAt().getNanos()).isEqualTo(999_999_999);
        assertThat(EventV1.parseFrom(theirs.toByteArray()).occurredAt()).isEqualTo(precise);
    }

    @Test
    void protocsTimestampDecodesIntoAnInstant() throws Exception {
        protogen.it.official.EventV1 theirs = protogen.it.official.EventV1.newBuilder()
                .setEventId("from-protoc")
                .setOccurredAt(Timestamp.newBuilder().setSeconds(42).setNanos(7).build())
                .build();

        EventV1 mine = EventV1.parseFrom(theirs.toByteArray());

        assertThat(mine.eventId()).isEqualTo("from-protoc");
        assertThat(mine.occurredAt()).isEqualTo(Instant.ofEpochSecond(42, 7));
    }

    @Test
    void theEpochIsWrittenBecauseTheFieldHasMessagePresence() {
        // both parts default, so the payload is empty - but the field itself is there, as protoc has it
        EventV1 mine = new EventV1("", Instant.EPOCH, null, List.of());
        protogen.it.official.EventV1 theirs = protogen.it.official.EventV1.newBuilder()
                .setOccurredAt(Timestamp.getDefaultInstance()).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray()).isNotEmpty();
    }

    @Test
    void anAbsentTimestampIsAbsentOnBothSides() {
        EventV1 mine = new EventV1("x", null, null, List.of());
        protogen.it.official.EventV1 theirs = protogen.it.official.EventV1.newBuilder()
                .setEventId("x").build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
    }

    @Test
    void anOptionalTimestampAgrees() throws Exception {
        Instant when = Instant.ofEpochSecond(5, 5);
        EventV1 mine = new EventV1("", null, when, List.of());
        protogen.it.official.EventV1 theirs = protogen.it.official.EventV1.newBuilder()
                .setAcknowledgedAt(reference(when)).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(EventV1.parseFrom(theirs.toByteArray()).acknowledgedAt()).isEqualTo(when);
    }

    @Test
    void repeatedTimestampsAgree() throws Exception {
        List<Instant> retries = List.of(Instant.ofEpochSecond(1, 1), Instant.EPOCH,
                Instant.ofEpochSecond(-1, 999_999_999));
        EventV1 mine = new EventV1("", null, null, retries);
        protogen.it.official.EventV1.Builder theirs = protogen.it.official.EventV1.newBuilder();
        retries.forEach(when -> theirs.addRetryAt(reference(when)));

        assertThat(mine.toByteArray()).isEqualTo(theirs.build().toByteArray());
        assertThat(EventV1.parseFrom(theirs.build().toByteArray()).retryAt()).isEqualTo(retries);
    }
}
