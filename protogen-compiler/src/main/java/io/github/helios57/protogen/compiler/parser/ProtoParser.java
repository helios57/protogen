package io.github.helios57.protogen.compiler.parser;

import io.github.helios57.protogen.compiler.ProtoCompileException;
import io.github.helios57.protogen.compiler.SourcePos;
import io.github.helios57.protogen.compiler.lexer.Lexer;
import io.github.helios57.protogen.compiler.lexer.Token;
import io.github.helios57.protogen.compiler.lexer.TokenType;
import io.github.helios57.protogen.compiler.model.Defs;
import io.github.helios57.protogen.compiler.model.ProtoFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursive-descent parser for proto3.
 * <p>
 * Constructs outside the supported subset - {@code service}, {@code extend}, groups, proto2 - are rejected
 * with a {@code file:line:col} diagnostic rather than silently skipped, so a schema never generates code
 * that quietly drops part of its meaning.
 */
public final class ProtoParser {

    private final List<Token> tokens;
    private final String fileName;
    private int pos;

    /**
     * Creates a parser over one schema source.
     *
     * @param fileName the file name to report in diagnostics
     * @param source   the schema text
     */
    public ProtoParser(String fileName, String source) {
        this.fileName = fileName;
        this.tokens = new Lexer(fileName, source).tokenize();
    }

    /**
     * Parses the whole file.
     *
     * @return the parsed file, with types declared but not yet resolved
     * @throws io.github.helios57.protogen.compiler.ProtoCompileException on a syntax error or an
     *         unsupported construct, located at {@code file:line:col}
     */
    public ProtoFile parse() {
        String syntax = null;
        String protoPackage = "";
        List<String> imports = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        List<Defs.MessageDef> messages = new ArrayList<>();
        List<Defs.EnumDef> enums = new ArrayList<>();

        while (!peek().is(TokenType.EOF)) {
            Token t = peek();
            if (t.is(TokenType.SYMBOL, ";")) {
                next();
            } else if (t.is(TokenType.IDENT, "syntax")) {
                next();
                expectSymbol("=");
                syntax = expect(TokenType.STRING).text();
                if (!"proto3".equals(syntax)) {
                    throw new ProtoCompileException(t.pos(), "only proto3 is supported, found '" + syntax + "'");
                }
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
                options.put(name, readOptionValue());
                expectSymbol(";");
            } else if (t.is(TokenType.IDENT, "message")) {
                messages.add(parseMessage());
            } else if (t.is(TokenType.IDENT, "enum")) {
                enums.add(parseEnum());
            } else {
                throw new ProtoCompileException(t.pos(), unsupported(t) + " at top level");
            }
        }
        if (syntax == null) {
            throw new ProtoCompileException(new SourcePos(fileName, 1, 1),
                    "missing 'syntax = \"proto3\";' declaration");
        }

        ProtoFile file = new ProtoFile(fileName, syntax, protoPackage, imports, options);
        file.messages().addAll(messages);
        file.enums().addAll(enums);
        return file;
    }

    private Defs.MessageDef parseMessage() {
        Token keyword = expectIdent("message");
        Token nameToken = expect(TokenType.IDENT);
        Defs.MessageDef message = new Defs.MessageDef(nameToken.text(), keyword.comment(), keyword.pos());
        expectSymbol("{");
        while (!peek().is(TokenType.SYMBOL, "}")) {
            Token t = peek();
            if (t.is(TokenType.EOF)) {
                throw new ProtoCompileException(t.pos(), "unterminated message '" + message.name() + "'");
            }
            if (t.is(TokenType.SYMBOL, ";")) {
                next();
            } else if (t.is(TokenType.IDENT, "message")) {
                message.nestedMessages().add(parseMessage());
            } else if (t.is(TokenType.IDENT, "enum")) {
                message.nestedEnums().add(parseEnum());
            } else if (t.is(TokenType.IDENT, "oneof")) {
                parseOneof(message);
            } else if (t.is(TokenType.IDENT, "reserved")) {
                skipReserved();
            } else if (t.is(TokenType.IDENT, "option")) {
                next();
                readOptionName();
                expectSymbol("=");
                readOptionValue();
                expectSymbol(";");
            } else {
                message.fields().add(parseField(-1));
            }
        }
        expectSymbol("}");
        return message;
    }

    private void parseOneof(Defs.MessageDef message) {
        Token keyword = expectIdent("oneof");
        String name = expect(TokenType.IDENT).text();
        Defs.OneofDef oneof = new Defs.OneofDef(name, keyword.comment());
        int index = message.oneofs().size();
        message.oneofs().add(oneof);
        expectSymbol("{");
        while (!peek().is(TokenType.SYMBOL, "}")) {
            Token t = peek();
            if (t.is(TokenType.EOF)) {
                throw new ProtoCompileException(t.pos(), "unterminated oneof '" + name + "'");
            }
            if (t.is(TokenType.SYMBOL, ";")) {
                next();
                continue;
            }
            Defs.FieldDef field = parseField(index);
            if (field.repeated()) {
                throw new ProtoCompileException(field.pos(), "a oneof field cannot be repeated");
            }
            message.fields().add(field);
            oneof.fields().add(field);
        }
        expectSymbol("}");
    }

    private Defs.FieldDef parseField(int oneofIndex) {
        Token first = peek();
        String comment = first.comment();
        Defs.Label label = Defs.Label.SINGULAR;
        if (first.is(TokenType.IDENT, "repeated")) {
            next();
            label = Defs.Label.REPEATED;
        } else if (first.is(TokenType.IDENT, "optional")) {
            next();
            label = Defs.Label.OPTIONAL;
        } else if (first.is(TokenType.IDENT, "required")) {
            throw new ProtoCompileException(first.pos(), "'required' is proto2 only");
        } else if (first.is(TokenType.IDENT, "group")) {
            throw new ProtoCompileException(first.pos(), "groups are not supported");
        }

        String typeName;
        String mapKeyType = null;
        String mapValueType = null;
        if (peek().is(TokenType.IDENT, "map")) {
            Token mapToken = next();
            if (label != Defs.Label.SINGULAR) {
                throw new ProtoCompileException(mapToken.pos(), "a map field cannot be repeated or optional");
            }
            expectSymbol("<");
            mapKeyType = readQualifiedName();
            expectSymbol(",");
            mapValueType = readQualifiedName();
            expectSymbol(">");
            typeName = "map";
        } else {
            typeName = readTypeName();
        }

        Token nameToken = expect(TokenType.IDENT);
        expectSymbol("=");
        Token numberToken = expect(TokenType.INT);
        int number = parseFieldNumber(numberToken);
        skipFieldOptions();
        expectSymbol(";");

        Defs.FieldDef field = new Defs.FieldDef(nameToken.text(), number, label, typeName,
                comment != null ? comment : nameToken.comment(), nameToken.pos(), oneofIndex);
        if (mapKeyType != null) {
            Defs.FieldDef key = new Defs.FieldDef("key", 1, Defs.Label.SINGULAR, mapKeyType, null,
                    nameToken.pos(), -1);
            Defs.FieldDef value = new Defs.FieldDef("value", 2, Defs.Label.SINGULAR, mapValueType, null,
                    nameToken.pos(), -1);
            field.resolveMap(key, value);
        }
        return field;
    }

    private int parseFieldNumber(Token token) {
        long number;
        try {
            number = Long.parseLong(token.text());
        } catch (NumberFormatException e) {
            throw new ProtoCompileException(token.pos(), "invalid field number '" + token.text() + "'");
        }
        if (number < 1 || number > 536_870_911) {
            throw new ProtoCompileException(token.pos(), "field number " + number + " is out of range 1..536870911");
        }
        if (number >= 19_000 && number <= 19_999) {
            throw new ProtoCompileException(token.pos(), "field number " + number + " is reserved for protobuf internals");
        }
        return (int) number;
    }

    private Defs.EnumDef parseEnum() {
        Token keyword = expectIdent("enum");
        Token nameToken = expect(TokenType.IDENT);
        Defs.EnumDef def = new Defs.EnumDef(nameToken.text(), keyword.comment(), keyword.pos());
        expectSymbol("{");
        while (!peek().is(TokenType.SYMBOL, "}")) {
            Token t = peek();
            if (t.is(TokenType.EOF)) {
                throw new ProtoCompileException(t.pos(), "unterminated enum '" + def.name() + "'");
            }
            if (t.is(TokenType.SYMBOL, ";")) {
                next();
            } else if (t.is(TokenType.IDENT, "reserved")) {
                skipReserved();
            } else if (t.is(TokenType.IDENT, "option")) {
                next();
                String name = readOptionName();
                expectSymbol("=");
                String value = readOptionValue();
                if ("allow_alias".equals(name)) {
                    def.setAllowAlias(Boolean.parseBoolean(value));
                }
                expectSymbol(";");
            } else {
                Token valueName = expect(TokenType.IDENT);
                expectSymbol("=");
                boolean negative = false;
                if (peek().is(TokenType.SYMBOL, "-")) {
                    next();
                    negative = true;
                }
                Token numberToken = expect(TokenType.INT);
                int number;
                try {
                    number = Integer.parseInt(numberToken.text());
                } catch (NumberFormatException e) {
                    throw new ProtoCompileException(numberToken.pos(),
                            "invalid enum value '" + numberToken.text() + "'");
                }
                skipFieldOptions();
                expectSymbol(";");
                def.values().add(new Defs.EnumValueDef(valueName.text(), negative ? -number : number,
                        valueName.comment()));
            }
        }
        expectSymbol("}");
        if (def.defaultValue() == null || def.defaultValue().number() != 0) {
            throw new ProtoCompileException(def.pos(),
                    "proto3 enum '" + def.name() + "' must define a constant with value 0");
        }
        if (!def.allowAlias()) {
            List<Integer> seen = new ArrayList<>();
            for (Defs.EnumValueDef v : def.values()) {
                if (seen.contains(v.number())) {
                    throw new ProtoCompileException(def.pos(), "duplicate value " + v.number() + " in enum '"
                            + def.name() + "'; set 'option allow_alias = true;' to permit it");
                }
                seen.add(v.number());
            }
        }
        return def;
    }

    /** Consumes {@code reserved 1, 2 to 5;} and {@code reserved "a", "b";}. Ranges are not enforced yet. */
    private void skipReserved() {
        expectIdent("reserved");
        while (!peek().is(TokenType.SYMBOL, ";")) {
            if (peek().is(TokenType.EOF)) {
                throw new ProtoCompileException(peek().pos(), "unterminated reserved statement");
            }
            next();
        }
        expectSymbol(";");
    }

    /** Consumes an optional {@code [ ... ]} block; protogen has no field options of its own yet. */
    private void skipFieldOptions() {
        if (!peek().is(TokenType.SYMBOL, "[")) {
            return;
        }
        next();
        int depth = 1;
        while (depth > 0) {
            Token t = next();
            if (t.is(TokenType.EOF)) {
                throw new ProtoCompileException(t.pos(), "unterminated field options");
            }
            if (t.is(TokenType.SYMBOL, "[")) {
                depth++;
            } else if (t.is(TokenType.SYMBOL, "]")) {
                depth--;
            }
        }
    }

    private String readTypeName() {
        StringBuilder sb = new StringBuilder();
        if (peek().is(TokenType.SYMBOL, ".")) {
            next();
            sb.append('.');
        }
        sb.append(expect(TokenType.IDENT).text());
        while (peek().is(TokenType.SYMBOL, ".")) {
            next();
            sb.append('.').append(expect(TokenType.IDENT).text());
        }
        return sb.toString();
    }

    private String readQualifiedName() {
        return readTypeName();
    }

    private String readOptionName() {
        if (peek().is(TokenType.SYMBOL, "(")) {
            next();
            String name = readTypeName();
            expectSymbol(")");
            StringBuilder sb = new StringBuilder(name);
            while (peek().is(TokenType.SYMBOL, ".")) {
                next();
                sb.append('.').append(expect(TokenType.IDENT).text());
            }
            return sb.toString();
        }
        return readTypeName();
    }

    private String readOptionValue() {
        if (peek().is(TokenType.SYMBOL, "{")) {
            // aggregate option value - consumed but not interpreted
            int depth = 0;
            StringBuilder sb = new StringBuilder();
            do {
                Token t = next();
                if (t.is(TokenType.EOF)) {
                    throw new ProtoCompileException(t.pos(), "unterminated option value");
                }
                if (t.is(TokenType.SYMBOL, "{")) {
                    depth++;
                } else if (t.is(TokenType.SYMBOL, "}")) {
                    depth--;
                }
                sb.append(t.text());
            } while (depth > 0);
            return sb.toString();
        }
        if (peek().is(TokenType.SYMBOL, "-")) {
            next();
            return "-" + next().text();
        }
        return next().text();
    }

    private String unsupported(Token t) {
        if (t.is(TokenType.IDENT, "service") || t.is(TokenType.IDENT, "extend")
                || t.is(TokenType.IDENT, "rpc") || t.is(TokenType.IDENT, "extensions")) {
            return "'" + t.text() + "' is not supported by protogen";
        }
        return "unexpected token '" + t.text() + "'";
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

    private Token expectIdent(String text) {
        Token t = peek();
        if (!t.is(TokenType.IDENT, text)) {
            throw new ProtoCompileException(t.pos(), "expected '" + text + "' but found '" + t.text() + "'");
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
}
