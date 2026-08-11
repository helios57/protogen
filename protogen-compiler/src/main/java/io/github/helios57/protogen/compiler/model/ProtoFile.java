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

    public ProtoFile(String fileName, String syntax, String protoPackage,
                     List<String> imports, Map<String, String> options) {
        this.fileName = fileName;
        this.syntax = syntax;
        this.protoPackage = protoPackage;
        this.imports = List.copyOf(imports);
        this.options = new LinkedHashMap<>(options);
    }

    public String fileName() {
        return fileName;
    }

    public String syntax() {
        return syntax;
    }

    public String protoPackage() {
        return protoPackage;
    }

    public List<String> imports() {
        return imports;
    }

    public Map<String, String> options() {
        return options;
    }

    public List<Defs.MessageDef> messages() {
        return messages;
    }

    public List<Defs.EnumDef> enums() {
        return enums;
    }

    /** @return the effective Java package: {@code option java_package} if set, else the proto package */
    public String javaPackage() {
        String javaPackage = options.get("java_package");
        return javaPackage != null && !javaPackage.isBlank() ? javaPackage : protoPackage;
    }

    /** @return whether each top-level type becomes its own Java file */
    public boolean javaMultipleFiles() {
        return Boolean.parseBoolean(options.getOrDefault("java_multiple_files", "false"));
    }

    /**
     * @return the wrapper class name used when {@link #javaMultipleFiles()} is {@code false}: the
     * {@code java_outer_classname} option, else the file's base name in upper camel case
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
