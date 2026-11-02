package com.jfx.git.core;

/**
 * A repository refused because someone other than the current user owns it.
 *
 * <p>Its own type because every engine must treat it as final. Git refuses such a
 * repository (CVE-2022-24765: a {@code C:\.git} planted by another user would otherwise
 * run their hooks as you), libgit2 refuses it too, and JGit has no such check at all - so
 * a composite engine that logged the native refusal and carried on with JGit reopened
 * exactly the hole git closed. Catching this type is how a caller knows not to fall back.
 *
 * <p>The message is git's own, which names the path and the command that trusts it.
 */
public class DubiousOwnershipException extends GitOperationException {

    public DubiousOwnershipException(String message) {
        super(message);
    }
}
