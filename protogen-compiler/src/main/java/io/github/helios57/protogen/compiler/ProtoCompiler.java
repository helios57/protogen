package io.github.helios57.protogen.compiler;

import io.github.helios57.protogen.compiler.model.ProtoFile;
import io.github.helios57.protogen.compiler.parser.ProtoParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Build-tool agnostic entry point: {@code .proto} sources in, self-contained Java sources out.
 * <p>
 * Deliberately free of Maven types so the same compiler can be driven from a CLI, a Gradle plugin or a test.
 * The Mojo is a thin wrapper over this class.
 */
public final class ProtoCompiler {

    private final Options options;

    public ProtoCompiler(Options options) {
        this.options = options;
    }

    /** Parses every input file. */
    public List<ProtoFile> parse(List<Path> protoFiles) {
        List<ProtoFile> parsed = new ArrayList<>(protoFiles.size());
        for (Path p : protoFiles) {
            parsed.add(parse(p));
        }
        return parsed;
    }

    /** Parses a single file. */
    public ProtoFile parse(Path protoFile) {
        try {
            String source = Files.readString(protoFile, StandardCharsets.UTF_8);
            return new ProtoParser(protoFile.getFileName().toString(), source).parse();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + protoFile, e);
        }
    }

    /**
     * Generates Java sources for the given files.
     * <p>
     * Phase 2/3 of PLAN.md. Until the emitters exist this returns an empty list rather than pretending to work.
     *
     * @return the files to be written, relative paths plus content
     */
    public List<GeneratedFile> generate(List<ProtoFile> files) {
        // TODO phase 2: emit ProtoWire.java + message/enum classes. See PLAN.md section 3.
        return List.of();
    }

    /** Compiler configuration. Mirrors the Mojo parameters, see PLAN.md section 5. */
    public record Options(String javaPackageOverride,
                          String runtimePackage,
                          boolean preserveUnknownFields,
                          boolean emitJavadoc,
                          boolean failOnUnsupported) {

        public static Options defaults() {
            return new Options(null, null, true, true, true);
        }
    }

    /**
     * One generated Java source file.
     *
     * @param relativePath path relative to the output directory, e.g. {@code com/example/Foo.java}
     * @param content      the full source text
     */
    public record GeneratedFile(String relativePath, String content) {
    }
}
