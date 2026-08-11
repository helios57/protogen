package io.github.helios57.protogen.interop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import protogen.it.model.EventV1;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * protogen surfaces {@code google.protobuf.Timestamp} as {@link Instant} but puts it on the wire as an
 * {@code int64} of epoch milliseconds.
 * <p>
 * That is a deliberate deviation from the reference encoding, which uses a seconds+nanos submessage. This
 * test pins the contract it creates instead: <strong>a protogen {@code Timestamp} field is byte-identical
 * to a protoc {@code optional int64} field of epoch milliseconds.</strong>
 * <p>
 * {@code optional} is the part that is easy to get wrong. A {@code Timestamp} field has message presence,
 * so an instant at the epoch is a real value and must go on the wire; a bare {@code int64} would treat
 * zero as absent and drop it. The reference schema used here is derived from the shared one by exactly
 * this substitution, so the two can never drift apart.
 */
class InstantInteropTest {

    @Test
    void timestampIsByteIdenticalToAnOfficialInt64OfMillis() throws Exception {
        Instant when = Instant.ofEpochMilli(1_760_000_000_123L);
        EventV1 mine = new EventV1("e", when, null, List.of());
        protogen.it.official.EventV1 theirs = protogen.it.official.EventV1.newBuilder()
                .setEventId("e").setOccurredAt(when.toEpochMilli()).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, -1L, 1_000L, -1_000L, 253_402_300_799_000L, -62_135_596_800_000L})
    void everyEpochMillisAgrees(long millis) throws Exception {
        EventV1 mine = new EventV1("", Instant.ofEpochMilli(millis), null, List.of());
        protogen.it.official.EventV1 theirs = protogen.it.official.EventV1.newBuilder()
                .setOccurredAt(millis).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(protogen.it.official.EventV1.parseFrom(mine.toByteArray()).getOccurredAt())
                .isEqualTo(millis);
        assertThat(EventV1.parseFrom(theirs.toByteArray()).occurredAt())
                .isEqualTo(Instant.ofEpochMilli(millis));
    }

    @Test
    void officialInt64DecodesIntoAnInstant() throws Exception {
        protogen.it.official.EventV1 theirs = protogen.it.official.EventV1.newBuilder()
                .setEventId("from-protoc").setOccurredAt(42L).build();

        EventV1 mine = EventV1.parseFrom(theirs.toByteArray());

        assertThat(mine.eventId()).isEqualTo("from-protoc");
        assertThat(mine.occurredAt()).isEqualTo(Instant.ofEpochMilli(42L));
    }

    @Test
    void unsetTimestampAgreesWithAnUnsetOfficialOptionalInt64() throws Exception {
        EventV1 mine = new EventV1("e", null, null, List.of());
        protogen.it.official.EventV1 theirs = protogen.it.official.EventV1.newBuilder()
                .setEventId("e").build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
    }

    @Test
    void optionalTimestampAtTheEpochIsStillTransmitted() throws Exception {
        EventV1 mine = new EventV1("", null, Instant.EPOCH, List.of());
        protogen.it.official.EventV1 theirs = protogen.it.official.EventV1.newBuilder()
                .setAcknowledgedAt(0L).build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(protogen.it.official.EventV1.parseFrom(mine.toByteArray()).hasAcknowledgedAt()).isTrue();
    }

    @Test
    void repeatedTimestampsArePackedJustLikeOfficialInt64s() throws Exception {
        List<Instant> retries = List.of(Instant.ofEpochMilli(1), Instant.ofEpochMilli(2),
                Instant.ofEpochMilli(1_760_000_000_000L));
        EventV1 mine = new EventV1("", null, null, retries);
        protogen.it.official.EventV1 theirs = protogen.it.official.EventV1.newBuilder()
                .addAllRetryAt(retries.stream().map(Instant::toEpochMilli).toList())
                .build();

        assertThat(mine.toByteArray()).isEqualTo(theirs.toByteArray());
        assertThat(EventV1.parseFrom(theirs.toByteArray()).retryAt()).isEqualTo(retries);
    }

    @Test
    void subMillisecondPrecisionIsLostByDesign() throws Exception {
        Instant precise = Instant.ofEpochSecond(1_760_000_000L, 123_456_789);
        EventV1 mine = new EventV1("", precise, null, List.of());

        assertThat(protogen.it.official.EventV1.parseFrom(mine.toByteArray()).getOccurredAt())
                .isEqualTo(precise.toEpochMilli());
        assertThat(EventV1.parseFrom(mine.toByteArray()).occurredAt().getNano())
                .isEqualTo(123_000_000);
    }
}
