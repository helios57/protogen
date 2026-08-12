package io.github.helios57.protogen.compiler.model;

import io.github.helios57.protogen.compiler.SourcePos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The declaration tree of a parsed {@code .proto} file.
 * <p>
 * These are mutable during parsing and linking, then treated as read-only by the generator. Keeping them
 * mutable avoids threading half-built records through the linker.
 */
public final class Defs {

    private Defs() {
    }

    /** Something a field can refer to by name: a message or an enum. */
    public sealed interface TypeDef permits MessageDef, EnumDef {
        /**
         * The simple name as declared, without any enclosing scope.
         *
         * @return the declared name
         */
        String name();

        /**
         * The name including package and any enclosing messages.
         *
         * @return the fully qualified proto name, e.g. {@code pkg.Outer.Inner}
         */
        String fullName();

        /**
         * The message this type is nested in.
         *
         * @return the enclosing message, or {@code null} for a top-level type
         */
        MessageDef parent();

        /**
         * The file this type was declared in, which decides its Java package and layout.
         *
         * @return the declaring file
         */
        ProtoFile file();
    }

    /** Field cardinality. */
    public enum Label {
        /** Implicit presence: absent and default-valued are indistinguishable. */
        SINGULAR,
        /** Explicit presence via the proto3 {@code optional} keyword. */
        OPTIONAL,
        /** A list of values, packed on the wire where the element type allows it. */
        REPEATED,
        /**
         * proto2's {@code required}. Enforced on construction and on parse, because a message missing one
         * is not a valid instance of its own schema.
         */
        REQUIRED
    }

    /** How a field's Java type is realised, once the type reference has been linked. */
    public enum Kind {
        /** One of the fifteen built-in scalar types. */
        SCALAR,
        /** A reference to a declared enum. */
        ENUM,
        /** A reference to a declared message. */
        MESSAGE,
        /** A {@code map<K, V>}, encoded as a repeated entry submessage. */
        MAP,
        /**
         * {@code google.protobuf.Timestamp}, surfaced as {@link java.time.Instant} and encoded as an
         * {@code int64} of epoch milliseconds.
         */
        TIMESTAMP
    }

    /** A message declaration. */
    public static final class MessageDef implements TypeDef {
        private final String name;
        private final String comment;
        private final SourcePos pos;
        private final List<FieldDef> fields = new ArrayList<>();
        private final List<MessageDef> nestedMessages = new ArrayList<>();
        private final List<EnumDef> nestedEnums = new ArrayList<>();
        private final List<OneofDef> oneofs = new ArrayList<>();
        private MessageDef parent;
        private ProtoFile file;
        private String fullName;
        /** Set for the synthetic entry message of a {@code map} field; such messages are not generated. */
        private boolean mapEntry;
        private final List<int[]> reservedRanges = new ArrayList<>();
        private final List<String> reservedNames = new ArrayList<>();
        private final List<int[]> extensionRanges = new ArrayList<>();

        /**
         * Creates an unlinked message; the linker fills in the parent, file and fully qualified name.
         *
         * @param name    the declared simple name
         * @param comment the leading comment, source of Javadoc and validation annotations
         * @param pos     where it was declared, for diagnostics
         */
        public MessageDef(String name, String comment, SourcePos pos) {
            this.name = name;
            this.comment = comment;
            this.pos = pos;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String fullName() {
            return fullName;
        }

        @Override
        public MessageDef parent() {
            return parent;
        }

        @Override
        public ProtoFile file() {
            return file;
        }

        /**
         * The leading comment, or {@code null} if there was none.
         *
         * @return the leading comment, or {@code null} if there was none
         */
        public String comment() {
            return comment;
        }

        /**
         * Where this message was declared.
         *
         * @return where this message was declared
         */
        public SourcePos pos() {
            return pos;
        }

        /**
         * The declared fields, in declaration order, including oneof members.
         *
         * @return the declared fields, in declaration order, including oneof members
         */
        public List<FieldDef> fields() {
            return fields;
        }

        /**
         * The messages declared inside this one.
         *
         * @return the messages declared inside this one
         */
        public List<MessageDef> nestedMessages() {
            return nestedMessages;
        }

        /**
         * The enums declared inside this one.
         *
         * @return the enums declared inside this one
         */
        public List<EnumDef> nestedEnums() {
            return nestedEnums;
        }

        /**
         * The oneof groups; a field's {@code oneofIndex} indexes into this list.
         *
         * @return the oneof groups; a field's {@code oneofIndex} indexes into this list
         */
        public List<OneofDef> oneofs() {
            return oneofs;
        }

        /**
         * Whether this is the synthetic entry type of a map field, which is not generated.
         *
         * @return whether this is the synthetic entry type of a map field, which is not generated
         */
        public boolean mapEntry() {
            return mapEntry;
        }

        /**
         * Reserved field-number ranges, each an inclusive {@code {from, to}} pair.
         *
         * @return the reserved ranges
         */
        public List<int[]> reservedRanges() {
            return reservedRanges;
        }

        /**
         * Reserved field names.
         *
         * @return the reserved names
         */
        public List<String> reservedNames() {
            return reservedNames;
        }

        /**
         * Proto2 {@code extensions} ranges, each an inclusive {@code {from, to}} pair.
         *
         * @return the ranges declared open for extension
         */
        public List<int[]> extensionRanges() {
            return extensionRanges;
        }

        /** Marks this message as the synthetic entry type of a map field. */
        public void markMapEntry() {
            this.mapEntry = true;
        }

        /**
         * Attaches the resolution the linker computed.
         *
         * @param parent   the enclosing message, or {@code null} at top level
         * @param file     the declaring file
         * @param fullName the fully qualified proto name
         */
        public void link(MessageDef parent, ProtoFile file, String fullName) {
            this.parent = parent;
            this.file = file;
            this.fullName = fullName;
        }
    }

    /** An enum declaration. */
    public static final class EnumDef implements TypeDef {
        private final String name;
        private final String comment;
        private final SourcePos pos;
        private final List<EnumValueDef> values = new ArrayList<>();
        private final List<int[]> reservedRanges = new ArrayList<>();
        private final List<String> reservedNames = new ArrayList<>();
        private boolean allowAlias;
        private MessageDef parent;
        private ProtoFile file;
        private String fullName;

        /**
         * Creates an unlinked enum; the linker fills in the parent, file and fully qualified name.
         *
         * @param name    the declared simple name
         * @param comment the leading comment
         * @param pos     where it was declared, for diagnostics
         */
        public EnumDef(String name, String comment, SourcePos pos) {
            this.name = name;
            this.comment = comment;
            this.pos = pos;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String fullName() {
            return fullName;
        }

        @Override
        public MessageDef parent() {
            return parent;
        }

        @Override
        public ProtoFile file() {
            return file;
        }

        /**
         * The leading comment, or {@code null} if there was none.
         *
         * @return the leading comment, or {@code null} if there was none
         */
        public String comment() {
            return comment;
        }

        /**
         * Where this enum was declared.
         *
         * @return where this enum was declared
         */
        public SourcePos pos() {
            return pos;
        }

        /**
         * The constants, in declaration order, aliases included.
         *
         * @return the constants, in declaration order, aliases included
         */
        public List<EnumValueDef> values() {
            return values;
        }

        /**
         * Reserved value ranges, each an inclusive {@code {from, to}} pair.
         *
         * @return the reserved ranges
         */
        public List<int[]> reservedRanges() {
            return reservedRanges;
        }

        /**
         * Reserved constant names.
         *
         * @return the reserved names
         */
        public List<String> reservedNames() {
            return reservedNames;
        }

        /**
         * Whether {@code option allow_alias} permits two constants to share a number.
         *
         * @return whether {@code option allow_alias} permits two constants to share a number
         */
        public boolean allowAlias() {
            return allowAlias;
        }

        /**
         * Records {@code option allow_alias}.
         *
         * @param allowAlias whether duplicate numbers are permitted
         */
        public void setAllowAlias(boolean allowAlias) {
            this.allowAlias = allowAlias;
        }

        /**
         * The constant a field of this type takes when absent.
         *
         * @return the constant whose number is 0, which proto3 requires to exist
         */
        public EnumValueDef defaultValue() {
            for (EnumValueDef v : values) {
                if (v.number() == 0) {
                    return v;
                }
            }
            return values.isEmpty() ? null : values.get(0);
        }

        /**
         * Attaches the resolution the linker computed.
         *
         * @param parent   the enclosing message, or {@code null} at top level
         * @param file     the declaring file
         * @param fullName the fully qualified proto name
         */
        public void link(MessageDef parent, ProtoFile file, String fullName) {
            this.parent = parent;
            this.file = file;
            this.fullName = fullName;
        }
    }

    /**
     * One enum constant.
     *
     * @param name    the constant name, kept verbatim as protoc does
     * @param number  the wire value
     * @param comment leading comment, for Javadoc
     */
    public record EnumValueDef(String name, int number, String comment) {
    }

    /** A {@code oneof} group. Fields carry the index of the group they belong to. */
    public static final class OneofDef {
        private final String name;
        private final String comment;
        private final List<FieldDef> fields = new ArrayList<>();

        /**
         * Creates an empty oneof group.
         *
         * @param name    the declared name
         * @param comment the leading comment
         */
        public OneofDef(String name, String comment) {
            this.name = name;
            this.comment = comment;
        }

        /**
         * The declared name of the group.
         *
         * @return the declared name of the group
         */
        public String name() {
            return name;
        }

        /**
         * The leading comment, or {@code null} if there was none.
         *
         * @return the leading comment, or {@code null} if there was none
         */
        public String comment() {
            return comment;
        }

        /**
         * The members of this group, in declaration order.
         *
         * @return the members of this group, in declaration order
         */
        public List<FieldDef> fields() {
            return fields;
        }
    }

    /** A field declaration, plus everything the linker resolves about it. */
    public static final class FieldDef {
        private final String name;
        private final int number;
        private final Label label;
        private final String typeName;
        private final String comment;
        private final SourcePos pos;
        private final Constraints constraints;
        private final int oneofIndex;
        /** Field options as written, e.g. {@code packed}, {@code deprecated}, {@code json_name}. */
        private final Map<String, String> options;

        // resolved by the linker
        private ProtoFile file;
        private Kind kind;
        private ScalarType scalar;
        private TypeDef resolved;
        private FieldDef mapKey;
        private FieldDef mapValue;

        /**
         * Creates an unresolved field; the linker decides what {@code typeName} refers to.
         *
         * @param name       the declared name
         * @param number     the wire field number
         * @param label      the cardinality
         * @param typeName   the type as written, resolved later
         * @param comment    the leading comment, source of Javadoc and constraints
         * @param pos        where it was declared, for diagnostics
         * @param oneofIndex index into the message's oneof list, or {@code -1}
         */
        public FieldDef(String name, int number, Label label, String typeName, String comment,
                        SourcePos pos, int oneofIndex) {
            this(name, number, label, typeName, comment, pos, oneofIndex, Map.of());
        }

        /**
         * Creates an unresolved field carrying its declared options.
         *
         * @param name       the declared name
         * @param number     the wire field number
         * @param label      the cardinality
         * @param typeName   the type as written, resolved later
         * @param comment    the leading comment, source of Javadoc and constraints
         * @param pos        where it was declared, for diagnostics
         * @param oneofIndex index into the message's oneof list, or {@code -1}
         * @param options    the field options as written
         */
        public FieldDef(String name, int number, Label label, String typeName, String comment,
                        SourcePos pos, int oneofIndex, Map<String, String> options) {
            this.name = name;
            this.number = number;
            this.label = label;
            this.typeName = typeName;
            this.comment = comment;
            this.pos = pos;
            this.constraints = Constraints.parse(comment);
            this.oneofIndex = oneofIndex;
            this.options = Map.copyOf(options);
        }

        /**
         * The file this field was declared in, which decides the default packing.
         *
         * @return the declaring file, or {@code null} before linking
         */
        public ProtoFile file() {
            return file;
        }

        /**
         * Records the declaring file.
         *
         * @param file the file this field belongs to
         */
        public void linkFile(ProtoFile file) {
            this.file = file;
        }

        /**
         * The field options as written in the schema.
         *
         * @return the options by name, e.g. {@code packed} or {@code deprecated}
         */
        public Map<String, String> options() {
            return options;
        }

        /**
         * Whether {@code [packed = ...]} overrides the default packing for this field.
         *
         * @return the explicit setting, or {@code null} when the schema did not say
         */
        public Boolean packedOverride() {
            String packed = options.get("packed");
            return packed == null ? null : Boolean.valueOf(packed);
        }

        /**
         * The proto2 {@code [default = ...]} literal.
         *
         * @return the default as written, or {@code null} for none
         */
        public String defaultLiteral() {
            return options.get("default");
        }

        /**
         * Whether the schema marks this field {@code [deprecated = true]}.
         *
         * @return whether to emit {@code @Deprecated}
         */
        public boolean deprecated() {
            return Boolean.parseBoolean(options.get("deprecated"));
        }

        /**
         * The declared name.
         *
         * @return the declared name
         */
        public String name() {
            return name;
        }

        /**
         * The wire field number.
         *
         * @return the wire field number
         */
        public int number() {
            return number;
        }

        /**
         * The cardinality.
         *
         * @return the cardinality
         */
        public Label label() {
            return label;
        }

        /**
         * The type as written in the schema, before resolution.
         *
         * @return the type as written in the schema, before resolution
         */
        public String typeName() {
            return typeName;
        }

        /**
         * The leading comment, or {@code null} if there was none.
         *
         * @return the leading comment, or {@code null} if there was none
         */
        public String comment() {
            return comment;
        }

        /**
         * Where this field was declared.
         *
         * @return where this field was declared
         */
        public SourcePos pos() {
            return pos;
        }

        /**
         * The constraints parsed out of the leading comment.
         *
         * @return the constraints parsed out of the leading comment
         */
        public Constraints constraints() {
            return constraints;
        }

        /**
         * The index of the owning oneof, or {@code -1} if the field is not in one.
         *
         * @return the index of the owning oneof, or {@code -1} if the field is not in one
         */
        public int oneofIndex() {
            return oneofIndex;
        }

        /**
         * Whether this field belongs to a oneof group.
         *
         * @return whether this field belongs to a oneof group
         */
        public boolean inOneof() {
            return oneofIndex >= 0;
        }

        /**
         * Whether this field is {@code repeated}.
         *
         * @return whether this field is {@code repeated}
         */
        public boolean repeated() {
            return label == Label.REPEATED;
        }

        /**
         * How the Java type is realised, or {@code null} before linking.
         *
         * @return how the Java type is realised, or {@code null} before linking
         */
        public Kind kind() {
            return kind;
        }

        /**
         * The scalar type, or {@code null} unless the kind is {@code SCALAR}.
         *
         * @return the scalar type, or {@code null} unless the kind is {@code SCALAR}
         */
        public ScalarType scalar() {
            return scalar;
        }

        /**
         * The referenced type, or {@code null} unless the kind is {@code ENUM} or {@code MESSAGE}.
         *
         * @return the referenced type, or {@code null} unless the kind is {@code ENUM} or {@code MESSAGE}
         */
        public TypeDef resolved() {
            return resolved;
        }

        /**
         * The synthetic key field, or {@code null} unless the kind is {@code MAP}.
         *
         * @return the synthetic key field, or {@code null} unless the kind is {@code MAP}
         */
        public FieldDef mapKey() {
            return mapKey;
        }

        /**
         * The synthetic value field, or {@code null} unless the kind is {@code MAP}.
         *
         * @return the synthetic value field, or {@code null} unless the kind is {@code MAP}
         */
        public FieldDef mapValue() {
            return mapValue;
        }

        /**
         * Resolves this field to a built-in scalar type.
         *
         * @param scalar the scalar type
         */
        public void resolveScalar(ScalarType scalar) {
            this.kind = Kind.SCALAR;
            this.scalar = scalar;
        }

        /** Resolves this field to {@code google.protobuf.Timestamp}, surfaced as {@link java.time.Instant}. */
        public void resolveTimestamp() {
            this.kind = Kind.TIMESTAMP;
        }

        /**
         * Resolves this field to a declared message or enum.
         *
         * @param def the referenced declaration
         */
        public void resolveType(TypeDef def) {
            this.kind = def instanceof EnumDef ? Kind.ENUM : Kind.MESSAGE;
            this.resolved = def;
        }

        /**
         * Resolves this field to a map, with the synthetic entry fields the wire format uses.
         *
         * @param key   the entry's key field, number 1
         * @param value the entry's value field, number 2
         */
        public void resolveMap(FieldDef key, FieldDef value) {
            this.kind = Kind.MAP;
            this.mapKey = key;
            this.mapValue = value;
        }
    }
}
