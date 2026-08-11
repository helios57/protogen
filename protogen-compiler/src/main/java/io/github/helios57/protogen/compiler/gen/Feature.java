package io.github.helios57.protogen.compiler.gen;

import io.github.helios57.protogen.compiler.model.Defs;
import io.github.helios57.protogen.compiler.model.ProtoFile;
import io.github.helios57.protogen.compiler.model.ScalarType;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A single helper in the generated {@code ProtoWire} codec.
 * <p>
 * The generator collects the features a Java package actually uses and emits only those, so a schema of
 * nothing but strings does not carry zig-zag or fixed-width helpers it will never call.
 */
public enum Feature {

    W_UVARINT32,
    W_VARINT32,
    W_VARINT64,
    W_FIXED32,
    W_FIXED64,
    W_STRING,
    W_BYTES,

    S_UVARINT32,
    S_VARINT32,
    S_VARINT64,
    S_STRING,
    S_BYTES,
    UTF8_LEN,

    ZIGZAG32,
    ZIGZAG64,

    R_FIXED32,
    R_FIXED64,
    R_STRING,
    R_BYTES,
    R_BOOL,
    /** {@code pushLimit}/{@code popLimit}, needed for packed repeated fields and map entries. */
    R_LIMIT,
    /** {@code array}/{@code slice}, needed to hand a submessage its byte range without copying. */
    R_SLICE;

    /** Features this one is implemented in terms of. */
    public Set<Feature> requires() {
        return switch (this) {
            case W_VARINT32 -> EnumSet.of(W_UVARINT32, W_VARINT64);
            case W_STRING -> EnumSet.of(W_UVARINT32);
            case W_BYTES -> EnumSet.of(W_UVARINT32);
            case S_STRING -> EnumSet.of(S_UVARINT32, UTF8_LEN);
            case S_BYTES -> EnumSet.of(S_UVARINT32);
            case R_STRING, R_BYTES -> EnumSet.noneOf(Feature.class);
            default -> EnumSet.noneOf(Feature.class);
        };
    }

    /** Expands {@code seed} with everything it transitively depends on. */
    public static EnumSet<Feature> close(Set<Feature> seed) {
        EnumSet<Feature> all = EnumSet.noneOf(Feature.class);
        List<Feature> queue = new java.util.ArrayList<>(seed);
        while (!queue.isEmpty()) {
            Feature f = queue.remove(queue.size() - 1);
            if (all.add(f)) {
                queue.addAll(f.requires());
            }
        }
        return all;
    }

    /** Collects the features needed by every message in the given files. */
    public static EnumSet<Feature> of(List<ProtoFile> files) {
        EnumSet<Feature> used = EnumSet.noneOf(Feature.class);
        for (ProtoFile file : files) {
            for (Defs.MessageDef m : file.messages()) {
                collect(m, used);
            }
        }
        return close(used);
    }

    private static void collect(Defs.MessageDef message, EnumSet<Feature> used) {
        for (Defs.FieldDef field : message.fields()) {
            collect(field, used);
        }
        for (Defs.MessageDef nested : message.nestedMessages()) {
            collect(nested, used);
        }
    }

    private static void collect(Defs.FieldDef field, EnumSet<Feature> used) {
        if (field.repeated() && isPacked(field)) {
            used.add(Feature.R_LIMIT);
            used.add(Feature.W_UVARINT32);
            used.add(Feature.S_UVARINT32);
        }
        switch (field.kind()) {
            case SCALAR -> collect(field.scalar(), used);
            case ENUM -> {
                used.add(Feature.W_VARINT32);
                used.add(Feature.S_VARINT32);
            }
            case MESSAGE -> {
                used.add(Feature.W_UVARINT32);
                used.add(Feature.S_UVARINT32);
                used.add(Feature.R_SLICE);
            }
            case TIMESTAMP -> {
                // encoded as an int64 of epoch milliseconds
                used.add(Feature.W_VARINT64);
                used.add(Feature.S_VARINT64);
            }
            case MAP -> {
                used.add(Feature.W_UVARINT32);
                used.add(Feature.S_UVARINT32);
                used.add(Feature.R_LIMIT);
                collect(field.mapKey(), used);
                collect(field.mapValue(), used);
            }
            default -> throw new IllegalStateException("unresolved field " + field.name());
        }
    }

    private static void collect(ScalarType scalar, EnumSet<Feature> used) {
        switch (scalar) {
            case DOUBLE -> {
                used.add(Feature.W_FIXED64);
                used.add(Feature.R_FIXED64);
            }
            case FLOAT -> {
                used.add(Feature.W_FIXED32);
                used.add(Feature.R_FIXED32);
            }
            case INT32 -> {
                used.add(Feature.W_VARINT32);
                used.add(Feature.S_VARINT32);
            }
            case UINT32 -> {
                used.add(Feature.W_UVARINT32);
                used.add(Feature.S_UVARINT32);
            }
            case INT64, UINT64 -> {
                used.add(Feature.W_VARINT64);
                used.add(Feature.S_VARINT64);
            }
            case SINT32 -> {
                used.add(Feature.ZIGZAG32);
                used.add(Feature.W_UVARINT32);
                used.add(Feature.S_UVARINT32);
            }
            case SINT64 -> {
                used.add(Feature.ZIGZAG64);
                used.add(Feature.W_VARINT64);
                used.add(Feature.S_VARINT64);
            }
            case FIXED32, SFIXED32 -> {
                used.add(Feature.W_FIXED32);
                used.add(Feature.R_FIXED32);
            }
            case FIXED64, SFIXED64 -> {
                used.add(Feature.W_FIXED64);
                used.add(Feature.R_FIXED64);
            }
            case BOOL -> used.add(Feature.R_BOOL);
            case STRING -> {
                used.add(Feature.W_STRING);
                used.add(Feature.S_STRING);
                used.add(Feature.R_STRING);
            }
            case BYTES -> {
                used.add(Feature.W_BYTES);
                used.add(Feature.S_BYTES);
                used.add(Feature.R_BYTES);
            }
        }
    }

    /** @return whether a repeated field is packed, which in proto3 is every repeated numeric or enum field */
    public static boolean isPacked(Defs.FieldDef field) {
        return field.repeated()
                && (field.kind() == Defs.Kind.ENUM
                || field.kind() == Defs.Kind.TIMESTAMP
                || (field.kind() == Defs.Kind.SCALAR && field.scalar().packable()));
    }
}
