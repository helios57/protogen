package io.github.helios57.protogen.compiler.lexer;

import io.github.helios57.protogen.compiler.ProtoCompileException;
import io.github.helios57.protogen.compiler.SourcePos;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written tokenizer for the {@code .proto} grammar (PLAN.md section 4, option A).
 * <p>
 * Comments are not discarded: the block of {@code //} or comment lines immediately preceding a token is
 * attached to it as {@link Token#comment()}. That is what lets protogen carry {@code @Example} /
 * {@code @MinLength} annotations from the schema into the generated Javadoc - the differentiator over
 * every protoc-based generator, which throws these away.
 */
public final class Lexer {

    private static final String SYMBOLS = "{}[]()<>=,;.:+-/";

    private final String src;
    private final String file;
    private int idx;
    private int line = 1;
    private int col = 1;

    public Lexer(String file, String src) {
        this.file = file;
        this.src = src;
    }

    /** Tokenizes the whole input. The last token is always {@link TokenType#EOF}. */
    public List<Token> tokenize() {
        List<Token> out = new ArrayList<>();
        StringBuilder pendingComment = new StringBuilder();
        while (true) {
            skipWhitespaceAndComments(pendingComment);
            SourcePos pos = pos();
            if (idx >= src.length()) {
                out.add(new Token(TokenType.EOF, "", pos, null));
                return out;
            }
            String comment = pendingComment.isEmpty() ? null : pendingComment.toString().strip();
            pendingComment.setLength(0);

            char c = src.charAt(idx);
            if (Character.isLetter(c) || c == '_') {
                out.add(new Token(TokenType.IDENT, readWhile(ch -> Character.isLetterOrDigit(ch) || ch == '_'), pos, comment));
            } else if (Character.isDigit(c)) {
                out.add(readNumber(pos, comment));
            } else if (c == '"' || c == '\'') {
                out.add(new Token(TokenType.STRING, readString(c), pos, comment));
            } else if (SYMBOLS.indexOf(c) >= 0) {
                advance();
                out.add(new Token(TokenType.SYMBOL, String.valueOf(c), pos, comment));
            } else {
                throw new ProtoCompileException(pos, "unexpected character '" + c + "'");
            }
        }
    }

    private void skipWhitespaceAndComments(StringBuilder pendingComment) {
        // A comment block is attached to the token that follows it, unless a blank line separates the two.
        int newlinesSinceComment = 0;
        while (idx < src.length()) {
            char c = src.charAt(idx);
            if (Character.isWhitespace(c)) {
                if (c == '\n' && !pendingComment.isEmpty() && ++newlinesSinceComment > 1) {
                    pendingComment.setLength(0);
                }
                advance();
            } else if (c == '/' && peek(1) == '/') {
                advance();
                advance();
                pendingComment.append(readWhile(ch -> ch != '\n').strip()).append('\n');
                newlinesSinceComment = 0;
            } else if (c == '/' && peek(1) == '*') {
                advance();
                advance();
                pendingComment.append(readBlockComment()).append('\n');
                newlinesSinceComment = 0;
            } else {
                return;
            }
        }
    }

    private String readBlockComment() {
        StringBuilder sb = new StringBuilder();
        while (idx < src.length() && !(src.charAt(idx) == '*' && peek(1) == '/')) {
            sb.append(src.charAt(idx));
            advance();
        }
        if (idx >= src.length()) {
            throw new ProtoCompileException(pos(), "unterminated block comment");
        }
        advance();
        advance();
        // strip the leading "*" decoration that proto files conventionally use
        return sb.toString().lines()
                .map(l -> l.strip().startsWith("*") ? l.strip().substring(1).strip() : l.strip())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private Token readNumber(SourcePos pos, String comment) {
        String digits = readWhile(ch -> Character.isLetterOrDigit(ch) || ch == '.');
        boolean isFloat = digits.indexOf('.') >= 0
                || (!digits.startsWith("0x") && !digits.startsWith("0X") && (digits.indexOf('e') >= 0 || digits.indexOf('E') >= 0));
        return new Token(isFloat ? TokenType.FLOAT : TokenType.INT, digits, pos, comment);
    }

    private String readString(char quote) {
        advance();
        StringBuilder sb = new StringBuilder();
        while (idx < src.length() && src.charAt(idx) != quote) {
            char c = src.charAt(idx);
            if (c == '\\') {
                advance();
                if (idx >= src.length()) {
                    break;
                }
                sb.append(unescape(src.charAt(idx)));
            } else {
                sb.append(c);
            }
            advance();
        }
        if (idx >= src.length()) {
            throw new ProtoCompileException(pos(), "unterminated string literal");
        }
        advance();
        return sb.toString();
    }

    private static char unescape(char c) {
        return switch (c) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            case '0' -> '\0';
            default -> c;
        };
    }

    private String readWhile(CharPredicate p) {
        int start = idx;
        while (idx < src.length() && p.test(src.charAt(idx))) {
            advance();
        }
        return src.substring(start, idx);
    }

    private char peek(int off) {
        return idx + off < src.length() ? src.charAt(idx + off) : '\0';
    }

    private void advance() {
        if (src.charAt(idx) == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        idx++;
    }

    private SourcePos pos() {
        return new SourcePos(file, line, col);
    }

    @FunctionalInterface
    private interface CharPredicate {
        boolean test(char c);
    }
}
