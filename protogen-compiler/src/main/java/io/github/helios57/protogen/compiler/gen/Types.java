package io.github.helios57.protogen.compiler.gen;

import io.github.helios57.protogen.compiler.model.Defs;
import io.github.helios57.protogen.compiler.model.ProtoFile;
import io.github.helios57.protogen.compiler.model.ScalarType;

/** Maps proto declarations onto Java types and names. */
final class Types {

    private Types() {
    }

    /** The wire tag of a field, i.e. {@code (number << 3) | wireType}. */
    static int tag(Defs.FieldDef field) {
        return (field.number() << 3) | wireType(field);
    }

    /** The wire type a field's values are written with, ignoring packing. */
    static int wireType(Defs.FieldDef field) {
        return switch (field.kind()) {
            case SCALAR -> field.scalar().wireType().code();
            case ENUM -> 0;
            case TIMESTAMP -> 0;
            case MESSAGE, MAP -> 2;
        };
    }

    /** The tag actually written for a field, which is length-delimited when the field is packed or a map. */
    static int writtenTag(Defs.FieldDef field) {
        if (field.kind() == Defs.Kind.MAP || Feature.isPacked(field)) {
            return (field.number() << 3) | 2;
        }
        return tag(field);
    }

    static int tagSize(int tag) {
        int size = 1;
        int value = tag;
        while ((value & ~0x7f) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    /** The fully qualified Java name of a generated type. */
    static String javaName(Defs.TypeDef def) {
        ProtoFile file = def.file();
        StringBuilder nested = new StringBuilder(def.name());
        for (Defs.MessageDef p = def.parent(); p != null; p = p.parent()) {
            nested.insert(0, p.name() + ".");
        }
        StringBuilder out = new StringBuilder();
        if (!file.javaPackage().isEmpty()) {
            out.append(file.javaPackage()).append('.');
        }
        if (!file.javaMultipleFiles()) {
            out.append(file.javaOuterClassName()).append('.');
        }
        return out.append(nested).toString();
    }

    /**
     * Whether a referenced type is generated into the package being emitted.
     * <p>
     * Only then can its package-private {@code parse(ProtoWire.R)} be called: across a package boundary a
     * message's surface is {@code byte[]} and {@code int}, which is what keeps generated packages from
     * depending on one another.
     */
    static boolean inPackage(Defs.TypeDef def, String currentPackage) {
        return def.file().javaPackage().equals(currentPackage);
    }

    /** The name to write in source, shortened when the type lives in {@code currentPackage}. */
    static String javaName(Defs.TypeDef def, String currentPackage) {
        String full = javaName(def);
        String pkg = def.file().javaPackage();
        if (pkg.equals(currentPackage) && !pkg.isEmpty()) {
            return full.substring(pkg.length() + 1);
        }
        return full;
    }

    /** The Java type of a record component for {@code field}. */
    static String componentType(Defs.FieldDef field, String currentPackage) {
        if (field.kind() == Defs.Kind.MAP) {
            return "java.util.Map<" + boxedElementType(field.mapKey(), currentPackage) + ", "
                    + boxedElementType(field.mapValue(), currentPackage) + ">";
        }
        if (field.repeated()) {
            return "java.util.List<" + boxedElementType(field, currentPackage) + ">";
        }
        if (nullable(field)) {
            return boxedElementType(field, currentPackage);
        }
        return switch (field.kind()) {
            case SCALAR -> field.scalar().javaType();
            case ENUM, MESSAGE -> javaName(field.resolved(), currentPackage);
            case TIMESTAMP -> "java.time.Instant";
            case MAP -> throw new IllegalStateException();
        };
    }

    /** The Java type usable as a generic argument or a nullable component. */
    static String boxedElementType(Defs.FieldDef field, String currentPackage) {
        return switch (field.kind()) {
            case SCALAR -> field.scalar().boxedType();
            case ENUM, MESSAGE -> javaName(field.resolved(), currentPackage);
            case TIMESTAMP -> "java.time.Instant";
            case MAP -> throw new IllegalStateException("nested map");
        };
    }

    /** The value a singular field holds when absent, as a Java expression. */
    static String defaultExpr(Defs.FieldDef field, String currentPackage) {
        if (field.kind() == Defs.Kind.MAP || field.repeated() || nullable(field)) {
            return "null";
        }
        return switch (field.kind()) {
            case SCALAR -> field.scalar() == ScalarType.BYTES ? "new byte[0]" : field.scalar().defaultLiteral();
            case ENUM -> javaName(field.resolved(), currentPackage) + "."
                    + io.github.helios57.protogen.compiler.model.Names
                    .escape(((Defs.EnumDef) field.resolved()).defaultValue().name());
            case MESSAGE, TIMESTAMP -> "null";
            case MAP -> "null";
        };
    }

    /**
     * Whether the component may hold {@code null}.
     *
     * @return whether the component may hold {@code null}
     */
    static boolean nullable(Defs.FieldDef field) {
        if (field.repeated() || field.kind() == Defs.Kind.MAP) {
            return false; // normalised to an empty collection
        }
        if (field.label() == Defs.Label.OPTIONAL || field.inOneof()) {
            return true;
        }
        // proto2 required is the one label that guarantees a scalar is there
        if (field.label() == Defs.Label.REQUIRED) {
            return field.kind() == Defs.Kind.MESSAGE || field.kind() == Defs.Kind.TIMESTAMP;
        }
        return field.kind() == Defs.Kind.MESSAGE || field.kind() == Defs.Kind.TIMESTAMP;
    }

    /**
     * Whether the field goes on the wire unconditionally.
     * <p>
     * proto2 {@code required} must be transmitted even at its default, or the message is invalid for the
     * peer that reads it.
     *
     * @param field the field to classify
     * @return whether presence is unconditional
     */
    static boolean alwaysWritten(Defs.FieldDef field) {
        return field.label() == Defs.Label.REQUIRED && !nullable(field);
    }

}
