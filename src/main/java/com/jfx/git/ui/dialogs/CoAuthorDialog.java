package com.jfx.git.ui.dialogs;


import com.jfx.git.ui.Icons;

import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Picks a co-author for the commit being composed: someone from recent history, or
 * anyone typed as {@code Name <email>}.
 *
 * <p>The result is the value of a {@code Co-authored-by:} trailer - the form GitHub and
 * GitLab read to credit a second author - which the composer adds to the message with
 * {@link com.jfx.git.model.CommitTrailers}. Offering recent authors is what makes the
 * feature usable: nobody remembers a colleague's exact commit email, and a trailer with
 * the wrong one credits nobody.
 */
public final class CoAuthorDialog extends Dialog<String> {

    /**
     * {@code Name <email>}: a non-empty name, one space, and an address with no spaces
     * or angle brackets - what git itself writes after an author's name.
     */
    private static final Pattern PERSON = Pattern.compile("^[^<>\\n]*[^<>\\s][^<>\\n]* <[^<>\\s]+>$");

    /** @param recentAuthors {@code Name <email>} entries to offer, most recent first */
    public CoAuthorDialog(List<String> recentAuthors) {
        JfxDialog.shell(this, JfxDialog.Size.SM, "Add co-author");
        JfxDialog.header(this, Icons.commit(), JfxDialog.Tone.NEUTRAL, "Add a co-author",
                "Credits someone else with a Co-authored-by line in the message");
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        JfxDialog.verb(this, ButtonType.OK, "Add co-author");

        ComboBox<String> person = new ComboBox<>();
        person.setEditable(true);
        person.getItems().setAll(recentAuthors);
        person.setPromptText("Grace Hopper <grace@example.com>");
        person.getStyleClass().add("co-author-picker");
        DialogParts.Field personField = DialogParts.field("Co-author", person)
                .hint(recentAuthors.isEmpty()
                        ? "The name and email they commit with, as Name <email>."
                        : "Someone who committed here recently, or anyone typed as Name <email>.");
        getDialogPane().setContent(DialogParts.stack(personField));

        // The editor's text, not the combo's value: an editable combo only commits typed
        // text to its value on Enter or focus loss, and OK is neither.
        var ok = getDialogPane().lookupButton(ButtonType.OK);
        ok.setDisable(true);
        person.getEditor().textProperty().addListener((o, was, now) -> {
            ok.setDisable(!isPerson(now));
            // Half-typed is not wrong yet; closed with ">" and still not the shape, it is.
            personField.error(now != null && now.strip().endsWith(">") && !isPerson(now)
                    ? "Not Name <email>: a name, a space, then the address in angle brackets." : null);
        });
        person.valueProperty().addListener((o, was, now) -> {
            if (now != null) person.getEditor().setText(now);
        });

        setResultConverter(button -> button == ButtonType.OK && isPerson(person.getEditor().getText())
                ? person.getEditor().getText().strip()
                : null);
    }

    /** Whether {@code text} is a {@code Name <email>} a trailer can carry. */
    public static boolean isPerson(String text) {
        return text != null && PERSON.matcher(text.strip()).matches();
    }

    /** How far back the offer looks for people. */
    private static final int HISTORY = 500;
    /** And how many of them it offers. */
    private static final int CHOICES = 30;

    /**
     * "Add co-author…" from anywhere a message is written - the commit box, Reword: reads
     * recent authors on the git lane, asks, and hands the {@code Name <email>} chosen to
     * {@code credit} on the FX thread.
     *
     * @param failed told why recent history could not be read
     */
    public static void ask(com.jfx.git.core.GitBackend backend, java.util.function.Consumer<String> credit,
                           java.util.function.Consumer<String> failed) {
        com.jfx.git.core.GitTaskRunner.query(backend, () -> recentAuthors(backend),
                authors -> new CoAuthorDialog(authors).showAndWait().ifPresent(credit),
                failed);
    }

    /**
     * Distinct {@code Name <email>} of recent authors, most recent first, leaving out
     * whoever is committing - crediting yourself as a co-author credits nobody.
     * Reads the repository: run it on the worker.
     */
    static List<String> recentAuthors(com.jfx.git.core.GitBackend backend) {
        String self = backend.getCommitIdentity();
        String selfEmail = self == null || self.indexOf('<') < 0 ? ""
                : self.substring(self.indexOf('<') + 1, self.length() - 1).strip();
        var page = backend.getCommitGraphPage(HISTORY, com.jfx.git.graph.GraphOrder.CHRONOLOGICAL);
        java.util.Map<String, String> byEmail = new java.util.LinkedHashMap<>();
        for (var row : page.rows()) {
            com.jfx.git.model.CommitInfo commit = row.getCommit();
            if (row.isWipNode() || row.isStash() || commit == null) continue;
            String name = commit.getAuthorName();
            String email = commit.getAuthorEmail();
            if (name == null || name.isBlank() || email == null || email.isBlank()) continue;
            if (email.strip().equalsIgnoreCase(selfEmail)) continue;
            String person = name.strip() + " <" + email.strip() + ">";
            // One entry per address: the same person under two spellings of their name
            // is still one co-author, and GitHub matches the trailer by email.
            byEmail.putIfAbsent(email.strip().toLowerCase(java.util.Locale.ROOT), person);
            if (byEmail.size() == CHOICES) break;
        }
        return List.copyOf(byEmail.values());
    }
}
