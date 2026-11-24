package dbml;

import com.intellij.icons.AllIcons;
import com.intellij.lang.Language;
import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

// ponytail: no PSI/ParserDefinition, so no Ctrl+/ commenting or structure view; add a flat ParserDefinition + Commenter if needed
public final class DbmlFileType extends LanguageFileType {
    public static final Language LANGUAGE = new Language("DBML") { };
    public static final DbmlFileType INSTANCE = new DbmlFileType();

    private DbmlFileType() { super(LANGUAGE); }

    @Override public @NotNull String getName() { return "DBML"; }
    @Override public @NotNull String getDescription() { return "DBML database diagram"; }
    @Override public @NotNull String getDefaultExtension() { return "dbml"; }
    @Override public Icon getIcon() { return AllIcons.Nodes.DataTables; }
}
