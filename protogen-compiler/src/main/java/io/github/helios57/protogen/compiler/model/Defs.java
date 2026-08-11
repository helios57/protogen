package io.github.helios57.protogen.compiler.model;

import io.github.helios57.protogen.compiler.SourcePos;

import java.util.ArrayList;
import java.util.List;

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
        /** @return the simple name as declared */
        String name();

        /** @return the fully qualified proto name, e.g. {@code pkg.Outer.Inner} */
        String fullName();

        /** @return the enclosing message, or {@code null} for a top-level type */
        MessageDef parent();

        /** @return the file this type was declared in */
        ProtoFile file();
    }

    /** Field cardinality. */
    public enum Label {
        /** Implicit presence: absent and default-valued are indistinguishable. */
        SINGULAR,
        /** Explicit presence via the proto3 {@code optional} keyword. */
        OPTIONAL,
        REPEATED
    }

    /** How a field's Java type is realised, once the type reference has been linked. */
    public enum Kind {
        SCALAR, ENUM, MESSAGE, MAP,
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

        public String comment() {
            return comment;
        }

        public SourcePos pos() {
            return pos;
        }

        public List<FieldDef> fields() {
            return fields;
        }

        public List<MessageDef> nestedMessages() {
            return nestedMessages;
        }

        public List<EnumDef> nestedEnums() {
            return nestedEnums;
        }

        public List<OneofDef> oneofs() {
            return oneofs;
        }

        public boolean mapEntry() {
            return mapEntry;
        }

        public void markMapEntry() {
            this.mapEntry = true;
        }

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
        private boolean allowAlias;
        private MessageDef parent;
        private ProtoFile file;
        private String fullName;

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

        public String comment() {
            return comment;
        }

        public SourcePos pos() {
            return pos;
        }

        public List<EnumValueDef> values() {
            return values;
        }

        public boolean allowAlias() {
            return allowAlias;
        }

        public void setAllowAlias(boolean allowAlias) {
            this.allowAlias = allowAlias;
        }

        /** @return the constant whose number is 0, which proto3 requires to exist */
        public EnumValueDef defaultValue() {
            for (EnumValueDef v : values) {
                if (v.number() == 0) {
                    return v;
                }
            }
            return values.isEmpty() ? null : values.get(0);
        }

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

        public OneofDef(String name, String comment) {
            this.name = name;
            this.comment = comment;
        }

        public String name() {
            return name;
        }

        public String comment() {
            return comment;
        }

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

        // resolved by the linker
        private Kind kind;
        private ScalarType scalar;
        private TypeDef resolved;
        private FieldDef mapKey;
        private FieldDef mapValue;

        public FieldDef(String name, int number, Label label, String typeName, String comment,
                        SourcePos pos, int oneofIndex) {
            this.name = name;
            this.number = number;
            this.label = label;
            this.typeName = typeName;
            this.comment = comment;
            this.pos = pos;
            this.constraints = Constraints.parse(comment);
            this.oneofIndex = oneofIndex;
        }

        public String name() {
            return name;
        }

        public int number() {
            return number;
        }

        public Label label() {
            return label;
        }

        public String typeName() {
            return typeName;
        }

        public String comment() {
            return comment;
        }

        public SourcePos pos() {
            return pos;
        }

        public Constraints constraints() {
            return constraints;
        }

        public int oneofIndex() {
            return oneofIndex;
        }

        public boolean inOneof() {
            return oneofIndex >= 0;
        }

        public boolean repeated() {
            return label == Label.REPEATED;
        }

        public Kind kind() {
            return kind;
        }

        public ScalarType scalar() {
            return scalar;
        }

        public TypeDef resolved() {
            return resolved;
        }

        public FieldDef mapKey() {
            return mapKey;
        }

        public FieldDef mapValue() {
            return mapValue;
        }

        public void resolveScalar(ScalarType scalar) {
            this.kind = Kind.SCALAR;
            this.scalar = scalar;
        }

        public void resolveTimestamp() {
            this.kind = Kind.TIMESTAMP;
        }

        public void resolveType(TypeDef def) {
            this.kind = def instanceof EnumDef ? Kind.ENUM : Kind.MESSAGE;
            this.resolved = def;
        }

        public void resolveMap(FieldDef key, FieldDef value) {
            this.kind = Kind.MAP;
            this.mapKey = key;
            this.mapValue = value;
        }
    }
}
