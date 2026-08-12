package io.github.helios57.protogen.it;

import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AsyncAPI scaffolding is help rather than a build output, so nothing in the normal build compiles it.
 * That makes it exactly the kind of output that rots unnoticed - these compile it on purpose.
 */
class AsyncApiScaffoldTest {

    private static final Path SCAFFOLD = Path.of("target", "protogen-scaffold");

    private static List<Path> scaffoldedJava() throws IOException {
        if (!Files.isDirectory(SCAFFOLD)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(SCAFFOLD)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void theScaffoldIsWrittenOutsideTheCompiledSources() {
        assertThat(SCAFFOLD).isDirectory();
        // it must not have been added as a source root, or a half-written guess would reach the artifact
        assertThat(Path.of("target", "classes", "protogen", "it", "scaffold")).doesNotExist();
    }

    @Test
    void everyScaffoldedFileIsThere() throws IOException {
        assertThat(SCAFFOLD.resolve("README.md")).exists();
        assertThat(SCAFFOLD.resolve("application-example-metrics.yaml")).exists();
        assertThat(scaffoldedJava())
                .extracting(p -> p.getFileName().toString())
                .containsExactlyInAnyOrder("IncrementalChannel.java", "ControlChannel.java",
                        "PublishIncrementalPublisher.java", "ReadControlListener.java");
    }

    @Test
    void theScaffoldedJavaCompiles() throws IOException {
        List<Path> sources = scaffoldedJava();
        assertThat(sources).isNotEmpty();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("these tests need a JDK, not a JRE").isNotNull();
        Path out = Files.createTempDirectory("scaffold-compile");

        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            boolean compiled = compiler.getTask(null, files, null,
                            List.of("-d", out.toString(), "--release", "17"), null,
                            files.getJavaFileObjectsFromPaths(sources))
                    .call();
            assertThat(compiled).as("the scaffolded Java must at least compile").isTrue();
        }
    }

    @Test
    void theChannelRecordResolvesItsAddress() throws Exception {
        // exercise the scaffolded record through the compiler, since it is not on the test classpath
        Path out = Files.createTempDirectory("scaffold-run");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            compiler.getTask(null, files, null, List.of("-d", out.toString(), "--release", "17"), null,
                    files.getJavaFileObjectsFromPaths(scaffoldedJava())).call();
        }

        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{out.toUri().toURL()})) {
            Class<?> channel = loader.loadClass("protogen.it.scaffold.IncrementalChannel");
            Object instance = channel.getConstructor(String.class, String.class).newInstance("acme", "prod");

            assertThat(channel.getMethod("address").invoke(instance))
                    .isEqualTo("example/metric/incremental/acme/prod");
        }
    }

    @Test
    void theChannelRecordRejectsAValueTheDocumentDisallows() throws Exception {
        Path out = Files.createTempDirectory("scaffold-reject");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            compiler.getTask(null, files, null, List.of("-d", out.toString(), "--release", "17"), null,
                    files.getJavaFileObjectsFromPaths(scaffoldedJava())).call();
        }

        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{out.toUri().toURL()})) {
            Class<?> channel = loader.loadClass("protogen.it.scaffold.IncrementalChannel");
            var constructor = channel.getConstructor(String.class, String.class);

            // 'staging' is not in the document's enum [dev, int, prod]
            assertThat(org.assertj.core.api.Assertions
                    .catchThrowable(() -> constructor.newInstance("acme", "staging")))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theBinderConfigBindsEachOperationToItsChannelAddress() throws IOException {
        String yaml = Files.readString(SCAFFOLD.resolve("application-example-metrics.yaml"));

        assertThat(yaml)
                .contains("binder: solace")
                // send becomes an out binding, receive an in binding
                .contains("publishIncremental-out-0:")
                .contains("destination: example/metric/incremental/{tenant}/{stage}")
                .contains("readControl-in-0:")
                .contains("destination: example/metric/control")
                .contains("contentType: \"application/gzip-protobuf\"")
                // only consumers need a queue
                .contains("queueNameExpression");
        assertThat(yaml).doesNotContain("publishIncremental-in-0:");
    }

    @Test
    void theStubsBindBytesRatherThanTheGeneratedRecords() throws IOException {
        String listener = Files.readString(SCAFFOLD.resolve("protogen/it/scaffold/ReadControlListener.java"));
        String publisher = Files.readString(
                SCAFFOLD.resolve("protogen/it/scaffold/PublishIncrementalPublisher.java"));

        // what crosses the binder is a byte[]; serializing and compressing is application logic
        assertThat(listener).contains("implements Consumer<byte[]>").contains("gzip");
        assertThat(publisher).contains("implements Supplier<byte[]>");
    }
}
