package com.jfx.git.ui.dialogs;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.graph.CommitOrdering;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.UiScale;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * What a reset to a point in the branch's history is about to do, before it does it.
 *
 * <h2>Why</h2>
 * The reflog undo is a hard reset. It already knows exactly which commit it is going back
 * to and therefore exactly which commits stop being on the branch and which files differ
 * afterwards - and it used to ask "are you sure?" without saying any of it. That is the
 * one dialog where "yes" is a guess.
 *
 * <p>So this shows the answer: the commits that will no longer be on this branch, the
 * commits that will be back on it, and the files whose contents will change on disk. All
 * three come from the two commits involved, which the caller already has. The toolbar's
 * "Preview undo…" and Branch history's "Restore branch to this point" both ask with it -
 * they are the same hard reset, so they answer the same question the same way.
 *
 * <p>A reset is not always backwards. Restoring the tip after restoring an older entry
 * moves the branch forward: nothing leaves it and commits come back. Restoring to before an
 * amend or a rebase does both. So the commits are listed in both directions, and the
 * headline says which way the branch is going rather than assuming "back".
 *
 * <p>The commits are not destroyed - the reflog still names them, which is how this
 * feature exists at all - so the wording says "no longer on this branch" rather than
 * anything about losing them. The files are the part that is genuinely overwritten, and
 * they are listed last because that is the part worth reading twice.
 */
public final class UndoPreviewDialog {

    private UndoPreviewDialog() {
    }

    /** Where the reset was asked for: the window's title, and how what leaves is got back. */
    public enum Origin {
        /** The toolbar's "Preview undo…". */
        UNDO("Undo", "Undo", "They stay in the reflog, which is what Redo uses."),
        /** Branch history's "Restore branch to this point". */
        BRANCH_HISTORY("Branch history", "Restore", "They stay in this history, so this can be undone in turn.");

        private final String title;
        private final String verb;
        private final String wayBack;

        Origin(String title, String verb, String wayBack) {
            this.title = title;
            this.verb = verb;
            this.wayBack = wayBack;
        }
    }

    /**
     * Asks the user to confirm {@code label}, showing what changes.
     *
     * <p>The preview is gathered on the worker and the dialog is raised when it arrives,
     * so a slow repository delays the dialog rather than freezing the window. If the
     * preview cannot be built the confirmation still appears, without it: failing to
     * describe an operation is not a reason to refuse to offer it.
     *
     * @param onConfirmed run on the FX thread when the user agrees
     */
    public static void confirm(GitBackend gitService, Origin origin, String label, String targetHash,
                               Consumer<Boolean> onConfirmed) {
        GitTaskRunner.query(gitService,
                () -> gather(gitService, targetHash),
                preview -> show(origin, label, targetHash, preview, onConfirmed),
                error -> show(origin, label, targetHash, Preview.unavailable(error), onConfirmed));
    }

    /** The commits leaving the branch, those coming back to it, and the files changing on disk. */
    private record Preview(List<CommitInfo> leaving, List<CommitInfo> returning,
                           List<String> files, String problem) {
        static Preview unavailable(String problem) {
            return new Preview(List.of(), List.of(), List.of(), problem);
        }
    }

    private static Preview gather(GitBackend gitService, String targetHash) {
        // getCommitsSince is target..HEAD: on the branch now and not at the target, which
        // is precisely the set that stops being reachable from the branch after the reset.
        List<CommitInfo> leaving = gitService.getCommitsSince(targetHash);
        String head = gitService.getHeadHash();
        if (head == null) {
            return new Preview(leaving, List.of(), List.of(), null);
        }
        // HEAD..target, the other way round: what the branch gains. Empty going back,
        // all of the answer going forward.
        List<CommitInfo> returning = oldestFirst(
                gitService.getCommitsInRange(head, targetHash, Integer.MAX_VALUE), targetHash);
        return new Preview(leaving, returning, gitService.getFilesBetweenCommits(head, targetHash), null);
    }

    /**
     * Newest-first {@code commits} ending at {@code tip}, in the order the leaving commits
     * come in - so the two lists read the same way on every engine, merges included.
     */
    private static List<CommitInfo> oldestFirst(List<CommitInfo> commits, String tip) {
        List<CommitInfo> ordered = new ArrayList<>(CommitOrdering.canonical(commits, tip));
        Collections.reverse(ordered);
        return ordered;
    }

    private static void show(Origin origin, String label, String targetHash, Preview preview,
                             Consumer<Boolean> onConfirmed) {
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog =
                new javafx.scene.control.Dialog<>();
        JfxDialog.shell(dialog, JfxDialog.Size.MD, origin.title);
        // Caution, not destructive: the commits stay reachable and the way back is named
        // below. The button is red all the same, because the files on disk are overwritten.
        JfxDialog.header(dialog, Icons.undo(), JfxDialog.Tone.CAUTION, label,
                "A hard reset: the branch moves and the working tree follows it");
        dialog.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        JfxDialog.verb(dialog, javafx.scene.control.ButtonType.OK, origin.verb);
        JfxDialog.danger(dialog, javafx.scene.control.ButtonType.OK);

        VBox content = DialogParts.stack(headline(targetHash, preview));
        if (preview.problem() != null) {
            content.getChildren().add(caption("The preview could not be built: " + preview.problem()));
        }
        // Each list sits with its heading, whose count says how long the list is; the
        // groups stand apart from one another.
        if (!preview.leaving().isEmpty()) {
            content.getChildren().add(group(
                    DialogParts.section(count(preview.leaving().size(), "commit") + " no longer be on this branch"),
                    commitList(preview.leaving()),
                    caption(origin.wayBack)));
        }
        if (!preview.returning().isEmpty()) {
            content.getChildren().add(group(
                    DialogParts.section(count(preview.returning().size(), "commit") + " be back on this branch"),
                    commitList(preview.returning())));
        }
        if (!preview.files().isEmpty()) {
            content.getChildren().add(group(
                    DialogParts.section(count(preview.files().size(), "file") + " change on disk"),
                    fileList(preview.files())));
        }

        dialog.getDialogPane().setContent(content);
        onConfirmed.accept(dialog.showAndWait()
                .filter(javafx.scene.control.ButtonType.OK::equals).isPresent());
    }

    /** "1 commit will", "3 files will". */
    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s") + " will";
    }

    /** Which way the branch goes, said before the lists that show it. */
    private static Label headline(String targetHash, Preview preview) {
        String shortHash = GitBackend.shortenHash(targetHash);
        boolean leaving = !preview.leaving().isEmpty();
        boolean returning = !preview.returning().isEmpty();
        String text;
        if (preview.problem() != null) {
            // Without the preview the direction is unknown, so the sentence claims none.
            text = "This resets the branch to " + shortHash + ".";
        } else if (leaving && returning) {
            text = "This resets the branch to " + shortHash + ", on a different line of history:"
                    + " some commits leave it and others come back.";
        } else if (leaving) {
            text = "This resets the branch to " + shortHash + ", discarding what came after.";
        } else if (returning) {
            text = "This moves the branch forward to " + shortHash + ". No commit leaves it.";
        } else if (preview.files().isEmpty()) {
            text = "This restores the branch to " + shortHash + ". Nothing on disk changes.";
        } else {
            text = "This resets the branch to " + shortHash + ".";
        }
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    /** A heading, its list, and what follows from it, closer to one another than to the next. */
    private static VBox group(Node... parts) {
        return new VBox(UiScale.SNUG, parts);
    }

    private static Label caption(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("t-caption");
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    /**
     * The commits as framed rows: the short SHA in the monospace face, then the subject.
     *
     * <p>As tall as the rows, up to six of them. A dialog that scrolls for three commits
     * reads as though there were more; one that grows without limit for two hundred stops
     * being a dialog - and with three lists, six each keeps the footer on a laptop screen.
     */
    private static ListView<CommitInfo> commitList(List<CommitInfo> commits) {
        ListView<CommitInfo> list = new ListView<>(FXCollections.observableArrayList(commits));
        DialogParts.framed(list, commit -> new DialogParts.Row(sha(commit.getShortHash()), commit.getSubject()),
                1, MAX_ROWS, false);
        return list;
    }

    /** The files as framed rows, each with the file mark and its path. */
    private static ListView<String> fileList(List<String> files) {
        ListView<String> list = new ListView<>(FXCollections.observableArrayList(files));
        DialogParts.framed(list, path -> DialogParts.Row.path(Icons.file(), path), 1, MAX_ROWS, false);
        return list;
    }

    /** A short SHA where a row's icon goes, so the subjects after it line up. */
    private static Label sha(String shortHash) {
        Label hash = new Label(shortHash);
        hash.getStyleClass().addAll("t-mono", "t-caption");
        hash.setMinWidth(Region.USE_PREF_SIZE);
        return hash;
    }

    private static final int MAX_ROWS = 6;
}
