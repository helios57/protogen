package io.github.helios57.protogen.it;

import org.junit.jupiter.api.Test;
import protogen.it.model.NodeV1;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The map a record hands out.
 * <p>
 * It is not {@code Collections.unmodifiableMap} any more: a map that {@code parse} built is handed over
 * rather than copied, which is worth a full rehash per map but means the wrapper has to be immutable on
 * its own account rather than by wrapping something. Every way out of it is checked here - the map, its
 * entry set, its keys, its values, its iterators - because one that is not covered is a way to reach into
 * a message that is supposed to be a value.
 */
class MapViewTest {

    private static NodeV1 withEndpoints(Map<String, String> endpoints) {
        return new NodeV1("n", null, null, null, null, null, endpoints, null, null, null, null);
    }

    private static Map<String, String> parsed(Map<String, String> endpoints) {
        // the handed-over map, which is the one that used to be a fresh copy behind an unmodifiable view
        return NodeV1.parseFrom(withEndpoints(endpoints).toByteArray()).endpoints();
    }

    private static final Map<String, String> TWO = new LinkedHashMap<>(
            Map.of("primary", "https://a.example"));

    static {
        TWO.put("backup", "https://b.example");
    }

    // --------------------------------------------------------- immutability

    @Test
    void theMapItselfRejectsEveryMutation() {
        Map<String, String> map = parsed(TWO);

        assertThatThrownBy(() -> map.put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.remove("primary")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(map::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.putAll(Map.of("x", "y")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.putIfAbsent("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.merge("primary", "v", (a, b) -> b))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.computeIfAbsent("x", k -> "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.replaceAll((k, v) -> "other"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.keySet().removeIf(k -> k.startsWith("p")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.entrySet().removeIf(e -> true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(map).hasSize(2);
    }

    @Test
    void anEntryCannotBeWrittenThrough() {
        Map<String, String> map = parsed(TWO);

        assertThatThrownBy(() -> map.entrySet().iterator().next().setValue("other"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(map.get("primary")).isEqualTo("https://a.example");
    }

    @Test
    void everyIteratorRefusesToRemove() {
        Map<String, String> map = parsed(TWO);

        Iterator<Map.Entry<String, String>> entries = map.entrySet().iterator();
        entries.next();
        assertThatThrownBy(entries::remove).isInstanceOf(UnsupportedOperationException.class);

        Iterator<String> keys = map.keySet().iterator();
        keys.next();
        assertThatThrownBy(keys::remove).isInstanceOf(UnsupportedOperationException.class);

        Iterator<String> values = map.values().iterator();
        values.next();
        assertThatThrownBy(values::remove).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theKeyAndValueViewsRejectMutationToo() {
        Map<String, String> map = parsed(TWO);

        assertThatThrownBy(() -> map.keySet().remove("primary"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.keySet().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.values().remove("https://a.example"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.entrySet().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aMapPassedInIsCopiedSoLaterChangesAreNotVisible() {
        Map<String, String> mutable = new LinkedHashMap<>(Map.of("k", "v"));
        NodeV1 message = withEndpoints(mutable);

        mutable.put("added", "later");
        mutable.remove("k");

        assertThat(message.endpoints()).containsExactly(Map.entry("k", "v"));
    }

    // ------------------------------------------------------------ semantics

    @Test
    void readsBehaveLikeAnyOtherMap() {
        Map<String, String> map = parsed(TWO);

        assertThat(map).hasSize(2).isNotEmpty();
        assertThat(map.get("primary")).isEqualTo("https://a.example");
        assertThat(map.get("absent")).isNull();
        assertThat(map.getOrDefault("absent", "fallback")).isEqualTo("fallback");
        assertThat(map.containsKey("backup")).isTrue();
        assertThat(map.containsKey("absent")).isFalse();
        assertThat(map.containsValue("https://b.example")).isTrue();
        assertThat(map.containsValue("nope")).isFalse();
        assertThat(map.keySet()).containsExactly("primary", "backup");
        assertThat(map.values()).containsExactly("https://a.example", "https://b.example");
    }

    @Test
    void insertionOrderSurvivesTheRoundTrip() {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            ordered.put("k" + i, "v" + i);
        }

        assertThat(parsed(ordered).keySet()).containsExactlyElementsOf(ordered.keySet());
        assertThat(withEndpoints(ordered).endpoints().keySet()).containsExactlyElementsOf(ordered.keySet());
    }

    @Test
    void equalsAndHashCodeFollowTheMapContract() {
        Map<String, String> map = parsed(TWO);
        Map<String, String> same = new HashMap<>(TWO);

        // a Map equals any Map with the same entries, whatever its class or iteration order
        assertThat(map).isEqualTo(same).isEqualTo(new TreeMap<>(TWO));
        assertThat(same).isEqualTo(map);
        assertThat(map.hashCode()).isEqualTo(same.hashCode());
        assertThat(map).isNotEqualTo(Map.of("primary", "other"));
        assertThat(Map.of()).isEqualTo(parsed(Map.of()));
    }

    @Test
    void anEntryEqualsAnyEntryWithTheSameKeyAndValue() {
        Map.Entry<String, String> entry = parsed(Map.of("k", "v")).entrySet().iterator().next();

        assertThat(entry).isEqualTo(Map.entry("k", "v"));
        assertThat(Map.entry("k", "v")).isEqualTo(entry);
        assertThat(entry.hashCode()).isEqualTo(Map.entry("k", "v").hashCode());
        assertThat(entry).isNotEqualTo(Map.entry("k", "other")).isNotEqualTo("not an entry");
        assertThat(entry).hasToString("k=v");
    }

    @Test
    void theEntrySetKnowsWhatItContains() {
        var entries = parsed(TWO).entrySet();

        assertThat(entries.contains(Map.entry("primary", "https://a.example"))).isTrue();
        assertThat(entries.contains(Map.entry("primary", "wrong"))).isFalse();
        assertThat(entries.contains(Map.entry("absent", "x"))).isFalse();
        assertThat(entries.contains("not an entry")).isFalse();
        assertThat(entries).hasSize(2);
    }

    @Test
    void forEachAndStreamsSeeEveryEntry() {
        Map<String, String> map = parsed(TWO);
        Map<String, String> seen = new LinkedHashMap<>();

        map.forEach(seen::put);

        assertThat(seen).isEqualTo(map);
        assertThat(map.entrySet().stream().map(Map.Entry::getKey).toList())
                .containsExactly("primary", "backup");
    }

    @Test
    void toStringRendersLikeAMap() {
        assertThat(parsed(Map.of("k", "v"))).hasToString("{k=v}");
    }

    @Test
    void aMessageEqualsAnotherBuiltFromAPlainMap() {
        // the record's equals goes through the map's, so the wrapper must not change the answer
        NodeV1 built = withEndpoints(new HashMap<>(TWO));

        assertThat(NodeV1.parseFrom(built.toByteArray())).isEqualTo(built);
        assertThat(NodeV1.parseFrom(built.toByteArray()).hashCode()).isEqualTo(built.hashCode());
    }

    @Test
    void aNullValueSurvivesAsFarAsTheEncoder() {
        // LinkedHashMap allows it, so the view must not turn a broken map into a different exception
        Map<String, String> withNull = new LinkedHashMap<>();
        withNull.put("k", null);
        NodeV1 message = withEndpoints(withNull);

        assertThat(message.endpoints().get("k")).isNull();
        assertThat(message.endpoints().entrySet().iterator().next().getValue()).isNull();
        assertThat(message.endpoints()).hasToString("{k=null}");
        assertThatThrownBy(message::toByteArray).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aMapWithMessageValuesIsAViewToo() {
        NodeV1 message = new NodeV1("n", null, null, null, null, null, null, null, null,
                Map.of("home", new NodeV1.CoordinatesV1(46.9, 7.4)), null);
        NodeV1 back = NodeV1.parseFrom(message.toByteArray());

        assertThat(back.namedLocations()).isEqualTo(message.namedLocations());
        assertThatThrownBy(() -> back.namedLocations().entrySet().iterator().next()
                .setValue(new NodeV1.CoordinatesV1(0, 0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aMapWithPrimitiveKeysBehavesTheSame() {
        NodeV1 message = new NodeV1("n", null, null, null, null, null, null, null, null, null,
                Map.of(7, true));
        Map<Integer, Boolean> flags = NodeV1.parseFrom(message.toByteArray()).flagsByCode();

        assertThat(flags).containsExactly(Map.entry(7, true));
        assertThat(flags.get(7)).isTrue();
        assertThatThrownBy(() -> flags.put(8, false)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aLargeMapKeepsEveryEntryThroughTheRoundTrip() {
        Map<String, String> big = new LinkedHashMap<>();
        for (int i = 0; i < 500; i++) {
            big.put("key-" + i, "value-" + i);
        }

        Map<String, String> back = parsed(big);

        assertThat(back).hasSize(500).isEqualTo(big);
        assertThat(List.copyOf(back.keySet())).isEqualTo(List.copyOf(big.keySet()));
    }
}
