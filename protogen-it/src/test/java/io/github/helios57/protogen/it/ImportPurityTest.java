package io.github.helios57.protogen.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards PLAN.md invariant I1: generated sources may import nothing but {@code java.*}.
 * <p>
 * This is the test that keeps the project honest. LightProto's README claims "no runtime dependencies"
 * while its generated codec imports {@code io.netty.buffer.ByteBuf} - exactly the erosion this prevents.
 */
class ImportPurityTest {

    private static final Path GENERATED = Path.of("target", "generated-sources", "protogen");
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);

    @Test
    void generatedSourcesImportOnlyTheJdk() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path java : generatedJavaFiles()) {
            String source = Files.readString(java);
            Matcher m = IMPORT.matcher(source);
            while (m.find()) {
                String imported = m.group(1);
                if (!imported.startsWith("java.") && !imported.startsWith("javax.")) {
                    violations.add(java.getFileName() + " imports " + imported);
                }
            }
        }

        assertThat(violations)
                .as("generated code must depend on the JDK only")
                .isEmpty();
    }

    @Test
    void generatedSourcesUseNoReflection() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path java : generatedJavaFiles()) {
            String source = Files.readString(java);
            for (String banned : List.of("Class.forName", "java.lang.reflect", "sun.misc.Unsafe", "getDeclaredField")) {
                if (source.contains(banned)) {
                    violations.add(java.getFileName() + " uses " + banned);
                }
            }
        }

        assertThat(violations)
                .as("generated code must not use reflection (PLAN.md I4)")
                .isEmpty();
    }

    private static List<Path> generatedJavaFiles() throws IOException {
        if (!Files.isDirectory(GENERATED)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(GENERATED)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
