package io.github.helios57.protogen.compiler.gen;

import io.github.helios57.protogen.compiler.ProtoCompiler;
import io.github.helios57.protogen.compiler.model.ProtoFile;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Generates from a schema, compiles the result with {@code javac}, and loads it.
 * <p>
 * Asserting on generated <em>text</em> only proves the emitter wrote what the test expected it to write.
 * Anything whose correctness is a claim about behaviour - that a hand-written scan decides a pattern the
 * way the regex would, that a size plan and a write agree - has to run.
 */
final class GeneratedCode implements AutoCloseable {

    private final Path directory;
    private final URLClassLoader loader;

    private GeneratedCode(Path directory, URLClassLoader loader) {
        this.directory = directory;
        this.loader = loader;
    }

    /**
     * Compiles the given schemas.
     *
     * @param sources the {@code .proto} sources, named {@code file0.proto} onwards
     * @return the loaded classes, to be closed when done
     */
    static GeneratedCode of(String... sources) {
        return of(ProtoCompiler.Options.defaults(), sources);
    }

    /**
     * Compiles the given schemas with explicit options.
     *
     * @param options what to generate
     * @param sources the {@code .proto} sources, named {@code file0.proto} onwards
     * @return the loaded classes, to be closed when done
     */
    static GeneratedCode of(ProtoCompiler.Options options, String... sources) {
        ProtoCompiler compiler = new ProtoCompiler(options);
        List<ProtoFile> parsed = new ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            parsed.add(compiler.parse("file" + i + ".proto", sources[i]));
        }
        try {
            Path directory = Files.createTempDirectory("protogen-generated");
            List<Path> java = new ArrayList<>();
            for (JavaGenerator.GeneratedFile file : compiler.generate(compiler.link(parsed))) {
                if (file.kind() != JavaGenerator.Kind.SOURCE) {
                    continue;
                }
                Path target = directory.resolve(file.relativePath());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.content(), StandardCharsets.UTF_8);
                java.add(target);
            }
            compile(directory, java);
            return new GeneratedCode(directory,
                    new URLClassLoader(new URL[]{directory.toUri().toURL()}, GeneratedCode.class.getClassLoader()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void compile(Path directory, List<Path> java) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("these tests need a JDK, not a JRE");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            boolean compiled = compiler.getTask(null, files, diagnostics,
                            List.of("-d", directory.toString(), "--release", "17"), null,
                            files.getJavaFileObjectsFromPaths(java))
                    .call();
            if (!compiled) {
                throw new IllegalStateException("the generated code does not compile:\n"
                        + diagnostics.getDiagnostics().stream().map(Object::toString).toList());
            }
        }
    }

    /**
     * Loads a generated class.
     *
     * @param binaryName the fully qualified name, nested types separated by {@code $}
     * @return the loaded class
     */
    Class<?> load(String binaryName) {
        try {
            return loader.loadClass(binaryName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("not generated: " + binaryName, e);
        }
    }

    @Override
    public void close() throws IOException {
        loader.close();
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
