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

    /**
     * An import is not the only way to reach outside the JDK - a fully qualified name works too, and would
     * slip past a check that only reads import statements.
     */
    @Test
    void generatedSourcesNameNoPackageOutsideTheJdkOrTheirOwn() throws IOException {
        Pattern qualified = Pattern.compile("\\b([a-z][a-z0-9]*(?:\\.[a-z][a-z0-9_]*)+)\\.[A-Z]\\w*");
        List<String> violations = new ArrayList<>();

        List<String> ownPackages = generatedPackages();

        for (Path java : generatedJavaFiles()) {
            Matcher m = qualified.matcher(withoutComments(Files.readString(java)));
            while (m.find()) {
                String pkg = m.group(1);
                boolean allowed = pkg.startsWith("java.") || pkg.startsWith("javax.")
                        || ownPackages.stream().anyMatch(own -> own.equals(pkg) || own.startsWith(pkg + "."));
                if (!allowed) {
                    violations.add(java.getFileName() + " references " + pkg);
                }
            }
        }

        assertThat(violations)
                .as("generated code may name only JDK types and its own generated types")
                .isEmpty();
    }

    /**
     * Strips comments, because the check is about what the code names.
     * <p>
     * A javadoc line saying which well-known type a helper encodes is prose about protobuf, not a
     * reference to anything - and a check that cannot tell the difference would push the generator into
     * writing worse comments.
     *
     * @param source the generated Java
     * @return the same source with block and line comments blanked out
     */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    @Test
    void theGeneratedCodecIsPrunedToWhatTheSchemaUses() throws IOException {
        Path codec = GENERATED.resolve("protogen/it/model/ProtoWire.java");
        assertThat(codec).exists();
        String source = Files.readString(codec);

        // these sample schemas use sint32/sint64, so the zig-zag helpers must be there
        assertThat(source).contains("static int zz32(").contains("static long zz64(");
        // and they never use a group, an extension or a text format, so none of that may appear
        assertThat(source).doesNotContain("class Builder").doesNotContain("Descriptor");
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

    /** @return the Java packages protogen generated into, derived from the output tree */
    private static List<String> generatedPackages() throws IOException {
        return generatedJavaFiles().stream()
                .map(p -> GENERATED.relativize(p).getParent())
                .filter(java.util.Objects::nonNull)
                .map(p -> p.toString().replace(java.io.File.separatorChar, '.'))
                .distinct()
                .toList();
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
