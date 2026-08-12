package io.github.helios57.protogen.compiler.linker;

import io.github.helios57.protogen.compiler.model.Defs;
import io.github.helios57.protogen.compiler.model.ProtoFile;
import io.github.helios57.protogen.compiler.model.WellKnown;
import io.github.helios57.protogen.compiler.parser.ProtoParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code google.protobuf} types that have no counterpart in the JDK, generated as ordinary records.
 * <p>
 * Their definitions are fixed and public, so a schema naming {@code google.protobuf.Struct} should not
 * have to hand protogen the file that declares it - and, unlike a {@code protoc} build, there is no
 * include path to point at one with. The definitions are bundled as resources and pulled in on demand,
 * transitively: naming {@code Struct} brings {@code Value}, {@code ListValue} and {@code NullValue} with
 * it.
 * <p>
 * They are generated into the java package of the schema that referenced them, like the {@code ProtoWire}
 * codec, rather than into {@code com.google.protobuf} - which would collide head-on with
 * {@code protobuf-java} for anyone who happens to have both on the classpath.
 * <p>
 * {@link WellKnown} handles the other half: the types that <em>do</em> have a counterpart, and are mapped
 * onto it instead of generated.
 */
final class WellKnownTypes {

    private WellKnownTypes() {
    }

    /** Which bundled file declares which type, so a reference can be traced back to one. */
    private static final Map<String, String> DECLARED_IN = new LinkedHashMap<>();

    static {
        declare("any.proto", "Any");
        declare("empty.proto", "Empty");
        declare("field_mask.proto", "FieldMask");
        declare("source_context.proto", "SourceContext");
        declare("struct.proto", "Struct", "Value", "ListValue", "NullValue");
        declare("type.proto", "Type", "Field", "Enum", "EnumValue", "Option", "Syntax");
        declare("api.proto", "Api", "Method", "Mixin");
    }

    private static void declare(String file, String... types) {
        for (String type : types) {
            DECLARED_IN.put("google.protobuf." + type, file);
        }
    }

    /**
     * Loads whatever bundled definitions the given files refer to and do not already have.
     *
     * @param files       the parsed schema, which is not modified
     * @param javaPackage the package to generate the definitions into
     * @return the extra files to link alongside, in dependency order, empty when none are needed
     */
    static List<ProtoFile> requiredBy(List<ProtoFile> files, String javaPackage) {
        Set<String> declared = new LinkedHashSet<>();
        Set<String> referenced = new LinkedHashSet<>();
        for (ProtoFile file : files) {
            for (Defs.MessageDef message : file.messages()) {
                collectDeclared(message, file.protoPackage(), declared);
                collectReferenced(message, referenced);
            }
            for (Defs.EnumDef def : file.enums()) {
                declared.add(qualify(file.protoPackage(), def.name()));
            }
        }

        List<ProtoFile> loaded = new ArrayList<>();
        Set<String> loadedFiles = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        for (String name : referenced) {
            String resource = DECLARED_IN.get(name);
            if (resource != null && !declared.contains(name)) {
                pending.add(resource);
            }
        }
        while (!pending.isEmpty()) {
            String resource = pending.poll();
            if (!loadedFiles.add(resource)) {
                continue;
            }
            ProtoFile parsed = parse(resource, javaPackage);
            loaded.add(parsed);
            // a bundled file may import another, which then has to come along too
            for (String imported : parsed.imports()) {
                String name = imported.substring(imported.lastIndexOf('/') + 1);
                if (DECLARED_IN.containsValue(name)) {
                    pending.add(name);
                }
            }
        }
        // imports before importers, so the linker registers a type before anything names it
        java.util.Collections.reverse(loaded);
        return loaded;
    }

    /**
     * Whether a file is one of the bundled definitions.
     *
     * @param fileName the file name as the linker knows it
     * @return whether protogen supplied it rather than the user
     */
    static boolean isBundled(String fileName) {
        return DECLARED_IN.containsValue(fileName);
    }

    private static ProtoFile parse(String resource, String javaPackage) {
        String path = "/protogen/wellknown/" + resource;
        try (InputStream in = WellKnownTypes.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("protogen is missing its bundled " + path);
            }
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("$PACKAGE$", javaPackage);
            return new ProtoParser(resource, source).parse();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    private static void collectDeclared(Defs.MessageDef message, String scope, Set<String> declared) {
        String fullName = qualify(scope, message.name());
        declared.add(fullName);
        for (Defs.MessageDef nested : message.nestedMessages()) {
            collectDeclared(nested, fullName, declared);
        }
        for (Defs.EnumDef nested : message.nestedEnums()) {
            declared.add(qualify(fullName, nested.name()));
        }
    }

    private static void collectReferenced(Defs.MessageDef message, Set<String> referenced) {
        for (Defs.FieldDef field : message.fields()) {
            collectReferenced(field, referenced);
        }
        for (Defs.MessageDef nested : message.nestedMessages()) {
            collectReferenced(nested, referenced);
        }
    }

    private static void collectReferenced(Defs.FieldDef field, Set<String> referenced) {
        if (field.mapKey() != null) {
            collectReferenced(field.mapKey(), referenced);
            collectReferenced(field.mapValue(), referenced);
            return;
        }
        String typeName = field.typeName();
        if (typeName == null) {
            return;
        }
        String normalized = typeName.startsWith(".") ? typeName.substring(1) : typeName;
        if (WellKnown.byProtoName(normalized) == null) {
            referenced.add(normalized);
        }
    }

    private static String qualify(String scope, String name) {
        return scope == null || scope.isEmpty() ? name : scope + "." + name;
    }
}
