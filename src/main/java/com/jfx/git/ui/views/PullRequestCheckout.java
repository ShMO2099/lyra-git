package com.jfx.git.ui.views;

import com.jfx.git.core.AutoStash;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.Refusal;
import com.jfx.git.ui.AutoStashQuestion;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.dialogs.Dialogs;
import com.jfx.git.ui.dialogs.JfxDialog;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Checking out a pull or merge request, and the question a diverged local branch raises.
 *
 * <p>Shared by the pull and merge request views. Checking a request out again
 * when its local branch has commits of its own <em>and</em> the request has moved on
 * used to end in a refusal telling the user to rename or delete the branch themselves.
 * Now the engine's {@link Refusal.Diverged} refusal becomes the question a person would
 * ask: take the request's head (the branch's own commits stay in its reflog), keep the
 * branch as it is, or do nothing.
 *
 * <p>Like every checkout, each of these offers auto-stash ({@link AutoStash}) when local
 * changes are in the way: the first attempt, the reset and the switch to the branch as it is.
 *
 * <p>Nothing here is a view of its own; it lives beside the views that use it. The question
 * stays an alert: it needs an answer before anything else can happen.
 */
public final class PullRequestCheckout {

    /** The answers to a diverged checkout. */
    public enum Choice {
        /** Move the branch onto the request's head. */
        RESET,
        /** Switch to the branch without updating it. */
        KEEP
    }

    /**
     * What a diverged checkout asks. Replaced by tests, which cannot answer a modal
     * dialog; empty means Cancel.
     */
    static volatile Asker asker = PullRequestCheckout::askWithAlert;

    /** Asks what to do with {@code localName}, which has {@code localOnly} commits {@code what} lacks. */
    @FunctionalInterface
    interface Asker {
        Optional<Choice> ask(String localName, String what, int localOnly);
    }

    private PullRequestCheckout() {
    }

    /**
     * Runs {@code checkout} (its argument: whether to reset a diverged branch) off the FX
     * thread, asking first when the branch has diverged - and offering auto-stash when local
     * changes are in the way - and reports the outcome.
     *
     * <p>One operation however many steps it takes, which refreshes the tab once it is
     * over unless it failed - a checkout that stopped on a conflict, putting the changes
     * back, moved the branch all the same - or was cancelled.
     *
     * @param localName the local branch the request is checked out as ({@code pr-7})
     * @param what      how the question names the request ("pull request #7")
     * @param onDone    receives the final result on the FX thread, after the refresh - a
     *                  failure when the user cancelled
     */
    public static void start(OperationRunner operations, String localName, String what,
                             Function<Boolean, GitResult> checkout, Consumer<GitResult> onDone) {
        GitBackend gitService = operations.repository();
        // What onDone hears when it is not what was reported: Cancel leaves the branch as it
        // was, which is said as it is, while for the caller nothing was checked out.
        AtomicReference<GitResult> handed = new AtomicReference<>();
        operations.operation("Checking out " + what, OperationRunner.Refresh.EVERYTHING)
                .refreshWhen(result -> (handed.get() != null ? handed.get() : result).getStatus()
                        != GitResult.Status.FAILED)
                .afterwards(result -> onDone.accept(handed.get() != null ? handed.get() : result))
                .start(steps -> withAutoStash(gitService, steps, () -> checkout.apply(false), first -> {
                    if (!(first.getRefusal() instanceof Refusal.Diverged diverged)) {
                        steps.finish(first);
                        return;
                    }
                    Optional<Choice> choice = asker.ask(localName, what, diverged.localCommits());
                    if (choice.isEmpty()) {
                        handed.set(first);
                        steps.finish(GitResult.ok("Left " + localName + " as it was"));
                        return;
                    }
                    if (choice.get() == Choice.RESET) {
                        withAutoStash(gitService, steps, com.jfx.git.ui.SnapshotGuard.guarded(gitService,
                                        "Before resetting " + localName + " to " + what, () -> checkout.apply(true)),
                                steps::finish);
                    } else {
                        withAutoStash(gitService, steps, () -> gitService.checkoutBranch(localName), kept ->
                                steps.finish(kept.isOk()
                                        ? GitResult.ok("Checked out " + localName + " as it is; " + what
                                                + " has commits it does not")
                                        : kept));
                    }
                }));
    }

    /** Runs a checkout as a step of the operation, offering auto-stash when local changes are in the way. */
    private static void withAutoStash(GitBackend gitService, OperationRunner.Steps steps,
                                      Supplier<GitResult> checkout, Consumer<GitResult> done) {
        AutoStash.run(gitService, steps::run, AutoStash.Operation.CHECKOUT, AutoStashQuestion::ask, checkout, done);
    }

    /**
     * The question, as a three-way warning. Reset is red - it moves the branch off its own
     * commits - and keeps Enter, as it always had: the commits stay in the reflog and the
     * working tree is snapshotted first ({@link #start}), so it can be undone.
     */
    private static Optional<Choice> askWithAlert(String localName, String what, int localOnly) {
        String commits = localOnly == 1 ? "1 local commit" : localOnly + " local commits";
        String kind = kindOf(what);
        // The branch is in the title; the buttons only need to say which way. Named in
        // full on the button, a long branch made a dialog 966px wide.
        ButtonType reset = new ButtonType("Reset to " + what, ButtonBar.ButtonData.YES);
        ButtonType keep = new ButtonType("Keep my branch", ButtonBar.ButtonData.NO);
        ButtonType answer = Dialogs.warn("Check out " + kind)
                .icon(com.jfx.git.ui.Icons.pullRequest())
                .title("`" + localName + "` and " + what + " have diverged")
                .subtitle("Your " + commits + " stay reachable in the reflog either way")
                .message(localName + " has " + commits + " that " + what + " does not, and the " + kind
                        + " has moved on. Reset moves the branch to the " + kind + "'s head, after"
                        + " a snapshot of any uncommitted changes; Keep my branch checks it out as it"
                        + " is, without the new commits.")
                .buttons(reset, keep, ButtonType.CANCEL)
                .danger(reset)
                .size(JfxDialog.Size.MD)
                .answer();
        if (answer == reset) return Optional.of(Choice.RESET);
        if (answer == keep) return Optional.of(Choice.KEEP);
        return Optional.empty();
    }

    /** "pull request" or "merge request", from how the caller named it. */
    private static String kindOf(String what) {
        return what.startsWith("merge request") ? "merge request" : "pull request";
    }
}
