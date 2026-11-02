package com.jfx.git.core;

import com.jfx.git.model.RefInfo;
import com.jfx.git.model.RefNames;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Which branch a repository's work goes back into: what a new pull request targets, and
 * what the branch cleanup measures "merged" against.
 *
 * <p>The remote's own answer first - {@code origin/HEAD}, the symbolic ref a clone
 * records for the branch the server calls default - and failing that a local {@code main}
 * or {@code master}. Not the configured {@code init.defaultBranch}: that names the first
 * branch of a new repository, not the one an existing project chose.
 *
 * <p>Reads refs and one file, so it belongs off the FX thread like any other git query.
 */
public final class DefaultBranch {

    private static final List<String> USUAL = List.of("main", "master");

    private DefaultBranch() {
    }

    /**
     * The branch {@code origin/HEAD} points at, as a remote-tracking name - {@code
     * origin/main} - or {@code null} when this clone has no {@code origin/HEAD}.
     *
     * <p>Read from the ref itself, directly: no engine lists a remote's symbolic
     * {@code HEAD} among the remote branches. Git never packs a symbolic ref, so in a
     * repository that keeps its refs as files it is a file under the (common) directory;
     * one that keeps them in a reftable has no such file, and the engine is asked instead.
     * A file holding a commit rather than a branch name - written by hand, not by git - is
     * matched to the one {@code origin} branch at that commit, provided only one is.
     */
    public static String remoteHead(GitBackend git) {
        String file = refFile(git, "refs/remotes/origin/HEAD");
        String text = file != null ? file : symbolicRef(git, "refs/remotes/origin/HEAD");
        if (text == null) return null;
        if (text.startsWith("ref:")) {
            String target = text.substring(4).trim();
            return target.startsWith("refs/remotes/") ? target.substring("refs/remotes/".length()) : null;
        }
        List<String> remotes = git.getRemoteNames();
        List<String> same = git.getRemoteBranches().stream()
                .filter(r -> "origin".equals(RefNames.remoteOf(r.shortName(), remotes)))
                .filter(r -> text.equals(r.targetHash()))
                .map(RefInfo::shortName).toList();
        return same.size() == 1 ? same.get(0) : null;
    }

    /**
     * The default branch's name without its remote - {@code main} - or {@code null} when
     * there is neither an {@code origin/HEAD} nor a {@code main} or {@code master}.
     */
    public static String name(GitBackend git) {
        String remote = remoteHead(git);
        if (remote != null) return remote.substring("origin/".length());
        List<String> locals = git.getLocalBranches().stream().map(RefInfo::shortName).toList();
        List<String> remotes = git.getRemoteBranches().stream().map(RefInfo::shortName).toList();
        for (String usual : USUAL) {
            if (locals.contains(usual) || remotes.contains("origin/" + usual)) return usual;
        }
        return null;
    }

    /**
     * What "merged" is measured against: {@code origin/HEAD}'s branch, or else a local
     * {@code main} or {@code master}, or else {@code null}.
     */
    public static String baseRef(GitBackend git) {
        String remote = remoteHead(git);
        if (remote != null) return remote;
        List<String> locals = git.getLocalBranches().stream().map(RefInfo::shortName).toList();
        for (String usual : USUAL) {
            if (locals.contains(usual)) return usual;
        }
        return null;
    }

    /** What a loose ref's file says - {@code ref: <name>} or a commit - or {@code null}. */
    private static String refFile(GitBackend git, String ref) {
        File gitDir = git.getDirectory();
        if (gitDir == null) return null;
        for (File dir : List.of(gitDir, commonDir(gitDir))) {
            File file = new File(dir, ref.replace('/', File.separatorChar));
            try {
                if (!file.isFile()) continue;
                String text = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) return text;
            } catch (IOException unreadable) {
                // Fall through to the next place.
            }
        }
        return null;
    }

    /**
     * {@code ref: <name>} for a symbolic {@code ref} as the engine's ref store has it, or
     * {@code null}: for a repository that keeps its refs in a reftable, where there is no
     * file to read.
     */
    private static String symbolicRef(GitBackend git, String ref) {
        String target = git.symbolicRefTarget(ref);
        return target == null ? null : "ref: " + target;
    }

    /** A linked worktree's shared directory, named in its {@code commondir} file; else itself. */
    private static File commonDir(File gitDir) {
        File pointer = new File(gitDir, "commondir");
        try {
            if (pointer.isFile()) {
                String path = Files.readString(pointer.toPath(), StandardCharsets.UTF_8).trim();
                File common = new File(path);
                return common.isAbsolute() ? common : new File(gitDir, path);
            }
        } catch (IOException unreadable) {
            // The worktree's own directory, then.
        }
        return gitDir;
    }
}
