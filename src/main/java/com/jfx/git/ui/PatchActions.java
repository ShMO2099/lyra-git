package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.PatchApplyMode;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * Commits out of the repository as patch files, and patch files back in.
 *
 * <p>A patch is how a change travels where a push cannot: to someone without access to
 * the remote, into a review by mail, or from a machine that is not on the network. Git
 * has always done it from the command line; this is the same thing from the commit menu
 * and the File menu.
 */
public final class PatchActions {

    private final GitBackend gitService;
    /** Runs each one; only applying a patch changes what the tab shows. */
    private final OperationRunner operations;

    public PatchActions(OperationRunner operations) {
        this.operations = operations;
        this.gitService = operations.repository();
    }

    // ------------------------------------------------------------------
    // Out
    // ------------------------------------------------------------------

    /** Asks where to save, then writes {@code commits} there as one patch file. */
    public void saveAsPatch(List<CommitInfo> commits, Window owner) {
        if (commits.isEmpty()) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle(commits.size() == 1 ? "Save commit as patch" : "Save commits as patch");
        chooser.setInitialFileName(suggestedName(commits));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Patch files", "*.patch", "*.diff"));
        File target = chooser.showSaveDialog(owner);
        if (target == null) return;
        saveAsPatch(commits, target);
    }

    /** Writes {@code commits} to {@code target}; the half of saving that needs no file chooser. */
    void saveAsPatch(List<CommitInfo> commits, File target) {
        List<String> hashes = commits.stream().map(CommitInfo::getHash).toList();
        operations.run("Saving " + describe(commits) + " as a patch", OperationRunner.Refresh.NONE,
                () -> gitService.createPatch(hashes, target));
    }

    /**
     * Puts {@code commits} on the clipboard as patch text, for pasting into a mail or a
     * review comment.
     *
     * <p>Through a temporary file, because writing one is what the engines do; it is
     * deleted as soon as it has been read.
     */
    public void copyAsPatch(List<CommitInfo> commits) {
        if (commits.isEmpty()) return;
        List<String> hashes = commits.stream().map(CommitInfo::getHash).toList();
        String[] text = new String[1];
        operations.operation("Copying " + describe(commits) + " as a patch", OperationRunner.Refresh.NONE)
                .onSuccess(result -> {
                    if (text[0] != null) {
                        ClipboardContent content = new ClipboardContent();
                        content.putString(text[0]);
                        Clipboard.getSystemClipboard().setContent(content);
                    }
                })
                .run(() -> {
                    File scratch = null;
                    try {
                        scratch = File.createTempFile("lyra-copy-", ".patch");
                        GitResult result = gitService.createPatch(hashes, scratch);
                        if (result.isOk()) {
                            text[0] = Files.readString(scratch.toPath(), StandardCharsets.UTF_8);
                            return GitResult.ok("Copied " + describe(commits) + " as a patch");
                        }
                        return result;
                    } catch (Exception e) {
                        return GitResult.failed("Could not copy the patch: " + e.getMessage());
                    } finally {
                        if (scratch != null) scratch.delete();
                    }
                });
    }

    /**
     * A file name for the patch: the commit's subject as words a file system accepts, or
     * the count when there are several.
     */
    static String suggestedName(List<CommitInfo> commits) {
        if (commits.size() != 1) {
            return commits.size() + "-commits.patch";
        }
        String slug = commits.get(0).getSubject().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 52) {
            slug = slug.substring(0, 52).replaceAll("-+$", "");
        }
        return (slug.isEmpty() ? commits.get(0).getShortHash() : slug) + ".patch";
    }

    private static String describe(List<CommitInfo> commits) {
        return commits.size() == 1 ? commits.get(0).getShortHash() : commits.size() + " commits";
    }

    // ------------------------------------------------------------------
    // In
    // ------------------------------------------------------------------

    /** Asks for a patch file and how to apply it, then applies it. */
    public void applyPatch(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Apply patch");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Patch files", "*.patch", "*.diff", "*.mbox"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File workTree = gitService.getWorkTree();
        if (workTree != null && workTree.isDirectory()) {
            chooser.setInitialDirectory(workTree);
        }
        File patch = chooser.showOpenDialog(owner);
        if (patch == null) return;
        new com.jfx.git.ui.dialogs.ApplyPatchDialog(patch.getName(), carriesCommits(patch))
                .showAndWait()
                .ifPresent(mode -> applyPatch(patch, mode));
    }

    /** Applies {@code patch}; the half of applying that needs no dialogs. */
    void applyPatch(File patch, PatchApplyMode mode) {
        // Refreshed whatever happened: applied, staged, committed, or stopped on a
        // conflict that the operation banner now has to show.
        operations.run("Applying " + patch.getName(), OperationRunner.Refresh.EVERYTHING,
                () -> gitService.applyPatch(patch, mode));
    }

    /**
     * Whether {@code patch} is in mail form, and so can become commits.
     *
     * <p>Read from the top of the file. A patch saved from a commit starts
     * {@code From <hash> ...}; a mail saved from a client has {@code From:} and
     * {@code Subject:} headers before its diff. A bare diff has neither - and so has no
     * author or message to make a commit from. One header-like line is not enough: an
     * old-style diff opens with {@code Index: file.c}.
     */
    static boolean carriesCommits(File patch) {
        try (var lines = Files.lines(patch.toPath(), StandardCharsets.ISO_8859_1)) {
            boolean from = false;
            boolean subject = false;
            boolean first = true;
            for (String line : (Iterable<String>) lines.limit(200)::iterator) {
                if (first && line.isBlank()) continue;
                if (first && line.startsWith("From ")) return true;
                first = false;
                if (line.startsWith("diff ") || line.startsWith("--- ") || line.equals("---")) break;
                if (line.regionMatches(true, 0, "From:", 0, 5)) from = true;
                if (line.regionMatches(true, 0, "Subject:", 0, 8)) subject = true;
            }
            return from && subject;
        } catch (Exception e) {
            return false;
        }
    }
}
