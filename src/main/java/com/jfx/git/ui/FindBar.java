package com.jfx.git.ui;

import com.jfx.git.core.TextSearch;
import com.jfx.git.core.TextSearch.Match;
import com.jfx.git.core.TextSearch.Options;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import org.fxmisc.richtext.GenericStyledArea;
import org.fxmisc.richtext.Selection;
import org.fxmisc.richtext.SelectionImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * Find, find and replace, and go to line, over the text areas a viewer shows.
 *
 * <p>One bar for every surface that has text in it - the diff, the editor, each pane of
 * the merge tool - because a search that works one way in the diff and another in the
 * merge tool is two features to learn. The surface says which areas to search and
 * whether they can be edited; the bar does the rest.
 *
 * <p>What is searched is a {@link Surface}: each text area, or a page drawn from one -
 * the Markdown preview - which can be searched and selected but not edited.
 *
 * <p>Keys, while the bar has focus: Enter and Shift+Enter for the next and previous
 * match, F3 and Shift+F3 likewise, Esc to close and hand focus back to the text.
 */
public final class FindBar extends VBox {

    /** What the bar searches. */
    public interface Host {
        /** The areas to search, in the order a reader meets them. */
        List<GenericStyledArea<?, ?, ?>> areas();

        /** Whether replacing is allowed: the areas are an editor, not a display. */
        boolean editable();

        /** How many lines there are to go to; 0 when going to a line means nothing here. */
        int lineCount();

        /**
         * The paragraph of {@link #lineArea()} that shows {@code line} (1-based), or -1 when it
         * is not on screen - a line outside every hunk of a diff, say.
         */
        int paragraphOfLine(int line);

        /** The area {@link #paragraphOfLine} counts paragraphs in: the first, unless said otherwise. */
        default GenericStyledArea<?, ?, ?> lineArea() {
            return areas().get(0);
        }

        /**
         * What is searched: the {@link #areas()}, unless the host is showing something else
         * with text in it - a rendered Markdown page in place of its source.
         */
        default List<Surface> surfaces() {
            return areas().stream().map(FindBar::surfaceOf).toList();
        }
    }

    /**
     * Something the bar can search: a text area, or a page drawn from one. Offsets are into
     * {@link #text()}.
     */
    public interface Surface {
        String text();

        /** Where a search "from here" starts: the start of the selection. */
        int anchor();

        /** Selects {@code start..end} and scrolls it into view. */
        void reveal(int start, int end);

        /** Marks every one of {@code matches}, replacing the marks made before. */
        void mark(List<Match> matches);

        void clearMarks();

        String selectedText();

        boolean isFocused();

        void focus();

        /** Runs {@code onChange} whenever the text changes; returns what stops that. */
        Runnable watch(Runnable onChange);

        /** Replaces {@code start..end}. Only asked of a host that says it is {@link Host#editable()}. */
        default void replace(int start, int end, String with) {
            throw new UnsupportedOperationException("this text cannot be edited");
        }

        /** Puts the caret at {@code offset}, selecting nothing. */
        default void moveTo(int offset) {
        }
    }

    /** A RichTextFX area as something to search. */
    public static Surface surfaceOf(GenericStyledArea<?, ?, ?> area) {
        return new AreaSurface(area);
    }

    /**
     * Matches are marked with selections of their own rather than with style classes. The
     * diff's colours are style ranges that a re-render replaces wholesale; a highlight
     * written into them would vanish on the next render, and would have to be taken out
     * again by hand before saving. A selection sits over the text and touches none of it.
     */
    private static final class AreaSurface implements Surface {
        private final GenericStyledArea<?, ?, ?> area;
        private final List<Selection<?, ?, ?>> marks = new ArrayList<>();

        AreaSurface(GenericStyledArea<?, ?, ?> area) {
            this.area = area;
        }

        @Override
        public String text() {
            return area.getText();
        }

        @Override
        public int anchor() {
            // The selection's start, not the caret: the caret sits at the end of the match
            // found for the query so far, and one more typed character must find that same
            // place again rather than skip to the next one.
            return area.getSelection().getStart();
        }

        @Override
        public void reveal(int start, int end) {
            area.selectRange(start, end);
            area.requestFollowCaret();
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void mark(List<Match> matches) {
            clearMarks();
            int drawn = 0;
            for (Match match : matches) {
                SelectionImpl mark = new SelectionImpl("find-match-" + drawn++, (GenericStyledArea) area,
                        path -> ((javafx.scene.Node) path).getStyleClass().add("find-match"));
                if (((GenericStyledArea) area).addSelection(mark)) {
                    mark.selectRange(match.start(), match.end());
                    marks.add(mark);
                }
            }
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void clearMarks() {
            for (Selection mark : marks) {
                ((GenericStyledArea) area).removeSelection(mark);
                mark.dispose();
            }
            marks.clear();
        }

        @Override
        public String selectedText() {
            return area.getSelectedText();
        }

        @Override
        public boolean isFocused() {
            return area.isFocused();
        }

        @Override
        public void focus() {
            area.requestFocus();
        }

        @Override
        public Runnable watch(Runnable onChange) {
            javafx.beans.value.ChangeListener<String> changed = (obs, old, text) -> onChange.run();
            area.textProperty().addListener(changed);
            return () -> area.textProperty().removeListener(changed);
        }

        @Override
        public void replace(int start, int end, String with) {
            area.replaceText(start, end, with);
        }

        @Override
        public void moveTo(int offset) {
            area.moveTo(offset);
        }
    }

    public static final String QUERY_ID = "find-query";
    public static final String REPLACE_ID = "find-replace";
    public static final String COUNT_ID = "find-count";
    public static final String LINE_ID = "find-line";

    /** Highlights beyond this many are not drawn; the count still says how many there are. */
    static final int MAX_HIGHLIGHTS = 500;

    private final Host host;

    private final TextField query = new TextField();
    private final Label count = new Label();
    private final ToggleButton matchCase = toggle("Aa", "Match case");
    private final ToggleButton wholeWord = toggle("Word", "Whole words only");
    private final ToggleButton regex = toggle(".*", "Regular expression");
    private final TextField replacement = new TextField();
    private final HBox replaceRow;
    private final HBox findRow;
    private final TextField line = new TextField();
    private final Label lineHint = new Label();
    private final HBox lineRow;

    /** Every match, as area index and range, in reading order. */
    private final List<Hit> hits = new ArrayList<>();
    private int current = -1;
    private boolean capped;
    /** The surfaces the hits were found in, and so the ones carrying marks. */
    private List<Surface> searched = List.of();
    /** How many matches are marked right now. */
    private int marked;
    /** Re-searches when the text changes underneath an open bar. */
    private final List<Runnable> unhooks = new ArrayList<>();

    /** One match in one of the host's areas. */
    record Hit(int area, Match match) {
    }

    public FindBar(Host host) {
        this.host = host;
        getStyleClass().add("find-bar");

        query.setId(QUERY_ID);
        query.setPromptText("Find");
        query.setPrefColumnCount(24);
        HBox.setHgrow(query, Priority.ALWAYS);
        count.setId(COUNT_ID);
        count.getStyleClass().addAll("t-caption", "find-count");
        count.setMinWidth(Region.USE_PREF_SIZE);

        Button previous = Icons.button(Icons.chevronUp(), "Previous match", "Previous match (Shift+Enter)");
        previous.setOnAction(e -> step(-1));
        Button next = Icons.button(Icons.chevronDown(), "Next match", "Next match (Enter)");
        next.setOnAction(e -> step(1));
        Button close = Icons.button(Icons.close(), "Close find", "Close (Esc)");
        close.setOnAction(e -> hide());

        findRow = new HBox(UiScale.SNUG, query, count, matchCase, wholeWord, regex, previous, next, close);
        findRow.setAlignment(Pos.CENTER_LEFT);

        replacement.setId(REPLACE_ID);
        replacement.setPromptText("Replace with");
        HBox.setHgrow(replacement, Priority.ALWAYS);
        Button replaceOne = new Button("Replace");
        replaceOne.getStyleClass().add("btn-sm");
        replaceOne.setOnAction(e -> replaceCurrent());
        Button replaceEvery = new Button("Replace all");
        replaceEvery.getStyleClass().add("btn-sm");
        replaceEvery.setOnAction(e -> replaceAll());
        replaceRow = new HBox(UiScale.SNUG, replacement, replaceOne, replaceEvery);
        replaceRow.setAlignment(Pos.CENTER_LEFT);

        line.setId(LINE_ID);
        line.setPrefColumnCount(8);
        lineHint.getStyleClass().add("t-caption");
        Button go = new Button("Go");
        go.getStyleClass().add("btn-sm");
        go.setOnAction(e -> goToLine());
        Button closeLine = Icons.button(Icons.close(), "Close go to line", "Close (Esc)");
        closeLine.setOnAction(e -> hide());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        lineRow = new HBox(UiScale.SNUG, new Label("Go to line"), line, go, lineHint, spacer, closeLine);
        lineRow.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(findRow, replaceRow, lineRow);
        setSpacing(UiScale.SNUG);
        show(this, false);

        query.textProperty().addListener((obs, old, text) -> search(true));
        for (ToggleButton option : List.of(matchCase, wholeWord, regex)) {
            option.selectedProperty().addListener((obs, old, on) -> search(true));
        }
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        line.setOnAction(e -> goToLine());
        replacement.setOnAction(e -> replaceCurrent());
    }

    // ------------------------------------------------------------------
    // Opening and closing
    // ------------------------------------------------------------------

    /**
     * Opens the bar to find - and to replace as well, when {@code withReplace} and the
     * text can be edited. Starts from the selection when there is a short one, as every
     * editor does: select a word, press Ctrl+F, and that word is what is looked for.
     */
    public void showFind(boolean withReplace) {
        String selected = selectedText();
        if (selected != null && !selected.isEmpty() && !selected.contains("\n") && selected.length() <= 200) {
            query.setText(selected);
        }
        show(findRow, true);
        show(replaceRow, withReplace && host.editable());
        show(lineRow, false);
        show(this, true);
        watchText();
        search(true);
        Platform.runLater(() -> {
            query.requestFocus();
            query.selectAll();
        });
    }

    /** Opens the bar to go to a line, saying which lines there are. */
    public void showGoToLine() {
        int lines = host.lineCount();
        lineHint.setText(lines > 0 ? "1 to " + lines : "There are no lines to go to here");
        line.setPromptText(lines > 0 ? "Line" : "");
        line.setDisable(lines <= 0);
        show(findRow, false);
        show(replaceRow, false);
        show(lineRow, true);
        show(this, true);
        clearHighlights();
        Platform.runLater(() -> {
            line.requestFocus();
            line.selectAll();
        });
    }

    /** Closes the bar, takes its marks off the text, and gives the text its focus back. */
    public void hide() {
        boolean wasOpen = isVisible();
        clearHighlights();
        unhooks.forEach(Runnable::run);
        unhooks.clear();
        show(this, false);
        List<Surface> surfaces = host.surfaces();
        if (wasOpen && !surfaces.isEmpty()) {
            surfaces.get(0).focus();
        }
    }

    /**
     * Follows the host to a different set of areas - Split view's two columns after the
     * one of Unified - and searches them, if the bar is open on finding.
     */
    public void retarget() {
        if (!isFinding()) return;
        watchText();
        search(false);
    }

    /** Whether the bar is open on finding (not on going to a line). */
    public boolean isFinding() {
        return isVisible() && findRow.isVisible();
    }

    // ------------------------------------------------------------------
    // Searching
    // ------------------------------------------------------------------

    private Options options() {
        return new Options(matchCase.isSelected(), wholeWord.isSelected(), regex.isSelected());
    }

    /**
     * Finds every match again. With {@code fromCaret}, the current match becomes the first
     * one at or after the caret - so typing a query does not jump back to the top of a
     * long file the reader is half-way through.
     */
    void search(boolean fromCaret) {
        clearHighlights();
        hits.clear();
        current = -1;
        capped = false;
        query.getStyleClass().remove("find-error");
        String text = query.getText();
        if (text == null || text.isEmpty() || !isVisible()) {
            count.setText("");
            return;
        }
        List<Surface> surfaces = host.surfaces();
        searched = surfaces;
        for (int i = 0; i < surfaces.size(); i++) {
            TextSearch.Result found = TextSearch.find(surfaces.get(i).text(), text, options());
            if (found.error() != null) {
                count.setText(found.error());
                query.getStyleClass().add("find-error");
                return;
            }
            for (Match match : found.matches()) {
                hits.add(new Hit(i, match));
            }
            capped |= found.capped();
        }
        if (hits.isEmpty()) {
            count.setText("No matches");
            return;
        }
        current = 0;
        if (fromCaret && !surfaces.isEmpty()) {
            int caret = surfaces.get(0).anchor();
            for (int i = 0; i < hits.size(); i++) {
                if (hits.get(i).area() == 0 && hits.get(i).match().start() >= caret) {
                    current = i;
                    break;
                }
            }
        }
        drawHighlights();
        reveal();
    }

    /** Moves to the next or previous match, wrapping round at either end. */
    public void step(int direction) {
        if (hits.isEmpty()) {
            search(false);
            if (hits.isEmpty()) return;
        }
        current = Math.floorMod(current + direction, hits.size());
        reveal();
    }

    /** Selects the current match, scrolls it into view, and says where it is in the count. */
    private void reveal() {
        if (current < 0 || current >= hits.size()) return;
        Hit hit = hits.get(current);
        searched.get(hit.area()).reveal(hit.match().start(), hit.match().end());
        count.setText((current + 1) + " of " + hits.size() + (capped ? "+" : ""));
    }

    private void drawHighlights() {
        List<Hit> drawn = hits.subList(0, Math.min(hits.size(), MAX_HIGHLIGHTS));
        for (int i = 0; i < searched.size(); i++) {
            int surface = i;
            List<Match> matches = drawn.stream().filter(hit -> hit.area() == surface).map(Hit::match).toList();
            if (!matches.isEmpty()) searched.get(i).mark(matches);
        }
        marked = drawn.size();
    }

    private void clearHighlights() {
        for (Surface surface : searched) {
            surface.clearMarks();
        }
        marked = 0;
    }

    /** Searches again whenever the text under the bar changes: an edit, or another file. */
    private void watchText() {
        unhooks.forEach(Runnable::run);
        unhooks.clear();
        for (Surface surface : host.surfaces()) {
            unhooks.add(surface.watch(() -> {
                if (isFinding()) search(false);
            }));
        }
    }

    // ------------------------------------------------------------------
    // Replacing
    // ------------------------------------------------------------------

    /** Replaces the current match and moves on to the next one. */
    void replaceCurrent() {
        if (!host.editable() || current < 0 || current >= hits.size()) return;
        Hit hit = hits.get(current);
        Surface surface = searched.get(hit.area());
        String text = surface.text();
        String with = TextSearch.replacementFor(text, hit.match(), query.getText(), replacement.getText(), options());
        int resumeAt = hit.match().start() + with.length();
        surface.replace(hit.match().start(), hit.match().end(), with);
        surface.moveTo(Math.min(resumeAt, surface.text().length()));
        // The text listener has searched again from scratch; carry on after this one.
        search(true);
    }

    /**
     * Replaces every match, in each area, as one change - so a single Ctrl+Z undoes the
     * whole replace rather than one occurrence at a time.
     */
    void replaceAll() {
        if (!host.editable() || query.getText() == null || query.getText().isEmpty()) return;
        int total = 0;
        for (Surface surface : host.surfaces()) {
            String text = surface.text();
            TextSearch.Replaced replaced = TextSearch.replaceAll(text, query.getText(), replacement.getText(), options());
            if (replaced.count() == 0) continue;
            total += replaced.count();
            // Only the span that changed is rewritten, so the caret and the scroll position
            // above it stay where they were.
            int prefix = 0;
            int limit = Math.min(text.length(), replaced.text().length());
            while (prefix < limit && text.charAt(prefix) == replaced.text().charAt(prefix)) prefix++;
            int suffix = 0;
            while (suffix < limit - prefix && text.charAt(text.length() - 1 - suffix)
                    == replaced.text().charAt(replaced.text().length() - 1 - suffix)) suffix++;
            surface.replace(prefix, text.length() - suffix,
                    replaced.text().substring(prefix, replaced.text().length() - suffix));
        }
        search(false);
        count.setText(total == 0 ? "No matches" : "Replaced " + total);
    }

    // ------------------------------------------------------------------
    // Going to a line
    // ------------------------------------------------------------------

    void goToLine() {
        int lines = host.lineCount();
        int wanted;
        try {
            wanted = Integer.parseInt(line.getText().trim());
        } catch (NumberFormatException e) {
            lineHint.setText("A line number, 1 to " + lines);
            return;
        }
        if (wanted < 1 || wanted > lines) {
            lineHint.setText("There is no line " + wanted + ". Lines run from 1 to " + lines + ".");
            return;
        }
        int paragraph = host.paragraphOfLine(wanted);
        if (paragraph < 0) {
            lineHint.setText("Line " + wanted + " is not in the changes shown. Switch to File view to see it.");
            return;
        }
        GenericStyledArea<?, ?, ?> area = host.lineArea();
        area.moveTo(paragraph, 0);
        area.showParagraphAtCenter(paragraph);
        hide();
        area.requestFocus();
    }

    // ------------------------------------------------------------------
    // Keys and helpers
    // ------------------------------------------------------------------

    private void onKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ESCAPE) {
            e.consume();
            hide();
        } else if (e.getCode() == KeyCode.F3 || (e.getCode() == KeyCode.ENTER && e.getTarget() == query)) {
            e.consume();
            step(e.isShiftDown() ? -1 : 1);
        }
    }

    private String selectedText() {
        for (Surface surface : host.surfaces()) {
            String selected = surface.selectedText();
            if (surface.isFocused() || !selected.isEmpty()) {
                return selected;
            }
        }
        return null;
    }

    private static ToggleButton toggle(String text, String tooltip) {
        ToggleButton toggle = new ToggleButton(text);
        toggle.getStyleClass().addAll("btn-sm", "find-option");
        toggle.setTooltip(new Tooltip(tooltip));
        toggle.setAccessibleText(tooltip);
        toggle.setFocusTraversable(false);
        toggle.setMinWidth(Region.USE_PREF_SIZE);
        return toggle;
    }

    private static void show(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /** The matches found, for tests. */
    List<Hit> hits() {
        return List.copyOf(hits);
    }

    int currentIndex() {
        return current;
    }

    TextField queryField() {
        return query;
    }

    TextField replacementField() {
        return replacement;
    }

    TextField lineField() {
        return line;
    }

    String countText() {
        return count.getText();
    }

    String lineHintText() {
        return lineHint.getText();
    }

    boolean replaceShown() {
        return replaceRow.isVisible();
    }

    /** The three options, in the order Aa, Word, .* - for tests. */
    List<ToggleButton> optionToggles() {
        return List.of(matchCase, wholeWord, regex);
    }

    /** How many matches are marked in the text right now. */
    int highlightCount() {
        return marked;
    }
}
