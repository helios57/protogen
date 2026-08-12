package io.github.helios57.protogen.compiler.asyncapi;

import java.util.List;
import java.util.Map;

/**
 * An AsyncAPI document, normalised.
 * <p>
 * 2.x and 3.0 describe the same thing in structurally different ways: 2.x keys channels by their address
 * and hangs {@code publish}/{@code subscribe} off them, while 3.0 gives channels an id plus an
 * {@code address} and moves the direction into a separate {@code operations} section. Both are read into
 * the model here, so everything downstream sees one shape.
 *
 * @param version           the {@code asyncapi} version as written, e.g. {@code 3.0.0}
 * @param title             the API title from {@code info}
 * @param apiVersion        the API version from {@code info}
 * @param defaultContentType the document-level {@code defaultContentType}, or {@code null}
 * @param channels          the channels, in document order
 * @param operations        the operations, in document order
 */
public record AsyncApi(String version,
                       String title,
                       String apiVersion,
                       String defaultContentType,
                       List<Channel> channels,
                       List<Operation> operations) {

    /** @return whether the document declares AsyncAPI 3 or newer */
    public boolean isV3() {
        return version != null && !version.startsWith("2.");
    }

    /**
     * A channel: an address, possibly with {@code {parameters}}, carrying one or more messages.
     *
     * @param id         the channel id - the map key in 3.0, the address itself in 2.x
     * @param address    the address template, e.g. {@code a/b/{param}/c}
     * @param description free text, for the generated Javadoc
     * @param parameters the declared parameters, keyed by name
     * @param messages   the messages this channel carries
     */
    public record Channel(String id,
                          String address,
                          String description,
                          Map<String, Parameter> parameters,
                          List<Message> messages) {

        /**
         * The parameter names in the order they appear in the address, which is the order the generated
         * constructor takes them in.
         *
         * @return the parameter names, in address order
         */
        public List<String> parametersInAddressOrder() {
            List<String> ordered = new java.util.ArrayList<>();
            int i = 0;
            while (i < address.length()) {
                int open = address.indexOf('{', i);
                if (open < 0) {
                    break;
                }
                int close = address.indexOf('}', open);
                if (close < 0) {
                    break;
                }
                String name = address.substring(open + 1, close);
                if (!ordered.contains(name)) {
                    ordered.add(name);
                }
                i = close + 1;
            }
            return ordered;
        }
    }

    /**
     * A channel parameter.
     *
     * @param name        the parameter name as it appears in the address
     * @param description free text, for the generated Javadoc
     * @param enumValues  the permitted values, or empty when unconstrained
     * @param pattern     a regular expression the value must match, or {@code null}
     * @param examples    example values, for the generated Javadoc
     */
    public record Parameter(String name,
                            String description,
                            List<String> enumValues,
                            String pattern,
                            List<String> examples) {
    }

    /**
     * A message and its payload.
     *
     * @param name        the message name
     * @param description free text, for the generated Javadoc
     * @param contentType the message content type, falling back to the document default
     * @param protoFile   the {@code .proto} the payload refers to, or {@code null} when it is not protobuf
     * @param schemaFormat the declared payload schema format
     */
    public record Message(String name,
                          String description,
                          String contentType,
                          String protoFile,
                          String schemaFormat) {

        /** @return whether this message's payload is a protobuf schema protogen can generate */
        public boolean isProtobuf() {
            return protoFile != null;
        }
    }

    /** Which way a message travels, from the point of view of the application that owns the document. */
    public enum Action {
        /** The application publishes on this channel. */
        SEND,
        /** The application consumes from this channel. */
        RECEIVE
    }

    /**
     * An operation: a direction on a channel.
     *
     * @param id          the operation id
     * @param action      whether the application sends or receives
     * @param channelId   the channel this operation runs on
     * @param description free text, for the generated Javadoc
     */
    public record Operation(String id, Action action, String channelId, String description) {
    }
}
