package io.github.helios57.protogen.compiler.asyncapi;

import io.github.helios57.protogen.compiler.gen.JavaGenerator;
import io.github.helios57.protogen.compiler.model.Names;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns an {@link AsyncApi} document into scaffolding: channel addresses, function stubs and a Spring
 * Cloud Stream binder configuration.
 * <p>
 * <strong>All of this is a starting point, not a build output.</strong> It is written to a throwaway
 * directory and never compiled, because a generator cannot know how your application is wired and code it
 * guessed at has no business appearing in a live source tree. Read it, take what is useful, delete the
 * rest.
 * <p>
 * The stubs bind {@code byte[]}, not the generated records, because that is what actually travels: the
 * payload is serialized - and, depending on the content type, compressed - by application code before it
 * reaches the binder. Where and how to do that is a decision the generator deliberately does not make.
 */
public final class AsyncApiEmitter {

    private final AsyncApi api;
    private final String javaPackage;
    private final Options options;

    /**
     * What to scaffold. Everything is on by default; turn off what you do not want.
     *
     * @param channels  typed address builders, one record per channel
     * @param stubs     a {@code java.util.function} stub per operation
     * @param binderConfig the Spring Cloud Stream + Solace binder configuration
     * @param notes     a README explaining what each file is and what to do with it
     */
    public record Options(boolean channels, boolean stubs, boolean binderConfig, boolean notes) {

        /** @return everything on */
        public static Options all() {
            return new Options(true, true, true, true);
        }
    }

    /**
     * Creates an emitter for one document.
     *
     * @param api         the parsed document
     * @param javaPackage the package to put the scaffolded Java in
     * @param options     what to scaffold
     */
    public AsyncApiEmitter(AsyncApi api, String javaPackage, Options options) {
        this.api = api;
        this.javaPackage = javaPackage;
        this.options = options;
    }

    /**
     * Produces the scaffolding.
     *
     * @return the files to write, all {@link JavaGenerator.Kind#SCAFFOLD}
     */
    public List<JavaGenerator.GeneratedFile> emit() {
        List<JavaGenerator.GeneratedFile> out = new ArrayList<>();
        if (options.channels()) {
            for (AsyncApi.Channel channel : api.channels()) {
                out.add(emitChannel(channel));
            }
        }
        if (options.stubs()) {
            for (AsyncApi.Operation operation : api.operations()) {
                out.add(emitStub(operation));
            }
        }
        if (options.binderConfig()) {
            out.add(emitBinderConfig());
        }
        if (options.notes()) {
            out.add(emitNotes());
        }
        return out;
    }

    // ------------------------------------------------------------ channels

    /** A record whose components are the address parameters, in the order the address uses them. */
    private JavaGenerator.GeneratedFile emitChannel(AsyncApi.Channel channel) {
        String type = Names.toUpperCamel(channel.id()) + "Channel";
        List<String> parameters = channel.parametersInAddressOrder();
        StringBuilder sb = new StringBuilder();

        sb.append("package ").append(javaPackage).append(";\n\n");
        sb.append("// Scaffolded by protogen from ").append(api.title()).append(". Adapt and move it.\n\n");
        sb.append("/**\n * ").append(oneLine(channel.description(), "The " + channel.id() + " channel."))
                .append("\n * <p>\n * Address template: {@code ").append(channel.address()).append("}\n */\n");

        if (parameters.isEmpty()) {
            sb.append("public final class ").append(type).append(" {\n\n");
            sb.append("    /** The channel address, which takes no parameters. */\n");
            sb.append("    public static final String ADDRESS = \"").append(channel.address()).append("\";\n\n");
            sb.append("    private ").append(type).append("() {\n    }\n}\n");
            return scaffoldJava(type, sb.toString());
        }

        sb.append("public record ").append(type).append("(\n");
        for (int i = 0; i < parameters.size(); i++) {
            sb.append("        String ").append(Names.fieldName(parameters.get(i)))
                    .append(i < parameters.size() - 1 ? ",\n" : ") {\n\n");
        }
        sb.append("    /** The address as written in the document, parameters unresolved. */\n");
        sb.append("    public static final String ADDRESS_TEMPLATE = \"").append(channel.address()).append("\";\n");

        List<String> checks = new ArrayList<>();
        for (String name : parameters) {
            AsyncApi.Parameter parameter = channel.parameters().get(name);
            String java = Names.fieldName(name);
            checks.add("if (" + java + " == null || " + java + ".isBlank()) {\n"
                    + "            throw new IllegalArgumentException(\"" + name + " is required\");\n        }");
            if (parameter == null) {
                continue;
            }
            if (!parameter.enumValues().isEmpty()) {
                String allowed = String.join(", ", parameter.enumValues().stream()
                        .map(v -> "\"" + v + "\"").toList());
                checks.add("if (!java.util.List.of(" + allowed + ").contains(" + java + ")) {\n"
                        + "            throw new IllegalArgumentException(\"" + name
                        + " must be one of " + parameter.enumValues() + ", was: \" + " + java + ");\n        }");
            }
            if (parameter.pattern() != null) {
                sb.append("\n    private static final java.util.regex.Pattern ")
                        .append(constantName(name)).append("_PATTERN =\n            java.util.regex.Pattern.compile(\"")
                        .append(parameter.pattern().replace("\\", "\\\\").replace("\"", "\\\"")).append("\");\n");
                checks.add("if (!" + constantName(name) + "_PATTERN.matcher(" + java + ").matches()) {\n"
                        + "            throw new IllegalArgumentException(\"" + name
                        + " does not match " + parameter.pattern().replace("\\", "\\\\") + ", was: \" + " + java + ");\n        }");
            }
        }

        sb.append("\n    /** Rejects a parameter the document says is not allowed, before it reaches a topic. */\n");
        sb.append("    public ").append(type).append(" {\n        ");
        sb.append(String.join("\n        ", checks));
        sb.append("\n    }\n\n");

        sb.append("    /**\n     * The address with the parameters substituted.\n     *\n"
                + "     * @return the resolved topic\n     */\n");
        sb.append("    public String address() {\n        return ADDRESS_TEMPLATE\n");
        for (String name : parameters) {
            sb.append("                .replace(\"{").append(name).append("}\", ")
                    .append(Names.fieldName(name)).append(")\n");
        }
        sb.setLength(sb.length() - 1);
        sb.append(";\n    }\n}\n");
        return scaffoldJava(type, sb.toString());
    }

    // --------------------------------------------------------------- stubs

    /**
     * A {@code java.util.function} stub per operation.
     * <p>
     * {@code Supplier<byte[]>} to send, {@code Consumer<byte[]>} to receive - the binder moves bytes, and
     * turning a payload into those bytes (and compressing it, if the content type says so) is application
     * logic this scaffold points at rather than writes.
     */
    private JavaGenerator.GeneratedFile emitStub(AsyncApi.Operation operation) {
        boolean send = operation.action() == AsyncApi.Action.SEND;
        String type = Names.toUpperCamel(operation.id()) + (send ? "Publisher" : "Listener");
        AsyncApi.Channel channel = channelOf(operation);
        String contentType = channel == null || channel.messages().isEmpty()
                ? api.defaultContentType()
                : channel.messages().get(0).contentType();
        String proto = channel == null || channel.messages().isEmpty()
                ? null
                : channel.messages().get(0).protoFile();

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(javaPackage).append(";\n\n");
        sb.append("import java.util.function.").append(send ? "Supplier" : "Consumer").append(";\n\n");
        sb.append("// Scaffolded by protogen. A starting point - move it into your application and finish it.\n\n");
        sb.append("/**\n * ").append(oneLine(operation.description(), "Operation " + operation.id() + "."))
                .append("\n * <p>\n");
        sb.append(" * ").append(send ? "Publishes to" : "Consumes from").append(" channel {@code ")
                .append(operation.channelId()).append("}");
        if (channel != null) {
            sb.append(", address {@code ").append(channel.address()).append("}");
        }
        sb.append(".\n");
        if (contentType != null) {
            sb.append(" * <p>\n * Content type {@code ").append(contentType).append("}. What travels is a\n");
            sb.append(" * {@code byte[]}: serializing the payload, and compressing it if the content type\n");
            sb.append(" * says so, is your code's job, not the binder's.\n");
        }
        sb.append(" */\n");
        sb.append("public class ").append(type).append(" implements ")
                .append(send ? "Supplier<byte[]>" : "Consumer<byte[]>").append(" {\n\n");
        sb.append("    @Override\n");
        if (send) {
            sb.append("    public byte[] get() {\n");
            if (proto != null) {
                sb.append("        // build the payload, e.g. new ").append(payloadType(proto)).append("(...)\n");
                sb.append("        // byte[] encoded = payload.toByteArray();\n");
            }
            if (isCompressed(contentType)) {
                sb.append("        // the content type says gzip, so compress 'encoded' before returning it\n");
            }
            sb.append("        throw new UnsupportedOperationException(\"scaffold: build and return the payload\");\n");
        } else {
            sb.append("    public void accept(byte[] message) {\n");
            if (isCompressed(contentType)) {
                sb.append("        // the content type says gzip, so decompress 'message' first\n");
            }
            if (proto != null) {
                sb.append("        // ").append(payloadType(proto)).append(" payload = ")
                        .append(payloadType(proto)).append(".parseFrom(message);\n");
            }
            sb.append("        throw new UnsupportedOperationException(\"scaffold: handle the message\");\n");
        }
        sb.append("    }\n}\n");
        return scaffoldJava(type, sb.toString());
    }

    // -------------------------------------------------------------- config

    /**
     * A Spring Cloud Stream configuration for the Solace binder.
     * <p>
     * Destinations come straight from the channel addresses, which is the part that is tedious and easy to
     * get wrong by hand. Connection details, credentials and tuning are deliberately absent: they belong to
     * the environment, not to the API description.
     */
    private JavaGenerator.GeneratedFile emitBinderConfig() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Scaffolded by protogen from ").append(api.title()).append(' ')
                .append(api.apiVersion() == null ? "" : api.apiVersion()).append('\n');
        sb.append("#\n# Merge what you need into your application configuration. Destinations come from the\n");
        sb.append("# AsyncAPI channel addresses; connection details and credentials are yours to supply.\n");
        sb.append("# A destination containing {parameters} has to be resolved before use - the generated\n");
        sb.append("# *Channel records do exactly that.\n\n");

        Set<String> functions = new LinkedHashSet<>();
        for (AsyncApi.Operation operation : api.operations()) {
            functions.add(Names.toLowerCamel(operation.id()));
        }
        sb.append("spring:\n  cloud:\n    function:\n      definition: |\n");
        // ';' separates the functions rather than terminating each one
        sb.append("        ").append(String.join(";\n        ", functions)).append('\n');
        sb.append("    stream:\n      default:\n        binder: solace\n      bindings:\n");
        for (AsyncApi.Operation operation : api.operations()) {
            AsyncApi.Channel channel = channelOf(operation);
            boolean send = operation.action() == AsyncApi.Action.SEND;
            String binding = Names.toLowerCamel(operation.id()) + (send ? "-out-0" : "-in-0");
            sb.append("        ").append(binding).append(":\n");
            sb.append("          destination: ").append(channel == null ? "TODO" : channel.address()).append('\n');
            String contentType = channel == null || channel.messages().isEmpty()
                    ? api.defaultContentType()
                    : channel.messages().get(0).contentType();
            if (contentType != null) {
                sb.append("          contentType: \"").append(contentType).append("\"\n");
            }
        }

        List<AsyncApi.Operation> receivers = api.operations().stream()
                .filter(o -> o.action() == AsyncApi.Action.RECEIVE)
                .toList();
        if (!receivers.isEmpty()) {
            sb.append("      solace:\n        bindings:\n");
            for (AsyncApi.Operation operation : receivers) {
                sb.append("          ").append(Names.toLowerCamel(operation.id())).append("-in-0:\n");
                sb.append("            consumer:\n");
                sb.append("              # keep queue names short and legal; wildcards are not allowed in them\n");
                sb.append("              queueNameExpression: \"destination.trim().replaceAll('[*>]', '_')\"\n");
            }
        }
        return new JavaGenerator.GeneratedFile("application-" + slug(api.title()) + ".yaml",
                sb.toString(), JavaGenerator.Kind.SCAFFOLD);
    }

    private JavaGenerator.GeneratedFile emitNotes() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Scaffolding for ").append(api.title()).append("\n\n");
        sb.append("Generated by protogen from the AsyncAPI document. **None of this is compiled** - it is a\n");
        sb.append("starting point to read, adapt and move where you want it.\n\n");
        sb.append("| File | What it is |\n|---|---|\n");
        if (options.channels()) {
            sb.append("| `*Channel.java` | one record per channel; construct it with the address parameters and call"
                    + " `address()` to get the topic. Values the document constrains are checked. |\n");
        }
        if (options.stubs()) {
            sb.append("| `*Publisher.java` / `*Listener.java` | a `java.util.function` stub per operation,"
                    + " binding `byte[]` because that is what travels |\n");
        }
        if (options.binderConfig()) {
            sb.append("| `application-*.yaml` | Spring Cloud Stream bindings for the Solace binder,"
                    + " destinations taken from the channel addresses |\n");
        }
        sb.append("\n## What is deliberately missing\n\n");
        sb.append("- **Serialization and compression.** The binder moves a `byte[]`. Turning a payload into\n");
        sb.append("  those bytes, and compressing it when the content type says gzip, is application logic;\n");
        sb.append("  the generator has no business guessing where that belongs.\n");
        sb.append("- **Connection details and credentials.** Those come from the environment, not the API.\n");
        sb.append("- **Anything the document does not say.** Queue naming, error handling and retries are\n");
        sb.append("  hinted at where the shape is obvious and left alone otherwise.\n");
        return new JavaGenerator.GeneratedFile("README.md", sb.toString(), JavaGenerator.Kind.SCAFFOLD);
    }

    // -------------------------------------------------------------- helpers

    private AsyncApi.Channel channelOf(AsyncApi.Operation operation) {
        return api.channels().stream()
                .filter(c -> c.id().equals(operation.channelId()))
                .findFirst()
                .orElse(null);
    }

    private static boolean isCompressed(String contentType) {
        return contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("gzip");
    }

    /** {@code kpiCollectionV1.proto} names the payload type {@code KpiCollectionV1}. */
    private static String payloadType(String protoFile) {
        String base = protoFile;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        if (base.endsWith(".proto")) {
            base = base.substring(0, base.length() - ".proto".length());
        }
        return Names.toUpperCamel(base);
    }

    private static String constantName(String parameter) {
        return parameter.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    private static String slug(String title) {
        return title == null ? "asyncapi"
                : title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String oneLine(String text, String fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        return text.strip().lines().findFirst().orElse(fallback).strip();
    }

    private JavaGenerator.GeneratedFile scaffoldJava(String type, String content) {
        String dir = javaPackage.isEmpty() ? "" : javaPackage.replace('.', '/') + "/";
        return new JavaGenerator.GeneratedFile(dir + type + ".java", content, JavaGenerator.Kind.SCAFFOLD);
    }
}
