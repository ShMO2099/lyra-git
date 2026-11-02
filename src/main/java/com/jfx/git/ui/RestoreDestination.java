package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.ui.dialogs.Dialogs;
import com.jfx.git.ui.dialogs.JfxDialog;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Where a file restored from an older commit is written when it has been renamed since.
 *
 * <p>A commit's own file list names each file as that commit had it, so restoring a
 * pre-rename version wrote it under the old name: a second copy beside the renamed file,
 * recreated without a word, for the user to notice, compare and delete. Writing into the
 * renamed file instead would be a guess as well - the old name may be what they want back -
 * so the confirmation offers both, today's name first and as the default. A file that HEAD
 * still has under that name, or that was deleted rather than renamed, is asked about
 * exactly as before.
 *
 * <p>File History needs none of this: it follows the file across renames, so what it acts
 * on is always the file as it is called now.
 */
final class RestoreDestination {

    private RestoreDestination() {
    }

    /**
     * What {@code path}, as {@code commit} had it, is called at HEAD, when that is another
     * name: HEAD no longer has the path, and rename detection between the two commits pairs
     * it with the file it became.
     *
     * <p>Runs git; call it on the repository lane.
     *
     * @return the name at HEAD, or {@code null} when HEAD still has {@code path}, when it
     *         was deleted rather than renamed, or when there is no HEAD to compare with
     */
    static String renamedSince(GitBackend git, String commit, String path) {
        String head = git.getHeadHash();
        if (head == null || head.equals(commit)) {
            return null;
        }
        // Read first because it is cheap and usually settles it: a file still under its
        // name needs no rename detection across everything that has changed since. An
        // empty answer is either a missing file or an empty one; the empty one goes on to
        // the comparison, which pairs nothing with a path HEAD still has.
        if (git.getFileBytesAtCommit(head, path).length > 0) {
            return null;
        }
        for (GitBackend.FileChange change : git.getChangesBetweenCommits(commit, head)) {
            if (change.status() == 'R' && path.equals(change.oldPath())) {
                return change.path();
            }
        }
        return null;
    }

    /**
     * {@link #renamedSince} for the files of one revision, as the viewer asks it: on
     * {@code git}'s lane, answering on the FX thread.
     */
    static DiffViewer.RenameLookup lookupFor(GitBackend git, String commit, Consumer<String> onError) {
        return (path, answer) -> GitTaskRunner.query(git, () -> renamedSince(git, commit, path), answer, onError);
    }

    /** The option that writes into the file as it is called now: "Into docs/README.md". */
    static String intoLabel(String renamedTo) {
        return "Into " + renamedTo;
    }

    /** The option that writes under the old name: "Recreate README.md". */
    static String recreateLabel(String path) {
        return "Recreate " + path;
    }

    /**
     * Asks where a version of {@code path} goes now that the file is called
     * {@code renamedTo}: into {@code renamedTo}, the default, or back under {@code path},
     * recreating it beside the renamed file.
     *
     * <p>Two cards and Restore, not three buttons. The paths were the button labels, and a
     * footer cut "Restore into docs/README.md" to "Restore into docs/REA…" - the one part of
     * the label that said which file. On a card a path has the dialog's width, and wraps.
     *
     * @param title   the question, with {@code path} in backticks; null for "Restore `path`?"
     * @param message a line above the options, or null for none: the options say what each
     *                one overwrites
     * @return the path to write, or empty when the user cancelled
     */
    static Optional<String> ask(String title, String message, String path, String renamedTo) {
        return Dialogs.choose("Restore file", List.of(renamedTo, path))
                .icon(Icons.restore())
                .tone(JfxDialog.Tone.CAUTION)
                .title(title == null || title.isBlank() ? "Restore `" + path + "`?" : title)
                .subtitle("Renamed to `" + renamedTo + "` since then")
                .message(message)
                .text(option -> option.equals(renamedTo) ? intoLabel(renamedTo) : recreateLabel(path))
                .description(option -> option.equals(renamedTo)
                        ? "The file under the name it has now. Anything uncommitted in it is overwritten."
                        : "Writes it under its old name, beside " + renamedTo + ", which is left as it is.")
                .initial(renamedTo)
                .action("Restore")
                .ask();
    }
}
