package io.github.helios57.protogen.compiler.asyncapi;

import io.github.helios57.protogen.compiler.ProtoCompileException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 2.x and 3.x describe the same API in structurally different ways. These pin that both land in the same
 * model, because everything downstream - addresses, stubs, binder config - reads only that model.
 */
class AsyncApiParserTest {

    private static final String V3 = """
            asyncapi: 3.0.0
            info:
              title: Metrics
              version: 2.3.3
            defaultContentType: application/gzip-protobuf
            channels:
              incremental:
                address: a/b/*/v1/metric/incremental/{abb1}/{abb2}/{stage}
                description: the incremental channel
                parameters:
                  abb1:
                    $ref: '#/components/parameters/abb1'
                  abb2:
                    description: second level
                  stage:
                    enum: [dev, prod]
                messages:
                  incremental:
                    $ref: '#/components/messages/MetricIncremental'
            operations:
              sendIncremental:
                action: send
                channel:
                  $ref: '#/channels/incremental'
              readIncremental:
                action: receive
                channel:
                  $ref: '#/channels/incremental'
            components:
              parameters:
                abb1:
                  description: top level building block
                  enum: [tms, iad]
              messages:
                MetricIncremental:
                  description: a batch of KPIs
                  contentType: application/gzip-protobuf
                  payload:
                    schemaFormat: 'application/vnd.google.protobuf;version=3'
                    schema:
                      $ref: kpiCollectionV1.proto
            """;

    private static final String V2 = """
            asyncapi: 2.6.0
            info:
              title: Metrics
              version: 2.3.3
            defaultContentType: application/json
            channels:
              a/b/*/v1/metric/incremental/{abb1}/{abb2}/{stage}:
                description: the incremental channel
                parameters:
                  abb1:
                    schema:
                      type: string
                      enum: [tms, iad]
                  abb2:
                    schema:
                      type: string
                  stage:
                    schema:
                      type: string
                publish:
                  message:
                    $ref: '#/components/messages/MetricIncremental'
            components:
              messages:
                MetricIncremental:
                  contentType: application/gzip-protobuf
                  schemaFormat: 'application/vnd.google.protobuf;version=3'
                  payload:
                    schema:
                      $ref: kpiCollectionV1.proto
            """;

    private static AsyncApi parse(String source) {
        return new AsyncApiParser("spec.yaml", source).parse();
    }

    @Test
    void readsTheThreeZeroShape() {
        AsyncApi api = parse(V3);

        assertThat(api.version()).isEqualTo("3.0.0");
        assertThat(api.isV3()).isTrue();
        assertThat(api.title()).isEqualTo("Metrics");
        assertThat(api.defaultContentType()).isEqualTo("application/gzip-protobuf");
        assertThat(api.channels()).extracting(AsyncApi.Channel::id).containsExactly("incremental");
    }

    @Test
    void readsTheTwoXShapeIntoTheSameModel() {
        AsyncApi api = parse(V2);

        assertThat(api.isV3()).isFalse();
        AsyncApi.Channel channel = api.channels().get(0);
        // 2.x has no channel id, so one is derived from the literal segments of the address
        assertThat(channel.id()).isEqualTo("aBV1MetricIncremental");
        assertThat(channel.address()).isEqualTo("a/b/*/v1/metric/incremental/{abb1}/{abb2}/{stage}");
    }

    @Test
    void parameterOrderFollowsTheAddressNotTheDeclaration() {
        // the generated constructor takes them in this order, so it must be the address order
        assertThat(parse(V3).channels().get(0).parametersInAddressOrder())
                .containsExactly("abb1", "abb2", "stage");
        assertThat(parse(V2).channels().get(0).parametersInAddressOrder())
                .containsExactly("abb1", "abb2", "stage");
    }

    @Test
    void resolvesInternalRefsForParameters() {
        AsyncApi.Parameter abb1 = parse(V3).channels().get(0).parameters().get("abb1");

        assertThat(abb1.description()).isEqualTo("top level building block");
        assertThat(abb1.enumValues()).containsExactly("tms", "iad");
    }

    @Test
    void readsParameterConstraintsFromEitherShape() {
        // 3.0 puts them on the parameter, 2.x nests them under 'schema'
        assertThat(parse(V3).channels().get(0).parameters().get("stage").enumValues())
                .containsExactly("dev", "prod");
        assertThat(parse(V2).channels().get(0).parameters().get("abb1").enumValues())
                .containsExactly("tms", "iad");
    }

    @Test
    void findsTheProtobufPayload() {
        AsyncApi.Message message = parse(V3).channels().get(0).messages().get(0);

        assertThat(message.name()).isEqualTo("MetricIncremental");
        assertThat(message.isProtobuf()).isTrue();
        assertThat(message.protoFile()).isEqualTo("kpiCollectionV1.proto");
        assertThat(message.contentType()).isEqualTo("application/gzip-protobuf");
    }

    @Test
    void findsTheProtobufPayloadInTwoXToo() {
        AsyncApi.Message message = parse(V2).channels().get(0).messages().get(0);

        assertThat(message.isProtobuf()).isTrue();
        assertThat(message.protoFile()).isEqualTo("kpiCollectionV1.proto");
    }

    @Test
    void aJsonPayloadIsNotMistakenForProtobuf() {
        AsyncApi api = parse("""
                asyncapi: 3.0.0
                info: {title: T, version: '1'}
                channels:
                  c:
                    address: a/b
                    messages:
                      m:
                        payload:
                          type: object
                """);

        assertThat(api.channels().get(0).messages().get(0).isProtobuf()).isFalse();
    }

    @Test
    void readsBothOperationDirections() {
        assertThat(parse(V3).operations())
                .extracting(AsyncApi.Operation::id, AsyncApi.Operation::action,
                        AsyncApi.Operation::channelId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("sendIncremental", AsyncApi.Action.SEND, "incremental"),
                        org.assertj.core.groups.Tuple.tuple("readIncremental", AsyncApi.Action.RECEIVE, "incremental"));
    }

    @Test
    void derivesOperationsFromTwoXPublishAndSubscribe() {
        AsyncApi api = parse(V2);

        assertThat(api.operations())
                .extracting(AsyncApi.Operation::action)
                .containsExactly(AsyncApi.Action.SEND);
        assertThat(api.operations().get(0).channelId()).isEqualTo(api.channels().get(0).id());
    }

    @Test
    void aMessageWithoutItsOwnContentTypeInheritsTheDocumentDefault() {
        AsyncApi api = parse("""
                asyncapi: 3.0.0
                info: {title: T, version: '1'}
                defaultContentType: application/json
                channels:
                  c:
                    address: a/b
                    messages:
                      m:
                        description: no contentType of its own
                """);

        assertThat(api.channels().get(0).messages().get(0).contentType()).isEqualTo("application/json");
    }

    @Test
    void rejectsADocumentWithoutAVersion() {
        assertThatThrownBy(() -> parse("info: {title: T}\n"))
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("missing the 'asyncapi' version");
    }

    @Test
    void rejectsAnUnresolvableRef() {
        assertThatThrownBy(() -> parse("""
                asyncapi: 3.0.0
                info: {title: T, version: '1'}
                channels:
                  c:
                    address: a
                    messages:
                      m:
                        $ref: '#/components/messages/Missing'
                """))
                .isInstanceOf(ProtoCompileException.class)
                .hasMessageContaining("cannot resolve $ref");
    }
}
