package io.github.helios57.protogen.compiler.lexer;

/** Lexical categories of the {@code .proto} grammar. */
public enum TokenType {
    /** An identifier or keyword - the proto grammar has no reserved words, so they are one category. */
    IDENT,
    /** An integer literal, decimal / hex / octal, already normalised to decimal in {@code text}. */
    INT,
    /** A floating point literal. */
    FLOAT,
    /** A string literal, unescaped. */
    STRING,
    /** One of {@code { } [ ] ( ) < > = , ; . : + -} */
    SYMBOL,
    /** End of input. */
    EOF
}
