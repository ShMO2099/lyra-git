package com.jfx.git.ui;

import com.jfx.git.core.LineEndings;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * The strip along the bottom of the diff viewer that says what the document is and where
 * the caret is in it.
 *
 * <h2>Why it is a separate bar from {@link StatusBar}</h2>
 * {@code StatusBar} belongs to a <em>repository</em>: one per {@link RepoTab}, shared with
 * the graph, the sidebar and the toolbar, and showing the branch, the engine and whatever
 * the last git operation said. This belongs to a <em>document</em>, and the two have
 * different lifetimes - the repository bar is still true while the graph is on screen,
 * where every fact here would be stale. They even disagree about zoom: the chrome scales
 * with {@link UiZoom} and the code surface with {@link EditorZoom}, and showing one
 * number for both would be wrong half the time.
 *
 * <h2>Why it owns no state</h2>
 * Everything it draws is computed by {@link DiffViewer} and handed over as a
 * {@link Status}. Two owners of one property is a bug class this codebase has already
 * been bitten by - see {@code DiffViewer.updateEditControls}'s comment about which node
 * owns button visibility - so this class reads its record and draws. It decides nothing.
 *
 * <p>The three nodes it does not create are {@link #adopt adopted} from the viewer: the
 * syntax notice, the encoding selector and the dirty badge already existed, already had
 * owners, and already had tests pinning their style classes. Moving the nodes is a
 * layout change; rebuilding them would have been a behaviour change wearing a layout
 * change's clothes.
 */
public class EditorStatusFooter extends HBox {

    /**
     * Everything the footer draws. Built fresh on each update and never mutated.
     *
     * @param endings      {@code null} hides the cell - see {@link DiffViewer} for why a
     *                     diff has no line-ending convention worth reporting
     * @param dominant     the majority convention behind a {@link LineEndings#MIXED}
     *                     verdict, so the cell can say which one the stragglers differ
     *                     from; ignored otherwise
     * @param externalChangePending the file changed on disk under an open editor
     */
    public record Status(boolean hasFile,
                         boolean editing,
                         int line,
                         int column,
                         int selectedChars,
                         int selectedLines,
                         String indentSummary,
                         LineEndings endings,
                         LineEndings dominant,
                         LineEndings.Counts endingCounts,
                         boolean externalChangePending) {

        /** Nothing open, so nothing to say. */
        public static Status idle() {
            return new Status(false, false, 1, 1, 0, 0, "", null, null, null, false);
        }
    }

    private final Label caret = cell();
    private final Label selection = cell();
    private final Label indent = cell();
    private final Label endings = cell();
    private final Label zoom = cell();
    private final Label externalChange = cell();

    /** Caret, selection, indentation and the save badge: shown and hidden as one. */
    private final HBox editingGroup = new HBox(UiScale.STEP);

    public EditorStatusFooter() {
        getStyleClass().add("diff-status-footer");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(UiScale.STEP);
        // The same guard the toolbar carries: a bar that reports its natural width as a
        // minimum stops the enclosing SplitPane honouring its dividers.
        setMinWidth(0);

        externalChange.getStyleClass().add("diff-status-warning");
        externalChange.setText("changed on disk");
        externalChange.setGraphic(Icons.warning());

        editingGroup.setAlignment(Pos.CENTER_LEFT);
        editingGroup.getChildren().addAll(caret, selection, indent);

        zoom.textProperty().bind(EditorZoom.percentProperty().asString("Zoom %d%%"));
        zoom.setTooltip(new Tooltip("Zoom of the code surface (Ctrl+scroll)"));
        zoom.getStyleClass().add("diff-status-control");
        zoom.setOnMouseClicked(e -> showZoomMenu());

        show(Status.idle());
    }

    /**
     * Takes the three controls that used to live in the toolbar and the title bar.
     *
     * <p>Called once, from the viewer's constructor, before anything is laid out. The
     * viewer keeps owning their text and their visibility; the footer only owns where
     * they sit.
     */
    public void adopt(Node syntaxNotice, Node encodingSelector, Node editingBadge) {
        editingGroup.getChildren().add(editingBadge);
        getChildren().setAll(editingGroup, grow(), externalChange, syntaxNotice,
                endings, encodingSelector, zoom);
    }

    /**
     * Redraws from {@code status}.
     *
     * <p>Idempotent and free of side effects, which matters because it is called from a
     * caret listener that fires during layout: anything here that touched the text area
     * would re-enter the render it was called from.
     */
    public void show(Status status) {
        setVisible(status.hasFile());
        setManaged(status.hasFile());

        showCell(editingGroup, status.editing());
        if (status.editing()) {
            caret.setText("Ln " + status.line() + ", Col " + status.column());
            showCell(selection, status.selectedChars() > 0);
            if (status.selectedChars() > 0) {
                selection.setText(status.selectedLines() > 1
                        ? status.selectedLines() + " lines \u00b7 " + status.selectedChars() + " chars"
                        : status.selectedChars() + " selected");
            }
            showCell(indent, !status.indentSummary().isEmpty());
            indent.setText(status.indentSummary());
        }

        showCell(externalChange, status.externalChangePending());

        showCell(endings, status.endings() != null);
        if (status.endings() != null) {
            LineEndings shown = status.endings();
            // Mixed names its majority, because "Mixed" alone tells the reader there is a
            // problem without telling them which way to resolve it.
            endings.setText(shown == LineEndings.MIXED && status.dominant() != null
                    ? "Mixed (" + status.dominant().label() + ")"
                    : shown.label());
            endings.setTooltip(new Tooltip(status.endingCounts() == null
                    ? shown.label()
                    : "Line endings: " + status.endingCounts().describe()));
        }
    }

    private void showZoomMenu() {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        for (int level : EditorZoom.LEVELS) {
            javafx.scene.control.RadioMenuItem item =
                    new javafx.scene.control.RadioMenuItem(level + "%");
            item.setSelected(level == EditorZoom.percent());
            item.setOnAction(e -> EditorZoom.set(level));
            menu.getItems().add(item);
        }
        menu.show(zoom, javafx.geometry.Side.TOP, 0, 0);
    }

    private static Label cell() {
        Label label = new Label();
        label.getStyleClass().add("diff-status-cell");
        return label;
    }

    private static void showCell(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static Region grow() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
