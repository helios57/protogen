package io.github.helios57.protogen.compiler.linker;

import io.github.helios57.protogen.compiler.ProtoCompileException;
import io.github.helios57.protogen.compiler.model.Defs;
import io.github.helios57.protogen.compiler.model.ProtoFile;
import io.github.helios57.protogen.compiler.model.ScalarType;
import io.github.helios57.protogen.compiler.model.WellKnown;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the symbol table across all parsed files and resolves every field's type reference.
 * <p>
 * Name resolution follows the proto rule: a relative name is looked up in the innermost enclosing scope
 * first, then outward, then at file scope. A leading dot forces a fully qualified lookup.
 */
public final class Linker {

    /** Creates a linker with an empty symbol table. */
    public Linker() {
    }

    private final Map<String, Defs.TypeDef> symbols = new LinkedHashMap<>();

    /** Per file, the files whose types it may name: itself, its imports, and their {@code import public}s. */
    private final Map<String, Set<String>> visibleFiles = new LinkedHashMap<>();

    /**
     * Links the given files into a resolved schema.
     *
     * @param files the parsed files, which must include everything they refer to
     * @return the linked schema
     * @throws io.github.helios57.protogen.compiler.ProtoCompileException if a reference cannot be resolved
     */
    public Schema link(List<ProtoFile> files) {
        List<ProtoFile> bundled = WellKnownTypes.requiredBy(files, javaPackageFor(files));
        if (!bundled.isEmpty()) {
            List<ProtoFile> all = new java.util.ArrayList<>(bundled);
            all.addAll(files);
            files = all;
        }
        for (ProtoFile file : files) {
            String scope = file.protoPackage();
            for (Defs.MessageDef m : file.messages()) {
                register(m, null, file, scope);
            }
            for (Defs.EnumDef e : file.enums()) {
                register(e, null, file, scope);
            }
        }
        computeVisibility(files);
        for (ProtoFile file : files) {
            for (Defs.MessageDef m : file.messages()) {
                resolveMessage(m, file);
            }
        }
        return new Schema(List.copyOf(files), Map.copyOf(symbols));
    }

    /**
     * Where the bundled well-known definitions are generated.
     * <p>
     * Into the first java package the schema uses, so they land next to the messages that name them
     * rather than in {@code com.google.protobuf}, which would collide with {@code protobuf-java} for
     * anyone who has it on the classpath.
     *
     * @param files the parsed schema
     * @return the java package to generate them into
     */
    private static String javaPackageFor(List<ProtoFile> files) {
        for (ProtoFile file : files) {
            if (!file.javaPackage().isEmpty()) {
                return file.javaPackage();
            }
        }
        return "";
    }

    /**
     * Works out which files each file is allowed to name types from.
     * <p>
     * A file sees itself, everything it imports, and - transitively - everything those imports re-export
     * with {@code import public}. An import naming a file that was not handed to the linker is simply not
     * in the set: the reference it was meant to satisfy then fails to resolve on its own, which is a better
     * diagnostic than complaining about the import.
     *
     * @param files the parsed files
     */
    private void computeVisibility(List<ProtoFile> files) {
        Map<String, ProtoFile> byName = new LinkedHashMap<>();
        for (ProtoFile file : files) {
            byName.put(file.fileName(), file);
        }
        for (ProtoFile file : files) {
            Set<String> visible = new LinkedHashSet<>();
            visible.add(file.fileName());
            Deque<String> pending = new ArrayDeque<>();
            for (String imported : file.imports()) {
                pending.add(imported);
            }
            while (!pending.isEmpty()) {
                ProtoFile imported = resolveImport(pending.poll(), byName);
                if (imported == null || !visible.add(imported.fileName())) {
                    continue;
                }
                // only 'import public' travels one hop further; a plain import stops here
                pending.addAll(imported.publicImports());
            }
            visibleFiles.put(file.fileName(), visible);
        }
    }

    /**
     * Matches an import path to a parsed file.
     * <p>
     * An import is written relative to the proto root ({@code model/common.proto}) while a parsed file
     * carries the name it was read under, so the base names are compared when the full paths do not match.
     *
     * @param imported the import path as written
     * @param byName   the parsed files by file name
     * @return the file the import refers to, or {@code null} when it was not handed to the linker
     */
    private static ProtoFile resolveImport(String imported, Map<String, ProtoFile> byName) {
        ProtoFile exact = byName.get(imported);
        if (exact != null) {
            return exact;
        }
        String base = baseName(imported);
        for (ProtoFile candidate : byName.values()) {
            if (baseName(candidate.fileName()).equals(base)) {
                return candidate;
            }
        }
        return null;
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * Rejects a type reference that reaches into a file the referencing file never imported.
     *
     * @param found the type that was resolved
     * @param field the field naming it
     * @param file  the file the field is declared in
     */
    private void assertImported(Defs.TypeDef found, Defs.FieldDef field, ProtoFile file) {
        String declaredIn = found.file().fileName();
        Set<String> visible = visibleFiles.get(file.fileName());
        if (visible == null || visible.contains(declaredIn)) {
            return;
        }
        // a bundled definition was never imported because it never had to be
        if (WellKnownTypes.isBundled(declaredIn)) {
            return;
        }
        throw new ProtoCompileException(field.pos(), "type '" + field.typeName() + "' is declared in "
                + declaredIn + ", which " + file.fileName() + " does not import");
    }

    private void register(Defs.MessageDef message, Defs.MessageDef parent, ProtoFile file, String scope) {
        String fullName = scope.isEmpty() ? message.name() : scope + "." + message.name();
        message.link(parent, file, fullName);
        putSymbol(fullName, message, message.pos().toString());
        for (Defs.MessageDef nested : message.nestedMessages()) {
            register(nested, message, file, fullName);
        }
        for (Defs.EnumDef nested : message.nestedEnums()) {
            register(nested, message, file, fullName);
        }
    }

    private void register(Defs.EnumDef def, Defs.MessageDef parent, ProtoFile file, String scope) {
        assertNotReserved(def);
        String fullName = scope.isEmpty() ? def.name() : scope + "." + def.name();
        def.link(parent, file, fullName);
        putSymbol(fullName, def, def.pos().toString());
    }

    private void putSymbol(String fullName, Defs.TypeDef def, String where) {
        Defs.TypeDef previous = symbols.put(fullName, def);
        if (previous != null) {
            throw new ProtoCompileException("duplicate type '" + fullName + "' declared at " + where
                    + " and in " + previous.file().fileName());
        }
    }

    private void resolveMessage(Defs.MessageDef message, ProtoFile file) {
        for (Defs.FieldDef field : message.fields()) {
            if (field.kind() == Defs.Kind.MAP) {
                resolveMapField(field, message, file);
            } else {
                resolve(field, message, file);
            }
        }
        assertUniqueFieldNumbers(message);
        for (Defs.MessageDef nested : message.nestedMessages()) {
            resolveMessage(nested, file);
        }
    }

    private void assertUniqueFieldNumbers(Defs.MessageDef message) {
        // a set rather than a list scan: a message with a thousand fields would otherwise cost half a
        // million comparisons here, which showed up as most of the link time when profiling
        Set<Integer> seen = new HashSet<>();
        for (Defs.FieldDef f : message.fields()) {
            if (!seen.add(f.number())) {
                throw new ProtoCompileException(f.pos(),
                        "field number " + f.number() + " is used twice in message '" + message.name() + "'");
            }
        }
        assertNotReserved(message);
    }

    /** A reserved number or name records a past mistake; reusing it silently would repeat it. */
    private void assertNotReserved(Defs.MessageDef message) {
        for (Defs.FieldDef f : message.fields()) {
            for (int[] range : message.reservedRanges()) {
                if (f.number() >= range[0] && f.number() <= range[1]) {
                    throw new ProtoCompileException(f.pos(), "field number " + f.number()
                            + " is reserved in message '" + message.name() + "'");
                }
            }
            if (message.reservedNames().contains(f.name())) {
                throw new ProtoCompileException(f.pos(), "field name '" + f.name()
                        + "' is reserved in message '" + message.name() + "'");
            }
        }
    }

    private void assertNotReserved(Defs.EnumDef def) {
        for (Defs.EnumValueDef v : def.values()) {
            for (int[] range : def.reservedRanges()) {
                if (v.number() >= range[0] && v.number() <= range[1]) {
                    throw new ProtoCompileException(def.pos(), "enum value " + v.number()
                            + " is reserved in enum '" + def.name() + "'");
                }
            }
            if (def.reservedNames().contains(v.name())) {
                throw new ProtoCompileException(def.pos(), "enum constant '" + v.name()
                        + "' is reserved in enum '" + def.name() + "'");
            }
        }
    }

    private void resolveMapField(Defs.FieldDef field, Defs.MessageDef scope, ProtoFile file) {
        resolve(field.mapKey(), scope, file);
        resolve(field.mapValue(), scope, file);
        if (field.mapKey().kind() != Defs.Kind.SCALAR
                || field.mapKey().scalar() == ScalarType.BYTES
                || field.mapKey().scalar() == ScalarType.DOUBLE
                || field.mapKey().scalar() == ScalarType.FLOAT) {
            throw new ProtoCompileException(field.pos(),
                    "map key must be an integral, bool or string type, found '" + field.mapKey().typeName() + "'");
        }
        if (field.mapValue().kind() == Defs.Kind.MAP) {
            throw new ProtoCompileException(field.pos(), "a map value cannot itself be a map");
        }
    }

    private void resolve(Defs.FieldDef field, Defs.MessageDef scope, ProtoFile file) {
        field.linkFile(file);
        String typeName = field.typeName();

        ScalarType scalar = ScalarType.byProtoName(typeName);
        if (scalar != null) {
            field.resolveScalar(scalar);
            return;
        }

        String normalized = typeName.startsWith(".") ? typeName.substring(1) : typeName;
        // the well-known types have fixed public definitions, so they need no import to be understood.
        // only the qualified name maps: an unqualified Timestamp is whatever the schema's own scope says
        WellKnown wellKnown = WellKnown.byProtoName(normalized);
        if (wellKnown != null) {
            field.resolveWellKnown(wellKnown);
            return;
        }

        Defs.TypeDef found = lookup(typeName, scope, file);
        if (found == null) {
            throw new ProtoCompileException(field.pos(),
                    "cannot resolve type '" + typeName + "' for field '" + field.name() + "'");
        }
        if (found instanceof Defs.MessageDef m && m.mapEntry()) {
            throw new ProtoCompileException(field.pos(), "cannot refer to a synthetic map entry type");
        }
        assertImported(found, field, file);
        field.resolveType(found);
    }

    private Defs.TypeDef lookup(String typeName, Defs.MessageDef scope, ProtoFile file) {
        if (typeName.startsWith(".")) {
            return symbols.get(typeName.substring(1));
        }
        // innermost scope outward
        for (Defs.MessageDef s = scope; s != null; s = s.parent()) {
            Defs.TypeDef found = symbols.get(s.fullName() + "." + typeName);
            if (found != null) {
                return found;
            }
        }
        // enclosing proto packages, longest prefix first
        String pkg = file.protoPackage();
        while (!pkg.isEmpty()) {
            Defs.TypeDef found = symbols.get(pkg + "." + typeName);
            if (found != null) {
                return found;
            }
            int dot = pkg.lastIndexOf('.');
            pkg = dot < 0 ? "" : pkg.substring(0, dot);
        }
        return symbols.get(typeName);
    }
}
