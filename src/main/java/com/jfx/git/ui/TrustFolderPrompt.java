package com.jfx.git.ui;

import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.SafeDirectory;
import com.jfx.git.ui.dialogs.Dialogs;
import com.jfx.git.ui.dialogs.JfxDialog;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import java.io.File;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Git refused a repository because someone else owns it. Says who owns it and who the
 * user is, and offers the fix git's message recommends - {@code git config --global
 * --add safe.directory <path>} - as the decision itself, because it is a permanent
 * security exception and the user is the only one who knows whether that owner is
 * trusted.
 *
 * <p>One dialog, where there were two: git's refusal verbatim, then a confirmation that
 * repeated the path and the command. Now the owner, the consequence and the command are
 * on the one question, git's own words are behind Show Details, and Cancel is what Enter
 * does - trusting has to be chosen, not fallen into.
 *
 * <p>Its own class, rather than a method on the application, so the flow can be driven
 * in a test. A repository owned by another user cannot be made in one, but the prompt
 * only needs git's message; and the write it ends in can be pointed at a scratch global
 * config instead of the developer's own.
 */
public final class TrustFolderPrompt {

    /** The button that trusts the folder and opens it. */
    public static final String TRUST = "Trust and open";

    /** git 2.4x: "'C:/x' is owned by:\n\tBUILTIN/Administrators (S-1-5-32-544)\nbut the current user is:\n\t...". */
    private static final Pattern OWNERS = Pattern.compile(
            "is owned by:\\s*\\R\\s*(.+?)\\s*\\R\\s*but the current user is:\\s*\\R\\s*(.+?)\\s*(?:\\R|$)");
    /** A Windows account's SID after its name, which says nothing a person can check. */
    private static final Pattern SID = Pattern.compile("\\s*\\(S-[0-9-]+\\)\\s*$");

    private final String gitMessage;
    private final String path;
    private final Function<String, GitResult> trust;
    private final Runnable reopen;

    /**
     * @param dir        the folder that was being opened
     * @param gitMessage git's refusal, read for the owner and the path to trust, and kept
     *                   behind Show Details as git wrote it
     * @param reopen     opens {@code dir} again once it is trusted; runs on the FX thread
     */
    public TrustFolderPrompt(File dir, String gitMessage, Runnable reopen) {
        this(dir, gitMessage, SafeDirectory::trust, reopen);
    }

    /**
     * @param trust adds a path to {@code safe.directory}; runs on a worker. Tests pass one
     *              that writes to a scratch global config.
     */
    TrustFolderPrompt(File dir, String gitMessage, Function<String, GitResult> trust, Runnable reopen) {
        this.gitMessage = gitMessage;
        // Git's own spelling when its message has one: on a network share that is a
        // %(prefix)/ form no path built here would match.
        this.path = SafeDirectory.suggestedPath(gitMessage).orElseGet(() -> SafeDirectory.gitPath(dir));
        this.trust = trust;
        this.reopen = reopen;
    }

    /** The path the prompt names and {@code safe.directory} would be given. */
    public String path() {
        return path;
    }

    /** The command trusting runs, as the prompt shows it. */
    public String command() {
        return "git config --global --add safe.directory " + path;
    }

    /**
     * The owner and the current user git's refusal names, without Windows' SIDs, or null
     * when it names neither - older git, and libgit2, say only that the owner differs.
     */
    static String[] owners(String gitMessage) {
        if (gitMessage == null) return null;
        Matcher m = OWNERS.matcher(gitMessage);
        if (!m.find()) return null;
        return new String[]{SID.matcher(m.group(1)).replaceAll(""), SID.matcher(m.group(2)).replaceAll("")};
    }

    /**
     * Asks, and waits for the answer. The trust itself runs on a worker - it is a git
     * subprocess - and reopens on success.
     */
    public void show() {
        ButtonType trustButton = new ButtonType(TRUST, ButtonBar.ButtonData.OK_DONE);
        String[] who = owners(gitMessage);
        String message = (who == null ? "Git will not open a repository another account owns."
                : "It belongs to " + who[0] + ", and you are " + who[1] + ".")
                + " Trusting it lets its hooks and config run as you, in every git client on this"
                + " computer.";
        ButtonType answer = Dialogs.warn("Trust folder")
                .icon(Icons.shield())
                .title("Trust a folder owned by another user?")
                .subtitle("`" + path + "`")
                .message(message)
                .code("TRUSTING RUNS", command())
                .details(gitMessage)
                .buttons(trustButton, ButtonType.CANCEL)
                .safeDefault()
                .size(JfxDialog.Size.MD)
                .answer();
        if (answer != trustButton) {
            return;
        }
        GitTaskRunner.runDetached(() -> trust.apply(path), result -> {
            if (result.isOk()) {
                reopen.run();
            } else {
                Dialogs.error("Trust folder")
                        .icon(Icons.shield())
                        .title("Could not trust this folder")
                        .subtitle("`" + path + "`")
                        .message("Git's global config could not be changed, so the repository was not opened.")
                        .details(result.getMessage())
                        .size(JfxDialog.Size.MD)
                        .show();
            }
        });
    }
}
