package io.github.helios57.protogen.compiler.lexer;

import io.github.helios57.protogen.compiler.SourcePos;

/**
 * A single lexical token.
 *
 * @param type    token category
 * @param text    the raw text; for {@link TokenType#STRING} the already-unescaped value
 * @param pos     where the token starts
 * @param comment leading doc comment attached to this token, or {@code null}
 */
public record Token(TokenType type, String text, SourcePos pos, String comment) {

    public boolean is(TokenType t) {
        return type == t;
    }

    public boolean is(TokenType t, String txt) {
        return type == t && text.equals(txt);
    }

    @Override
    public String toString() {
        return type + "(" + text + ")@" + pos;
    }
}
