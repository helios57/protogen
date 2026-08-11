package io.github.helios57.protogen.it;

import com.java.proto.model.proto.KpiCollectionV1;
import com.java.proto.model.proto.KpiV1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A schema taken from a real project, in its own Java package and with its own formatting habits:
 * blank lines between the comment block and the field, a regex constraint, two string maps, a negative
 * double, and a cross-file {@code repeated} reference.
 */
class KpiTest {

    private static KpiV1 kpi(String key, double value) {
        return new KpiV1(key, Map.of(), value, Map.of());
    }

    @Test
    void generatedIntoItsOwnJavaPackageWithItsOwnCodec() {
        // com.java.proto.model.proto is a second package in the same build; it must not need the first
        assertThat(KpiV1.class.getPackageName()).isEqualTo("com.java.proto.model.proto");
        assertThat(KpiCollectionV1.class.getPackageName()).isEqualTo("com.java.proto.model.proto");
    }

    @Test
    void roundTripsWithLabelsAndMeta() {
        Map<String, String> label = new LinkedHashMap<>();
        label.put("area", "heap");
        label.put("id", "G1 Eden Space");
        KpiV1 message = new KpiV1("jvm_memory_committed_bytes", label, 42.1, Map.of("TYPE", "seconds"));

        KpiV1 parsed = KpiV1.parseFrom(message.toByteArray());

        assertThat(parsed).isEqualTo(message);
        assertThat(parsed.label()).containsExactlyEntriesOf(label);
        assertThat(parsed.meta()).containsEntry("TYPE", "seconds");
        assertThat(parsed.value()).isEqualTo(42.1);
    }

    @Test
    void valueMayBeNegative() {
        KpiV1 parsed = KpiV1.parseFrom(kpi("temperature_celsius", -12.5).toByteArray());

        assertThat(parsed.value()).isEqualTo(-12.5);
    }

    @Test
    void optionalMapsMayBeEmpty() {
        KpiV1 message = kpi("up", 1.0);

        assertThat(message.label()).isEmpty();
        assertThat(message.meta()).isEmpty();
        assertThat(KpiV1.parseFrom(message.toByteArray())).isEqualTo(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jvm_memory_committed_bytes", "_underscore", ":colon", "a", "A0:_z"})
    void keysMatchingTheOpenMetricsPatternAreAccepted(String key) {
        assertThatCode(() -> kpi(key, 1.0)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0leading_digit", "has space", "has-dash", "hä"})
    void keysViolatingTheOpenMetricsPatternAreRejected(String key) {
        assertThatThrownBy(() -> kpi(key, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KpiV1.key")
                .hasMessageContaining("@Pattern");
    }

    @Test
    void theKeyPatternMakesTheFieldEffectivelyMandatory() {
        // proto3 cannot tell absent from "", and "" does not match the pattern, so an empty message is
        // rejected - which is exactly what "key is mandatory" means for this schema
        assertThatThrownBy(() -> kpi("", 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@Pattern");
        assertThatThrownBy(() -> KpiV1.parseFrom(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPeerCannotSmuggleAnInvalidKeyPastTheParser() {
        byte[] badKey = {0x0a, 0x03, '0', 'a', 'b'}; // key = "0ab", a leading digit

        assertThatThrownBy(() -> KpiV1.parseFrom(badKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@Pattern");
    }

    @Test
    void collectionAcrossFilesRoundTrips() {
        KpiCollectionV1 collection = new KpiCollectionV1(List.of(
                kpi("up", 1.0),
                new KpiV1("jvm_memory_committed_bytes", Map.of("area", "heap"), 42.1, Map.of()),
                kpi("errors_total", 0.0)));

        KpiCollectionV1 parsed = KpiCollectionV1.parseFrom(collection.toByteArray());

        assertThat(parsed).isEqualTo(collection);
        assertThat(parsed.items()).hasSize(3);
        assertThat(parsed.items().get(1).label()).containsEntry("area", "heap");
    }

    @Test
    void anEmptyCollectionEncodesToNothing() {
        KpiCollectionV1 empty = new KpiCollectionV1(List.of());

        assertThat(empty.protoSize()).isZero();
        assertThat(KpiCollectionV1.parseFrom(empty.toByteArray())).isEqualTo(empty);
    }

    @Test
    void aZeroValueIsOmittedBecauseItIsTheProto3Default() {
        KpiV1 message = kpi("errors_total", 0.0);

        // only the key is on the wire
        assertThat(message.protoSize()).isEqualTo(2 + "errors_total".length());
        assertThat(KpiV1.parseFrom(message.toByteArray()).value()).isZero();
    }

    @Test
    void mapsAreUnmodifiable() {
        KpiV1 message = new KpiV1("up", Map.of("a", "b"), 1.0, Map.of());

        assertThatThrownBy(() -> message.label().put("c", "d"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
