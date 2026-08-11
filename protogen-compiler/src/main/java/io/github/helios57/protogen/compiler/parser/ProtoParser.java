package io.github.helios57.protogen.compiler.parser;

import io.github.helios57.protogen.compiler.ProtoCompileException;
import io.github.helios57.protogen.compiler.lexer.Lexer;
import io.github.helios57.protogen.compiler.lexer.Token;
import io.github.helios57.protogen.compiler.lexer.TokenType;
import io.github.helios57.protogen.compiler.model.ProtoFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursive-descent parser for {@code .proto} sources.
 * <p>
 * <strong>Phase 0 scope</strong> (PLAN.md section 7): the file header - {@code syntax}, {@code package},
 * {@code import}, file-level {@code option} - plus the names of top-level {@code message} and {@code enum}
 * declarations, whose bodies are skipped by brace matching. Phase 1 replaces the skipping with a real
 * declaration parser.
 */
public final class ProtoParser {

    private final List<Token> tokens;
    private final String fileName;
    private int pos;

    public ProtoParser(String fileName, String source) {
        this.fileName = fileName;
        this.tokens = new Lexer(fileName, source).tokenize();
    }

    /** Parses the whole file. */
    public ProtoFile parse() {
        String syntax = "proto2";
        String protoPackage = "";
        List<String> imports = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        List<ProtoFile.TypeDecl> types = new ArrayList<>();

        while (!peek().is(TokenType.EOF)) {
            Token t = peek();
            if (t.is(TokenType.SYMBOL, ";")) {
                next();
            } else if (t.is(TokenType.IDENT, "syntax")) {
                next();
                expectSymbol("=");
                syntax = expect(TokenType.STRING).text();
                expectSymbol(";");
            } else if (t.is(TokenType.IDENT, "package")) {
                next();
                protoPackage = readQualifiedName();
                expectSymbol(";");
            } else if (t.is(TokenType.IDENT, "import")) {
                next();
                if (peek().is(TokenType.IDENT, "public") || peek().is(TokenType.IDENT, "weak")) {
                    next();
                }
                imports.add(expect(TokenType.STRING).text());
                expectSymbol(";");
            } else if (t.is(TokenType.IDENT, "option")) {
                next();
                String name = readOptionName();
                expectSymbol("=");
                options.put(name, next().text());
                expectSymbol(";");
            } else if (t.is(TokenType.IDENT, "message") || t.is(TokenType.IDENT, "enum")) {
                var kind = t.is(TokenType.IDENT, "message")
                        ? ProtoFile.TypeDecl.Kind.MESSAGE
                        : ProtoFile.TypeDecl.Kind.ENUM;
                String comment = t.comment();
                next();
                String name = expect(TokenType.IDENT).text();
                skipBracedBody();
                types.add(new ProtoFile.TypeDecl(kind, name, comment));
            } else if (t.is(TokenType.IDENT, "service") || t.is(TokenType.IDENT, "extend")) {
                // out of scope per PLAN.md section 4 - reject loudly rather than silently mis-generate
                throw new ProtoCompileException(t.pos(), "'" + t.text() + "' is not supported by protogen");
            } else {
                throw new ProtoCompileException(t.pos(), "unexpected token '" + t.text() + "' at top level");
            }
        }
        return new ProtoFile(fileName, syntax, protoPackage, List.copyOf(imports), Map.copyOf(options), List.copyOf(types));
    }

    /** Consumes a balanced {@code { ... }} block, including nested blocks. */
    private void skipBracedBody() {
        expectSymbol("{");
        int depth = 1;
        while (depth > 0) {
            Token t = next();
            if (t.is(TokenType.EOF)) {
                throw new ProtoCompileException(t.pos(), "unterminated declaration body");
            }
            if (t.is(TokenType.SYMBOL, "{")) {
                depth++;
            } else if (t.is(TokenType.SYMBOL, "}")) {
                depth--;
            }
        }
    }

    private String readQualifiedName() {
        StringBuilder sb = new StringBuilder(expect(TokenType.IDENT).text());
        while (peek().is(TokenType.SYMBOL, ".")) {
            next();
            sb.append('.').append(expect(TokenType.IDENT).text());
        }
        return sb.toString();
    }

    private String readOptionName() {
        if (peek().is(TokenType.SYMBOL, "(")) {
            next();
            String name = readQualifiedName();
            expectSymbol(")");
            return name;
        }
        return readQualifiedName();
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token next() {
        return tokens.get(pos < tokens.size() - 1 ? pos++ : pos);
    }

    private Token expect(TokenType type) {
        Token t = peek();
        if (!t.is(type)) {
            throw new ProtoCompileException(t.pos(), "expected " + type + " but found '" + t.text() + "'");
        }
        return next();
    }

    private void expectSymbol(String symbol) {
        Token t = peek();
        if (!t.is(TokenType.SYMBOL, symbol)) {
            throw new ProtoCompileException(t.pos(), "expected '" + symbol + "' but found '" + t.text() + "'");
        }
        next();
    }

    /** @return the file name this parser was created for */
    public String fileName() {
        return fileName;
    }
}
