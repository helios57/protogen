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
import java.util.List;
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

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("protogen: skipped");
            return;
        }
        Path sourceRoot = protoSourceRoot.toPath();
        List<Path> protoFiles = Files.isDirectory(sourceRoot) ? discover(sourceRoot) : List.of();
        if (protoFiles.isEmpty()) {
            getLog().info("protogen: no .proto files under " + sourceRoot);
            // an AsyncAPI document stands on its own, so carry on rather than returning
            scaffoldFromAsyncApi();
            return;
        }
        getLog().info("protogen: " + protoFiles.size() + " .proto file(s) under " + sourceRoot);

        ProtoCompiler compiler = new ProtoCompiler(new ProtoCompiler.Options(javaPackage, emitJavadoc,
                failOnUnsupported, preserveUnknownFields, emitValidation, emitSchemaMetadata));

        List<JavaGenerator.GeneratedFile> generated;
        try {
            List<ProtoFile> parsed = compiler.parse(protoFiles);
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

        scaffoldFromAsyncApi();

        project.addCompileSourceRoot(sourceDir.toString());
        if (resources > 0) {
            getLog().info("protogen: generated " + resources + " metadata file(s) into " + resourceDir);
            Resource resource = new Resource();
            resource.setDirectory(resourceDir.toString());
            project.addResource(resource);
        }
    }

    /**
     * Reads the AsyncAPI documents, if any, and writes the scaffolding.
     * <p>
     * The output is never added as a source root: it is help, not a build artefact, and code the generator
     * guessed at has no business compiling into the application by accident.
     */
    private void scaffoldFromAsyncApi() throws MojoExecutionException {
        if (asyncApiSourceRoot == null) {
            return;
        }
        Path root = asyncApiSourceRoot.toPath();
        if (!Files.isDirectory(root)) {
            getLog().info("protogen: no AsyncAPI source root at " + root + ", nothing to scaffold");
            return;
        }
        List<Path> documents;
        try (Stream<Path> walk = Files.walk(root)) {
            documents = walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new MojoExecutionException("protogen: cannot scan " + root, e);
        }

        AsyncApiEmitter.Options options = new AsyncApiEmitter.Options(
                scaffoldChannels, scaffoldStubs, scaffoldBinderConfig, scaffoldNotes);
        Path out = scaffoldOutputDirectory.toPath();
        int written = 0;
        for (Path document : documents) {
            AsyncApi api;
            try {
                api = AsyncApiParser.of(document).parse();
            } catch (ProtoCompileException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            } catch (RuntimeException e) {
                // a directory of yaml is rarely all AsyncAPI; anything else is simply not ours
                getLog().debug("protogen: " + document.getFileName() + " is not an AsyncAPI document");
                continue;
            }
            String targetPackage = scaffoldPackage != null ? scaffoldPackage : javaPackageOfFirstProto();
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
    private String javaPackageOfFirstProto() {
        return javaPackage != null ? javaPackage : "protogen.scaffold";
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
