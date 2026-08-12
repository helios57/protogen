package io.github.helios57.protogen.compiler.model;

/**
 * A {@code google.protobuf} type that is mapped onto something the JDK already has, rather than generated.
 * <p>
 * These need no {@code import} in the schema: protogen knows what they are, because their definitions are
 * fixed and public. Each is encoded exactly as {@code protoc} encodes it - a length-delimited submessage
 * with the documented field numbers - so a peer built with {@code protoc} reads and writes the same bytes.
 * What differs is only the Java surface: a {@code Timestamp} is an {@link java.time.Instant} rather than a
 * pair of numbers, and a wrapper is the nullable value it exists to carry.
 * <p>
 * The rest of the well-known types - {@code Any}, {@code Struct}, {@code Empty} and friends - have no
 * counterpart in the JDK and are generated as ordinary records instead.
 */
public enum WellKnown {

    /** {@code {int64 seconds = 1; int32 nanos = 2;}}, as {@link java.time.Instant}. */
    TIMESTAMP("google.protobuf.Timestamp", "java.time.Instant", null),
    /** {@code {int64 seconds = 1; int32 nanos = 2;}}, as {@link java.time.Duration}. */
    DURATION("google.protobuf.Duration", "java.time.Duration", null),

    /** {@code {double value = 1;}}, as a nullable {@code Double}. */
    DOUBLE_VALUE("google.protobuf.DoubleValue", "Double", ScalarType.DOUBLE),
    /** {@code {float value = 1;}}, as a nullable {@code Float}. */
    FLOAT_VALUE("google.protobuf.FloatValue", "Float", ScalarType.FLOAT),
    /** {@code {int64 value = 1;}}, as a nullable {@code Long}. */
    INT64_VALUE("google.protobuf.Int64Value", "Long", ScalarType.INT64),
    /** {@code {uint64 value = 1;}}, as a nullable {@code Long}. */
    UINT64_VALUE("google.protobuf.UInt64Value", "Long", ScalarType.UINT64),
    /** {@code {int32 value = 1;}}, as a nullable {@code Integer}. */
    INT32_VALUE("google.protobuf.Int32Value", "Integer", ScalarType.INT32),
    /** {@code {uint32 value = 1;}}, as a nullable {@code Integer}. */
    UINT32_VALUE("google.protobuf.UInt32Value", "Integer", ScalarType.UINT32),
    /** {@code {bool value = 1;}}, as a nullable {@code Boolean}. */
    BOOL_VALUE("google.protobuf.BoolValue", "Boolean", ScalarType.BOOL),
    /** {@code {string value = 1;}}, as a nullable {@code String}. */
    STRING_VALUE("google.protobuf.StringValue", "String", ScalarType.STRING),
    /** {@code {bytes value = 1;}}, as a nullable {@code byte[]}. */
    BYTES_VALUE("google.protobuf.BytesValue", "byte[]", ScalarType.BYTES);

    private final String protoName;
    private final String javaType;
    private final ScalarType wrapped;

    WellKnown(String protoName, String javaType, ScalarType wrapped) {
        this.protoName = protoName;
        this.javaType = javaType;
        this.wrapped = wrapped;
    }

    /**
     * The type as it is written in a schema.
     *
     * @return the fully qualified proto name, without a leading dot
     */
    public String protoName() {
        return protoName;
    }

    /**
     * The Java type a field of this type surfaces as.
     *
     * @return the Java type, always a reference type so absence can be {@code null}
     */
    public String javaType() {
        return javaType;
    }

    /**
     * The scalar a wrapper carries in its single field.
     *
     * @return the wrapped scalar, or {@code null} for {@code Timestamp} and {@code Duration}
     */
    public ScalarType wrapped() {
        return wrapped;
    }

    /**
     * Whether this is one of the nine wrapper types, which carry one value and exist to make it nullable.
     *
     * @return whether this type wraps a single scalar
     */
    public boolean isWrapper() {
        return wrapped != null;
    }

    /**
     * Looks a type up by the name a schema wrote.
     *
     * @param protoName the fully qualified proto name, without a leading dot
     * @return the mapped type, or {@code null} if it is not one of these
     */
    public static WellKnown byProtoName(String protoName) {
        for (WellKnown candidate : values()) {
            if (candidate.protoName.equals(protoName)) {
                return candidate;
            }
        }
        return null;
    }
}
