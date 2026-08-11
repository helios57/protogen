package io.github.helios57.protogen.compiler;

/** A located, user-facing compilation error. Never thrown for internal bugs - use assertions for those. */
public class ProtoCompileException extends RuntimeException {

    private final transient SourcePos pos;

    /**
     * Creates a located error.
     *
     * @param pos     where in the schema the problem is
     * @param message what is wrong, phrased for the person who wrote the schema
     */
    public ProtoCompileException(SourcePos pos, String message) {
        super(pos + ": " + message);
        this.pos = pos;
    }

    /**
     * Creates an error that is not tied to a single position.
     *
     * @param message what is wrong
     */
    public ProtoCompileException(String message) {
        super(message);
        this.pos = null;
    }

    /**
     * Where the problem is, when it can be pinned to one place.
     *
     * @return the location, or {@code null} if the error is not tied to one
     */
    public SourcePos pos() {
        return pos;
    }
}
