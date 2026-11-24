package dbml;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

// ponytail: colors map onto the default language palette, no Color Settings page; add one if users want to tweak them
public final class DbmlHighlighter extends SyntaxHighlighterBase {
    static final Map<DbmlLexer.Kind, IElementType> TYPES = new EnumMap<>(DbmlLexer.Kind.class);
    private static final Map<IElementType, TextAttributesKey[]> KEYS = new java.util.HashMap<>();

    static {
        for (DbmlLexer.Kind k : DbmlLexer.Kind.values()) {
            TYPES.put(k, k == DbmlLexer.Kind.WS ? TokenType.WHITE_SPACE : new IElementType("DBML_" + k, DbmlFileType.LANGUAGE));
        }
        key(DbmlLexer.Kind.COMMENT, DefaultLanguageHighlighterColors.LINE_COMMENT);
        key(DbmlLexer.Kind.STRING, DefaultLanguageHighlighterColors.STRING);
        key(DbmlLexer.Kind.EXPR, DefaultLanguageHighlighterColors.TEMPLATE_LANGUAGE_COLOR);
        key(DbmlLexer.Kind.NUMBER, DefaultLanguageHighlighterColors.NUMBER);
        key(DbmlLexer.Kind.COLOR, DefaultLanguageHighlighterColors.CONSTANT);
        key(DbmlLexer.Kind.KEYWORD, DefaultLanguageHighlighterColors.KEYWORD);
        key(DbmlLexer.Kind.SETTING, DefaultLanguageHighlighterColors.METADATA);
        key(DbmlLexer.Kind.BRACE, DefaultLanguageHighlighterColors.BRACES);
        key(DbmlLexer.Kind.BRACKET, DefaultLanguageHighlighterColors.BRACKETS);
        key(DbmlLexer.Kind.PAREN, DefaultLanguageHighlighterColors.PARENTHESES);
        key(DbmlLexer.Kind.OP, DefaultLanguageHighlighterColors.OPERATION_SIGN);
    }

    private static void key(DbmlLexer.Kind kind, TextAttributesKey base) {
        KEYS.put(TYPES.get(kind), new TextAttributesKey[] {createTextAttributesKey("DBML_" + kind, base)});
    }

    @Override public @NotNull Lexer getHighlightingLexer() { return new DbmlLexer(); }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType type) {
        return KEYS.getOrDefault(type, TextAttributesKey.EMPTY_ARRAY);
    }
}
