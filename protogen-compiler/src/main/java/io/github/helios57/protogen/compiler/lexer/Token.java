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

    /**
     * Tests the token's category.
     *
     * @param t the category to test for
     * @return whether this token has that category
     */
    public boolean is(TokenType t) {
        return type == t;
    }

    /**
     * Tests the token's category and exact text, which is how keywords are recognised - the proto
     * grammar has no reserved words, so {@code message} is just an identifier until read in context.
     *
     * @param t   the category to test for
     * @param txt the exact text to match
     * @return whether this token matches both
     */
    public boolean is(TokenType t, String txt) {
        return type == t && text.equals(txt);
    }

    @Override
    public String toString() {
        return type + "(" + text + ")@" + pos;
    }
}
