package io.github.helios57.protogen.compiler.model;

import java.util.Locale;

/** The proto3 scalar types, with everything code generation needs to know about each. */
public enum ScalarType {

    /** 64-bit IEEE 754, fixed width. */
    DOUBLE("double", "double", "Double", WireType.FIXED64, "0.0D", true),
    /** 32-bit IEEE 754, fixed width. */
    FLOAT("float", "float", "Float", WireType.FIXED32, "0.0F", true),
    /** Signed 32-bit varint; negatives cost ten bytes. */
    INT32("int32", "int", "Integer", WireType.VARINT, "0", true),
    /** Signed 64-bit varint; negatives cost ten bytes. */
    INT64("int64", "long", "Long", WireType.VARINT, "0L", true),
    /** Unsigned 32-bit varint. */
    UINT32("uint32", "int", "Integer", WireType.VARINT, "0", true),
    /** Unsigned 64-bit varint. */
    UINT64("uint64", "long", "Long", WireType.VARINT, "0L", true),
    /** Signed 32-bit varint, zig-zag encoded so small negatives stay small. */
    SINT32("sint32", "int", "Integer", WireType.VARINT, "0", true),
    /** Signed 64-bit varint, zig-zag encoded so small negatives stay small. */
    SINT64("sint64", "long", "Long", WireType.VARINT, "0L", true),
    /** Unsigned 32-bit, fixed width. */
    FIXED32("fixed32", "int", "Integer", WireType.FIXED32, "0", true),
    /** Unsigned 64-bit, fixed width. */
    FIXED64("fixed64", "long", "Long", WireType.FIXED64, "0L", true),
    /** Signed 32-bit, fixed width. */
    SFIXED32("sfixed32", "int", "Integer", WireType.FIXED32, "0", true),
    /** Signed 64-bit, fixed width. */
    SFIXED64("sfixed64", "long", "Long", WireType.FIXED64, "0L", true),
    /** A single byte, 0 or 1. */
    BOOL("bool", "boolean", "Boolean", WireType.VARINT, "false", true),
    /** Length-prefixed UTF-8. */
    STRING("string", "String", "String", WireType.LENGTH_DELIMITED, "\"\"", false),
    /** Length-prefixed raw bytes. */
    BYTES("bytes", "byte[]", "byte[]", WireType.LENGTH_DELIMITED, "ProtoWire.EMPTY_BYTES", false);

    /** Protobuf wire types. */
    public enum WireType {
        /** Variable-length integer. */
        VARINT(0),
        /** Eight bytes, little endian. */
        FIXED64(1),
        /** A length prefix followed by that many bytes. */
        LENGTH_DELIMITED(2),
        /** Four bytes, little endian. */
        FIXED32(5);

        private final int code;

        WireType(int code) {
            this.code = code;
        }

        /**
         * The three-bit code carried in a field tag.
         *
         * @return the wire type code
         */
        public int code() {
            return code;
        }
    }

    private final String protoName;
    private final String javaType;
    private final String boxedType;
    private final WireType wireType;
    private final String defaultLiteral;
    private final boolean packable;

    ScalarType(String protoName, String javaType, String boxedType, WireType wireType,
               String defaultLiteral, boolean packable) {
        this.protoName = protoName;
        this.javaType = javaType;
        this.boxedType = boxedType;
        this.wireType = wireType;
        this.defaultLiteral = defaultLiteral;
        this.packable = packable;
    }

    /**
     * The scalar type with this proto name, or {@code null} if the name is not a scalar.
     *
     * @param name the proto type name to look up
     * @return the scalar type with this proto name, or {@code null} if the name is not a scalar
     */
    public static ScalarType byProtoName(String name) {
        for (ScalarType t : values()) {
            if (t.protoName.equals(name)) {
                return t;
            }
        }
        return null;
    }

    /**
     * The name as written in a schema.
     *
     * @return the proto name of this type
     */
    public String protoName() {
        return protoName;
    }

    /**
     * The Java type used for a singular field, primitive where possible.
     *
     * @return the Java type used for a singular field, primitive where possible
     */
    public String javaType() {
        return javaType;
    }

    /**
     * The Java type usable as a generic argument, e.g. in {@code List<Integer>}.
     *
     * @return the Java type usable as a generic argument, e.g. in {@code List<Integer>}
     */
    public String boxedType() {
        return boxedType;
    }

    /**
     * How values of this type are framed on the wire.
     *
     * @return the wire type
     */
    public WireType wireType() {
        return wireType;
    }

    /**
     * The proto3 default, as a Java expression.
     *
     * @return the default literal
     */
    public String defaultLiteral() {
        return defaultLiteral;
    }

    /**
     * Whether {@code repeated} fields of this type are packed by default in proto3.
     *
     * @return whether {@code repeated} fields of this type are packed by default in proto3
     */
    public boolean packable() {
        return packable;
    }

    /**
     * The {@code ProtoWire.Writer} method that writes a value of this type without its tag.
     *
     * @return the {@code ProtoWire.Writer} method that writes a value of this type without its tag
     */
    public String writerMethod() {
        return switch (this) {
            case DOUBLE -> "writeFixed64";
            case FLOAT -> "writeFixed32";
            case INT32 -> "writeVarint32";
            case INT64, UINT64 -> "writeVarint64";
            case UINT32 -> "writeUnsignedVarint32";
            case SINT32 -> "writeUnsignedVarint32";
            case SINT64 -> "writeVarint64";
            case FIXED32, SFIXED32 -> "writeFixed32";
            case FIXED64, SFIXED64 -> "writeFixed64";
            case BOOL -> "writeBool";
            case STRING -> "writeStringNoTag";
            case BYTES -> "writeBytesNoTag";
        };
    }

    /**
     * An expression converting the Java value {@code v} into what {@link #writerMethod()} expects.
     *
     * @param v a Java expression holding the value
     * @return an expression converting the Java value {@code v} into what {@link #writerMethod()} expects
     */
    public String toWireExpr(String v) {
        return switch (this) {
            case DOUBLE -> "Double.doubleToRawLongBits(" + v + ")";
            case FLOAT -> "Float.floatToRawIntBits(" + v + ")";
            case SINT32 -> "ProtoWire.zigZag32(" + v + ")";
            case SINT64 -> "ProtoWire.zigZag64(" + v + ")";
            default -> v;
        };
    }

    /**
     * An expression reading one value of this type from the {@code ProtoWire.Reader} {@code r}.
     *
     * @param r the name of the reader variable in scope
     * @return an expression reading one value of this type from the {@code ProtoWire.Reader} {@code r}
     */
    public String readExpr(String r) {
        return switch (this) {
            case DOUBLE -> "Double.longBitsToDouble(" + r + ".readFixed64())";
            case FLOAT -> "Float.intBitsToFloat(" + r + ".readFixed32())";
            case INT32, UINT32 -> r + ".readVarint32()";
            case INT64, UINT64 -> r + ".readVarint64()";
            case SINT32 -> "ProtoWire.unZigZag32(" + r + ".readVarint32())";
            case SINT64 -> "ProtoWire.unZigZag64(" + r + ".readVarint64())";
            case FIXED32, SFIXED32 -> r + ".readFixed32()";
            case FIXED64, SFIXED64 -> r + ".readFixed64()";
            case BOOL -> r + ".readBool()";
            case STRING -> r + ".readString()";
            case BYTES -> r + ".readBytes()";
        };
    }

    /**
     * An expression yielding the encoded size of the Java value {@code v}, excluding its tag.
     *
     * @param v a Java expression holding the value
     * @return an expression yielding the encoded size of the Java value {@code v}, excluding its tag
     */
    public String sizeExpr(String v) {
        return switch (this) {
            case DOUBLE, FIXED64, SFIXED64 -> "8";
            case FLOAT, FIXED32, SFIXED32 -> "4";
            case INT32 -> "ProtoWire.varint32Size(" + v + ")";
            case UINT32 -> "ProtoWire.unsignedVarint32Size(" + v + ")";
            case INT64, UINT64 -> "ProtoWire.varint64Size(" + v + ")";
            case SINT32 -> "ProtoWire.unsignedVarint32Size(ProtoWire.zigZag32(" + v + "))";
            case SINT64 -> "ProtoWire.varint64Size(ProtoWire.zigZag64(" + v + "))";
            case BOOL -> "1";
            case STRING -> "ProtoWire.stringSizeNoTag(" + v + ")";
            case BYTES -> "ProtoWire.bytesSizeNoTag(" + v + ")";
        };
    }

    /**
     * An expression that is true when {@code v} differs from this type's proto3 default.
     *
     * @param v a Java expression holding the value
     * @return an expression that is true when {@code v} differs from this type's proto3 default
     */
    public String isNonDefaultExpr(String v) {
        return switch (this) {
            case STRING -> "!" + v + ".isEmpty()";
            case BYTES -> v + ".length > 0";
            case BOOL -> v;
            case DOUBLE -> Double.compare(0, 0) == 0 ? v + " != 0.0D" : v;
            case FLOAT -> v + " != 0.0F";
            case INT64, UINT64, SINT64, FIXED64, SFIXED64 -> v + " != 0L";
            default -> v + " != 0";
        };
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
