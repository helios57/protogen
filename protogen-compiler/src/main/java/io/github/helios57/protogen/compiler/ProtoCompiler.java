package io.github.helios57.protogen.compiler;

import io.github.helios57.protogen.compiler.gen.JavaGenerator;
import io.github.helios57.protogen.compiler.linker.Linker;
import io.github.helios57.protogen.compiler.linker.Schema;
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

    /** Parses, links and generates in one step. */
    public List<JavaGenerator.GeneratedFile> compile(List<Path> protoFiles) {
        return generate(link(parse(protoFiles)));
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
            return parse(protoFile.getFileName().toString(), source);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + protoFile, e);
        }
    }

    /** Parses proto source held in memory, for tests. */
    public ProtoFile parse(String fileName, String source) {
        return new ProtoParser(fileName, source).parse();
    }

    /** Resolves every type reference across the given files. */
    public Schema link(List<ProtoFile> files) {
        return new Linker().link(files);
    }

    /** Generates the Java sources for a linked schema. */
    public List<JavaGenerator.GeneratedFile> generate(Schema schema) {
        return new JavaGenerator(new io.github.helios57.protogen.compiler.gen.GeneratorOptions(
                options.emitJavadoc(), options.preserveUnknownFields(), options.emitValidation(),
                options.emitSchemaMetadata())).generate(schema);
    }

    /**
     * Compiler configuration. Mirrors the Mojo parameters, see PLAN.md section 7.
     *
     * @param javaPackageOverride      replaces {@code option java_package} for every file, or {@code null}
     * @param emitJavadoc              carry schema comments into the generated Javadoc
     * @param failOnUnsupported        reject unsupported constructs rather than skipping them
     * @param preserveUnknownFields    keep fields this build does not know in a trailing component, so a
     *                                 message survives a round trip through a newer schema
     * @param emitValidation           generate the checks declared by the schema's {@code @Minimum} style
     *                                 annotations; the generated code can still switch them off at runtime
     * @param emitSchemaMetadata       write a JSON sidecar describing examples, root nodes and constraints,
     *                                 for documentation pipelines to consume
     */
    public record Options(String javaPackageOverride,
                          boolean emitJavadoc,
                          boolean failOnUnsupported,
                          boolean preserveUnknownFields,
                          boolean emitValidation,
                          boolean emitSchemaMetadata) {

        public static Options defaults() {
            return new Options(null, true, true, false, true, true);
        }
    }
}
