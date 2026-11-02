package com.jfx.git.core.gitlab;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * A discussion thread on a merge request, as GitLab's REST API v4 describes it. A
 * plain top-level MR comment and a line-anchored diff comment share this exact shape -
 * only a diff comment's first note carries a non-null {@link GitLabNote#position()} -
 * so a discussion with no position on its first note is filtered out rather than
 * modelled separately.
 *
 * @param id    the discussion's own id
 * @param notes every note in the thread, oldest first - only the first note's
 *              position is read; a reply inherits the thread's own anchor rather than
 *              carrying its own
 */
public record GitLabDiscussion(String id, List<GitLabNote> notes) {

    /** The first note's position, or {@code null} for a thread not anchored to a diff line at all. */
    public GitLabNote.Position position() {
        return notes == null || notes.isEmpty() ? null : notes.get(0).position();
    }

    /** The first note's body and author - what a diff-anchored comment thread actually shows. */
    public GitLabNote firstNote() {
        return notes == null || notes.isEmpty() ? null : notes.get(0);
    }

    public record GitLabNote(long id, String body, GitLabUser author, Position position) {

        /**
         * @param newPath the file as it appears in the new (head) version
         * @param oldPath the file as it appears in the old (base) version - the same
         *                as {@code newPath} unless the file was renamed
         * @param newLine the line number in the new version, {@code null} for a
         *                comment on a removed line
         * @param oldLine the line number in the old version, {@code null} for a
         *                comment on an added line
         */
        public record Position(@SerializedName("new_path") String newPath,
                                @SerializedName("old_path") String oldPath,
                                @SerializedName("new_line") Integer newLine,
                                @SerializedName("old_line") Integer oldLine) {
        }
    }
}
