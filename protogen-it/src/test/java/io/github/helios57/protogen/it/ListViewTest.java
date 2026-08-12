package io.github.helios57.protogen.it;

import org.junit.jupiter.api.Test;
import protogen.it.model.NodeV1;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The list a record hands out.
 * <p>
 * A list built by {@code parse} is handed over rather than copied, so - like the map - it has to be
 * immutable on its own account. A list passed in by a caller is still copied, because they keep theirs.
 * Both must behave identically from the outside, which is what this checks.
 */
class ListViewTest {

    private static NodeV1 withTags(List<String> tags) {
        return new NodeV1("n", null, null, null, null, tags, null, null, null, null, null);
    }

    /** The handed-over list: what parse built. */
    private static List<String> parsed(List<String> tags) {
        return NodeV1.parseFrom(withTags(tags).toByteArray()).tags();
    }

    /** The copied list: what a caller passed in. */
    private static List<String> constructed(List<String> tags) {
        return withTags(tags).tags();
    }

    private static final List<String> THREE = List.of("alpha", "beta", "gamma");

    @Test
    void bothListsRejectEveryMutation() {
        for (List<String> list : List.of(parsed(THREE), constructed(THREE))) {
            assertThatThrownBy(() -> list.add("delta")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> list.set(0, "delta")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> list.remove(0)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> list.remove("alpha")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(list::clear).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> list.addAll(List.of("x"))).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> list.sort(null)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> list.replaceAll(String::toUpperCase))
                    .isInstanceOf(UnsupportedOperationException.class);
            // both ways round: the inherited removeIf only fails once the predicate matches something
            assertThatThrownBy(() -> list.removeIf(String::isEmpty))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> list.removeIf(e -> e.startsWith("a")))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(list).hasSize(3);
        }
    }

    @Test
    void bothIteratorsRefuseToRemoveOrSet() {
        for (List<String> list : List.of(parsed(THREE), constructed(THREE))) {
            Iterator<String> it = list.iterator();
            it.next();
            assertThatThrownBy(it::remove).isInstanceOf(UnsupportedOperationException.class);

            ListIterator<String> listIterator = list.listIterator();
            listIterator.next();
            assertThatThrownBy(() -> listIterator.set("x")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> listIterator.add("x")).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void aListPassedInIsCopiedSoLaterChangesAreNotVisible() {
        List<String> mutable = new ArrayList<>(THREE);
        NodeV1 message = withTags(mutable);

        mutable.add("delta");
        mutable.set(0, "changed");

        assertThat(message.tags()).containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void aNullElementIsRejectedWhereItIsIntroduced() {
        assertThatThrownBy(() -> withTags(Arrays.asList("a", null, "c")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void readsBehaveLikeAnyOtherList() {
        for (List<String> list : List.of(parsed(THREE), constructed(THREE))) {
            assertThat(list).hasSize(3).isNotEmpty();
            assertThat(list.get(1)).isEqualTo("beta");
            assertThat(list.indexOf("gamma")).isEqualTo(2);
            assertThat(list.lastIndexOf("alpha")).isZero();
            assertThat(list.contains("beta")).isTrue();
            assertThat(list.contains("absent")).isFalse();
            assertThat(list.subList(1, 3)).containsExactly("beta", "gamma");
            assertThat(list.toArray()).containsExactly("alpha", "beta", "gamma");
            assertThat(list.stream().toList()).isEqualTo(THREE);
            assertThatThrownBy(() -> list.get(3)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Test
    void equalsAndHashCodeFollowTheListContract() {
        List<String> fromParse = parsed(THREE);
        List<String> fromConstructor = constructed(THREE);

        // a List equals any List with the same elements in the same order, whatever its class
        assertThat(fromParse).isEqualTo(fromConstructor).isEqualTo(THREE)
                .isEqualTo(new ArrayList<>(THREE)).isEqualTo(new LinkedList<>(THREE));
        assertThat(THREE).isEqualTo(fromParse);
        assertThat(fromParse.hashCode()).isEqualTo(THREE.hashCode());
        assertThat(fromParse).isNotEqualTo(List.of("alpha", "gamma", "beta"));
        assertThat(fromParse).hasToString("[alpha, beta, gamma]");
    }

    @Test
    void orderAndContentSurviveARoundTrip() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            many.add("tag-" + i);
        }

        assertThat(parsed(many)).isEqualTo(many);
        assertThat(NodeV1.parseFrom(withTags(many).toByteArray())).isEqualTo(withTags(many));
    }

    @Test
    void aListFromAnyImplementationIsAccepted() {
        assertThat(constructed(new LinkedList<>(THREE))).isEqualTo(THREE);
        assertThat(constructed(new ArrayList<>(THREE))).isEqualTo(THREE);
        assertThat(constructed(List.of())).isEmpty();
        assertThat(withTags(null).tags()).isEmpty();
    }

    @Test
    void aRepeatedMessageFieldIsAViewToo() {
        NodeV1 child = new NodeV1("c", null, null, null, null, null, null, null, null, null, null);
        NodeV1 parent = new NodeV1("p", null, null, List.of(child), null, null, null, null, null, null, null);
        List<NodeV1> children = NodeV1.parseFrom(parent.toByteArray()).children();

        assertThat(children).containsExactly(child);
        assertThatThrownBy(() -> children.add(child)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aPackedRepeatedFieldIsAViewToo() {
        NodeV1 message = new NodeV1("n", null, null, null, List.of(1, 2, 3), null, null, null, null,
                null, null);
        List<Integer> ports = NodeV1.parseFrom(message.toByteArray()).ports();

        assertThat(ports).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> ports.set(0, 9)).isInstanceOf(UnsupportedOperationException.class);
    }
}
