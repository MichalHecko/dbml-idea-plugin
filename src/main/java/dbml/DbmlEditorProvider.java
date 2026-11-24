package dbml;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.StatusBar;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class DbmlEditorProvider implements FileEditorProvider, DumbAware {
    private static final double EXPORT_SCALE = 2; // crisp on HiDPI and when zoomed in docs

    @Override
    public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return file.getFileType() == DbmlFileType.INSTANCE;
    }

    @Override
    public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        TextEditor text = (TextEditor) TextEditorProvider.getInstance().createEditor(project, file);
        Preview preview = new Preview(project, file, text);
        return new TextEditorWithPreview(text, preview, "DBML") {
            @Override
            protected ActionGroup createLeftToolbarActionGroup() { return preview.actions(); }
        };
    }

    @Override public @NotNull String getEditorTypeId() { return "dbml-split-editor"; }
    @Override public @NotNull FileEditorPolicy getPolicy() { return FileEditorPolicy.HIDE_DEFAULT_EDITOR; }

    private static final class Preview extends UserDataHolderBase implements FileEditor {
        private final Project project;
        private final VirtualFile file;
        private final DbmlDiagram diagram;
        private final Timer debounce;

        Preview(Project project, VirtualFile file, TextEditor text) {
            this.project = project;
            this.file = file;
            Document doc = text.getEditor().getDocument();
            diagram = new DbmlDiagram(offset -> { // double-click a table: jump to its definition
                text.getEditor().getCaretModel().moveToOffset(offset);
                text.getEditor().getScrollingModel().scrollToCaret(ScrollType.CENTER);
                IdeFocusManager.getInstance(project).requestFocus(text.getEditor().getContentComponent(), true);
            });
            diagram.setModel(DbmlModel.parse(doc.getImmutableCharSequence()));
            debounce = new Timer(250, e -> diagram.setModel(DbmlModel.parse(doc.getImmutableCharSequence())));
            debounce.setRepeats(false);
            doc.addDocumentListener(new DocumentListener() {
                @Override public void documentChanged(@NotNull DocumentEvent e) { debounce.restart(); }
            }, this);
        }

        DefaultActionGroup actions() {
            DefaultActionGroup export = DefaultActionGroup.createPopupGroup(() -> "Export Image");
            export.getTemplatePresentation().setIcon(AllIcons.ToolbarDecorator.Export);
            export.add(DumbAwareAction.create("PNG…", e -> save("png", png(diagram.toImage(EXPORT_SCALE)))));
            export.add(DumbAwareAction.create("SVG…", e -> save("svg", diagram.toSvg().getBytes(UTF_8))));
            return new DefaultActionGroup(
                    DumbAwareAction.create("Zoom In", AllIcons.General.ZoomIn, e -> diagram.zoomBy(1.25)),
                    DumbAwareAction.create("Zoom Out", AllIcons.General.ZoomOut, e -> diagram.zoomBy(0.8)),
                    DumbAwareAction.create("Fit to Window", AllIcons.General.FitContent, e -> diagram.fit()),
                    DumbAwareAction.create("Reset Layout", AllIcons.Actions.Refresh, e -> diagram.resetLayout()),
                    new DumbAwareToggleAction("Highlight All Relationships", null, AllIcons.Actions.Highlighting) {
                        @Override public boolean isSelected(@NotNull AnActionEvent e) { return diagram.isHighlightAll(); }
                        @Override public void setSelected(@NotNull AnActionEvent e, boolean on) { diagram.setHighlightAll(on); }
                        @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
                    },
                    DumbAwareAction.create("Copy Diagram to Clipboard", AllIcons.Actions.Copy, e -> copy()),
                    export);
        }

        private void copy() {
            BufferedImage img = diagram.toImage(EXPORT_SCALE);
            CopyPasteManager.getInstance().setContents(new Transferable() {
                @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[] {DataFlavor.imageFlavor}; }
                @Override public boolean isDataFlavorSupported(DataFlavor f) { return DataFlavor.imageFlavor.equals(f); }
                @Override public @NotNull Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                    if (!isDataFlavorSupported(f)) throw new UnsupportedFlavorException(f);
                    return img;
                }
            });
            StatusBar.Info.set("DBML diagram copied to clipboard", project);
        }

        private static byte[] png(BufferedImage img) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                ImageIO.write(img, "png", out);
            } catch (IOException e) {
                throw new IllegalStateException(e); // in-memory stream, cannot happen
            }
            return out.toByteArray();
        }

        private void save(String ext, byte[] data) {
            VirtualFileWrapper target = FileChooserFactory.getInstance()
                    .createSaveFileDialog(new FileSaverDescriptor("Export Diagram", "Save the diagram as " + ext.toUpperCase(), ext), project)
                    .save(file.getParent(), file.getNameWithoutExtension() + "." + ext);
            if (target == null) return;
            try {
                Files.write(target.getFile().toPath(), data);
                VfsUtil.markDirtyAndRefresh(true, false, false, target.getFile());
            } catch (IOException e) {
                Messages.showErrorDialog(project, e.getMessage(), "Diagram Export Failed");
            }
        }

        @Override public @NotNull JComponent getComponent() { return diagram; }
        @Override public JComponent getPreferredFocusedComponent() { return diagram; }
        @Override public @NotNull String getName() { return "Diagram"; }
        @Override public void setState(@NotNull FileEditorState state) { }
        @Override public boolean isModified() { return false; }
        @Override public boolean isValid() { return true; }
        @Override public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) { }
        @Override public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) { }
        @Override public VirtualFile getFile() { return file; }
        @Override public void dispose() { debounce.stop(); }
    }
}
