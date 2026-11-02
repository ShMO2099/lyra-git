package com.jfx.git.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code SQUASH_MSG} a squash merge leaves behind, given a subject line.
 *
 * <h2>Why it is rewritten</h2>
 * git's own file starts "Squashed commit of the following:" and lists every commit in
 * full, which as a commit subject says nothing about which branch was squashed - and git
 * ignores {@code -m} under {@code --squash} altogether, so a message the user typed for
 * the merge had nowhere to go. The list is worth keeping as the body; it is exactly the
 * record of what the one commit now stands for. So a subject goes on top: the user's
 * message, or "Squash merge branch '...'" in the shape git gives a real merge.
 *
 * <p>Shared by every engine, so the composer is pre-filled with the same subject
 * whichever one made the merge. The listing underneath is each engine's own - JGit's
 * {@code SquashMessageFormatter} and git's differ in small ways - and nothing reads it
 * back except the user.
 */
final class SquashMessage {

    private static final Logger LOGGER = Logger.getLogger(SquashMessage.class.getName());

    private SquashMessage() {
    }

    /** The subject used when the user gave none. */
    static String defaultSubject(String sourceBranch) {
        return "Squash merge branch '" + sourceBranch + "'";
    }

    /**
     * Puts {@code customMessage}, or {@link #defaultSubject}, above what the engine wrote.
     *
     * <p>Does nothing when there is no {@code SQUASH_MSG}: a squash of a branch that is
     * already merged stages nothing and writes no file, and inventing one would pre-fill
     * the commit box for a commit there is nothing to make.
     */
    static void title(File gitDir, String sourceBranch, String customMessage) {
        if (gitDir == null) return;
        File file = new File(gitDir, "SQUASH_MSG");
        if (!file.isFile()) return;
        String subject = customMessage == null || customMessage.isBlank()
                ? defaultSubject(sourceBranch)
                : customMessage.strip();
        try {
            String listing = Files.readString(file.toPath(), StandardCharsets.UTF_8).strip();
            String combined = listing.isEmpty() ? subject + "\n" : subject + "\n\n" + listing + "\n";
            Files.writeString(file.toPath(), combined, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // The squash itself happened; only the pre-filled subject is lost.
            LOGGER.log(Level.WARNING, "Could not add a subject to " + file, e);
        }
    }

    /**
     * Deletes {@code SQUASH_MSG}, as {@code git commit} and {@code git reset} do.
     *
     * <p>JGit's commit only removes it when it took its message from the file, and this
     * application always passes the message explicitly, so a squash's message outlived
     * the commit that used it and was offered again for the next, unrelated one.
     */
    static void forget(File gitDir) {
        if (gitDir == null) return;
        try {
            Files.deleteIfExists(new File(gitDir, "SQUASH_MSG").toPath());
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not delete SQUASH_MSG in " + gitDir, e);
        }
    }
}
