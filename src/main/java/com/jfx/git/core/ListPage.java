package com.jfx.git.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One page of a provider list, and whether there is another after it.
 *
 * <h2>Why pages</h2>
 * The pull request and issue dialogs used to follow every page before showing anything -
 * up to {@link ApiPaging#MAX_PAGES} requests of a hundred - so a busy repository sat on
 * "Loading..." for as long as that took. They now show the newest {@link #SIZE} as soon as
 * one request answers and fetch the next page only when asked.
 *
 * @param items   this page's items, oldest-last as the provider sorted them; never null
 * @param hasMore whether the provider said there is a next page ({@code Link: rel="next"}
 *                on GitHub and GitLab, the body's {@code next} on Bitbucket)
 */
public record ListPage<T>(List<T> items, boolean hasMore) {

    /** Items asked for per page: the most Bitbucket allows for pull requests, and one screenful-and-a-bit. */
    public static final int SIZE = 50;

    public ListPage {
        items = items == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(items));
    }
}
