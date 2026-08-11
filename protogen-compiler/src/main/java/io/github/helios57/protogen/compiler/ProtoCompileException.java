package io.github.helios57.protogen.compiler;

/** A located, user-facing compilation error. Never thrown for internal bugs - use assertions for those. */
public class ProtoCompileException extends RuntimeException {

    private final transient SourcePos pos;

    public ProtoCompileException(SourcePos pos, String message) {
        super(pos + ": " + message);
        this.pos = pos;
    }

    public ProtoCompileException(String message) {
        super(message);
        this.pos = null;
    }

    /** @return the location of the error, or {@code null} if it is not tied to one */
    public SourcePos pos() {
        return pos;
    }
}
