package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.RebaseTodo;
import com.jfx.git.core.RepositoryCommands;

import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;

import java.util.List;
import java.util.function.Supplier;

/**
 * What dropping one branch on another offers: merge, rebase, push, track.
 *
 * <p>Shared by the sidebar's branch rows and the graph's ref chips, so a branch dragged
 * from either onto either gets the same menu and the same actions. It began as a private
 * method of the sidebar that knew only local branches; the graph could not reach it, and
 * a remote branch had nowhere to be dropped at all.
 *
 * <p>The menu depends on what the two ends are:
 * <ul>
 *   <li><b>onto a local branch</b> - merge the dragged branch into it, with or without
 *       options; and then either rebase the dragged branch onto it (a local branch) or
 *       make the dragged branch its upstream (a remote one);</li>
 *   <li><b>a local branch onto a remote branch</b> - push it to that remote, or track
 *       that branch;</li>
 *   <li><b>a remote branch onto a remote branch</b> - nothing: neither end is something
 *       this clone can change.</li>
 * </ul>
 */
public final class RefDropMenu {

    private static final javafx.css.PseudoClass DROP_TARGET = javafx.css.PseudoClass.getPseudoClass("drop-target");

    /**
     * Marks or unmarks {@code node} as where a drop would land. Nothing used to change
     * under a dragged branch, so whether letting go here would do anything - and to
     * which branch - was found out by letting go.
     */
    public static void markTarget(javafx.scene.Node node, boolean on) {
        node.pseudoClassStateChanged(DROP_TARGET, on);
    }

    /** Drags a picture of what was picked up, so the pointer carries the branch it is moving. */
    public static void dragImage(javafx.scene.input.Dragboard board, javafx.scene.Node picked) {
        try {
            javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
            params.setFill(javafx.scene.paint.Color.TRANSPARENT);
            board.setDragView(picked.snapshot(params, null));
        } catch (RuntimeException unavailable) {
            // A snapshot needs a rendered node; without one the drag simply has no picture.
        }
    }

    /** Which side of the repository a dragged or dropped-on branch lives on. */
    public enum Kind { LOCAL, REMOTE }

    /**
     * One end of a drag.
     *
     * @param name the branch's short name: {@code feature/x}, or {@code origin/feature/x}
     */
    public record Ref(Kind kind, String name) {

        private static final String PREFIX = "lyra-ref:";

        public static Ref local(String name) {
            return new Ref(Kind.LOCAL, name);
        }

        public static Ref remote(String name) {
            return new Ref(Kind.REMOTE, name);
        }

        /**
         * This ref as dragboard text. Marked as the application's own, so text dragged in
         * from an editor or a browser is not taken for a branch name and merged.
         */
        public String encode() {
            return PREFIX + kind + ":" + name;
        }

        /** The ref {@code text} carries, or {@code null} when it is not one of ours. */
        public static Ref decode(String text) {
            if (text == null || !text.startsWith(PREFIX)) return null;
            String rest = text.substring(PREFIX.length());
            int colon = rest.indexOf(':');
            if (colon <= 0 || colon == rest.length() - 1) return null;
            try {
                return new Ref(Kind.valueOf(rest.substring(0, colon)), rest.substring(colon + 1));
            } catch (IllegalArgumentException unknownKind) {
                return null;
            }
        }
    }

    private final GitBackend gitService;
    private final StatusBar statusBar;
    /** Runs every action, each refreshing the whole tab whether or not it worked. */
    private final OperationRunner operations;
    /** The configured remotes from the newest ref snapshot, read when a menu is built. */
    private Supplier<List<String>> knownRemotes = List::of;

    public RefDropMenu(OperationRunner operations) {
        this.operations = operations;
        this.gitService = operations.repository();
        this.statusBar = operations.statusBar();
    }

    /**
     * Where the configured remotes come from - the newest ref snapshot - so a label names
     * the remote a target belongs to by the same rule the sidebar files it under, without
     * asking git on the FX thread. Unset, a remote branch's first segment is its remote.
     */
    public void setRemoteNames(Supplier<List<String>> remotes) {
        this.knownRemotes = remotes == null ? List::of : remotes;
    }

    /** Whether dropping {@code source} on {@code target} would offer anything. */
    public static boolean accepts(Ref source, Ref target) {
        if (source == null || target == null || source.equals(target)) return false;
        return target.kind() == Kind.LOCAL || source.kind() == Kind.LOCAL;
    }

    /** Shows the menu for the drop at a screen position; does nothing for a pair that offers nothing. */
    public void show(Node anchor, double screenX, double screenY, Ref source, Ref target,
                     String currentBranch) {
        ContextMenu menu = build(source, target, currentBranch);
        if (!menu.getItems().isEmpty()) {
            menu.show(anchor, screenX, screenY);
        }
    }

    /**
     * The menu for dropping {@code source} on {@code target}.
     *
     * @param currentBranch the branch that is checked out, or {@code null}/blank when
     *                      detached; decides whether an item has to say it checks out first
     * @return the menu, with no items when the pair offers nothing
     */
    public ContextMenu build(Ref source, Ref target, String currentBranch) {
        ContextMenu menu = new ContextMenu();
        if (!accepts(source, target)) {
            return menu;
        }
        if (target.kind() == Kind.LOCAL) {
            menu.getItems().addAll(mergeItem(source, target), mergeWithOptionsItem(source, target));
            menu.getItems().add(source.kind() == Kind.LOCAL
                    ? rebaseItem(source, target)
                    : trackItem(target.name(), source.name()));
        } else {
            menu.getItems().addAll(pushItem(source, target, currentBranch),
                    trackItem(source.name(), target.name()));
        }
        return menu;
    }

    private MenuItem mergeItem(Ref source, Ref target) {
        MenuItem item = new MenuItem("Merge " + source.name() + " into " + target.name());
        item.setOnAction(e -> mergeAfterCheckout(target.name(),
                () -> new RepositoryCommands(gitService).merge(source.name(), false),
                "Merging " + source.name() + " into " + target.name()));
        return item;
    }

    private MenuItem mergeWithOptionsItem(Ref source, Ref target) {
        MenuItem item = new MenuItem("Merge " + source.name() + " into " + target.name() + " with options…");
        // Asked before anything moves, so cancelling leaves the checkout where it was.
        item.setOnAction(e -> new com.jfx.git.ui.dialogs.MergeDialog(source.name(), target.name())
                .showAndWait()
                .ifPresent(choice -> mergeAfterCheckout(target.name(),
                        () -> gitService.merge(source.name(), choice.mode(), choice.messageOrNull()),
                        "Merging " + source.name() + " into " + target.name())));
        return item;
    }

    private MenuItem rebaseItem(Ref source, Ref target) {
        MenuItem item = new MenuItem("Rebase " + source.name() + " onto " + target.name());
        item.setOnAction(e -> operations.operation("Checking out " + source.name(), OperationRunner.Refresh.EVERYTHING)
                .afterwards(checkedOut -> {
                    if (checkedOut.isOk()) rebaseCurrentOnto(target.name());
                })
                .withAutoStash(com.jfx.git.core.AutoStash.Operation.CHECKOUT,
                        () -> gitService.checkoutBranch(source.name())));
        return item;
    }

    private MenuItem trackItem(String local, String remoteBranch) {
        MenuItem item = new MenuItem("Set " + remoteBranch + " as upstream of " + local);
        item.setOnAction(e -> run(() -> gitService.setUpstream(local, remoteBranch),
                "Setting the upstream of " + local));
        return item;
    }

    /**
     * Pushes the dragged branch to the remote the drop target belongs to, under its own
     * name. Push sends the branch that is checked out, so any other branch is checked
     * out first - and the item says so, rather than moving the working tree unannounced.
     */
    private MenuItem pushItem(Ref source, Ref target, String currentBranch) {
        // For the label: the remote the target belongs to, by the one rule every view
        // reads a remote branch by - the longest configured name, slashes and all.
        String shownRemote = com.jfx.git.model.RefNames.remoteOf(target.name(), remoteNames());
        boolean current = source.name().equals(currentBranch);
        MenuItem item = new MenuItem(current
                ? "Push " + source.name() + " to " + shownRemote
                : "Checkout " + source.name() + " and push it to " + shownRemote);
        Supplier<GitResult> push = () -> {
            String remote = remoteOf(target.name());
            if (remote == null) {
                return GitResult.failed("No configured remote has a branch called " + target.name());
            }
            if (!current) {
                GitResult checkedOut = gitService.checkoutBranch(source.name());
                if (!checkedOut.isOk()) return checkedOut;
            }
            return new RepositoryCommands(gitService).push(remote, false, false, false);
        };
        String description = "Pushing " + source.name() + " to " + shownRemote;
        item.setOnAction(e -> {
            if (current) {
                run(push, description);
            } else {
                withAutoStash(com.jfx.git.core.AutoStash.Operation.CHECKOUT, push, description);
            }
        });
        return item;
    }

    /**
     * The longest configured remote name {@code remoteBranch} starts with, or {@code null}
     * when no configured remote has it; see {@link com.jfx.git.model.RefNames#remoteOf}.
     * Asked on the repository lane, when the push runs.
     */
    private String remoteOf(String remoteBranch) {
        List<String> remotes = gitService.getRemoteNames();
        String remote = com.jfx.git.model.RefNames.remoteOf(remoteBranch, remotes);
        return remotes.contains(remote) ? remote : null;
    }

    /** The configured remotes as of the last refresh, for labels drawn on the FX thread. */
    private List<String> remoteNames() {
        return knownRemotes.get();
    }

    /**
     * Opens the interactive-rebase editor to replay the current branch's own commits onto
     * {@code upstream}, the same flow the graph's "Interactive rebase" uses.
     *
     * <p>Not {@code gitService.rebase(upstream)}: that is an instant, unreviewable rebase
     * where the user expects to see the plan first.
     */
    public void rebaseCurrentOnto(String upstream) {
        operations.read("Loading commits to rebase onto " + upstream,
                () -> gitService.getRebaseRange(upstream),
                range -> range.isUsable() ? null : range.problem(),
                range -> {
                    var dialog = new com.jfx.git.ui.dialogs.InteractiveRebaseDialog(
                            RebaseTodo.fromNewestFirst(range.commits(), range.base()));
                    dialog.showAndWait().ifPresent(plan ->
                            run(() -> gitService.rebaseInteractive(plan), "Rebasing " + plan.size() + " commits"));
                });
    }

    private void run(Supplier<GitResult> work, String description) {
        operations.run(description, OperationRunner.Refresh.EVERYTHING, work);
    }

    /**
     * Checks out {@code branch}, and only if that works merges with {@code merge} - with
     * stash and merge offered when local changes are in the way of either
     * ({@link com.jfx.git.core.AutoStash}). Asked as a merge, since that is what the item
     * said it would do: the stash is carried across the checkout and re-applied on top of
     * the merge.
     */
    private void mergeAfterCheckout(String branch, Supplier<GitResult> merge, String description) {
        withAutoStash(com.jfx.git.core.AutoStash.Operation.MERGE, () -> {
            GitResult result = gitService.checkoutBranch(branch);
            return result.isOk() ? merge.get() : result;
        }, description);
    }

    /** Runs {@code work}, offering auto-stash when local changes are in the way of it. */
    private void withAutoStash(com.jfx.git.core.AutoStash.Operation operation, Supplier<GitResult> work,
                               String description) {
        operations.operation(description, OperationRunner.Refresh.EVERYTHING).withAutoStash(operation, work);
    }
}
