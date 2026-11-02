package com.jfx.git.ui.views;

import com.jfx.git.core.BuildStatus;

import javafx.scene.control.Label;

/**
 * Colours a small status-dot {@link Label} for a PR/MR list row's CI status -
 * shared by the provider views, whose {@code EntryCell}s are otherwise
 * independent classes with no common ancestor to hang this on.
 */
final class PrStatusIcon {

    private PrStatusIcon() {
    }

    /**
     * @param status {@code null} for "not fetched yet" (rendered the same as {@link
     *               BuildStatus#NONE} - a first pass does not need to tell "still
     *               loading" apart from "no CI at all")
     */
    static void style(Label dot, BuildStatus status) {
        dot.getStyleClass().removeAll("pr-status-passed", "pr-status-pending", "pr-status-failed");
        if (status == BuildStatus.PASSED) {
            dot.getStyleClass().add("pr-status-passed");
        } else if (status == BuildStatus.PENDING) {
            dot.getStyleClass().add("pr-status-pending");
        } else if (status == BuildStatus.FAILED) {
            dot.getStyleClass().add("pr-status-failed");
        }
    }
}
