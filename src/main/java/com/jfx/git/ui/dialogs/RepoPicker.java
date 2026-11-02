package com.jfx.git.ui.dialogs;

import com.jfx.git.ui.Icons;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The body the four provider pickers share - GitHub's, Gitea's and Bitbucket's
 * repositories, GitLab's projects: a filter, and the account's repositories framed under
 * it, one row each with the name, the description under it, and what kind of repository
 * it is ("Private", "Fork").
 *
 * <h2>Why one</h2>
 * The four were copies of one another, line for line, down to typing "· private" into the
 * row and using a bare label as the list's placeholder for loading, empty and failed alike.
 * They differ only in the client they ask and the record they get back, so each dialog
 * now says how to ask and how to read its record, and this does the rest.
 *
 * <p>The list says what it is doing in its own frame: loading, with a spinner; nothing
 * found; or why it could not load, in red. Once loaded, the first repository is selected,
 * so the footer's button works without a click first, and the filter's placeholder counts
 * what there is to filter.
 *
 * <h2>Focus</h2>
 * The filter is off until there is something to filter, and the dialog opens on the list:
 * Enter takes the selected row and the arrows move, while a letter typed there moves to
 * the filter with it. A focused field hides its placeholder, so opening on the filter
 * would have hidden the count as soon as it arrived.
 */
final class RepoPicker<T> {

    static final String FILTER_ID = "repo-picker-filter";
    static final String LIST_ID = "repo-picker-list";

    /** What a picker lists, one and many: "repository", "repositories". */
    record Noun(String one, String many) {
        String count(int n) {
            return n + " " + (n == 1 ? one : many);
        }
    }

    static final Noun REPOSITORIES = new Noun("repository", "repositories");
    static final Noun PROJECTS = new Noun("project", "projects");

    /**
     * A repository as its row shows it, and as the filter reads it.
     *
     * @param description may be null or blank for none
     * @param tags        what kind of repository it is: "Private", "Fork"
     */
    record Entry(String name, String description, List<String> tags) {
    }

    private final Dialog<T> dialog;
    private final Noun noun;
    private final Function<T, Entry> describe;
    private final ObservableList<T> all = FXCollections.observableArrayList();
    private final FilteredList<T> shown = new FilteredList<>(all);
    private final ListView<T> list = new ListView<>(shown);
    private final TextField filter = new TextField();

    /**
     * Fills {@code dialog}'s body and wires {@code use}: enabled while a row is selected,
     * pressed by a double-click on one, and the dialog's answer is that row.
     */
    RepoPicker(Dialog<T> dialog, ButtonType use, Noun noun, Function<T, Entry> describe) {
        this.dialog = dialog;
        this.noun = noun;
        this.describe = describe;

        filter.setId(FILTER_ID);
        filter.setPromptText("Filter " + noun.many());
        filter.setDisable(true);
        filter.textProperty().addListener((obs, old, text) -> applyFilter(text));

        list.setId(LIST_ID);
        DialogParts.framed(list, item -> row(describe.apply(item)), 4, 8, true);
        // Rows as wide as the frame and no wider. Left to the list, a row is as wide as its
        // longest description, and one long description put a horizontal scrollbar under
        // every row and pushed their "Private" and "Fork" out of sight past the frame's edge;
        // held to the frame, the description is cut instead and the tags stay at the end.
        var drawn = list.getCellFactory();
        list.setCellFactory(view -> {
            ListCell<T> cell = drawn.call(view);
            cell.setPrefWidth(0);
            return cell;
        });
        // The frame keeps the height it opened at while filtering, rather than the window
        // shrinking under the pointer with each letter.
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setPlaceholder(DialogParts.listMessage("Loading your " + noun.many() + "…",
                DialogParts.ListState.LOADING));
        list.addEventHandler(KeyEvent.KEY_TYPED, this::typeIntoFilter);

        dialog.getDialogPane().setContent(DialogParts.stack(filter, list));

        Node button = dialog.getDialogPane().lookupButton(use);
        button.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        // A double-click on a row is the choice made, with no second trip to the button.
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2
                    && onFilledRow(e.getPickResult().getIntersectedNode()) && button instanceof Button b) {
                b.fire();
            }
        });
        // After the shell's own move off the footer, which would pick the filter once it
        // is enabled - and whether it is by then depends on how fast the server answered.
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, e -> Platform.runLater(list::requestFocus));

        dialog.setResultConverter(type -> type == use ? list.getSelectionModel().getSelectedItem() : null);
    }

    /** The list arrived: shown, counted, and its first row selected. */
    void loaded(List<T> items) {
        all.setAll(items == null ? List.of() : items);
        filter.setDisable(all.isEmpty());
        filter.setPromptText("Filter " + (all.isEmpty() ? noun.many() : noun.count(all.size())));
        list.setPlaceholder(DialogParts.listMessage("No " + noun.many() + " found.", DialogParts.ListState.EMPTY));
        if (!shown.isEmpty()) {
            list.getSelectionModel().selectFirst();
        }
        // Arrives after the window opened at the height of the loading frame.
        JfxDialog.refit(dialog);
    }

    /** It did not arrive: why, in red, in the frame. */
    void failed(String reason) {
        all.clear();
        filter.setDisable(true);
        list.setPlaceholder(DialogParts.listMessage("Could not load " + noun.many() + ": " + reason,
                DialogParts.ListState.FAILED));
    }

    private void applyFilter(String text) {
        String typed = text == null ? "" : text.strip();
        String wanted = typed.toLowerCase(Locale.ROOT);
        shown.setPredicate(wanted.isEmpty() ? null : item -> matches(describe.apply(item), wanted));
        if (!all.isEmpty()) {
            list.setPlaceholder(DialogParts.listMessage("No " + noun.one() + " matches “" + typed + "”.",
                    DialogParts.ListState.EMPTY));
        }
        if (list.getSelectionModel().isEmpty() && !shown.isEmpty()) {
            list.getSelectionModel().selectFirst();
        }
    }

    private static boolean matches(Entry entry, String wanted) {
        return lower(entry.name()).contains(wanted) || lower(entry.description()).contains(wanted);
    }

    private static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    /** A letter typed in the list starts the filter with it. */
    private void typeIntoFilter(KeyEvent e) {
        String typed = e.getCharacter();
        if (filter.isDisabled() || typed == null || typed.isEmpty() || e.isShortcutDown() || e.isAltDown()
                || Character.isISOControl(typed.charAt(0)) || Character.isWhitespace(typed.charAt(0))) {
            return;
        }
        filter.requestFocus();
        filter.appendText(typed);
        filter.positionCaret(filter.getLength());
        e.consume();
    }

    private static boolean onFilledRow(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n instanceof ListCell<?> cell) {
                return !cell.isEmpty();
            }
        }
        return false;
    }

    /** The repository's mark, its name, its description on one line under it, its tags. */
    private static DialogParts.Row row(Entry entry) {
        String description = entry.description() == null ? null
                : entry.description().strip().replaceAll("\\s+", " ");
        return new DialogParts.Row(Icons.remote(), entry.name(),
                description == null || description.isEmpty() ? null : description,
                entry.tags() == null ? List.of() : entry.tags());
    }
}
