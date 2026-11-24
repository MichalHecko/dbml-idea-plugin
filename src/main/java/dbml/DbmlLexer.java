package dbml;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;

/** Hand-written DBML tokenizer, shared by the syntax highlighter and {@link DbmlModel}. State 1 = inside [settings]. */
public final class DbmlLexer extends LexerBase {
    enum Kind { WS, COMMENT, STRING, EXPR, NUMBER, COLOR, KEYWORD, SETTING, IDENT, BRACE, BRACKET, PAREN, OP, PUNCT }

    private static final Set<String> KEYWORDS = Set.of(
            "table", "tablegroup", "tablepartial", "ref", "enum", "project", "note", "indexes", "records", "checks", "as");
    private static final Set<String> SETTINGS = Set.of(
            "pk", "primary", "key", "null", "not", "unique", "increment", "default", "note", "ref", "headercolor", "color",
            "delete", "update", "cascade", "restrict", "set", "no", "action", "name", "type", "check", "true", "false");

    private CharSequence buf;
    private int end, tokStart, tokEnd, state, tokState;
    private Kind kind;

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        buf = buffer;
        end = endOffset;
        state = initialState;
        tokEnd = startOffset;
        advance();
    }

    @Override
    public void advance() {
        tokStart = tokEnd;
        tokState = state;
        if (tokStart >= end) {
            kind = null;
            return;
        }
        tokEnd = scan(tokStart);
    }

    Kind kind() { return kind; }

    @Override public int getState() { return tokState; }
    @Override public IElementType getTokenType() { return kind == null ? null : DbmlHighlighter.TYPES.get(kind); }
    @Override public int getTokenStart() { return tokStart; }
    @Override public int getTokenEnd() { return tokEnd; }
    @Override public @NotNull CharSequence getBufferSequence() { return buf; }
    @Override public int getBufferEnd() { return end; }

    private int scan(int p) {
        char c = buf.charAt(p);
        if (Character.isWhitespace(c)) {
            while (p < end && Character.isWhitespace(buf.charAt(p))) p++;
            kind = Kind.WS;
            return p;
        }
        if (c == '/' && p + 1 < end && buf.charAt(p + 1) == '/') {
            while (p < end && buf.charAt(p) != '\n') p++;
            kind = Kind.COMMENT;
            return p;
        }
        if (c == '/' && p + 1 < end && buf.charAt(p + 1) == '*') {
            kind = Kind.COMMENT;
            return closeAfter("*/", p + 2);
        }
        if (c == '\'' && startsWith("'''", p)) {
            kind = Kind.STRING;
            return closeAfter("'''", p + 3);
        }
        if (c == '\'' || c == '"' || c == '`') {
            kind = c == '"' ? Kind.IDENT : c == '`' ? Kind.EXPR : Kind.STRING; // "quoted name" is an identifier in DBML
            p++;
            while (p < end) {
                char d = buf.charAt(p);
                if (d == '\\') p += 2;
                else if (d == c) return p + 1;
                else if (d == '\n') return p; // unterminated: stop at the line end
                else p++;
            }
            return end;
        }
        if (c == '#') {
            p++;
            while (p < end && Character.isLetterOrDigit(buf.charAt(p))) p++;
            kind = Kind.COLOR;
            return p;
        }
        if (Character.isDigit(c)) {
            while (p < end && (Character.isDigit(buf.charAt(p)) || buf.charAt(p) == '.')) p++;
            kind = Kind.NUMBER;
            return p;
        }
        if (Character.isLetter(c) || c == '_') {
            int s = p;
            while (p < end && (Character.isLetterOrDigit(buf.charAt(p)) || buf.charAt(p) == '_')) p++;
            String w = buf.subSequence(s, p).toString().toLowerCase(Locale.ROOT);
            kind = state == 1 && SETTINGS.contains(w) ? Kind.SETTING : KEYWORDS.contains(w) ? Kind.KEYWORD : Kind.IDENT;
            return p;
        }
        p++;
        switch (c) {
            case '{', '}' -> kind = Kind.BRACE;
            case '[' -> { kind = Kind.BRACKET; state = 1; }
            case ']' -> { kind = Kind.BRACKET; state = 0; }
            case '(', ')' -> kind = Kind.PAREN;
            case '<' -> { kind = Kind.OP; if (p < end && buf.charAt(p) == '>') p++; }
            case '>', '-' -> kind = Kind.OP;
            default -> kind = Kind.PUNCT;
        }
        return p;
    }

    private boolean startsWith(String s, int p) {
        if (p + s.length() > end) return false;
        for (int j = 0; j < s.length(); j++) if (buf.charAt(p + j) != s.charAt(j)) return false;
        return true;
    }

    /** End offset just after the closing {@code s} searched from {@code p}, or the buffer end when unterminated. */
    private int closeAfter(String s, int p) {
        for (; p < end; p++) if (startsWith(s, p)) return p + s.length();
        return end;
    }
}
