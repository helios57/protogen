package io.github.helios57.protogen.compiler.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A parsed {@code .proto} file.
 * <p>
 * Phase 0 populates the header and the top-level type names only. Phase 1 (PLAN.md) fills in the full
 * declaration tree - fields, nested types, oneofs, maps, reserved ranges.
 *
 * @param fileName    the source file name
 * @param syntax      declared syntax, e.g. {@code proto3}
 * @param protoPackage declared {@code package}, or empty
 * @param imports     imported file names, in declaration order
 * @param options     file-level options, e.g. {@code java_package}
 * @param types       top-level declared types
 */
public record ProtoFile(String fileName,
                        String syntax,
                        String protoPackage,
                        List<String> imports,
                        Map<String, String> options,
                        List<TypeDecl> types) {

    /** @return the effective Java package: {@code option java_package} if present, else the proto package */
    public String javaPackage() {
        return Optional.ofNullable(options.get("java_package"))
                .filter(s -> !s.isBlank())
                .orElse(protoPackage);
    }

    /** @return {@code true} if each message gets its own top-level Java file (proto3 default here is {@code false}) */
    public boolean javaMultipleFiles() {
        return Boolean.parseBoolean(options.getOrDefault("java_multiple_files", "false"));
    }

    /** A top-level or nested type declaration. */
    public record TypeDecl(Kind kind, String name, String comment) {
        /** Whether the declaration is a {@code message} or an {@code enum}. */
        public enum Kind { MESSAGE, ENUM }
    }
}
