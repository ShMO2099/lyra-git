package com.jfx.git.ui;

import com.jfx.git.model.MessageParts;
import com.jfx.git.ui.dialogs.DialogParts;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * A commit message, written the way the commit box writes one: a summary line with the
 * characters left before most tools cut it off, and a description under it.
 *
 * <h2>Why one editor</h2>
 * The same message was typed in four shapes. The commit box split it into a summary with a
 * countdown and a description; Reword and Squash gave one 12- or 14-row text area at 15px
 * holding both; the merge and tag messages a three-row area or a single line; the rebase
 * plan's reword a one-line field that could not hold a body at all. A message reworded
 * looked different from the same message committed. Here every place that writes one uses
 * the commit box's shape - and the commit box's own rules, {@link #join} and {@link #split},
 * which are {@link MessageParts}', so the two cannot drift.
 */
public final class MessageEditor {

    /** Past this the countdown turns amber: the length git's own documentation suggests. */
    public static final int SUBJECT_TARGET = 50;
    /** Past this it turns red and negative: what most tools show of a subject. */
    public static final int SUBJECT_LIMIT = 72;

    /** A message's summary line and its description. */
    public record Parts(String summary, String description) {
    }

    /** Git's subject/body convention: two parts, one blank line between them; see {@link MessageParts#join}. */
    public static String join(String summary, String description) {
        return MessageParts.join(summary, description);
    }

    /** The inverse of {@link #join}: an existing message back into its summary and description. */
    public static Parts split(String message) {
        MessageParts parts = MessageParts.split(message);
        return new Parts(parts.summary(), parts.description());
    }

    /**
     * What is left of the 72 characters, counting down as {@code summary} is typed. A soft
     * limit: past it the number goes negative and red, and nothing is blocked - some
     * projects' conventions run longer, and the message is still the user's to write.
     */
    public static Label counter(TextInputControl summary) {
        Label counter = new Label();
        counter.getStyleClass().add("composer-counter");
        counter.setMinWidth(Region.USE_PREF_SIZE);
        counter.setTooltip(new Tooltip("Characters left before the summary is longer than most "
                + "tools show (" + SUBJECT_LIMIT + ")"));
        Runnable update = () -> {
            int length = summary.getText() == null ? 0 : summary.getText().length();
            counter.setText(Integer.toString(SUBJECT_LIMIT - length));
            counter.getStyleClass().removeAll("composer-counter-warn", "composer-counter-over");
            if (length > SUBJECT_LIMIT) {
                counter.getStyleClass().add("composer-counter-over");
            } else if (length > SUBJECT_TARGET) {
                counter.getStyleClass().add("composer-counter-warn");
            }
        };
        summary.textProperty().addListener((obs, old, text) -> update.run());
        update.run();
        return counter;
    }

    private final TextField summary = new TextField();
    private final TextArea description = new TextArea();
    private final DialogParts.Field summaryField;
    private final DialogParts.Field descriptionField;
    private final VBox node;

    /** @param message what the editor opens on; null or blank for nothing */
    public MessageEditor(String message) {
        summary.getStyleClass().addAll("composer-summary", "composer-message", "message-summary");
        summary.setPromptText("Fix rounding of refunds");
        description.getStyleClass().addAll("composer-message", "message-description");
        description.setWrapText(true);
        description.setPrefRowCount(6);
        summaryField = DialogParts.field("Summary", summary).trailing(counter(summary));
        descriptionField = DialogParts.field("Description", description).optional();
        node = DialogParts.stack(summaryField, descriptionField);
        node.getStyleClass().add("message-editor");
        setMessage(message);
    }

    /** How many rows the description shows before it scrolls. */
    public MessageEditor rows(int rows) {
        description.setPrefRowCount(rows);
        return this;
    }

    /** A hint under the description: what happens to the message, where it goes. */
    public MessageEditor hint(String text) {
        descriptionField.hint(text);
        return this;
    }

    /** An example for an empty summary, in place of the commit-flavoured default. */
    public MessageEditor placeholder(String example) {
        summary.setPromptText(example);
        return this;
    }

    /**
     * "(optional)" on the summary too, for a message that may be left out altogether: a
     * merge commit's, which git writes when there is none, or a tag's, whose absence
     * makes the tag lightweight.
     */
    public MessageEditor optional() {
        summaryField.optional();
        return this;
    }

    /** The message as git takes it: summary, a blank line, description. */
    public String message() {
        return join(summary.getText(), description.getText());
    }

    public void setMessage(String message) {
        Parts parts = split(message);
        summary.setText(parts.summary());
        description.setText(parts.description());
    }

    /**
     * Focuses the summary with the caret at its end. Not selected, as a field taking focus
     * otherwise is: a message being reworded or squashed is corrected far more often than
     * replaced, and the first key typed would have replaced it.
     */
    public void focus() {
        summary.requestFocus();
        summary.end();
    }

    /**
     * Adds a {@code token: value} trailer - "Co-authored-by: Grace <grace@example.com>" -
     * to the description, joined to a trailer block it already ends with, and not twice.
     */
    public void addTrailer(String token, String value) {
        description.setText(com.jfx.git.model.CommitTrailers.appendToBody(description.getText(), token, value));
    }

    /** Runs {@code change} whenever either part is edited. */
    public MessageEditor onChange(Runnable change) {
        summary.textProperty().addListener((obs, old, text) -> change.run());
        description.textProperty().addListener((obs, old, text) -> change.run());
        return this;
    }

    /** Disables or enables both parts, for a mode that makes no commit to put a message on. */
    public void setDisable(boolean disabled) {
        summary.setDisable(disabled);
        description.setDisable(disabled);
    }

    public TextField summary() {
        return summary;
    }

    public TextArea description() {
        return description;
    }

    /** The summary's field, for an error under it. */
    public DialogParts.Field summaryField() {
        return summaryField;
    }

    public Node node() {
        return node;
    }
}
