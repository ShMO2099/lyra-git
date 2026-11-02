package com.jfx.git.core;

import com.jfx.git.model.LfsLock;

import java.util.ArrayList;
import java.util.List;

/**
 * Git LFS: which files it holds, fetching and pruning their content, and locking them.
 *
 * <p>One of the capability interfaces {@link GitBackend} composes; see its javadoc for
 * why the contract is split up rather than kept as one list.
 *
 * <h2>One engine</h2>
 * LFS is a program, {@code git-lfs}, that git runs as a filter and as a subcommand. An
 * engine that is not git has neither. So everything here that changes something is done
 * by the Native Git engine alone, and every other engine answers with {@link
 * #NEEDS_NATIVE} - a refusal that says what would work - rather than doing nothing and
 * reporting success. Reading the tracked patterns is the exception: that is a file, and
 * any engine can read a file.
 */
public interface LfsOps {

    /** What an engine that cannot run {@code git-lfs} says, so the answer is the same everywhere. */
    String NEEDS_NATIVE = "Git LFS needs the Native Git engine.";

    /** What the Native engine says when {@code git-lfs} itself is not installed. */
    String NOT_INSTALLED = "Git LFS is not installed on this machine. Install it from git-lfs.com,"
            + " then try again.";

    /**
     * The patterns LFS tracks, from the repository's root {@code .gitattributes}, in the
     * order they are written there.
     *
     * <p>The root file only, which is where {@code git lfs track} writes. A nested
     * {@code .gitattributes} can add more, and those are honoured by git; they are just
     * not listed.
     */
    List<String> lfsTrackedPatterns();

    /**
     * Sets LFS up for this repository - {@code git lfs install --local}: the filter in
     * the repository's own configuration, and the hooks that push LFS content.
     *
     * <p>{@code --local}, so nothing is written to the user's global configuration on
     * their behalf. It is enough for this repository, which is what was asked about.
     */
    GitResult lfsInstall();

    /**
     * Has LFS hold the files matching {@code pattern} - {@code git lfs track}. Writes
     * {@code .gitattributes}, which then has to be committed like any other change;
     * files already committed are not converted.
     */
    GitResult lfsTrack(String pattern);

    /** Stops LFS holding the files matching {@code pattern} - {@code git lfs untrack}. */
    GitResult lfsUntrack(String pattern);

    /** Downloads the LFS content the current checkout needs, without touching the working tree. */
    GitResult lfsFetch();

    /** {@link #lfsFetch()}, then replaces pointer files in the working tree with their content. */
    GitResult lfsPull();

    /** Deletes local copies of LFS content that no recent commit needs and the remote already has. */
    GitResult lfsPrune();

    /** The files locked on the remote, by anyone. Asks the server, so it can be slow and can fail. */
    List<LfsLock> lfsLocks();

    /** Locks {@code path} on the remote, so nobody else can push a change to it. */
    GitResult lfsLock(String path);

    /**
     * Releases the lock on {@code path}.
     *
     * @param force release it although someone else holds it; the server decides whether
     *              this user may
     */
    GitResult lfsUnlock(String path, boolean force);

    /**
     * The patterns marked {@code filter=lfs} in the text of a {@code .gitattributes}.
     *
     * <p>Shared so every engine reads the file the same way. A pattern is the first
     * field of its line; one with spaces in it is written by git with {@code [[:space:]]}
     * in their place, and is given back as git wrote it, since that is the form
     * {@link #lfsUntrack} needs.
     */
    static List<String> patternsIn(String gitattributes) {
        List<String> patterns = new ArrayList<>();
        if (gitattributes == null) return patterns;
        for (String raw : gitattributes.split("\\r?\\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("\\s+");
            for (int i = 1; i < fields.length; i++) {
                if (fields[i].equals("filter=lfs")) {
                    patterns.add(fields[0]);
                    break;
                }
            }
        }
        return patterns;
    }
}
