package io.github.helios57.protogen.it;

import org.junit.jupiter.api.Test;
import protogen.it.specfirst.OrderEventV1;
import protogen.it.specfirst.OrderLineV1;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec-first: nothing under a proto source root declares these types. They exist because an AsyncAPI
 * document names {@code schemas/orderEventV1.proto} as its payload, and that file imports the other one.
 * <p>
 * That this class compiles at all is half the assertion.
 */
class SpecFirstModelTest {

    private static OrderEventV1 order() {
        return new OrderEventV1("A-4711", "eu", List.of(
                new OrderLineV1("widget", 2, 1999L),
                new OrderLineV1("gasket", 1, 250L)));
    }

    @Test
    void theModelNamedByThePayloadRoundTrips() {
        OrderEventV1 order = order();

        assertThat(OrderEventV1.parseFrom(order.toByteArray())).isEqualTo(order);
    }

    @Test
    void theImportedModelIsGeneratedToo() {
        // orderLineV1.proto is named by nothing except the import inside orderEventV1.proto
        OrderLineV1 line = new OrderLineV1("widget", 2, 1999L);

        assertThat(OrderLineV1.parseFrom(line.toByteArray())).isEqualTo(line);
        assertThat(order().lines()).hasSize(2);
    }

    @Test
    void theConstraintsInTheReferencedSchemaAreEnforced() {
        assertThatThrownBy(() -> new OrderEventV1("A1", "eu", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@MinLength 3");
        assertThatThrownBy(() -> new OrderLineV1("widget", 0, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@Minimum 1");
    }

    @Test
    void theScaffoldForTheDocumentLandsInItsOwnDirectory() {
        // a second document must not overwrite the first one's scaffolding
        assertThat(java.nio.file.Path.of("target", "protogen-scaffold-orders", "protogen", "it",
                "specfirst", "scaffold", "ExampleOrderCreatedChannel.java")).exists();
    }
}
