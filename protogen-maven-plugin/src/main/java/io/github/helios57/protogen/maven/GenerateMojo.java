package io.github.helios57.protogen.maven;

import io.github.helios57.protogen.compiler.ProtoCompileException;
import io.github.helios57.protogen.compiler.ProtoCompiler;
import io.github.helios57.protogen.compiler.model.ProtoFile;
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

    /** Package receiving the generated {@code ProtoWire} codec. Defaults to the common java package + {@code .protogen}. */
    @Parameter(property = "protogen.runtimePackage")
    String runtimePackage;

    /** Keep unknown fields so messages survive a round trip through an older schema. */
    @Parameter(property = "protogen.preserveUnknownFields", defaultValue = "true")
    boolean preserveUnknownFields;

    /** Carry leading {@code .proto} comments into the generated Javadoc. */
    @Parameter(property = "protogen.emitJavadoc", defaultValue = "true")
    boolean emitJavadoc;

    /** Fail the build on constructs protogen does not support, instead of skipping them. */
    @Parameter(property = "protogen.failOnUnsupported", defaultValue = "true")
    boolean failOnUnsupported;

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
        if (!Files.isDirectory(sourceRoot)) {
            getLog().info("protogen: no proto source root at " + sourceRoot + ", nothing to do");
            return;
        }

        List<Path> protoFiles = discover(sourceRoot);
        if (protoFiles.isEmpty()) {
            getLog().info("protogen: no .proto files under " + sourceRoot);
            return;
        }
        getLog().info("protogen: " + protoFiles.size() + " .proto file(s) under " + sourceRoot);

        ProtoCompiler compiler = new ProtoCompiler(new ProtoCompiler.Options(
                javaPackage, runtimePackage, preserveUnknownFields, emitJavadoc, failOnUnsupported));

        List<ProtoFile> parsed;
        try {
            parsed = compiler.parse(protoFiles);
        } catch (ProtoCompileException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        for (ProtoFile file : parsed) {
            getLog().info("  " + file.fileName() + " -> package " + file.javaPackage()
                    + " (" + file.types().size() + " type(s))");
        }

        Path outDir = outputDirectory.toPath();
        try {
            Files.createDirectories(outDir);
            for (ProtoCompiler.GeneratedFile generated : compiler.generate(parsed)) {
                Path target = outDir.resolve(generated.relativePath());
                Files.createDirectories(target.getParent());
                Files.writeString(target, generated.content(), StandardCharsets.UTF_8);
                getLog().debug("protogen: wrote " + target);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("protogen: cannot write generated sources to " + outDir, e);
        }

        project.addCompileSourceRoot(outDir.toString());
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
