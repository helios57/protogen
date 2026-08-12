package io.github.helios57.protogen.maven;

import io.github.helios57.protogen.compiler.ProtoCompileException;
import io.github.helios57.protogen.compiler.ProtoCompiler;
import io.github.helios57.protogen.compiler.asyncapi.AsyncApi;
import io.github.helios57.protogen.compiler.asyncapi.AsyncApiEmitter;
import io.github.helios57.protogen.compiler.asyncapi.AsyncApiParser;
import io.github.helios57.protogen.compiler.gen.JavaGenerator;
import io.github.helios57.protogen.compiler.model.ProtoFile;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Generates optimized, fully self-contained Java sources from {@code .proto} files.
 * <p>
 * The generated code compiles and runs against the JDK alone - no {@code protobuf-java}, no Netty, and no
 * protogen artifact on the runtime classpath.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class GenerateMojo extends AbstractMojo {

    /** Creates the mojo; Maven injects the parameters below. */
    public GenerateMojo() {
    }

    /** Root directory scanned for {@code .proto} files. */
    @Parameter(property = "protogen.protoSourceRoot", defaultValue = "${basedir}/src/main/proto")
    File protoSourceRoot;

    /** Where the generated Java sources are written; added to the project's compile source roots. */
    @Parameter(property = "protogen.outputDirectory", defaultValue = "${project.build.directory}/generated-sources/protogen")
    File outputDirectory;

    /** Glob patterns of files to generate, relative to {@link #protoSourceRoot}. */
    @Parameter
    List<String> includes = List.of("**/*.proto");

    /** Glob patterns of files to skip, relative to {@link #protoSourceRoot}. */
    @Parameter
    List<String> excludes = List.of();

    /** Overrides {@code option java_package} for every generated file. */
    @Parameter(property = "protogen.javaPackage")
    String javaPackage;

    /** Carry leading {@code .proto} comments into the generated Javadoc. */
    @Parameter(property = "protogen.emitJavadoc", defaultValue = "true")
    boolean emitJavadoc;

    /**
     * Keep the encoded bytes of fields this build does not know in a trailing {@code unknownFields}
     * component, so a message written against a newer schema survives a round trip unchanged.
     * <p>
     * Off by default: it adds a component to every record, which shows up in the constructor, in
     * {@code equals} and in {@code toString}. Turn it on for a service that relays messages it does not
     * fully own.
     */
    @Parameter(property = "protogen.preserveUnknownFields", defaultValue = "false")
    boolean preserveUnknownFields;

    /**
     * Generate the checks declared by the schema's {@code @Minimum} / {@code @Pattern} style annotations.
     * <p>
     * This is the compile-time half of the validation controls; the generated code carries a second,
     * runtime switch ({@code -Dprotogen.validation=false}) that turns the same checks off without
     * regenerating. Set this to {@code false} to emit no checks at all.
     */
    @Parameter(property = "protogen.emitValidation", defaultValue = "true")
    boolean emitValidation;

    /**
     * Write a JSON sidecar per {@code .proto} to {@code META-INF/protogen/}, describing the
     * {@code @Example} values, the {@code @RootNode} markers, the constraints and the Java names each
     * declaration ended up with - for a documentation pipeline to consume without re-parsing the schema.
     */
    @Parameter(property = "protogen.emitSchemaMetadata", defaultValue = "true")
    boolean emitSchemaMetadata;

    /** Where the schema metadata sidecars are written; added to the project's resources. */
    @Parameter(property = "protogen.resourceOutputDirectory",
            defaultValue = "${project.build.directory}/generated-resources/protogen")
    File resourceOutputDirectory;

    /** Fail the build on constructs protogen does not support, instead of skipping them. */
    @Parameter(property = "protogen.failOnUnsupported", defaultValue = "true")
    boolean failOnUnsupported;

    /**
     * Directory scanned for AsyncAPI documents, 2.x or 3.x, YAML or JSON.
     * <p>
     * Leave it unset to skip AsyncAPI entirely. Note that specs often carry build placeholders such as
     * {@code @project.version@}; point this at the filtered output rather than the source tree if so.
     */
    @Parameter(property = "protogen.asyncApiSourceRoot")
    File asyncApiSourceRoot;

    /**
     * Where the AsyncAPI scaffolding is written.
     * <p>
     * A throwaway directory by default, and <strong>never compiled</strong>: this output is a starting
     * point to read and adapt, not a build artefact. Point it at {@code src/main/java} only if you want it
     * landing directly in your sources, and know it will overwrite.
     */
    @Parameter(property = "protogen.scaffoldOutputDirectory",
            defaultValue = "${project.build.directory}/protogen-scaffold")
    File scaffoldOutputDirectory;

    /** Package for the scaffolded Java. Defaults to the java package of the generated messages. */
    @Parameter(property = "protogen.scaffoldPackage")
    String scaffoldPackage;

    /** Scaffold a typed address record per channel, validating the parameters the document constrains. */
    @Parameter(property = "protogen.scaffoldChannels", defaultValue = "true")
    boolean scaffoldChannels;

    /** Scaffold a {@code java.util.function} stub per operation, binding {@code byte[]}. */
    @Parameter(property = "protogen.scaffoldStubs", defaultValue = "true")
    boolean scaffoldStubs;

    /** Scaffold the Spring Cloud Stream binding configuration for the Solace binder. */
    @Parameter(property = "protogen.scaffoldBinderConfig", defaultValue = "true")
    boolean scaffoldBinderConfig;

    /** Scaffold a README explaining what each scaffolded file is. */
    @Parameter(property = "protogen.scaffoldNotes", defaultValue = "true")
    boolean scaffoldNotes;

    /** Skips the whole execution. */
    @Parameter(property = "protogen.skip", defaultValue = "false")
    boolean skip;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /** Parsed schemas by absolute path: one file can be reached from the source root and from a payload ref. */
    private final Map<Path, ProtoFile> parsed = new HashMap<>();

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("protogen: skipped");
            return;
        }
        ProtoCompiler compiler = new ProtoCompiler(new ProtoCompiler.Options(javaPackage, emitJavadoc,
                failOnUnsupported, preserveUnknownFields, emitValidation, emitSchemaMetadata));

        List<Document> documents = readAsyncApiDocuments();
        Path sourceRoot = protoSourceRoot.toPath();
        Set<Path> protoFiles = new LinkedHashSet<>(Files.isDirectory(sourceRoot) ? discover(sourceRoot) : List.of());
        int fromSourceRoot = protoFiles.size();
        addProtosReferencedByAsyncApi(documents, protoFiles, compiler);

        if (protoFiles.isEmpty()) {
            getLog().info("protogen: no .proto files under " + sourceRoot);
            // an AsyncAPI document stands on its own, so carry on rather than returning
            scaffold(documents);
            return;
        }
        getLog().info("protogen: " + fromSourceRoot + " .proto file(s) under " + sourceRoot
                + (protoFiles.size() > fromSourceRoot
                ? ", " + (protoFiles.size() - fromSourceRoot) + " more referenced by an AsyncAPI payload"
                : ""));

        List<JavaGenerator.GeneratedFile> generated;
        try {
            List<ProtoFile> parsed = protoFiles.stream().map(p -> parse(p, compiler)).toList();
            for (ProtoFile file : parsed) {
                getLog().info("  " + file.fileName() + " -> package " + file.javaPackage()
                        + " (" + (file.messages().size() + file.enums().size()) + " type(s))");
            }
            generated = compiler.generate(compiler.link(parsed));
        } catch (ProtoCompileException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        Path sourceDir = outputDirectory.toPath();
        Path resourceDir = resourceOutputDirectory.toPath();
        int sources = 0;
        int resources = 0;
        try {
            for (JavaGenerator.GeneratedFile file : generated) {
                boolean isSource = file.kind() == JavaGenerator.Kind.SOURCE;
                Path target = (isSource ? sourceDir : resourceDir).resolve(file.relativePath());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.content(), StandardCharsets.UTF_8);
                getLog().debug("protogen: wrote " + target);
                if (isSource) {
                    sources++;
                } else {
                    resources++;
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("protogen: cannot write generated output", e);
        }
        getLog().info("protogen: generated " + sources + " Java file(s) into " + sourceDir);

        scaffold(documents);

        project.addCompileSourceRoot(sourceDir.toString());
        if (resources > 0) {
            getLog().info("protogen: generated " + resources + " metadata file(s) into " + resourceDir);
            Resource resource = new Resource();
            resource.setDirectory(resourceDir.toString());
            project.addResource(resource);
        }
    }

    /** An AsyncAPI document and where it was read from, which is what its payload refs resolve against. */
    private record Document(Path path, AsyncApi api) {
    }

    /**
     * Reads every AsyncAPI document under the configured root.
     *
     * @return the parsed documents, or empty when no root is configured
     * @throws MojoExecutionException if a document is AsyncAPI but malformed
     */
    private List<Document> readAsyncApiDocuments() throws MojoExecutionException {
        if (asyncApiSourceRoot == null) {
            return List.of();
        }
        Path root = asyncApiSourceRoot.toPath();
        if (!Files.isDirectory(root)) {
            getLog().info("protogen: no AsyncAPI source root at " + root + ", nothing to scaffold");
            return List.of();
        }
        List<Path> candidates;
        try (Stream<Path> walk = Files.walk(root)) {
            candidates = walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new MojoExecutionException("protogen: cannot scan " + root, e);
        }

        List<Document> documents = new ArrayList<>();
        for (Path candidate : candidates) {
            try {
                documents.add(new Document(candidate, AsyncApiParser.of(candidate).parse()));
            } catch (ProtoCompileException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            } catch (RuntimeException e) {
                // a directory of yaml is rarely all AsyncAPI; anything else is simply not ours
                getLog().debug("protogen: " + candidate.getFileName() + " is not an AsyncAPI document");
            }
        }
        return documents;
    }

    /**
     * Adds the {@code .proto} files an AsyncAPI payload points at, so the models it describes are generated
     * even when they live outside the proto source root - which is the normal case for a spec-first project
     * where the document is the contract.
     * <p>
     * A payload ref is resolved next to its document first, then against the proto source root, then by
     * file name anywhere under either. Whatever it pulls in brings its own imports along.
     *
     * @param documents  the parsed documents
     * @param protoFiles the set to add to, in discovery order
     * @param compiler   used to read the imports of the files that are pulled in
     */
    private void addProtosReferencedByAsyncApi(List<Document> documents, Set<Path> protoFiles,
                                               ProtoCompiler compiler) throws MojoExecutionException {
        for (Document document : documents) {
            for (AsyncApi.Channel channel : document.api().channels()) {
                for (AsyncApi.Message message : channel.messages()) {
                    if (!message.isProtobuf()) {
                        continue;
                    }
                    Path resolved = resolveProtoReference(message.protoFile(), document.path());
                    if (resolved == null) {
                        getLog().warn("protogen: " + document.path().getFileName() + " channel '"
                                + channel.id() + "' refers to " + message.protoFile()
                                + ", which is not under " + protoSourceRoot + " or next to the document"
                                + " - no model generated for it");
                        continue;
                    }
                    addWithImports(resolved, protoFiles, compiler);
                }
            }
        }
    }

    /**
     * Adds a schema and, transitively, everything it imports.
     *
     * @param proto      the schema to add
     * @param protoFiles the set to add to
     * @param compiler   used to read the file's imports
     */
    private void addWithImports(Path proto, Set<Path> protoFiles, ProtoCompiler compiler)
            throws MojoExecutionException {
        if (!protoFiles.add(proto.toAbsolutePath().normalize())) {
            return;
        }
        ProtoFile parsed;
        try {
            parsed = parse(proto, compiler);
        } catch (ProtoCompileException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        for (String imported : parsed.imports()) {
            if (imported.startsWith("google/protobuf/")) {
                // the well-known types are mapped, not generated
                continue;
            }
            Path resolved = resolveProtoReference(imported, proto);
            if (resolved != null) {
                addWithImports(resolved, protoFiles, compiler);
            } else {
                getLog().warn("protogen: " + proto.getFileName() + " imports " + imported
                        + ", which was not found - the reference it satisfies will fail to resolve");
            }
        }
    }

    /**
     * Finds the file a payload ref or an import names.
     *
     * @param reference the path as written in the document or the import
     * @param relativeTo the file the reference was written in
     * @return the resolved schema, or {@code null} when there is no such file
     */
    private Path resolveProtoReference(String reference, Path relativeTo) {
        String path = reference.startsWith("#") ? reference.substring(reference.indexOf('/') + 1) : reference;
        Path sibling = relativeTo.toAbsolutePath().getParent().resolve(path).normalize();
        if (Files.isRegularFile(sibling)) {
            return sibling;
        }
        Path underSourceRoot = protoSourceRoot.toPath().resolve(path).normalize();
        if (Files.isRegularFile(underSourceRoot)) {
            return underSourceRoot;
        }
        String name = Path.of(path).getFileName().toString();
        for (Path root : new Path[]{protoSourceRoot.toPath(),
                asyncApiSourceRoot == null ? null : asyncApiSourceRoot.toPath()}) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                Optional<Path> found = walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equals(name))
                        .sorted()
                        .findFirst();
                if (found.isPresent()) {
                    return found.get().toAbsolutePath().normalize();
                }
            } catch (IOException e) {
                getLog().debug("protogen: cannot scan " + root + " for " + name);
            }
        }
        return null;
    }

    /** Parses once per path, since a schema can be reached both from the source root and from a payload ref. */
    private ProtoFile parse(Path proto, ProtoCompiler compiler) {
        return parsed.computeIfAbsent(proto.toAbsolutePath().normalize(), compiler::parse);
    }

    /**
     * Writes the scaffolding for the documents that were read.
     * <p>
     * The output is never added as a source root: it is help, not a build artefact, and code the generator
     * guessed at has no business compiling into the application by accident.
     *
     * @param documents the parsed documents
     */
    private void scaffold(List<Document> documents) throws MojoExecutionException {
        AsyncApiEmitter.Options options = new AsyncApiEmitter.Options(
                scaffoldChannels, scaffoldStubs, scaffoldBinderConfig, scaffoldNotes);
        Path out = scaffoldOutputDirectory.toPath();
        int written = 0;
        for (Document document : documents) {
            AsyncApi api = document.api();
            String targetPackage = scaffoldPackage != null ? scaffoldPackage : defaultScaffoldPackage();
            try {
                for (JavaGenerator.GeneratedFile file : new AsyncApiEmitter(api, targetPackage, options).emit()) {
                    Path target = out.resolve(file.relativePath());
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, file.content(), StandardCharsets.UTF_8);
                    written++;
                }
            } catch (IOException e) {
                throw new MojoExecutionException("protogen: cannot write scaffolding to " + out, e);
            }
            getLog().info("protogen: scaffolded " + api.title() + " (AsyncAPI " + api.version() + ", "
                    + api.channels().size() + " channel(s), " + api.operations().size() + " operation(s))");
        }
        if (written > 0) {
            getLog().info("protogen: wrote " + written + " scaffold file(s) into " + out
                    + " - not compiled, adapt and move what you need");
        }
    }

    /** The scaffolding lands next to the messages unless told otherwise. */
    private String defaultScaffoldPackage() {
        if (javaPackage != null) {
            return javaPackage;
        }
        return parsed.values().stream()
                .map(ProtoFile::javaPackage)
                .filter(p -> p != null && !p.isBlank())
                .findFirst()
                .orElse("protogen.scaffold");
    }

    private List<Path> discover(Path sourceRoot) throws MojoExecutionException {
        List<PathMatcher> in = includes.stream().map(GenerateMojo::glob).toList();
        List<PathMatcher> ex = excludes.stream().map(GenerateMojo::glob).toList();
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            List<Path> found = new ArrayList<>();
            walk.filter(Files::isRegularFile).forEach(p -> {
                Path rel = sourceRoot.relativize(p);
                if (in.stream().anyMatch(m -> m.matches(rel)) && ex.stream().noneMatch(m -> m.matches(rel))) {
                    found.add(p);
                }
            });
            found.sort(Path::compareTo);
            return found;
        } catch (IOException e) {
            throw new MojoExecutionException("protogen: cannot scan " + sourceRoot, e);
        }
    }

    /**
     * Builds a matcher for an Ant-style include/exclude pattern.
     * <p>
     * Java's {@code glob:**}{@code /*.proto} requires at least one directory separator, so it would not match a
     * {@code .proto} sitting directly in the source root. Maven users expect it to, so a leading
     * {@code **}{@code /} is treated as optional.
     */
    private static PathMatcher glob(String pattern) {
        PathMatcher full = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        if (!pattern.startsWith("**/")) {
            return full;
        }
        PathMatcher flat = FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3));
        return path -> full.matches(path) || flat.matches(path);
    }
}
