package io.github.helios57.protogen.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import protogen.it.model.EventV1;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code google.protobuf.Timestamp} surfaces as {@link Instant} and travels as an {@code int64} of epoch
 * milliseconds.
 */
class InstantTest {

    private static EventV1 event(Instant occurredAt) {
        return new EventV1("e", occurredAt, null, List.of());
    }

    @Test
    void instantRoundTrips() {
        Instant now = Instant.ofEpochMilli(1_760_000_000_123L);

        assertThat(EventV1.parseFrom(event(now).toByteArray()).occurredAt()).isEqualTo(now);
    }

    @Test
    void encodedAsAPlainInt64OfMilliseconds() {
        EventV1 message = new EventV1("", Instant.ofEpochMilli(1), null, List.of());

        // field 2, wire type 0 (varint) - not wire type 2, which a Timestamp submessage would use
        assertThat(message.toByteArray()).containsExactly(0x10, 0x01);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, -1L, 1_000L, -1_000L, 253_402_300_799_000L, -62_135_596_800_000L})
    void epochMillisBoundariesRoundTrip(long millis) {
        Instant value = Instant.ofEpochMilli(millis);

        assertThat(EventV1.parseFrom(event(value).toByteArray()).occurredAt()).isEqualTo(value);
    }

    @Test
    void subMillisecondPrecisionIsTruncatedNotCorrupted() {
        Instant precise = Instant.ofEpochSecond(1_760_000_000L, 123_456_789);

        Instant parsed = EventV1.parseFrom(event(precise).toByteArray()).occurredAt();

        assertThat(parsed).isEqualTo(Instant.ofEpochMilli(precise.toEpochMilli()));
        assertThat(parsed.getNano()).isEqualTo(123_000_000);
    }

    @Test
    void epochIsWrittenBecauseTheFieldHasMessagePresence() {
        // a Timestamp field is nullable, so Instant.EPOCH is a real value and must not be skipped
        EventV1 message = event(Instant.EPOCH);

        assertThat(message.toByteArray()).isNotEmpty();
        assertThat(EventV1.parseFrom(message.toByteArray()).occurredAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void unsetTimestampStaysNull() {
        EventV1 message = new EventV1("e", null, null, List.of());

        assertThat(message.toByteArray()).isNotEmpty();
        assertThat(EventV1.parseFrom(message.toByteArray()).occurredAt()).isNull();
    }

    @Test
    void optionalTimestampDistinguishesUnsetFromEpoch() {
        EventV1 unset = new EventV1("", null, null, List.of());
        EventV1 epoch = new EventV1("", null, Instant.EPOCH, List.of());

        assertThat(unset.toByteArray()).isEmpty();
        assertThat(epoch.toByteArray()).isNotEmpty();
        assertThat(EventV1.parseFrom(epoch.toByteArray()).acknowledgedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void repeatedTimestampsArePacked() {
        List<Instant> retries = List.of(Instant.ofEpochMilli(1), Instant.ofEpochMilli(2));
        EventV1 message = new EventV1("", null, null, retries);

        assertThat(message.protoSize()).isEqualTo(1 + 1 + 2);
        assertThat(EventV1.parseFrom(message.toByteArray()).retryAt()).isEqualTo(retries);
    }
}
