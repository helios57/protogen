package io.github.helios57.protogen.compiler.linker;

import io.github.helios57.protogen.compiler.ProtoCompileException;
import io.github.helios57.protogen.compiler.model.Defs;
import io.github.helios57.protogen.compiler.model.ProtoFile;
import io.github.helios57.protogen.compiler.model.ScalarType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** {@code google.protobuf.Timestamp}, mapped to {@link java.time.Instant} rather than generated. */
    public static final String TIMESTAMP = "google.protobuf.Timestamp";

    private static final List<String> KNOWN_UNSUPPORTED_WELL_KNOWN = List.of(
            "google.protobuf.Any", "google.protobuf.Duration", "google.protobuf.Struct",
            "google.protobuf.Value", "google.protobuf.FieldMask", "google.protobuf.Empty",
            "google.type.Date", "google.type.DateTime", "google.type.TimeOfDay");

    private final Map<String, Defs.TypeDef> symbols = new LinkedHashMap<>();

    /**
     * Links the given files into a resolved schema.
     *
     * @param files the parsed files, which must include everything they refer to
     * @return the linked schema
     * @throws io.github.helios57.protogen.compiler.ProtoCompileException if a reference cannot be resolved
     */
    public Schema link(List<ProtoFile> files) {
        for (ProtoFile file : files) {
            String scope = file.protoPackage();
            for (Defs.MessageDef m : file.messages()) {
                register(m, null, file, scope);
            }
            for (Defs.EnumDef e : file.enums()) {
                register(e, null, file, scope);
            }
        }
        for (ProtoFile file : files) {
            for (Defs.MessageDef m : file.messages()) {
                resolveMessage(m, file);
            }
        }
        return new Schema(List.copyOf(files), Map.copyOf(symbols));
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
        List<Integer> seen = new ArrayList<>();
        for (Defs.FieldDef f : message.fields()) {
            if (seen.contains(f.number())) {
                throw new ProtoCompileException(f.pos(),
                        "field number " + f.number() + " is used twice in message '" + message.name() + "'");
            }
            seen.add(f.number());
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
        String typeName = field.typeName();

        ScalarType scalar = ScalarType.byProtoName(typeName);
        if (scalar != null) {
            field.resolveScalar(scalar);
            return;
        }

        String normalized = typeName.startsWith(".") ? typeName.substring(1) : typeName;
        if (TIMESTAMP.equals(normalized)) {
            field.resolveTimestamp();
            return;
        }
        if (KNOWN_UNSUPPORTED_WELL_KNOWN.contains(normalized)) {
            throw new ProtoCompileException(field.pos(), "well-known type '" + normalized
                    + "' is not supported; only google.protobuf.Timestamp is, mapped to java.time.Instant");
        }

        Defs.TypeDef found = lookup(typeName, scope, file);
        if (found == null) {
            throw new ProtoCompileException(field.pos(),
                    "cannot resolve type '" + typeName + "' for field '" + field.name() + "'");
        }
        if (found instanceof Defs.MessageDef m && m.mapEntry()) {
            throw new ProtoCompileException(field.pos(), "cannot refer to a synthetic map entry type");
        }
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
