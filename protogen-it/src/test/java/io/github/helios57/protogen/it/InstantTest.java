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
    void encodedAsProtocEncodesIt() {
        EventV1 message = new EventV1("", Instant.ofEpochSecond(1, 2), null, List.of());

        // field 2, wire type 2, then {seconds = 1 (field 1), nanos = 2 (field 2)} - protoc's Timestamp
        assertThat(message.toByteArray()).containsExactly(0x12, 0x04, 0x08, 0x01, 0x10, 0x02);
    }

    @Test
    void theEpochIsAnEmptySubmessageRatherThanAnAbsentField() {
        // both parts are at their default, and proto3 does not write a default - but the field is present
        EventV1 message = new EventV1("", Instant.EPOCH, null, List.of());

        assertThat(message.toByteArray()).containsExactly(0x12, 0x00);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, -1L, 1_000L, -1_000L, 253_402_300_799_000L, -62_135_596_800_000L})
    void epochMillisBoundariesRoundTrip(long millis) {
        Instant value = Instant.ofEpochMilli(millis);

        assertThat(EventV1.parseFrom(event(value).toByteArray()).occurredAt()).isEqualTo(value);
    }

    @Test
    void nanosecondPrecisionSurvives() {
        Instant precise = Instant.ofEpochSecond(1_760_000_000L, 123_456_789);

        Instant parsed = EventV1.parseFrom(event(precise).toByteArray()).occurredAt();

        assertThat(parsed).isEqualTo(precise);
        assertThat(parsed.getNano()).isEqualTo(123_456_789);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, -1L, 999_999_999L, -62_135_596_800L, 253_402_300_799L})
    void secondsAndNanosRoundTripAtTheirBoundaries(long seconds) {
        for (int nanos : new int[]{0, 1, 999_999_999}) {
            Instant value = Instant.ofEpochSecond(seconds, nanos);

            assertThat(EventV1.parseFrom(event(value).toByteArray()).occurredAt()).isEqualTo(value);
        }
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
    void repeatedTimestampsAreNotPackedBecauseTheyAreSubmessages() {
        List<Instant> retries = List.of(Instant.ofEpochSecond(1), Instant.ofEpochSecond(2));
        EventV1 message = new EventV1("", null, null, retries);

        // one tag and one length prefix each, as for any repeated message field
        assertThat(message.protoSize()).isEqualTo(2 * (1 + 1 + 2));
        assertThat(EventV1.parseFrom(message.toByteArray()).retryAt()).isEqualTo(retries);
    }
}
