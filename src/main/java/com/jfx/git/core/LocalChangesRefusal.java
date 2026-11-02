package com.jfx.git.core;

import java.util.Locale;

/**
 * Recognises a checkout, pull or merge that was refused because it would overwrite local
 * changes - the one failure a stash, the operation again, and a pop of the stash turns
 * into a success.
 *
 * <p>Both engines' words, because both engines refuse: git says "Your local changes to the
 * following files would be overwritten by checkout" (or "by merge" - for an edit the merge
 * touches, and for a real merge over anything staged), or "The following untracked working
 * tree files would be overwritten", or for a rebasing pull "cannot pull with rebase: You
 * have unstaged changes"; JGit says "Checkout conflict with files", and where the
 * application words a refusal itself it uses git's sentence ({@code JGitPart.refusedOver},
 * which a JGit merge refused over a staged change or a path in the way also reports) or
 * "you have uncommitted changes". Matched on those phrases, case-insensitively, and asked
 * only of a failed result: a conflict is a merge that started, which a stash cannot take
 * back.
 *
 * <p>The engines' reading of those words, not the callers': the CLI engine turns git's
 * stderr into a {@link Refusal.LocalChanges} through these phrases
 * ({@link RefusalWords#localChanges}), and the JGit engine sets one where it catches the
 * refusal itself. A caller deciding whether to offer a stash asks
 * {@link GitResult#getRefusal()}, never this.
 */
public final class LocalChangesRefusal {

    private static final String[] PHRASES = {
            "would be overwritten",
            "checkout conflict with files",
            "you have unstaged changes",
            "you have uncommitted changes",
            "your index contains uncommitted changes",
            "commit your changes or stash them",
            "please commit or stash them",
    };

    private LocalChangesRefusal() {
    }

    /** Whether {@code message} words a refusal to overwrite local changes. */
    public static boolean matches(String message) {
        if (message == null || message.isBlank()) return false;
        String text = message.toLowerCase(Locale.ROOT);
        for (String phrase : PHRASES) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }
}
