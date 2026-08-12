package io.github.helios57.protogen.compiler.asyncapi;

import io.github.helios57.protogen.compiler.ProtoCompileException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads an AsyncAPI document - YAML or JSON, 2.x or 3.x - into the normalised {@link AsyncApi} model.
 * <p>
 * Only the parts protogen can act on are interpreted: channels, their addresses and parameters,
 * operations, and message payloads that point at a {@code .proto}. Everything else in the document is
 * left alone rather than half-understood.
 */
public final class AsyncApiParser {

    /** The payload format that marks a protobuf schema, e.g. {@code application/vnd.google.protobuf}. */
    private static final String PROTOBUF_FORMAT_MARKER = "protobuf";

    private final String fileName;
    private final Map<String, Object> root;

    /**
     * Creates a parser over one document.
     *
     * @param fileName the file name to report in diagnostics
     * @param source   the document text, YAML or JSON
     */
    public AsyncApiParser(String fileName, String source) {
        this.fileName = fileName;
        LoaderOptions options = new LoaderOptions();
        // AsyncAPI documents are large; the defaults are too small for a real spec
        options.setCodePointLimit(Integer.MAX_VALUE);
        options.setMaxAliasesForCollections(1000);
        Object loaded = new Yaml(options).load(source);
        if (!(loaded instanceof Map)) {
            throw new ProtoCompileException(fileName + ": not an AsyncAPI document");
        }
        this.root = asMap(loaded);
    }

    /**
     * Reads a document from disk.
     *
     * @param file the document to read
     * @return the parser
     */
    public static AsyncApiParser of(Path file) {
        try {
            return new AsyncApiParser(file.getFileName().toString(),
                    Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /**
     * Parses the document.
     *
     * @return the normalised model
     */
    public AsyncApi parse() {
        String version = string(root.get("asyncapi"));
        if (version == null) {
            throw new ProtoCompileException(fileName + ": missing the 'asyncapi' version field");
        }
        Map<String, Object> info = asMap(root.get("info"));
        String defaultContentType = string(root.get("defaultContentType"));

        boolean v3 = !version.startsWith("2.");
        List<AsyncApi.Channel> channels = v3
                ? parseV3Channels(defaultContentType)
                : parseV2Channels(defaultContentType);
        List<AsyncApi.Operation> operations = v3
                ? parseV3Operations()
                : parseV2Operations();

        return new AsyncApi(version, string(info.get("title")), string(info.get("version")),
                defaultContentType, channels, operations);
    }

    // ------------------------------------------------------------------ 3.x

    private List<AsyncApi.Channel> parseV3Channels(String defaultContentType) {
        List<AsyncApi.Channel> channels = new ArrayList<>();
        for (Map.Entry<String, Object> e : asMap(root.get("channels")).entrySet()) {
            Map<String, Object> channel = resolve(e.getValue());
            String address = string(channel.get("address"));
            if (address == null) {
                address = e.getKey();
            }
            channels.add(new AsyncApi.Channel(
                    e.getKey(),
                    address,
                    string(channel.get("description")),
                    parseParameters(channel.get("parameters")),
                    parseMessages(channel.get("messages"), defaultContentType)));
        }
        return channels;
    }

    private List<AsyncApi.Operation> parseV3Operations() {
        List<AsyncApi.Operation> operations = new ArrayList<>();
        for (Map.Entry<String, Object> e : asMap(root.get("operations")).entrySet()) {
            Map<String, Object> operation = resolve(e.getValue());
            String action = string(operation.get("action"));
            operations.add(new AsyncApi.Operation(
                    e.getKey(),
                    "receive".equalsIgnoreCase(action) ? AsyncApi.Action.RECEIVE : AsyncApi.Action.SEND,
                    channelIdOf(operation.get("channel")),
                    string(operation.get("description"))));
        }
        return operations;
    }

    /** A 3.0 operation points at its channel with a {@code $ref} like {@code #/channels/keyframe}. */
    private String channelIdOf(Object channel) {
        if (!(channel instanceof Map)) {
            return null;
        }
        String ref = string(asMap(channel).get("$ref"));
        if (ref == null) {
            return null;
        }
        int slash = ref.lastIndexOf('/');
        return slash < 0 ? ref : ref.substring(slash + 1);
    }

    // ------------------------------------------------------------------ 2.x

    private List<AsyncApi.Channel> parseV2Channels(String defaultContentType) {
        List<AsyncApi.Channel> channels = new ArrayList<>();
        for (Map.Entry<String, Object> e : asMap(root.get("channels")).entrySet()) {
            Map<String, Object> channel = resolve(e.getValue());
            // in 2.x the key *is* the address, so an id has to be derived from it
            List<AsyncApi.Message> messages = new ArrayList<>();
            for (String direction : List.of("publish", "subscribe")) {
                Map<String, Object> operation = resolve(channel.get(direction));
                if (!operation.isEmpty()) {
                    messages.addAll(parseV2Message(operation.get("message"), defaultContentType));
                }
            }
            channels.add(new AsyncApi.Channel(
                    idFromAddress(e.getKey()),
                    e.getKey(),
                    string(channel.get("description")),
                    parseParameters(channel.get("parameters")),
                    messages));
        }
        return channels;
    }

    private List<AsyncApi.Operation> parseV2Operations() {
        List<AsyncApi.Operation> operations = new ArrayList<>();
        for (Map.Entry<String, Object> e : asMap(root.get("channels")).entrySet()) {
            Map<String, Object> channel = resolve(e.getValue());
            String id = idFromAddress(e.getKey());
            // 2.x is written from the consumer's point of view: 'publish' means others publish to us
            if (channel.containsKey("publish")) {
                Map<String, Object> op = resolve(channel.get("publish"));
                operations.add(new AsyncApi.Operation(id + "Publish", AsyncApi.Action.SEND, id,
                        string(op.get("description"))));
            }
            if (channel.containsKey("subscribe")) {
                Map<String, Object> op = resolve(channel.get("subscribe"));
                operations.add(new AsyncApi.Operation(id + "Subscribe", AsyncApi.Action.RECEIVE, id,
                        string(op.get("description"))));
            }
        }
        return operations;
    }

    private List<AsyncApi.Message> parseV2Message(Object message, String defaultContentType) {
        Map<String, Object> resolved = resolve(message);
        if (resolved.isEmpty()) {
            return List.of();
        }
        // a 2.x 'message' may be a oneOf list of messages
        if (resolved.get("oneOf") instanceof List<?> oneOf) {
            List<AsyncApi.Message> messages = new ArrayList<>();
            for (Object each : oneOf) {
                messages.add(parseMessage(nameOf(each, "message"), resolve(each), defaultContentType));
            }
            return messages;
        }
        return List.of(parseMessage(nameOf(message, "message"), resolved, defaultContentType));
    }

    /**
     * Derives a channel id from a 2.x address, which has no id of its own.
     * <p>
     * Only the literal segments contribute: {@code {parameters}} and wildcards say nothing about what the
     * channel <em>is</em>, and folding them in produces names like
     * {@code metricIncrementalAbb1Abb2Abb3Stage}.
     */
    private static String idFromAddress(String address) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (String segment : address.split("/")) {
            if (segment.isEmpty() || segment.startsWith("{") || "*".equals(segment) || ">".equals(segment)) {
                continue;
            }
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (Character.isLetterOrDigit(c)) {
                    sb.append(upper ? Character.toUpperCase(c) : c);
                    upper = false;
                } else {
                    upper = !sb.isEmpty();
                }
            }
            upper = !sb.isEmpty();
        }
        return sb.isEmpty() ? "channel" : sb.toString();
    }

    // -------------------------------------------------------------- shared

    private Map<String, AsyncApi.Parameter> parseParameters(Object parameters) {
        Map<String, AsyncApi.Parameter> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : asMap(parameters).entrySet()) {
            Map<String, Object> parameter = resolve(e.getValue());
            // 2.x nests the constraints under 'schema', 3.0 puts them on the parameter itself
            Map<String, Object> schema = parameter.containsKey("schema")
                    ? resolve(parameter.get("schema"))
                    : parameter;
            out.put(e.getKey(), new AsyncApi.Parameter(
                    e.getKey(),
                    string(parameter.get("description")),
                    strings(schema.get("enum")),
                    string(schema.get("pattern")),
                    strings(parameter.get("examples"))));
        }
        return out;
    }

    private List<AsyncApi.Message> parseMessages(Object messages, String defaultContentType) {
        List<AsyncApi.Message> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : asMap(messages).entrySet()) {
            out.add(parseMessage(nameOf(e.getValue(), e.getKey()), resolve(e.getValue()), defaultContentType));
        }
        return out;
    }

    private AsyncApi.Message parseMessage(String name, Map<String, Object> message, String defaultContentType) {
        Map<String, Object> payload = resolve(message.get("payload"));
        String schemaFormat = string(payload.get("schemaFormat"));
        if (schemaFormat == null) {
            schemaFormat = string(message.get("schemaFormat"));
        }
        String protoFile = null;
        if (schemaFormat != null && schemaFormat.toLowerCase(java.util.Locale.ROOT)
                .contains(PROTOBUF_FORMAT_MARKER)) {
            // the payload schema points at the .proto by relative path
            Object schema = payload.get("schema");
            String ref = schema instanceof Map ? string(asMap(schema).get("$ref")) : string(schema);
            if (ref == null) {
                ref = string(payload.get("$ref"));
            }
            protoFile = ref;
        }
        String contentType = string(message.get("contentType"));
        return new AsyncApi.Message(name, string(message.get("description")),
                contentType != null ? contentType : defaultContentType, protoFile, schemaFormat);
    }

    /** Uses the last segment of a {@code $ref} as the name when there is one, else the given fallback. */
    private String nameOf(Object node, String fallback) {
        if (node instanceof Map) {
            String ref = string(asMap(node).get("$ref"));
            if (ref != null) {
                int slash = ref.lastIndexOf('/');
                return slash < 0 ? ref : ref.substring(slash + 1);
            }
        }
        return fallback;
    }

    /**
     * Follows an internal {@code $ref} such as {@code #/components/messages/Foo}.
     * <p>
     * External refs are left alone: the only one protogen cares about is the payload's {@code .proto},
     * which is handled where the payload is read.
     */
    private Map<String, Object> resolve(Object node) {
        Map<String, Object> map = asMap(node);
        for (int hops = 0; hops < 32; hops++) {
            String ref = string(map.get("$ref"));
            if (ref == null || !ref.startsWith("#/")) {
                return map;
            }
            Object target = root;
            for (String segment : ref.substring(2).split("/")) {
                target = asMap(target).get(unescapePointer(segment));
                if (target == null) {
                    throw new ProtoCompileException(fileName + ": cannot resolve $ref '" + ref + "'");
                }
            }
            map = asMap(target);
        }
        throw new ProtoCompileException(fileName + ": $ref chain is too deep, is it a cycle?");
    }

    private static String unescapePointer(String segment) {
        return segment.replace("~1", "/").replace("~0", "~");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return node instanceof Map ? (Map<String, Object>) node : Map.of();
    }

    private static String string(Object node) {
        return node == null ? null : String.valueOf(node);
    }

    private static List<String> strings(Object node) {
        if (!(node instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object each : list) {
            out.add(String.valueOf(each));
        }
        return out;
    }
}
