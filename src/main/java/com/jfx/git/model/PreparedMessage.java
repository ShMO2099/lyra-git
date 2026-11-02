package com.jfx.git.model;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The commit message git prepares in {@code .git/MERGE_MSG} when a merge, cherry-pick or
 * revert stops before its commit.
 *
 * <h2>Why read it</h2>
 * Finishing such an operation is still that operation's commit: the cherry-picked commit's
 * own message, "Revert "…"" with the reverted hash, or "Merge branch …". Nothing read this
 * file, so the composer came up empty and whatever the user typed replaced the message -
 * the cherry-picked commit lost its description and a revert lost the line saying what it
 * reverts. The composer is filled from here instead, and the JGit engine's
 * {@code continueOperation()} commits with it.
 *
 * <h2>What is removed</h2>
 * Only the conflict summary git appends - {@code # Conflicts:} and the commented file list
 * under it - and nothing else. git strips it because its editor runs with
 * {@code cleanup=strip}; this application commits the text as given, so stripping every
 * {@code #} line the same way would also delete "#123 root cause" from a message that
 * legitimately starts a line with one.
 */
public final class PreparedMessage {

    private PreparedMessage() {
    }

    /** {@code MERGE_MSG} from {@code gitDir}, cleaned; empty when there is none. */
    public static String read(File gitDir) {
        if (gitDir == null) return "";
        File file = new File(gitDir, "MERGE_MSG");
        if (!file.isFile()) return "";
        try {
            return clean(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * {@code SQUASH_MSG} from {@code gitDir}, cleaned; empty when there is none.
     *
     * <p>A squash merge stops before committing and leaves no {@code MERGE_HEAD}, so
     * nothing reports it as an operation in progress - the staged changes and this file
     * are all there is. git's own {@code commit} pre-fills its editor from it; the
     * composer does the same, and every engine deletes it once a commit (or a reset)
     * has been made, as git does.
     */
    public static String readSquash(File gitDir) {
        if (gitDir == null) return "";
        File file = new File(gitDir, "SQUASH_MSG");
        if (!file.isFile()) return "";
        try {
            return clean(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * Drops git's trailing conflict summary and surrounding blank lines.
     *
     * <p>Both spellings are handled: newer git writes {@code # Conflicts:} commented,
     * older git and JGit's {@code MergeMessageFormatter} wrote {@code Conflicts:} bare,
     * followed by tab-indented paths.
     */
    public static String clean(String raw) {
        if (raw == null) return "";
        List<String> kept = new ArrayList<>();
        String[] lines = raw.replace("\r\n", "\n").split("\n", -1);
        boolean inSummary = false;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.equals("# Conflicts:") || trimmed.equals("Conflicts:")) {
                inSummary = true;
                continue;
            }
            if (inSummary) {
                // The summary is the commented or tab-indented path list and blank lines.
                if (trimmed.isEmpty() || line.startsWith("#") || line.startsWith("\t")) {
                    continue;
                }
                inSummary = false;
            }
            kept.add(line);
        }
        return String.join("\n", kept).strip();
    }
}
