package io.github.helios57.protogen.compiler.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A parsed {@code .proto} file: header plus the top-level declaration tree. */
public final class ProtoFile {

    private final String fileName;
    private final String syntax;
    private final String protoPackage;
    private final List<String> imports;
    private final Map<String, String> options;
    private final List<Defs.MessageDef> messages = new ArrayList<>();
    private final List<Defs.EnumDef> enums = new ArrayList<>();

    /**
     * Creates a parsed file. The declaration lists start empty and are filled by the parser.
     *
     * @param fileName     the source file name, used in diagnostics and to derive the outer class
     * @param syntax       the declared syntax, {@code proto2} or {@code proto3}
     * @param protoPackage the declared package, or empty
     * @param imports      imported file names, in declaration order
     * @param options      file-level options such as {@code java_package}
     */
    public ProtoFile(String fileName, String syntax, String protoPackage,
                     List<String> imports, Map<String, String> options) {
        this.fileName = fileName;
        this.syntax = syntax;
        this.protoPackage = protoPackage;
        this.imports = List.copyOf(imports);
        this.options = new LinkedHashMap<>(options);
    }

    /**
     * The source file name.
     *
     * @return the source file name
     */
    public String fileName() {
        return fileName;
    }

    /**
     * Whether the file declares proto3, which changes presence rules and default packing.
     *
     * @return whether the syntax is proto3
     */
    public boolean proto3() {
        return "proto3".equals(syntax);
    }

    /**
     * The declared syntax.
     *
     * @return the declared syntax
     */
    public String syntax() {
        return syntax;
    }

    /**
     * The declared proto package, or empty.
     *
     * @return the declared proto package, or empty
     */
    public String protoPackage() {
        return protoPackage;
    }

    /**
     * The imported file names, in declaration order.
     *
     * @return the imported file names, in declaration order
     */
    public List<String> imports() {
        return imports;
    }

    /**
     * The file-level options, by name.
     *
     * @return the file-level options, by name
     */
    public Map<String, String> options() {
        return options;
    }

    /**
     * The top-level messages, in declaration order.
     *
     * @return the top-level messages, in declaration order
     */
    public List<Defs.MessageDef> messages() {
        return messages;
    }

    /**
     * The top-level enums, in declaration order.
     *
     * @return the top-level enums, in declaration order
     */
    public List<Defs.EnumDef> enums() {
        return enums;
    }

    /**
     * The package the generated types land in.
     *
     * @return {@code option java_package} if set, else the proto package
     */
    public String javaPackage() {
        String javaPackage = options.get("java_package");
        return javaPackage != null && !javaPackage.isBlank() ? javaPackage : protoPackage;
    }

    /**
     * Which of the two file layouts to generate.
     *
     * @return whether each top-level type becomes its own Java file
     */
    public boolean javaMultipleFiles() {
        return Boolean.parseBoolean(options.getOrDefault("java_multiple_files", "false"));
    }

    /**
     * The wrapper class generated when {@link #javaMultipleFiles()} is {@code false}.
     *
     * @return the {@code java_outer_classname} option, else the file's base name in upper camel case,
     *         with {@code OuterClass} appended if that would collide with a declared type
     */
    public String javaOuterClassName() {
        String explicit = options.get("java_outer_classname");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String base = fileName;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        if (base.endsWith(".proto")) {
            base = base.substring(0, base.length() - ".proto".length());
        }
        String candidate = Names.toUpperCamel(base);
        // protoc appends OuterClass when the wrapper would collide with a declared type
        boolean collides = messages.stream().anyMatch(m -> m.name().equals(candidate))
                || enums.stream().anyMatch(e -> e.name().equals(candidate));
        return collides ? candidate + "OuterClass" : candidate;
    }
}
