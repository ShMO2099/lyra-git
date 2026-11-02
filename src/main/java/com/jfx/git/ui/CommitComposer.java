package com.jfx.git.ui;

import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.BranchIssueKey;
import com.jfx.git.core.CommitMessages;
import com.jfx.git.core.RepositoryPreferences;
import com.jfx.git.model.CommitTrailers;
import com.jfx.git.model.MessageParts;
import com.jfx.git.model.OperationState;
import com.jfx.git.model.StagingState;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The commit composer: the commit box apart from its controls. It holds the message being
 * written and the options that go with it, fills in what the box fills in by itself, says
 * what the Commit button reads and whether it can be pressed, and turns a press into a
 * {@link CommitRequest}.
 *
 * <h2>Why a model of its own</h2>
 * The commit box lived in the staging panel's field namespace, among the lists, the
 * banners and the stash box. "Why is Commit disabled, and why does it say Resolve 2
 * conflicts first?" meant reading staging state, operation state, Amend and three settings
 * across two thousand lines, and the only tests that could check the answer drove a whole
 * repository tab. Here the inputs come in through a handful of calls, the decisions come
 * out as values, and every rule can be checked without a window.
 *
 * <h2>What it is told</h2>
 * The staging answer each refresh brings ({@link #stagingRead}), the stopped operation the
 * banner read ({@link #operationRead}), the commit an amend would replace
 * ({@link #amendTargetRead}), what the user types ({@link #edited}) and ticks, and when the
 * Commit settings change. The live staged count is passed to {@link #button} and
 * {@link #request(boolean, int)} because it is the lists' count of the moment: a stage click
 * moves a row before git has answered.
 *
 * <h2>How long the options last</h2>
 * <ul>
 *   <li><b>Amend</b> survives the box being rebuilt - staging one more file is not the user
 *       changing their mind - and ends with the commit, or when the box leaves the screen.</li>
 *   <li><b>Skip hooks</b> is for the commit being written: it starts from the repository's
 *       default, and goes back to it after every commit, for a box built anew and on another
 *       branch - and when the default itself changes. A refresh of the same box keeps what
 *       the user chose. Putting the default back there ticked it again behind a user who had
 *       unticked it to run the hooks once - after a commit the hooks refused, or a save - and
 *       the next commit skipped them without a word.</li>
 *   <li><b>Sign off</b> lasts as long as the composer, which is the tab: a project that asks
 *       for a DCO sign-off asks for it on every commit.</li>
 * </ul>
 * Off screen there is no box to tick, so a shortcut that commits from elsewhere does not
 * amend, and skips hooks exactly when the repository says to.
 *
 * <h2>What it fills in by itself</h2>
 * The branch's issue key, the message git prepared for a stopped merge, cherry-pick or
 * revert (or a squash) - or, when there is none, as {@code git commit} decides, the
 * {@code commit.template} - and HEAD's message when Amend is ticked. None of them over text
 * the user typed: a box holding nothing, or only the issue key it put there itself, is empty
 * to all of them, and the message git prepared also replaces a template or an earlier
 * prepared message the box put in. Nothing it filled in is kept as a draft, and a prepared
 * message still untouched when its operation ends goes with it.
 *
 * <p>Nothing here touches JavaFX, git or the disk: the controls are a {@link Screen}, the
 * repository's slow answers come through a {@link Reader}, the remembered messages through a
 * {@link Memory} and the settings through {@link Preferences}.
 */
public final class CommitComposer {

    /** What the Commit button reads, and whether it can be pressed. */
    public record CommitButton(String label, boolean enabled) {
    }

    /**
     * The line under the options while Amend is ticked: which commit it replaces, and a
     * warning when that commit is already published.
     *
     * @param shown     whether the line is on screen at all - only while amending
     * @param published whether {@code text} is the warning
     */
    public record AmendLine(boolean shown, String text, boolean published) {
    }

    /**
     * One press of the Commit button, as the box was when it was pressed.
     *
     * @param push          push the commit afterwards
     * @param stageAllFirst stage everything that is not a conflict before committing
     */
    public record CommitRequest(String message, boolean amend, boolean skipHooks, boolean signOff,
                                boolean push, boolean stageAllFirst) {

        /**
         * The message signed off by {@code identity}: {@code git commit --signoff}'s trailer.
         * Resolved when committing rather than typed into the description, so it names whoever
         * actually commits - a profile switch included - and is never there twice.
         */
        public String signedOffBy(String identity) {
            return CommitTrailers.append(message, "Signed-off-by", identity);
        }
    }

    /** The settings the box follows. Read each time they matter, so a change applies at once. */
    public interface Preferences {
        /** Whether every commit is followed by a push. */
        boolean pushAfterCommit();

        /** Whether an empty summary starts with the branch's issue key. */
        boolean issueKeyFromBranch();

        /** The expression that finds the key in a branch name; see {@link BranchIssueKey}. */
        String issueKeyPattern();

        /** The prefix's shape, with {@link BranchIssueKey#KEY_TOKEN} where the key goes. */
        String issueKeyFormat();

        /** Whether this repository starts the box with Skip hooks ticked. */
        boolean skipHooksByDefault();

        /** The Commit page's settings, and the repository's own for Skip hooks. */
        static Preferences of(Supplier<File> workTree) {
            return new Preferences() {
                @Override public boolean pushAfterCommit() { return AppPreferences.pushAfterCommit(); }
                @Override public boolean issueKeyFromBranch() { return AppPreferences.issueKeyFromBranch(); }
                @Override public String issueKeyPattern() { return AppPreferences.issueKeyPattern(); }
                @Override public String issueKeyFormat() { return AppPreferences.issueKeyFormat(); }
                @Override public boolean skipHooksByDefault() {
                    return RepositoryPreferences.skipHooksByDefault(workTree.get());
                }
            };
        }
    }

    /** The messages committed in this repository, and the one left unsent. */
    public interface Memory {
        /** Newest first. */
        List<String> history();

        /** The unsent message, or {@code ""}. */
        String draft();

        /** Keeps {@code text} as the unsent message; blank forgets it. */
        void saveDraft(String text);

        /** Records a committed message; the draft goes with it. */
        void remember(String message);

        /** {@link CommitMessages}, for whichever repository {@code repository} names. */
        static Memory of(Supplier<File> repository) {
            return new Memory() {
                @Override public List<String> history() { return CommitMessages.history(repository.get()); }
                @Override public String draft() { return CommitMessages.draft(repository.get()); }
                @Override public void saveDraft(String text) { CommitMessages.saveDraft(repository.get(), text); }
                @Override public void remember(String message) { CommitMessages.remember(repository.get(), message); }
            };
        }
    }

    /**
     * The repository's answers the box waits for. Each is read away from the thread that
     * draws and handed back on it, later; a read that fails is never answered.
     */
    public interface Reader {
        /** {@code commit.template}'s text, or blank. */
        void commitTemplate(Consumer<String> then);

        /** The message git prepared: SQUASH_MSG when {@code squash}, MERGE_MSG otherwise; blank when none. */
        void preparedMessage(boolean squash, Consumer<String> then);

        /** HEAD's full message. */
        void headMessage(Consumer<String> then);
    }

    /**
     * The box's controls while it is on screen. Each call puts in what the composer decided;
     * text the controls report back through {@link #edited} as though typed is expected.
     */
    public interface Screen {
        void showSummary(String text);

        void showDescription(String text);

        void caretToSummaryEnd();

        void showAmend(boolean amend);

        void showSkipHooks(boolean skip);
    }

    /** What the button says when git would refuse the commit for conflicts. */
    public static String resolveConflictsFirst(int conflicts) {
        return "Resolve " + conflicts + (conflicts == 1 ? " conflict" : " conflicts") + " first";
    }

    /** What a press with no message to commit says instead. */
    public static final String MESSAGE_REQUIRED = "A commit message is required";

    /** What the box last filled in by itself, which decides what may replace it. */
    private enum Filled { NOTHING, TEMPLATE, HEAD_MESSAGE, PREPARED }

    private final Preferences preferences;
    private final Memory memory;
    private final Reader reader;
    /** The controls, while the box is on screen; null otherwise. */
    private Screen screen;

    private String summary = "";
    private String description = "";
    private boolean amend;
    private boolean skipHooks;
    /** The repository's Skip hooks default as last read, so a change to it applies at the next read. */
    private boolean skipHooksDefault;
    private boolean signOff;

    /** The last staging answer; null until the first. */
    private StagingState staging;
    /** What the last accepted operation read found stopped. */
    private OperationState.Kind operation = OperationState.Kind.NONE;

    /** HEAD as read for the box on screen; null until that read lands. */
    private AmendTarget amendTarget;
    /**
     * Whether HEAD is published, as last read. Unlike {@link #amendTarget} it outlives a
     * rebuild: the button says whether an amend will be pushed, and it would rather say
     * what the last read found than nothing until the next one lands.
     */
    private boolean amendTargetPublished;

    /** Whether the draft left by an earlier session has been looked for. Once is enough. */
    private boolean draftRestored;

    /**
     * The issue-key prefix this box last put in the summary, or {@code ""}. A summary holding
     * exactly this is still empty to everything else that fills the box, and is the one text a
     * branch change may replace.
     */
    private String issuePrefix = "";
    /**
     * The branch {@link #issuePrefix} was worked out for; null for a box just put on screen.
     * Not set while amending or mid-operation, when there is no key to work out: the branch
     * is tried again once there is.
     */
    private String issueKeyBranch;

    /**
     * The message the box last filled in by itself - a template, HEAD's message for an amend,
     * a prepared merge message. Not a draft: nobody wrote it, and bringing it back in a later
     * session would bring back a merge message long after the merge.
     */
    private String autoFilledMessage = "";
    /** Where {@link #autoFilledMessage} came from. */
    private Filled filled = Filled.NOTHING;
    /** The prepared message last put in, so one the user cleared is not put back. */
    private String lastPreparedMessage;
    /**
     * Whether this commit has been offered {@code commit.template}. Reset by a commit, so a
     * template the user cleared on purpose does not come back with every refresh.
     */
    private boolean templateOffered;

    /** Which remembered message is in the box: -1 for the user's own text. */
    private int historyIndex = -1;
    /**
     * The user's own text, kept while they look through the history: the two parts exactly as
     * they were, not joined, so the key alone comes back as the key the box put there.
     */
    private String stashedSummary = "";
    private String stashedDescription = "";
    /** True while the history puts text in, so that does not count as typing. */
    private boolean recallingHistory;

    private record AmendTarget(String message, String publishedTo) {
    }

    public CommitComposer(Preferences preferences, Memory memory, Reader reader) {
        this.preferences = preferences;
        this.memory = memory;
        this.reader = reader;
    }

    // ------------------------------------------------------------------
    // The message
    // ------------------------------------------------------------------

    public String summary() {
        return summary;
    }

    public String description() {
        return description;
    }

    /** The message git receives: the summary and description joined by the blank line. */
    public String message() {
        return MessageParts.join(summary, description);
    }

    /**
     * The controls' text after it changed - typed, pasted, or put there by this composer.
     * Kept as the draft unless it is only what the box filled in by itself; typing over a
     * recalled message makes it the user's own text again.
     */
    public void edited(String summary, String description) {
        this.summary = summary == null ? "" : summary;
        this.description = description == null ? "" : description;
        String message = message();
        boolean onlyFilledIn = message.equals(autoFilledMessage) || onlyIssueKey();
        memory.saveDraft(onlyFilledIn ? "" : message);
        if (!recallingHistory) historyIndex = -1;
    }

    /**
     * Brings back the message left unsent when this repository was last open, the first time
     * a box is built. Not in the constructor: the panel can be made before its repository is.
     */
    public void restoreDraft() {
        if (draftRestored) return;
        draftRestored = true;
        if (!message().isEmpty()) return;
        var parts = MessageParts.split(memory.draft());
        summary = parts.summary();
        description = parts.description();
    }

    // ------------------------------------------------------------------
    // The options
    // ------------------------------------------------------------------

    public boolean amend() {
        return amend;
    }

    /**
     * Amend ticked or unticked. Ticking it over an empty box fills in the message of the
     * commit it replaces; unticking gives an emptied summary its issue key back.
     */
    public void setAmend(boolean amend) {
        if (this.amend == amend) return;
        this.amend = amend;
        if (!amend) {
            applyIssueKey();
            return;
        }
        if (!isEmpty()) return;
        AmendTarget known = amendTarget;
        if (known != null) {
            fillFromHead(known.message());
        } else {
            // The box's own read has not landed yet; ask rather than wait for it.
            reader.headMessage(head -> {
                if (this.amend) fillFromHead(head);
            });
        }
    }

    public boolean skipHooks() {
        return skipHooks;
    }

    public void setSkipHooks(boolean skip) {
        skipHooks = skip;
    }

    public boolean signOff() {
        return signOff;
    }

    public void setSignOff(boolean signOff) {
        this.signOff = signOff;
    }

    // ------------------------------------------------------------------
    // What the box is told
    // ------------------------------------------------------------------

    /**
     * A staging answer arrived, for a box being built or one already on screen. Skip hooks
     * starts again from the default for a new box and for another branch, and follows a
     * changed default; a refresh of the same box keeps what the user chose. A stopped
     * operation that has ended takes the message git prepared for it out of the box, unless
     * the user has made it theirs.
     */
    public void stagingRead(StagingState state) {
        StagingState before = staging;
        staging = state;
        // Committed from the banner, aborted, or finished in a terminal: the merge message
        // describes a merge that is no longer there, and the next commit is not it.
        if (before != null && before.midOperation() && !state.midOperation() && holdsOnly(Filled.PREPARED)) {
            filled = Filled.NOTHING;
            autoFilledMessage = "";
            show("", "");
        }
        boolean byDefault = preferences.skipHooksByDefault();
        boolean anotherCommit = screen == null || before == null || !branchOf(before).equals(branchOf(state));
        if (anotherCommit || byDefault != skipHooksDefault) showSkipHooks(byDefault);
        skipHooksDefault = byDefault;
    }

    /** The banner's latest accepted read of what is stopped. */
    public void operationRead(OperationState state) {
        operation = state == null ? OperationState.Kind.NONE : state.kind();
    }

    /**
     * The commit an amend would replace, read for the box on screen.
     *
     * @param publishedTo where HEAD is already published, or null when only this clone has it
     */
    public void amendTargetRead(String message, String publishedTo) {
        amendTarget = new AmendTarget(message == null ? "" : message, publishedTo);
        amendTargetPublished = publishedTo != null;
    }

    /** A Commit setting changed: an empty summary follows the issue-key settings at once. */
    public void settingsChanged() {
        if (staging != null) applyIssueKey();
    }

    /**
     * New controls are on screen, showing {@link #summary()}, {@link #description()} and the
     * options. HEAD is unknown to them until its read lands.
     */
    public void attach(Screen screen) {
        this.screen = screen;
        amendTarget = null;
        issueKeyBranch = null;
    }

    /**
     * The box left the screen with the rest of the staging panel. Amend ends with it; a
     * commit from a shortcut meanwhile does not amend, and skips hooks only when the
     * repository says to. The next box starts Skip hooks from that default.
     */
    public void detach() {
        screen = null;
        amend = false;
    }

    /**
     * Puts in what the box fills in by itself, over an empty box only: the issue key of a
     * branch it has not been worked out for (any branch, for a box just put on screen), then
     * the message git prepared, or the commit template when there is none. Called when the
     * box is built and whenever a refresh finds something changed.
     */
    public void fillIn() {
        if (screen == null) return;
        // A checkout elsewhere - the toolbar, the sidebar, a terminal - lands here.
        if (!branch().equals(issueKeyBranch)) applyIssueKey();
        // As git commit opens its editor: with MERGE_MSG or SQUASH_MSG when there is one,
        // and with the template only otherwise. Offered the other way round, the template
        // filled the box first and a merge's own message never got in.
        if (!offerPreparedMessage(this::offerTemplate)) offerTemplate();
    }

    // ------------------------------------------------------------------
    // What the box shows
    // ------------------------------------------------------------------

    /**
     * What the Commit button says and whether it can be pressed, with {@code stagedCount}
     * files staged. It names what committing will do and where it lands, and never waits on
     * git: every answer comes from the staging read that built the panel.
     */
    public CommitButton button(int stagedCount) {
        String branch = branch();
        // Detached, there is no branch for the commit to land on, nothing to name and
        // nothing to push.
        String where = branch.isBlank() ? "" : " to " + branch;
        boolean pushing = preferences.pushAfterCommit() && !branch.isBlank();
        // Git refuses a commit while any file is still conflicted, so the button says what
        // is left rather than "Commit 1 file to main", enabled, ahead of an error.
        int conflicts = staging == null ? 0 : staging.conflicting().size();
        if (conflicts > 0) {
            return new CommitButton(resolveConflictsFirst(conflicts), false);
        }
        if (amend) {
            String refused = amendRefused();
            if (refused != null) return new CommitButton(refused, false);
            // An amend of a published commit is committed but not pushed: pushing it needs a
            // force, which is never done on the user's behalf.
            return new CommitButton((pushing && !amendTargetPublished ? "Amend and push" : "Amend commit") + where,
                    true);
        }
        // A merge resolved by taking one side verbatim stages nothing, but the merge still
        // needs this commit to finish it. The staging answer says only that something is in
        // progress; the box finishes only a merge - rebases, cherry-picks and reverts have
        // their banner's actions - so the operation read decides. With files staged it is
        // still the merge being committed, so it says so.
        if (staging != null && staging.midOperation() && operation == OperationState.Kind.MERGING) {
            return new CommitButton((pushing ? "Commit merge and push" : "Commit merge") + where, true);
        }
        if (stagedCount == 0) {
            return new CommitButton("Nothing staged", false);
        }
        return new CommitButton(pushing
                ? "Commit and push" + where
                : "Commit " + stagedCount + (stagedCount == 1 ? " file" : " files") + where, true);
    }

    /**
     * Why git would refuse {@code commit --amend} now, or null. It refuses in the middle of a
     * merge or a cherry-pick: the commit to make is that operation's, not a new HEAD.
     */
    private String amendRefused() {
        if (staging == null || !staging.midOperation()) return null;
        return switch (operation) {
            case MERGING -> "Cannot amend during a merge";
            case CHERRY_PICKING -> "Cannot amend during a cherry-pick";
            default -> null;
        };
    }

    /** The line naming the commit an amend replaces; see {@link AmendLine}. */
    public AmendLine amendLine() {
        AmendTarget target = amendTarget;
        if (target == null || target.message().isBlank()) {
            return new AmendLine(amend, "", false);
        }
        boolean warn = amend && target.publishedTo() != null;
        return new AmendLine(amend, warn
                ? "Already on " + target.publishedTo() + " - amending rewrites a published commit: "
                  + firstLineOf(target.message())
                : "Amending: " + firstLineOf(target.message()), warn);
    }

    // ------------------------------------------------------------------
    // Committing
    // ------------------------------------------------------------------

    /**
     * What a press commits, with {@code stagedCount} files staged: the message and options of
     * the moment, pushed afterwards when {@code andPush} or the setting says so and there is a
     * branch to push. Empty when the press would commit nothing - the button would refuse it,
     * or there is no message; {@link #refusal} says which.
     *
     * <p>Ctrl+Enter, the window's shortcut and the palette reach this without the button, so
     * the button's own refusals are made here too: they used to commit whatever the button
     * said.
     */
    public Optional<CommitRequest> request(boolean andPush, int stagedCount) {
        if (refusal(stagedCount) != null) return Optional.empty();
        return Optional.of(build(andPush || preferences.pushAfterCommit(), false));
    }

    /** {@link #request(boolean, int)} with the files the last staging read found staged: for a press with no lists on screen. */
    public Optional<CommitRequest> request(boolean andPush) {
        return request(andPush, stagedRead());
    }

    /** {@link #request} for Stage all and commit: everything but the conflicts staged first. */
    public Optional<CommitRequest> requestStagingAll() {
        if (refusalStagingAll() != null) return Optional.empty();
        return Optional.of(build(preferences.pushAfterCommit(), true));
    }

    /**
     * Why a press with {@code stagedCount} files staged commits nothing - the disabled
     * button's own words, or {@link #MESSAGE_REQUIRED} - or null when it commits.
     */
    public String refusal(int stagedCount) {
        CommitButton button = button(stagedCount);
        if (!button.enabled()) return button.label();
        return hasMessage() ? null : MESSAGE_REQUIRED;
    }

    /** {@link #refusal} for Stage all and commit: the button as it would read once all of it is staged. */
    public String refusalStagingAll() {
        StagingState state = staging;
        int plainUnstaged = state == null ? 0
                : (int) state.unstaged().stream().filter(path -> !state.conflicting().contains(path)).count();
        return refusal(stagedRead() + plainUnstaged);
    }

    private CommitRequest build(boolean push, boolean stageAllFirst) {
        // Off screen there is no box to say otherwise, so the repository's default.
        boolean skip = screen == null ? preferences.skipHooksByDefault() : skipHooks;
        return new CommitRequest(message(), amend, skip, signOff, push && !branch().isBlank(), stageAllFirst);
    }

    /** What the last staging read found staged. */
    private int stagedRead() {
        return staging == null ? 0 : staging.staged().size();
    }

    /**
     * Whether there is a message to commit. Blank is none, and so is the issue key alone -
     * committed, git got "ABC-123:" and nothing else.
     */
    private boolean hasMessage() {
        return !message().isBlank() && !onlyIssueKey();
    }

    /**
     * {@code request} landed: the message joins the history and the box starts the next
     * commit - empty, not amending, Skip hooks back to the default, and offered the template
     * and the issue key again.
     */
    public void committed(CommitRequest request) {
        memory.remember(request.message());
        historyIndex = -1;
        show("", "");
        amend = false;
        if (screen != null) screen.showAmend(false);
        skipHooksDefault = preferences.skipHooksByDefault();
        showSkipHooks(skipHooksDefault);
        templateOffered = false;
        issuePrefix = "";
        if (staging != null) applyIssueKey();
    }

    // ------------------------------------------------------------------
    // Co-authors and the message history
    // ------------------------------------------------------------------

    /**
     * Credits {@code person} with a {@code Co-authored-by:} trailer in the description: joined
     * to a trailer block it already ends with, and not added twice. In the text, where the user
     * can see and edit it, because who gets credit is part of the message.
     */
    public void addCoAuthor(String person) {
        String body = CommitTrailers.appendToBody(description, "Co-authored-by", person);
        description = body;
        if (screen != null) screen.showDescription(body);
    }

    /** The messages committed here, newest first. */
    public List<String> history() {
        return memory.history();
    }

    /**
     * Steps through the messages committed here: {@code +1} older, {@code -1} newer. Past the
     * newest is what the user had typed before they started, so looking is never destructive.
     *
     * @return false when there is nothing to recall yet
     */
    public boolean stepHistory(int step) {
        List<String> history = memory.history();
        if (history.isEmpty()) return false;
        recallHistory(Math.max(-1, Math.min(history.size() - 1, historyIndex + step)), history);
        return true;
    }

    /** Puts {@code history}'s entry {@code index} - or the user's own text, for -1 - in the box. */
    public void recallHistory(int index, List<String> history) {
        if (screen == null || index == historyIndex) return;
        if (historyIndex == -1) {
            stashedSummary = summary;
            stashedDescription = description;
        }
        historyIndex = index;
        recallingHistory = true;
        try {
            if (index == -1) {
                show(stashedSummary, stashedDescription);
            } else {
                var parts = MessageParts.split(history.get(index));
                show(parts.summary(), parts.description());
            }
        } finally {
            recallingHistory = false;
        }
        screen.caretToSummaryEnd();
    }

    // ------------------------------------------------------------------
    // What the box fills in by itself
    // ------------------------------------------------------------------

    /** Whether the box holds nothing the user typed: blank, or only the issue key. */
    private boolean isEmpty() {
        return (summary.isBlank() && description.isBlank()) || onlyIssueKey();
    }

    /** Whether the message is the issue key this box put in and nothing else, the space after it or not. */
    private boolean onlyIssueKey() {
        return !issuePrefix.isBlank() && message().equals(issuePrefix.strip());
    }

    /** Whether the box still holds exactly what it filled in from {@code kind}. */
    private boolean holdsOnly(Filled kind) {
        return filled == kind && message().equals(autoFilledMessage);
    }

    /**
     * Whether the message git prepared may go in: over an empty box, or over a template or an
     * earlier prepared message the box put in itself - git's own commit would open with this
     * one. Never while amending: the message is then the amended commit's.
     */
    private boolean preparedMayReplace() {
        return isEmpty() || (!amend && (holdsOnly(Filled.TEMPLATE) || holdsOnly(Filled.PREPARED)));
    }

    /** Puts a message the user did not type into the box, so it is not kept as a draft. */
    private void fill(Filled kind, String summary, String description) {
        autoFilledMessage = MessageParts.join(summary, description);
        filled = kind;
        show(summary, description);
    }

    private void fillFromHead(String headMessage) {
        if (!isEmpty()) return;
        var parts = MessageParts.split(headMessage);
        fill(Filled.HEAD_MESSAGE, parts.summary(), parts.description());
    }

    /** The text, and the controls when they are on screen. */
    private void show(String summary, String description) {
        this.summary = summary;
        this.description = description;
        if (screen != null) {
            screen.showSummary(summary);
            screen.showDescription(description);
        }
    }

    private void showSkipHooks(boolean skip) {
        skipHooks = skip;
        if (screen != null) screen.showSkipHooks(skip);
    }

    /** The branch a commit would land on; {@code ""} when HEAD is detached or not read yet. */
    private String branch() {
        return branchOf(staging);
    }

    private static String branchOf(StagingState state) {
        String branch = state == null ? null : state.branch();
        return branch == null ? "" : branch;
    }

    /**
     * Starts an empty summary with the issue key the branch is named after. Never over typed
     * text, never while amending (the message is the amended commit's), and never mid-merge
     * (git prepared that message) - and those two leave the branch to be tried again, so the
     * key comes back once the amend or the merge is over. A summary holding only an earlier
     * key is replaced, or emptied when this branch has none.
     */
    private void applyIssueKey() {
        if (screen == null) return;
        if (amend) return;
        if (staging != null && staging.midOperation()) return;
        String branch = branch();
        issueKeyBranch = branch;
        if (!summary.isEmpty() && !summary.equals(issuePrefix)) return;
        String prefix = preferences.issueKeyFromBranch()
                ? BranchIssueKey.prefixFor(branch, preferences.issueKeyPattern(), preferences.issueKeyFormat())
                : "";
        issuePrefix = prefix;
        if (!summary.equals(prefix)) {
            summary = prefix;
            screen.showSummary(prefix);
            screen.caretToSummaryEnd();
        }
    }

    /**
     * Fills an empty box from {@code commit.template}, as {@code git commit} fills its
     * editor, once per commit. The issue key still leads when the template does not start
     * with it.
     */
    private void offerTemplate() {
        if (templateOffered || !isEmpty()) return;
        templateOffered = true;
        reader.commitTemplate(template -> {
            if (template == null || template.isBlank() || !isEmpty()) return;
            var parts = MessageParts.split(withoutCommentLines(template));
            String subject = parts.summary().startsWith(issuePrefix) ? parts.summary()
                    : issuePrefix + parts.summary();
            fill(Filled.TEMPLATE, subject, parts.description());
        });
    }

    /**
     * A stopped merge, cherry-pick or revert is finished by a commit that is still that
     * operation's: git prepared its message in MERGE_MSG - the picked commit's own text,
     * "Revert ..." with the hash, "Merge branch ...". Offered once per prepared message, and
     * never over text already typed, so clearing it sticks.
     *
     * <p>A squash merge is the same idea without the in-progress marker: it stops with the
     * branch's changes staged (or conflicting) and its message in SQUASH_MSG, which is what
     * git's own commit would open with. Only looked for when something is staged or
     * conflicting, since a squash always leaves one or the other.
     *
     * @param otherwise run when the read finds no prepared message at all
     * @return whether there was anything to look for; {@code otherwise} is then left to the answer
     */
    private boolean offerPreparedMessage(Runnable otherwise) {
        StagingState state = staging;
        if (state == null || !preparedMayReplace()) return false;
        boolean midOperation = state.midOperation();
        boolean maybeSquash = !midOperation
                && (!state.staged().isEmpty() || !state.conflicting().isEmpty());
        if (!midOperation && !maybeSquash) return false;
        reader.preparedMessage(!midOperation, prepared -> {
            if (prepared == null || prepared.isBlank()) {
                otherwise.run();
                return;
            }
            if (prepared.equals(lastPreparedMessage) || !preparedMayReplace()) return;
            lastPreparedMessage = prepared;
            var parts = MessageParts.split(prepared);
            fill(Filled.PREPARED, parts.summary(), parts.description());
        });
        return true;
    }

    /**
     * A template with its {@code #} lines removed, as git's cleanup would. In an editor they
     * are instructions; this box's message is committed with whitespace cleanup only, which
     * would keep them.
     */
    static String withoutCommentLines(String template) {
        return template.lines()
                .filter(line -> !line.startsWith("#"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** A message's first line, cut to what fits beside the Amend box. */
    static String firstLineOf(String text) {
        int end = text.indexOf('\n');
        String line = end < 0 ? text : text.substring(0, end);
        return line.length() > 48 ? line.substring(0, 45) + "..." : line;
    }
}
