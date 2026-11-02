package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitBackendFactory;
import com.jfx.git.core.GitBackendFactory.BackendEngine;
import com.jfx.git.core.GitResult;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.List;

/**
 * Bottom bar reporting the current action, the active branch, git engine, and any error.
 */
public class StatusBar extends HBox {

    /** One line of a status bar's height; anything taller is a panel, not a bar. */
    private static final double BAR_HEIGHT = 28;

    private final Label messageLabel = new Label();
    private final Label branchLabel = new Label();
    private final Label engineBadge = new Label();
    /**
     * "Conflicts with origin/main · 3 files": the early warning that merging the base
     * branch would conflict. Hidden while there is nothing to warn about.
     */
    private final Label conflictPill = new Label();
    /** What clicking the conflict pill offers; built when the warning is shown. */
    private java.util.function.Supplier<List<javafx.scene.control.MenuItem>> conflictMenu;
    /** When auto-fetch last ran for this repository; hidden until the first run. */
    private final Label lastFetchedLabel = new Label();
    /** The interface size, shown and changed here as commercial clients do. */
    private final Label zoomBadge = new Label();
    private final ProgressBar progressBar = new ProgressBar();
    /** Stops the operation the bar is showing; there once it has reported progress. */
    private final Button cancelButton = new Button("Cancel");
    /** "2 waiting": repository work queued behind what is running. */
    private final Label queueLabel = new Label();
    /** A quiet spinner for work nobody asked for: auto-fetch, a refresh after outside changes. */
    private final javafx.scene.control.ProgressIndicator backgroundSpinner =
            new javafx.scene.control.ProgressIndicator();
    private int backgroundDepth;
    /** Opens the activity log, for every message there is more to know about. */
    private final Button activityButton =
            Icons.button(Icons.history(), "Activity log", "Activity log (Ctrl+Shift+L)");
    /** The task whose progress the bar shows, claimed as it is submitted; see busy. */
    private com.jfx.git.core.GitTaskRunner.Task shownTask;
    /** What busy said, so progress can be appended to it rather than replace it. */
    private String busyMessage = "";
    /** Where errors, warnings and undo offers also appear; null until a tab provides one. */
    private ToastHost toasts;

    /** How every status bar opens the activity log; set once by the application. */
    private static Runnable activityLogOpener;

    public static void setActivityLogOpener(Runnable opener) {
        activityLogOpener = opener;
    }

    /** Gives the bar somewhere to raise notifications. */
    public void setToastHost(ToastHost host) {
        this.toasts = host;
    }

    /** Opens this repository's snapshot list; set by the tab, offered after a discard. */
    private Runnable snapshotsOpener;

    /** How a discard reported here leads to the snapshots kept before it. */
    public void setSnapshotsOpener(Runnable opener) {
        this.snapshotsOpener = opener;
    }

    /** The notification host, for tests; null when none was given. */
    ToastHost toastHost() {
        return toasts;
    }
    /** Opens the full text; shown only when there is more than the bar can display. */
    private final Button detailsButton = new Button("Details");
    /** Clears a message that does not clear itself. */
    private final Button dismissButton =
            Icons.button(Icons.close(), "Dismiss this message", "Dismiss this message");
    private PauseTransition clearTimer;

    /** How many operations have called {@link #busy} without their {@link #idle}. */
    private int busyDepth;

    /**
     * The untruncated message behind whatever the bar is showing.
     *
     * <p>Git's errors are routinely dozens of lines — "your local changes would be
     * overwritten by checkout" names every file. The bar shows the first line and keeps
     * the rest here for {@link #showDetails()}, because a status bar that grows to fit its
     * content stops being a status bar: it took most of the window and, having no dismiss,
     * could not be got rid of.
     */
    private String fullMessage = "";

    private GitBackend currentBackend;
    /** Asked to actually perform the switch; the bar only reports the outcome. */
    private java.util.function.Consumer<BackendEngine> engineSwitcher;

    /**
     * Which repository this bar's {@link com.jfx.git.core.ActivityLog} entries belong to.
     *
     * <p>Null until a repository is actually open, which is also when the bar exists but
     * has nothing to log about - an unnamed entry is better than none, so entries are
     * still recorded and simply show up under no repository.
     */
    private String loggedRepository;

    /**
     * Operations this bar has started and not yet closed, oldest first.
     *
     * <p>{@link #busy} and {@link #idle()} are a counted pair, not an identified one:
     * callers say "something started" and later "something finished" without naming
     * which. So the log closes them in the order they opened. Both worker lanes are
     * single-threaded, so work on either one genuinely does finish in start order; the
     * only way to mis-pair is for a repository operation and a network operation to
     * overlap and finish out of order, which swaps two durations in the log and loses
     * nothing else. The alternative - handing every one of the call sites a token to
     * give back - buys exactness in a display nobody consults for milliseconds.
     */
    private final java.util.ArrayDeque<com.jfx.git.core.ActivityLog.Entry> openEntries =
            new java.util.ArrayDeque<>();

    /**
     * The operation this bar most recently closed, until something judges it.
     *
     * <p>A call site reports an outcome in the line after {@link #idle()}, so the entry
     * has to survive that gap to be told whether it worked. Cleared once judged, so a
     * later unrelated message cannot re-label an operation that already has its verdict.
     */
    private com.jfx.git.core.ActivityLog.Entry lastFinished;

    /** Held so the weak listener on the static update property lives as long as this bar does. */
    private javafx.beans.value.ChangeListener<String[]> updateListener;

    /**
     * The newer version there is to tell about, as {@code {version, url}}, or null.
     *
     * <p>Static, because the answer belongs to the application and every tab has a
     * status bar of its own: a notice shown only in the tab that happened to be open
     * when the check came back would vanish on switching to another.
     */
    private static final javafx.beans.property.ObjectProperty<String[]> AVAILABLE_UPDATE =
            new javafx.beans.property.SimpleObjectProperty<>();

    /** Shows "Version X available" in every status bar, linking to {@code url} when there is one. */
    public static void announceUpdate(String version, String url) {
        AVAILABLE_UPDATE.set(version == null || version.isBlank()
                ? null : new String[]{version, url == null ? "" : url});
    }

    private final javafx.scene.control.Hyperlink updateLink = new javafx.scene.control.Hyperlink();

    public StatusBar() {
        super(10);
        setPadding(new Insets(4, 10, 4, 10));
        setAlignment(Pos.CENTER_LEFT);
        getStyleClass().add("status-bar");

        messageLabel.getStyleClass().add("status-message");
        // Never wrap and never grow: the bar's height is fixed below, and a message too
        // long for it is reached through Details rather than by pushing the app aside.
        messageLabel.setWrapText(false);
        messageLabel.setMaxHeight(BAR_HEIGHT);
        HBox.setHgrow(messageLabel, Priority.ALWAYS);
        messageLabel.setMaxWidth(Double.MAX_VALUE);

        // A fact, not a control: bare text rather than a pill. The bar used to render
        // the branch, the last fetch, the engine and the zoom as four identical pills
        // with an identical hover, so the two you can click and the two you cannot were
        // indistinguishable - and the ones you cannot still lit up under the pointer.
        branchLabel.getStyleClass().addAll("status-fact", "status-fact-branch");

        // Hidden until auto-fetch actually runs once: a repository with it turned off,
        // which is the default, has nothing true to say here yet.
        lastFetchedLabel.getStyleClass().add("status-fact");
        lastFetchedLabel.setVisible(false);
        lastFetchedLabel.setManaged(false);

        detailsButton.getStyleClass().addAll("btn-ghost", "status-details");
        detailsButton.setVisible(false);
        detailsButton.setManaged(false);
        detailsButton.setOnAction(e -> showDetails());

        dismissButton.getStyleClass().addAll("btn-sm", "status-dismiss");
        dismissButton.setVisible(false);
        dismissButton.setManaged(false);
        dismissButton.setOnAction(e -> clearMessage());

        // Quiet until there is something to say, and then a link rather than a dialog:
        // an update is news, not an interruption.
        updateLink.getStyleClass().add("status-update");
        updateLink.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        Runnable showUpdate = () -> {
            String[] update = AVAILABLE_UPDATE.get();
            updateLink.setText(update == null ? "" : "Version " + update[0] + " available");
            updateLink.setVisible(update != null);
            updateLink.setManaged(update != null);
        };
        // Weak: the property is static and outlives every tab's status bar.
        updateListener = (obs, old, update) -> showUpdate.run();
        AVAILABLE_UPDATE.addListener(new javafx.beans.value.WeakChangeListener<>(updateListener));
        updateLink.setOnAction(e -> {
            String[] update = AVAILABLE_UPDATE.get();
            if (update != null && !update[1].isBlank()) {
                report(com.jfx.git.core.UrlOpener.open(update[1]));
            }
        });
        showUpdate.run();

        // A status bar is one line tall, whatever it is asked to display.
        setMinHeight(BAR_HEIGHT);
        setPrefHeight(BAR_HEIGHT);
        setMaxHeight(BAR_HEIGHT);

        engineBadge.getStyleClass().addAll("pill", "status-control");
        engineBadge.setCursor(javafx.scene.Cursor.HAND);
        engineBadge.setOnMouseClicked(e -> showEngineMenu());

        conflictPill.getStyleClass().addAll("pill", "status-control", "status-conflict");
        conflictPill.setCursor(javafx.scene.Cursor.HAND);
        conflictPill.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        conflictPill.setOnMouseClicked(e -> showConflictMenu());
        conflictPill.setVisible(false);
        conflictPill.setManaged(false);

        progressBar.setPrefWidth(120);
        progressBar.setVisible(false);

        cancelButton.getStyleClass().addAll("btn-sm", "btn-ghost", "status-cancel");
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
        cancelButton.setOnAction(e -> cancelShownTask());

        queueLabel.getStyleClass().addAll("status-fact", "status-queue");
        queueLabel.setVisible(false);
        queueLabel.setManaged(false);

        backgroundSpinner.getStyleClass().add("status-background");
        backgroundSpinner.setPrefSize(14, 14);
        backgroundSpinner.setMaxSize(14, 14);
        backgroundSpinner.setVisible(false);
        backgroundSpinner.setManaged(false);

        activityButton.getStyleClass().addAll("btn-sm", "btn-ghost", "status-activity");
        activityButton.setOnAction(e -> {
            if (activityLogOpener != null) activityLogOpener.run();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // The zoom selector sits at the trailing end of the status bar, as in commercial
        // clients. Bound rather than set, because zoom belongs to the window: changing it
        // from a shortcut, or from another tab's selector, has to show up here too.
        zoomBadge.getStyleClass().addAll("pill", "status-control");
        zoomBadge.setCursor(javafx.scene.Cursor.HAND);
        zoomBadge.setTooltip(new javafx.scene.control.Tooltip(
                "Interface size — Ctrl+= larger, Ctrl+- smaller, Ctrl+0 to reset"));
        zoomBadge.textProperty().bind(UiZoom.percentProperty().asString("%d%%"));
        zoomBadge.setOnMouseClicked(e -> showZoomMenu());

        // Facts, a rule, then the things that open a menu. The rule is what makes the
        // grouping legible rather than merely present.
        Region stateDivider = new Region();
        stateDivider.getStyleClass().add("status-sep");

        getChildren().addAll(messageLabel, detailsButton, dismissButton, spacer,
                backgroundSpinner, updateLink, lastFetchedLabel, branchLabel, stateDivider,
                conflictPill, engineBadge, zoomBadge, queueLabel, progressBar, cancelButton, activityButton);
        updateEngineBadge();
    }

    /**
     * Records that auto-fetch just ran, showing when relative to now.
     *
     * <p>Relative rather than a clock time, matching how the graph already reports
     * commit ages elsewhere in this application - "3m ago" answers "is this stale"
     * without the reader doing the subtraction themselves.
     */
    public void reportAutoFetch(java.time.Instant when) {
        // Auto-fetch runs on a timer with no busy/idle pair around it, so it would
        // otherwise be the one thing that changes a repository without leaving a trace.
        com.jfx.git.core.ActivityLog.note(com.jfx.git.core.ActivityLog.Scope.REPOSITORY,
                loggedRepository, "Auto-fetch", com.jfx.git.core.ActivityLog.Outcome.OK);
        lastFetchedLabel.setVisible(true);
        lastFetchedLabel.setManaged(true);
        lastFetchedLabel.setText("Fetched " + DateDisplay.of(when, DateDisplay.Style.SHORT));
        lastFetchedLabel.setTooltip(new Tooltip("Last automatic fetch: " + DateDisplay.absolute(when)));
    }

    /** Offers the zoom levels, ticking the one in use. */
    private void showZoomMenu() {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        for (int level : UiZoom.LEVELS) {
            javafx.scene.control.RadioMenuItem item =
                    new javafx.scene.control.RadioMenuItem(level + "%");
            item.setSelected(level == UiZoom.percent());
            item.setOnAction(e -> UiZoom.set(level));
            menu.getItems().add(item);
        }
        menu.show(zoomBadge, javafx.geometry.Side.TOP, 0, 0);
    }

    /**
     * Points the badge at a backend and supplies the action that changes engines.
     *
     * @param engineSwitcher performs the switch and refreshes whatever the old engine fed;
     *                       the bar deliberately does not do this itself, because writing
     *                       the preference alone leaves the running engine untouched
     */
    public void setBackend(GitBackend backend, java.util.function.Consumer<BackendEngine> engineSwitcher) {
        this.currentBackend = backend;
        this.engineSwitcher = engineSwitcher;
        updateEngineBadge();
    }

    /** Repaints the badge from the engine that is actually running. */
    public void updateEngineBadge() {
        // Shown only when someone has chosen an engine. Left on automatic - which is how
        // nearly everyone runs it - the name of the library doing the work is not a fact
        // the status bar needs to carry on every screen; with an engine picked by hand it
        // is the reminder that explains why something behaves differently.
        boolean chosen = GitBackendFactory.getEnginePreference() != GitBackendFactory.BackendEngine.AUTO;
        engineBadge.setVisible(chosen);
        engineBadge.setManaged(chosen);
        if (currentBackend != null) {
            engineBadge.setText(GitBackendFactory.describeBackend(currentBackend));
            engineBadge.setTooltip(new Tooltip(GitBackendFactory.describeBackendDetail(currentBackend)));
        } else {
            engineBadge.setText(GitBackendFactory.getEnginePreference().label());
            engineBadge.setTooltip(new Tooltip("No repository open yet.\nClick to change engine."));
        }
    }

    private void showEngineMenu() {
        ContextMenu menu = new ContextMenu();
        BackendEngine currentPref = GitBackendFactory.getEnginePreference();
        ToggleGroup group = new ToggleGroup();

        for (BackendEngine engine : BackendEngine.values()) {
            boolean available = GitBackendFactory.isEngineAvailable(engine);
            String title = engine.label() + (available ? "" : " (Unavailable)");
            RadioMenuItem item = new RadioMenuItem(title);
            item.setToggleGroup(group);
            item.setSelected(engine == currentPref);
            item.setDisable(!available);
            item.setOnAction(e -> {
                GitBackendFactory.setEnginePreference(engine);
                if (engineSwitcher != null) {
                    // The switcher reports success or failure itself; announcing it here
                    // would claim the engine changed before anything had tried to.
                    engineSwitcher.accept(engine);
                } else {
                    updateEngineBadge();
                    info("Engine preference saved; it applies to repositories opened from now on.");
                }
            });
            menu.getItems().add(item);
        }

        menu.show(engineBadge, javafx.geometry.Side.TOP, 0, 0);
    }

    /**
     * Warns that merging {@code base} into the current branch would conflict in
     * {@code files}: a pill that lists them on hover and, clicked, offers what to do.
     *
     * @param actions builds the click menu - merge, rebase, settings - each time it opens
     */
    public void showConflictWarning(String base, List<String> files,
                                    java.util.function.Supplier<List<javafx.scene.control.MenuItem>> actions) {
        if (base == null || files == null || files.isEmpty()) {
            clearConflictWarning();
            return;
        }
        conflictPill.setText("Conflicts with " + base + " · " + files.size()
                + (files.size() == 1 ? " file" : " files"));
        StringBuilder tip = new StringBuilder("Merging ").append(base)
                .append(" into this branch would conflict in:");
        int shown = Math.min(files.size(), 15);
        for (int i = 0; i < shown; i++) {
            tip.append("\n  ").append(files.get(i));
        }
        if (files.size() > shown) tip.append("\n  and ").append(files.size() - shown).append(" more");
        tip.append("\n\nClick for ways to deal with it now.");
        conflictPill.setTooltip(new Tooltip(tip.toString()));
        conflictPill.setAccessibleText(conflictPill.getText());
        conflictMenu = actions;
        conflictPill.setVisible(true);
        conflictPill.setManaged(true);
    }

    /** Hides the conflict warning: nothing conflicts, or the check is off or cannot run. */
    public void clearConflictWarning() {
        conflictPill.setVisible(false);
        conflictPill.setManaged(false);
        conflictPill.setTooltip(null);
        conflictMenu = null;
    }

    /** The conflict pill's text while it shows, else null; for tests. */
    String conflictWarningText() {
        return conflictPill.isVisible() ? conflictPill.getText() : null;
    }

    /** The conflict pill, for tests that click it. */
    Label conflictPill() {
        return conflictPill;
    }

    private void showConflictMenu() {
        if (conflictMenu == null) return;
        ContextMenu menu = new ContextMenu();
        menu.getItems().setAll(conflictMenu.get());
        if (menu.getItems().isEmpty()) return;
        menu.show(conflictPill, javafx.geometry.Side.TOP, 0, 0);
    }

    /**
     * Names the repository whose activity this bar records.
     *
     * <p>Set when a repository opens, so the log can keep six open tabs apart instead of
     * interleaving them into one unreadable stream.
     */
    public void setLoggedRepository(String repository) {
        this.loggedRepository = repository;
    }

    /** Shows a neutral informational message that fades after a few seconds. */
    public void info(String message) {
        judgeLastOperation(com.jfx.git.core.ActivityLog.Outcome.OK, message);
        show(message, "status-info", true);
    }

    /** Shows an error; stays until replaced so it cannot be missed. */
    public void error(String message) {
        showError(message, failureSummary(message));
    }

    /**
     * Shows an error that followed a step which did work - "Committed locally, but the push
     * failed" - with {@code context} leading the one line shown and git's own reason after it.
     *
     * <p>{@link #error(String)} alone sums git's output up by its refusal line, which says
     * what failed but not what had already succeeded: after Commit and push, a bare "error:
     * failed to push some refs" read as though the commit had failed too.
     */
    public void errorAfter(String context, String message) {
        String reason = failureSummary(message);
        String full = message == null || message.isBlank() ? context
                : hasMoreThanOneLine(message) ? context + ":\n" + message
                : context + ": " + message.strip();
        showError(full, reason.isEmpty() ? context : context + ": " + reason);
    }

    private void showError(String message, String summary) {
        if (!judgeLastOperation(com.jfx.git.core.ActivityLog.Outcome.FAILED, message)) {
            // Not every failure follows an operation - a dialog that could not read a
            // file reports here too, and a log that dropped those would be missing
            // exactly the lines someone came looking for.
            logNote(message, com.jfx.git.core.ActivityLog.Outcome.FAILED);
        }
        presentError(message, summary);
    }

    private void presentError(String message, String summary) {
        show(message, "status-error", false, summary);
        toast(ToastHost.Kind.ERROR, message, null, summary);
    }

    public void warn(String message) {
        if (!judgeLastOperation(com.jfx.git.core.ActivityLog.Outcome.WARNING, message)) {
            logNote(message, com.jfx.git.core.ActivityLog.Outcome.WARNING);
        }
        presentWarning(message);
    }

    private void presentWarning(String message) {
        show(message, "status-warn", false);
        toast(ToastHost.Kind.WARNING, message, ToastHost.LONG);
    }

    /**
     * Says something was done, with the way back: "Deleted branch x · Undo". The undo is
     * offered for a while and then goes; the activity log keeps the record.
     */
    public void offerUndo(String message, Runnable undo) {
        info(message);
        if (toasts != null) {
            toasts.show(ToastHost.Kind.SUCCESS, message, ToastHost.LONG,
                    List.of(new ToastHost.Action("Undo", undo)));
        }
    }

    /**
     * Says work was thrown away, with the ways back: "Discarded a.txt · Undo ·
     * Snapshots". Undo puts back what the snapshot taken just before kept; it is left out
     * when there is no such snapshot.
     */
    public void offerDiscardRecovery(String message, Runnable undo, Runnable openSnapshots) {
        info(message);
        if (toasts == null) return;
        Runnable snapshots = openSnapshots != null ? openSnapshots : snapshotsOpener;
        List<ToastHost.Action> actions = new java.util.ArrayList<>();
        if (undo != null) actions.add(new ToastHost.Action("Undo", undo));
        if (snapshots != null) actions.add(new ToastHost.Action("Snapshots", snapshots));
        if (!actions.isEmpty()) toasts.show(ToastHost.Kind.SUCCESS, message, ToastHost.LONG, actions);
    }

    /**
     * Says something and offers what to do next - "Published feature/x · Create pull
     * request". Unlike {@link #offerUndo}, the notification stays until it is used or
     * dismissed: an offer that times out while the user was reading the push's output is
     * an offer they never got.
     *
     * @return the notification, or {@code null} when this bar has nowhere to show one
     */
    public javafx.scene.Node offer(ToastHost.Kind kind, String message, List<ToastHost.Action> actions) {
        switch (kind) {
            case ERROR -> show(message, "status-error", false);
            case WARNING -> show(message, "status-warn", false);
            default -> show(message, "status-info", true);
        }
        logNote(message, kind == ToastHost.Kind.ERROR ? com.jfx.git.core.ActivityLog.Outcome.FAILED
                : kind == ToastHost.Kind.WARNING ? com.jfx.git.core.ActivityLog.Outcome.WARNING
                : com.jfx.git.core.ActivityLog.Outcome.OK);
        return toasts == null ? null : toasts.show(kind, message, null, List.copyOf(actions));
    }

    /**
     * Raises a notification for a message worth more than one line in the bar: an error
     * until dismissed, a warning for a while, with Details for a long one and a way to the
     * activity log.
     */
    private void toast(ToastHost.Kind kind, String message, Duration autoHide) {
        toast(kind, message, autoHide, null);
    }

    private void toast(ToastHost.Kind kind, String message, Duration autoHide, String summary) {
        if (toasts == null || message == null || message.isBlank()) return;
        List<ToastHost.Action> actions = new java.util.ArrayList<>();
        if (hasMoreThanOneLine(message)) {
            String said = summary == null ? firstLine(message) : firstLine(summary);
            actions.add(new ToastHost.Action("Details", () -> showDetails(message, said)));
        }
        if (activityLogOpener != null) {
            actions.add(new ToastHost.Action("Activity log", activityLogOpener));
        }
        toasts.show(kind, summary == null ? firstLine(message) : firstLine(summary), autoHide, actions);
    }

    /**
     * Shows work nobody asked for - an automatic fetch, a reload after another program
     * changed the repository - as a quiet spinner rather than as a message. Counted, like
     * busy, because the two can overlap.
     */
    public void backgroundActivity(String what, boolean running) {
        backgroundDepth = Math.max(0, backgroundDepth + (running ? 1 : -1));
        boolean on = backgroundDepth > 0;
        backgroundSpinner.setVisible(on);
        backgroundSpinner.setManaged(on);
        if (running) {
            Tooltip.install(backgroundSpinner, new Tooltip(what + "…"));
        }
    }

    /** Whether background work is being shown, for tests. */
    boolean showingBackgroundActivity() {
        return backgroundSpinner.isVisible();
    }

    /** Reports a completed operation using the appropriate severity. */
    public void report(GitResult result) {
        switch (result.getStatus()) {
            case OK -> info(result.describe());
            case CONFLICT -> warn(result.describe());
            case FAILED -> error(result.describe());
        }
    }

    /**
     * Attaches a verdict to the operation this bar most recently finished.
     *
     * @return whether there was one to attach it to; false means the message stands
     *         alone and the caller decides whether it is worth logging by itself
     */
    private boolean judgeLastOperation(com.jfx.git.core.ActivityLog.Outcome outcome,
                                       String message) {
        if (lastFinished == null) return false;
        com.jfx.git.core.ActivityLog.reviseOutcome(lastFinished, outcome, message);
        lastFinished = null;
        return true;
    }

    private void logNote(String message, com.jfx.git.core.ActivityLog.Outcome outcome) {
        if (message == null || message.isBlank()) return;
        com.jfx.git.core.ActivityLog.note(
                com.jfx.git.core.ActivityLog.Scope.REPOSITORY, loggedRepository, message, outcome);
    }

    /** Marks the start of a long-running operation. */
    /**
     * Shows the progress bar and says what is happening.
     *
     * <h2>Why this counts</h2>
     * There are two worker lanes now - one for the repository, one for the network - and
     * both report here. A fetch and a stage overlap routinely, and with a plain
     * show/hide the first of the two to finish hid the bar while the other was still
     * working. A progress indicator that says "done" while work continues is worse than
     * none: it is the one thing an indicator must never do.
     *
     * <p>So the bar tracks depth, and hides when the last operation finishes. Every
     * {@code busy} needs its {@code idle}, which callers already pair.
     */
    public void busy(String message) {
        openEntries.addLast(raiseBusy(message));
        // The work about to be submitted is this message's: its progress shows here, and
        // Cancel reaches it.
        com.jfx.git.core.GitTaskRunner.claimNextTask(task -> follow(task, message));
    }

    /** Says {@code message} is under way, and opens its activity-log entry. */
    private com.jfx.git.core.ActivityLog.Entry raiseBusy(String message) {
        cancelTimer();
        busyDepth++;
        // Opened before the bar is even repainted, so the recorded duration covers the
        // work rather than the work minus however long the toolkit took to get to it.
        com.jfx.git.core.ActivityLog.Entry entry = com.jfx.git.core.ActivityLog.begin(
                com.jfx.git.core.ActivityLog.Scope.REPOSITORY, loggedRepository, message);
        progressBar.setVisible(true);
        busyMessage = message;
        setMessage(message + "…", "status-info");
        return entry;
    }

    /** Shows {@code task}'s progress under {@code message}, and points Cancel at it. */
    private void follow(com.jfx.git.core.GitTaskRunner.Task task, String message) {
        shownTask = task;
        task.onProgress(progress -> showProgress(task, message, progress));
    }

    /** A stage and a percentage on the bar, for the task it is showing. */
    private void showProgress(com.jfx.git.core.GitTaskRunner.Task task, String message,
                              com.jfx.git.core.GitTaskRunner.Progress progress) {
        if (task != shownTask || busyDepth == 0) return;
        boolean known = progress.fraction() >= 0;
        progressBar.setProgress(known ? progress.fraction() : ProgressBar.INDETERMINATE_PROGRESS);
        String stage = progress.stage() == null || progress.stage().isBlank() ? "" : progress.stage();
        String detail = stage.isEmpty() ? "" : " — " + stage
                + (known ? " " + Math.round(progress.fraction() * 100) + "%" : "");
        setMessage(message + "…" + detail, "status-info");
        // Something that reports progress is something long enough to want stopping.
        cancelButton.setVisible(true);
        cancelButton.setManaged(true);
    }

    private void cancelShownTask() {
        if (shownTask != null) {
            shownTask.cancel();
            setMessage("Cancelling…", "status-info");
        }
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
    }

    /**
     * Shows "N waiting" for {@code repository}'s work: the tab's own lane, so another
     * tab's queue - which this tab does not wait on - never shows here.
     */
    public void countWaitingOn(com.jfx.git.core.GitBackend repository) {
        // Both weakly: the runner is static and outlives every tab's bar, and it holds its
        // lanes only as long as something else holds their repository.
        java.lang.ref.WeakReference<StatusBar> self = new java.lang.ref.WeakReference<>(this);
        java.lang.ref.WeakReference<com.jfx.git.core.GitBackend> lane = new java.lang.ref.WeakReference<>(repository);
        java.util.function.IntConsumer[] listener = new java.util.function.IntConsumer[1];
        listener[0] = count -> {
            StatusBar bar = self.get();
            if (bar == null) {
                com.jfx.git.core.GitBackend owner = lane.get();
                if (owner != null) com.jfx.git.core.GitTaskRunner.removeQueueListener(owner, listener[0]);
            } else {
                bar.showQueue(count);
            }
        };
        com.jfx.git.core.GitTaskRunner.addQueueListener(repository, listener[0]);
    }

    private void showQueue(int waiting) {
        boolean show = waiting > 0 && busyDepth > 0;
        queueLabel.setText(waiting + " waiting");
        queueLabel.setVisible(show);
        queueLabel.setManaged(show);
    }

    /** The progress bar, for tests. */
    ProgressBar progressBar() {
        return progressBar;
    }

    /**
     * Reports one operation finished; the bar stays up while others are still running.
     */
    public void idle() {
        lowerBusy();
        closeEntry(openEntries.pollFirst());
    }

    private void lowerBusy() {
        // Never below zero: an unpaired idle - a failure path that reports twice, say -
        // must not leave the count negative, or the next real operation's bar never
        // appears. Clamping loses nothing, since zero already means "nothing running".
        busyDepth = Math.max(0, busyDepth - 1);
        if (busyDepth == 0) {
            progressBar.setVisible(false);
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            cancelButton.setVisible(false);
            cancelButton.setManaged(false);
            queueLabel.setVisible(false);
            queueLabel.setManaged(false);
            shownTask = null;
        }
    }

    private void closeEntry(com.jfx.git.core.ActivityLog.Entry closing) {
        // Finished, but not yet judged: the outcome arrives in whatever the caller
        // reports next. Left as OK if nothing does, which is what an operation that
        // finished quietly did.
        if (closing != null) {
            com.jfx.git.core.ActivityLog.finish(closing, com.jfx.git.core.ActivityLog.Outcome.OK, null);
            lastFinished = closing;
        }
    }

    /**
     * This bar as an {@link OperationRunner} sees it.
     *
     * <p>The runner's operations are paired by identity rather than counted: each one's
     * busy hands back its own entry, and its idle closes that entry and no other. Counted
     * pairs close first-in first-out, which mislabels the log whenever two operations
     * finish out of order - one that waited on a question (auto-stash) while another
     * started and finished behind it took the other's verdict. And each claims the task it
     * submits directly, rather than through {@link com.jfx.git.core.GitTaskRunner#claimNextTask}'s one
     * static slot, which two tabs saying busy in one pulse used to overwrite.
     */
    OperationRunner.Feedback operations() {
        return operations;
    }

    private final OperationRunner.Feedback operations = new OperationRunner.Feedback() {

        @Override
        public Object busy(String label) {
            return new Shown(raiseBusy(label), label);
        }

        @Override
        public void claim(Object operation, com.jfx.git.core.GitTaskRunner.Task task) {
            follow(task, ((Shown) operation).label());
        }

        @Override
        public void idle(Object operation) {
            lowerBusy();
            closeEntry(((Shown) operation).entry());
        }

        @Override
        public void report(GitResult result) {
            StatusBar.this.report(result);
        }

        @Override
        public void reportAfter(String context, GitResult result) {
            errorAfter(context, result.describe());
        }

        @Override
        public void show(GitResult result) {
            String message = result.describe();
            switch (result.getStatus()) {
                case OK -> StatusBar.this.show(message, "status-info", true);
                case CONFLICT -> presentWarning(message);
                case FAILED -> presentError(message, failureSummary(message));
            }
        }

        @Override
        public void settle(Object operation, GitResult result) {
            if (lastFinished != ((Shown) operation).entry()) return;
            judgeLastOperation(switch (result.getStatus()) {
                case OK -> com.jfx.git.core.ActivityLog.Outcome.OK;
                case CONFLICT -> com.jfx.git.core.ActivityLog.Outcome.WARNING;
                case FAILED -> com.jfx.git.core.ActivityLog.Outcome.FAILED;
            }, result.describe());
        }

        @Override
        public void cancelled(Object operation) {
            Shown shown = (Shown) operation;
            if (lastFinished == shown.entry()) {
                judgeLastOperation(com.jfx.git.core.ActivityLog.Outcome.WARNING, "Cancelled before it started");
            }
            StatusBar.this.show(shown.label() + " cancelled", "status-info", true);
        }
    };

    /** One of the runner's operations on this bar: its log entry and what busy said. */
    private record Shown(com.jfx.git.core.ActivityLog.Entry entry, String label) {
    }

    /** How many operations are in flight; the bar is visible while this is positive. */
    int busyDepth() {
        return busyDepth;
    }

    public void setBranch(String branch, int[] tracking) {
        if (branch == null || branch.isEmpty()) {
            branchLabel.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder("⎇ ").append(branch);
        if (tracking != null && (tracking[0] > 0 || tracking[1] > 0)) {
            sb.append("  ");
            if (tracking[0] > 0) sb.append("↑").append(tracking[0]).append(' ');
            if (tracking[1] > 0) sb.append("↓").append(tracking[1]);
        }
        branchLabel.setText(sb.toString().trim());
    }

    private void show(String message, String styleClass, boolean autoClear) {
        show(message, styleClass, autoClear, null);
    }

    private void show(String message, String styleClass, boolean autoClear, String summary) {
        cancelTimer();
        // Hidden only when nothing is running: a message about one finished operation is
        // not a reason to hide the bar of another still going.
        progressBar.setVisible(busyDepth > 0);
        setMessage(message, styleClass, summary);
        // A result arriving, not a running count: progress updates do not fade.
        Motion.fadeIn(messageLabel);

        // Anything that does not clear itself needs a way to be got rid of.
        dismissButton.setVisible(!autoClear);
        dismissButton.setManaged(!autoClear);

        if (autoClear) {
            clearTimer = new PauseTransition(Duration.seconds(6));
            clearTimer.setOnFinished(e -> clearMessage());
            clearTimer.play();
        }
    }

    private void setMessage(String message, String styleClass) {
        setMessage(message, styleClass, null);
    }

    private void setMessage(String message, String styleClass, String summary) {
        fullMessage = message == null ? "" : message;

        messageLabel.getStyleClass().removeAll("status-info", "status-error", "status-warn");
        messageLabel.getStyleClass().add(styleClass);
        messageLabel.setText(summary == null ? firstLine(fullMessage) : firstLine(summary));
        messageLabel.setTooltip(fullMessage.isBlank() ? null : new Tooltip(summaryTooltip(fullMessage)));

        boolean hasMore = hasMoreThanOneLine(fullMessage);
        detailsButton.setVisible(hasMore);
        detailsButton.setManaged(hasMore);
    }

    /** Clears the message and hides the controls that belong to it. */
    public void clearMessage() {
        cancelTimer();
        fullMessage = "";
        messageLabel.setText("");
        messageLabel.setTooltip(null);
        detailsButton.setVisible(false);
        detailsButton.setManaged(false);
        dismissButton.setVisible(false);
        dismissButton.setManaged(false);
    }

    /** The whole message, in something that can hold it and be closed. */
    private void showDetails() {
        showDetails(fullMessage, messageLabel.getText());
    }

    /** The whole of {@code fullMessage}, under the line that sums it up. */
    private void showDetails(String fullMessage) {
        showDetails(fullMessage, firstLine(failureSummary(fullMessage)));
    }

    private void showDetails(String fullMessage, String summary) {
        detailsDialog(fullMessage, summary).showAndWait();
    }

    /**
     * The Details window: the line the bar or the notification showed, as the title, and the
     * whole message under it as git wrote it - monospace, so its columns line up, and read
     * only - with Copy at the left of the footer for pasting it into an issue.
     */
    static javafx.scene.control.Alert detailsDialog(String fullMessage, String summary) {
        // Exactly as git wrote it, leading and trailing lines included: Details is the
        // record of what happened, and ManualQaFailureSummaryTest holds it to that.
        String text = fullMessage == null ? "" : fullMessage;
        javafx.scene.control.TextArea body = new javafx.scene.control.TextArea(text);
        body.setEditable(false);
        body.setWrapText(false);
        body.setPrefRowCount((int) Math.max(6, Math.min(20, text.lines().count() + 1)));
        body.getStyleClass().add("jfx-details");

        Button copy = new Button("Copy", Icons.copy());
        copy.getStyleClass().add("status-details-copy");
        copy.setTooltip(new Tooltip("Copy all of it to the clipboard"));
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(text);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            copy.setText("Copied");
        });

        long lines = text.lines().filter(line -> !line.isBlank()).count();
        return com.jfx.git.ui.dialogs.Dialogs.info("Details")
                .icon(Icons.terminal())
                .tone(com.jfx.git.ui.dialogs.JfxDialog.Tone.NEUTRAL)
                // git pads its ref lines into columns; a title is not a column.
                .title((summary == null || summary.isBlank() ? firstLine(text) : summary).replaceAll("\\s{2,}", " "))
                .subtitle(lines == 1 ? "The whole message" : "The whole message, " + lines + " lines")
                .extra(body)
                .footerLeft(copy)
                .size(com.jfx.git.ui.dialogs.JfxDialog.Size.MD)
                .build();
    }

    /** First non-blank line, trimmed to something a single-line bar can show. */
    private static String firstLine(String message) {
        if (message == null || message.isBlank()) return "";
        String line = message.lines()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse("");
        return line.length() > 200 ? line.substring(0, 197) + "…" : line;
    }

    /**
     * Pull and push output often starts with transport progress. Prefer Git's specific
     * refusal line for the one-line error, while leaving the original output in Details.
     */
    static String failureSummary(String message) {
        if (message == null || message.isBlank()) return "";
        List<String> lines = message.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();

        // `git push` follows the rejected ref with a generic "failed to push some refs";
        // the ref line is the useful cause, especially for a stale lease.
        for (String line : lines) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("[rejected]")
                    && (lower.contains("stale info") || lower.contains("stale lease"))) {
                return line;
            }
        }
        for (String line : lines) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("not possible to fast-forward")) return line;
        }
        // "[remote rejected]" is a refusal on the remote's side - a hook, a lock, a protected
        // branch - with the reason in brackets; it comes before the generic error line too.
        for (String line : lines) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("[rejected]") || lower.contains("[remote rejected]")
                    || lower.startsWith("fatal:") || lower.startsWith("error:")) {
                return line;
            }
        }
        return lines.isEmpty() ? "" : lines.getFirst();
    }

    private static boolean hasMoreThanOneLine(String message) {
        if (message == null || message.isBlank()) return false;
        return message.lines().filter(s -> !s.isBlank()).count() > 1 || message.length() > 200;
    }

    /** A few lines for the hover, so Details is not the only way to see any of it. */
    private static String summaryTooltip(String message) {
        List<String> lines = message.lines().filter(s -> !s.isBlank()).limit(12).toList();
        String text = String.join("\n", lines);
        return message.lines().filter(s -> !s.isBlank()).count() > 12 ? text + "\n…" : text;
    }

    private void cancelTimer() {
        if (clearTimer != null) {
            clearTimer.stop();
            clearTimer = null;
        }
    }
}
