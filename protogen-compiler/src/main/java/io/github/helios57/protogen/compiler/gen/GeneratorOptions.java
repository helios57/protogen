package io.github.helios57.protogen.compiler.gen;

/**
 * The subset of the compiler configuration the emitters care about.
 *
 * @param emitJavadoc           carry schema comments into the generated Javadoc
 * @param preserveUnknownFields add a trailing component holding the bytes of fields this build does not know
 * @param emitValidation        generate the checks declared by the schema's annotations
 * @param emitSchemaMetadata    write the JSON sidecar describing examples, root nodes and constraints
 */
public record GeneratorOptions(boolean emitJavadoc,
                               boolean preserveUnknownFields,
                               boolean emitValidation,
                               boolean emitSchemaMetadata) {

    /**
     * The defaults: Javadoc, validation and metadata on, unknown fields dropped.
     *
     * @return the defaults: Javadoc, validation and metadata on, unknown fields dropped
     */
    public static GeneratorOptions defaults() {
        return new GeneratorOptions(true, false, true, true);
    }
}
