package com.jfx.git.model;

/**
 * A named git identity: which name, email and signing key a repository should use.
 *
 * <p>{@code name} is what the user calls this profile in the switcher - "Work",
 * "Open source" - and is never written to a repository. {@code gitName}/{@code
 * gitEmail} are what actually becomes {@code user.name}/{@code user.email} once the
 * profile is applied. Kept apart because the identity a commit needs (a real name, a
 * real email a remote's notifications go to) is rarely a good label for a menu item.
 *
 * @param name       the profile's own label, unique among a user's profiles
 * @param gitName    {@code user.name} to apply
 * @param gitEmail   {@code user.email} to apply
 * @param signingKey {@code user.signingkey} to apply, or blank to leave gpg's own default
 * @param gpgSign    {@code commit.gpgsign} to apply
 * @param sshKeyPath the private key remotes are reached with while this profile is
 *                   active on a repository, or blank to leave the choice to ssh. Unlike
 *                   the fields above it is not written into the repository: it is
 *                   looked up from the profile whenever something talks to a remote.
 */
public record Profile(String name, String gitName, String gitEmail, String signingKey, boolean gpgSign,
                      String sshKeyPath) {

    public Profile {
        sshKeyPath = sshKeyPath == null ? "" : sshKeyPath;
    }

    /** A profile that names no SSH key, which is every profile made before it could. */
    public Profile(String name, String gitName, String gitEmail, String signingKey, boolean gpgSign) {
        this(name, gitName, gitEmail, signingKey, gpgSign, "");
    }
}
