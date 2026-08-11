package io.github.helios57.protogen.compiler;

/**
 * A location in a {@code .proto} source file. Used to make every diagnostic point at
 * {@code file:line:col}, per PLAN.md phase 1.
 *
 * @param file the source file name as given to the compiler
 * @param line 1-based line number
 * @param col  1-based column number
 */
public record SourcePos(String file, int line, int col) {

    @Override
    public String toString() {
        return file + ":" + line + ":" + col;
    }
}
