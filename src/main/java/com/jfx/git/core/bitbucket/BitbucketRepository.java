package com.jfx.git.core.bitbucket;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * A repository as Bitbucket Cloud's REST API v2 describes it - only the fields this
 * app's clone flow needs, out of the several dozen a real response carries.
 *
 * <p>Unlike GitHub's flat {@code clone_url}/{@code ssh_url} strings, Bitbucket names
 * both clone URLs inside one {@code links.clone} array, each entry naming its own
 * protocol - {@link #cloneUrl()} and {@link #sshUrl()} pick the right one out of it.
 *
 * @param fullName  {@code workspace/repo_slug}, what the repo picker shows
 * @param isPrivate whether this repository is private to the authenticated user
 * @param description the repository's own description, or blank
 */
public record BitbucketRepository(
        @SerializedName("full_name") String fullName,
        @SerializedName("is_private") boolean isPrivate,
        String description,
        Links links) {

    /** {@code cloneLinks} maps the JSON field {@code clone} - a record component cannot be named that: it collides with {@link Object#clone()}. */
    public record Links(@SerializedName("clone") List<CloneLink> cloneLinks) {
    }

    /** @param name the protocol this URL uses - {@code "https"} or {@code "ssh"} */
    public record CloneLink(String name, String href) {
    }

    private String cloneLink(String protocol) {
        if (links == null || links.cloneLinks() == null) {
            return null;
        }
        return links.cloneLinks().stream()
                .filter(l -> protocol.equals(l.name()))
                .map(CloneLink::href)
                .findFirst()
                .orElse(null);
    }

    public String cloneUrl() {
        return cloneLink("https");
    }

    public String sshUrl() {
        return cloneLink("ssh");
    }
}
