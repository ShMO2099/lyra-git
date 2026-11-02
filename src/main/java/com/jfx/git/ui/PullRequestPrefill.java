package com.jfx.git.ui;

import com.jfx.git.core.DefaultBranch;
import com.jfx.git.core.GitBackend;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.ui.dialogs.CreatePullRequestDialog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * What a new pull request for a branch that was just published most likely says, worked
 * out before the form opens so the form opens filled in.
 *
 * <ul>
 *   <li>From the branch, into the repository's default branch ({@link DefaultBranch}).</li>
 *   <li>Titled with the subject of its one commit - a one-commit request is usually
 *       titled exactly that - or else with the branch's name read as words:
 *       {@code feature/add-login_page} is "Add login page".</li>
 *   <li>Described with the repository's pull request template, the first of
 *       {@link #TEMPLATES} that exists, the way the hosting sites start their own
 *       forms.</li>
 * </ul>
 */
public final class PullRequestPrefill {

    /** Where the hosts look for a template, in the order they look. */
    static final List<String> TEMPLATES = List.of(
            ".github/pull_request_template.md",
            ".github/PULL_REQUEST_TEMPLATE.md",
            "docs/pull_request_template.md",
            "docs/PULL_REQUEST_TEMPLATE.md",
            "pull_request_template.md",
            "PULL_REQUEST_TEMPLATE.md",
            ".gitlab/merge_request_templates/Default.md",
            ".gitea/pull_request_template.md");

    /** Templates bigger than this are not anyone's description. */
    private static final long MAX_TEMPLATE_BYTES = 64 * 1024;

    private PullRequestPrefill() {
    }

    /**
     * The draft for a request from {@code branch}; reads git, so never on the FX thread.
     *
     * @return the draft, with a {@code null} target when the repository has no default
     *         branch to propose into
     */
    public static CreatePullRequestDialog.Draft read(GitBackend git, String branch) {
        String target = DefaultBranch.name(git);
        String base = DefaultBranch.remoteHead(git);
        if (base == null) base = target;
        List<CommitInfo> commits = base == null ? List.of() : git.getCommitsInRange(base, branch, 2);
        String title = commits.size() == 1 ? commits.get(0).getSubject() : titleFromBranch(branch);
        return new CreatePullRequestDialog.Draft(title, template(git.getWorkTree()), branch, target);
    }

    /**
     * {@code feature/add-login_page} as "Add login page": the last segment, as words. An
     * issue key keeps its dash - {@code ABC-123-fix} is "ABC-123 fix".
     */
    static String titleFromBranch(String branch) {
        if (branch == null || branch.isBlank()) return "";
        String last = branch.substring(branch.lastIndexOf('/') + 1);
        List<String> words = new java.util.ArrayList<>();
        String[] parts = last.split("[-_.\\s]+");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (parts[i].matches("[A-Z][A-Z0-9]+") && i + 1 < parts.length && parts[i + 1].matches("\\d+")) {
                words.add(parts[i] + "-" + parts[++i]);
            } else {
                words.add(parts[i]);
            }
        }
        if (words.isEmpty()) return branch;
        String title = String.join(" ", words);
        return title.substring(0, 1).toUpperCase(Locale.ROOT) + title.substring(1);
    }

    /** The first template that exists under {@code workTree}, or an empty description. */
    static String template(File workTree) {
        if (workTree == null) return "";
        for (String path : TEMPLATES) {
            File file = new File(workTree, path);
            try {
                if (file.isFile() && file.length() <= MAX_TEMPLATE_BYTES) {
                    return Files.readString(file.toPath(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                }
            } catch (IOException unreadable) {
                // Not UTF-8 text, or not readable: try the next place.
            }
        }
        return "";
    }
}
