package com.jfx.git.model;

/**
 * One entry of {@code .gitmodules} together with its state on disk.
 *
 * <p>The hashes are plain strings. They used to be JGit {@code ObjectId}s, which made
 * this the one type crossing the {@code GitBackend} boundary that carried a JGit type -
 * the CLI backend had to construct {@code ObjectId} values it never otherwise needed, and
 * a libgit2 engine serving submodules natively would have had to do the same. Strings
 * cost nothing here: every consumer either abbreviates them for display or compares them
 * for equality.
 *
 * @param path       path within the superproject, which is also the submodule's name
 * @param url        configured remote, or {@code null} when {@code .gitmodules} lacks one
 * @param recorded   full SHA-1 the superproject points at, or {@code null}
 * @param checkedOut full SHA-1 the submodule's own HEAD is on, or {@code null} when it
 *                   is not initialised
 */
public record Submodule(String path,
                        String url,
                        String recorded,
                        String checkedOut,
                        SubmoduleState state) {

    /** "abc1234" of the recorded commit, or "-" when there is none. */
    public String shortRecorded() {
        return abbreviate(recorded);
    }

    public String shortCheckedOut() {
        return abbreviate(checkedOut);
    }

    private static String abbreviate(String hash) {
        if (hash == null || hash.isBlank()) return "-";
        return hash.length() >= 7 ? hash.substring(0, 7) : hash;
    }
}
