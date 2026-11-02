package com.jfx.git.ui;

import com.jfx.git.core.BranchIssueKey;
import com.jfx.git.model.OperationState;
import com.jfx.git.model.StagingState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commit composer through its own interface, with no window: what the Commit button
 * says, what a press asks for, how long each option lasts, and everything the box fills in
 * by itself. The controls, the repository's slow answers, the remembered messages and the
 * settings are fakes that behave like the real ones - a control reports a change back as
 * typed, a read answers only when the test lets it.
 */
class CommitComposerTest {

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    /** The Commit page's settings and the repository's Skip hooks default, changeable mid-test. */
    static final class Settings implements CommitComposer.Preferences {
        boolean push;
        boolean issueKeys = true;
        String pattern = BranchIssueKey.DEFAULT_PATTERN;
        String format = BranchIssueKey.DEFAULT_FORMAT;
        boolean skipHooks;

        @Override public boolean pushAfterCommit() { return push; }
        @Override public boolean issueKeyFromBranch() { return issueKeys; }
        @Override public String issueKeyPattern() { return pattern; }
        @Override public String issueKeyFormat() { return format; }
        @Override public boolean skipHooksByDefault() { return skipHooks; }
    }

    /** The message store, in memory. */
    static final class Messages implements CommitComposer.Memory {
        final List<String> history = new ArrayList<>();
        String draft = "";

        @Override public List<String> history() { return List.copyOf(history); }
        @Override public String draft() { return draft; }
        @Override public void saveDraft(String text) { draft = text == null ? "" : text; }

        @Override
        public void remember(String message) {
            history.remove(message.strip());
            history.add(0, message.strip());
            draft = "";
        }
    }

    /** The repository's answers, held until {@link #answer()} hands them over. */
    static final class Reads implements CommitComposer.Reader {
        String template = "";
        String merge = "";
        String squash = "";
        String head = "";
        int templateReads;
        final List<Boolean> preparedReads = new ArrayList<>();
        int headReads;
        private final List<Runnable> pending = new ArrayList<>();

        @Override
        public void commitTemplate(Consumer<String> then) {
            templateReads++;
            pending.add(() -> then.accept(template));
        }

        @Override
        public void preparedMessage(boolean squashed, Consumer<String> then) {
            preparedReads.add(squashed);
            pending.add(() -> then.accept(squashed ? squash : merge));
        }

        @Override
        public void headMessage(Consumer<String> then) {
            headReads++;
            pending.add(() -> then.accept(head));
        }

        void answer() {
            while (!pending.isEmpty()) pending.remove(0).run();
        }
    }

    /**
     * Controls that behave like the real ones: built from what the composer holds, and
     * reporting every change of their own back to it, whoever made it.
     */
    static final class Controls implements CommitComposer.Screen {
        private final CommitComposer composer;
        String summary;
        String description;
        boolean amend;
        boolean skipHooks;
        int caret = -1;

        Controls(CommitComposer composer) {
            this.composer = composer;
            summary = composer.summary();
            description = composer.description();
            amend = composer.amend();
            skipHooks = composer.skipHooks();
        }

        @Override
        public void showSummary(String text) {
            if (text.equals(summary)) return; // a text property reports changes only
            summary = text;
            composer.edited(summary, description);
        }

        @Override
        public void showDescription(String text) {
            if (text.equals(description)) return;
            description = text;
            composer.edited(summary, description);
        }

        @Override
        public void caretToSummaryEnd() {
            caret = summary.length();
        }

        @Override
        public void showAmend(boolean on) {
            if (amend == on) return;
            amend = on;
            composer.setAmend(on);
        }

        @Override
        public void showSkipHooks(boolean on) {
            if (skipHooks == on) return;
            skipHooks = on;
            composer.setSkipHooks(on);
        }

        void typeSummary(String text) {
            summary = text;
            composer.edited(summary, description);
        }

        void typeDescription(String text) {
            description = text;
            composer.edited(summary, description);
        }

        void tickAmend(boolean on) {
            amend = on;
            composer.setAmend(on);
        }

        void tickSkipHooks(boolean on) {
            skipHooks = on;
            composer.setSkipHooks(on);
        }
    }

    private final Settings settings = new Settings();
    private final Messages messages = new Messages();
    private final Reads reads = new Reads();
    private final CommitComposer composer = new CommitComposer(settings, messages, reads);

    /** Builds a box for {@code state} as the staging panel does, and fills it in. */
    private Controls show(StagingState state) {
        composer.stagingRead(state);
        composer.restoreDraft();
        Controls controls = new Controls(composer);
        composer.attach(controls);
        composer.fillIn();
        return controls;
    }

    /** A refresh that found the repository changed while the box stayed on screen. */
    private void refresh(StagingState state) {
        composer.stagingRead(state);
        composer.fillIn();
    }

    /** One unstaged file on {@code branch}. */
    private static StagingState onBranch(String branch) {
        return new StagingState(List.of("a.txt"), List.of(), Set.of(), Map.of(), branch, false);
    }

    private static StagingState staging(String branch, int staged, int conflicts, boolean midOperation) {
        List<String> stagedPaths = IntStream.range(0, staged).mapToObj(i -> "staged" + i + ".txt").toList();
        Set<String> conflicted = new TreeSet<>();
        IntStream.range(0, conflicts).forEach(i -> conflicted.add("conflict" + i + ".txt"));
        return new StagingState(List.copyOf(conflicted), stagedPaths, conflicted, Map.of(), branch, midOperation);
    }

    private static final String KEYED = "feature/ABC-123-login";

    // ------------------------------------------------------------------
    // The Commit button
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            # situation                              | branch | staged | conflicts | stopped | operation      | amend | published | push  | label                         | enabled
            nothing staged                           | main   | 0      | 0         | false   | NONE           | false | false     | false | Nothing staged                | false
            one file staged                          | main   | 1      | 0         | false   | NONE           | false | false     | false | Commit 1 file to main         | true
            several files staged                     | main   | 3      | 0         | false   | NONE           | false | false     | false | Commit 3 files to main        | true
            staged, push after commit                | main   | 2      | 0         | false   | NONE           | false | false     | true  | Commit and push to main       | true
            nothing staged, push after commit        | main   | 0      | 0         | false   | NONE           | false | false     | true  | Nothing staged                | false
            one conflict                             | main   | 0      | 1         | true    | MERGING        | false | false     | false | Resolve 1 conflict first      | false
            conflicts beside staged files            | main   | 2      | 3         | true    | MERGING        | false | false     | false | Resolve 3 conflicts first     | false
            conflicts while amending                 | main   | 0      | 2         | false   | NONE           | true  | false     | true  | Resolve 2 conflicts first     | false
            a merge resolved to nothing              | main   | 0      | 0         | true    | MERGING        | false | false     | false | Commit merge to main          | true
            a merge with files staged                | main   | 2      | 0         | true    | MERGING        | false | false     | false | Commit merge to main          | true
            a merge, push after commit               | main   | 0      | 0         | true    | MERGING        | false | false     | true  | Commit merge and push to main | true
            a stopped cherry-pick, nothing staged    | main   | 0      | 0         | true    | CHERRY_PICKING | false | false     | false | Nothing staged                | false
            a stopped cherry-pick, a file staged     | main   | 1      | 0         | true    | CHERRY_PICKING | false | false     | false | Commit 1 file to main         | true
            a stopped revert, nothing staged         | main   | 0      | 0         | true    | REVERTING      | false | false     | false | Nothing staged                | false
            in progress, the operation not read yet  | main   | 0      | 0         | true    | NONE           | false | false     | false | Nothing staged                | false
            a stale merge read, nothing in progress  | main   | 0      | 0         | false   | MERGING        | false | false     | false | Nothing staged                | false
            amending with nothing staged             | main   | 0      | 0         | false   | NONE           | true  | false     | false | Amend commit to main          | true
            amending with files staged               | main   | 2      | 0         | false   | NONE           | true  | false     | false | Amend commit to main          | true
            amending, push after commit              | main   | 0      | 0         | false   | NONE           | true  | false     | true  | Amend and push to main        | true
            amending a published commit, push after  | main   | 1      | 0         | false   | NONE           | true  | true      | true  | Amend commit to main          | true
            amending a published commit              | main   | 1      | 0         | false   | NONE           | true  | true      | false | Amend commit to main          | true
            amending mid-merge                       | main   | 0      | 0         | true    | MERGING        | true  | false     | false | Cannot amend during a merge   | false
            amending mid-cherry-pick                 | main   | 1      | 0         | true    | CHERRY_PICKING | true  | false     | false | Cannot amend during a cherry-pick | false
            detached HEAD, a file staged             | ''     | 1      | 0         | false   | NONE           | false | false     | false | Commit 1 file                 | true
            detached HEAD, push after commit         | ''     | 1      | 0         | false   | NONE           | false | false     | true  | Commit 1 file                 | true
            detached HEAD, a merge, push after       | ''     | 0      | 0         | true    | MERGING        | false | false     | true  | Commit merge                  | true
            detached HEAD, amending, push after      | ''     | 0      | 0         | false   | NONE           | true  | false     | true  | Amend commit                  | true
            no branch at all, amending               |        | 0      | 0         | false   | NONE           | true  | false     | false | Amend commit                  | true
            a slash-named branch                     | team/x | 1      | 0         | false   | NONE           | false | false     | false | Commit 1 file to team/x       | true
            """)
    void theCommitButton(String situation, String branch, int staged, int conflicts, boolean stopped,
                         OperationState.Kind operation, boolean amend, boolean published, boolean push,
                         String label, boolean enabled) {
        settings.push = push;
        Controls controls = show(staging(branch, staged, conflicts, stopped));
        composer.operationRead(OperationState.of(operation));
        composer.amendTargetRead("the last commit", published ? "origin/main" : null);
        controls.tickAmend(amend);

        assertEquals(new CommitComposer.CommitButton(label, enabled), composer.button(staged), situation);
    }

    @Test
    void theButtonCountsTheListsOfTheMomentNotTheLastRead() {
        show(staging("main", 0, 0, false));
        // A stage click moves the row before git has answered; the button follows the row.
        assertEquals("Commit 1 file to main", composer.button(1).label());
    }

    @Test
    void theButtonDoesNotWaitForAMessage() {
        show(staging("main", 1, 0, false));
        assertEquals(new CommitComposer.CommitButton("Commit 1 file to main", true), composer.button(1),
                "an empty box still offers the commit");
        assertTrue(composer.request(false).isEmpty(), "and pressing it asks for a message instead");
    }

    @Test
    void aBoxHoldingOnlyTheTemplateCommitsTheTemplate() {
        reads.template = "Summary line\n\nWhy:";
        show(staging("main", 1, 0, false));
        reads.answer();

        assertEquals("Commit 1 file to main", composer.button(1).label());
        assertEquals("Summary line\n\nWhy:", composer.request(false).orElseThrow().message(),
                "committed as it stands, as git commit -F would");
    }

    @Test
    void theSettingsAreReadEachTimeSoAChangeAppliesAtOnce() {
        show(staging("main", 1, 0, false));
        settings.push = true;
        assertEquals("Commit and push to main", composer.button(1).label());
        settings.push = false;
        assertEquals("Commit 1 file to main", composer.button(1).label());
    }

    @Test
    void whetherAnAmendIsPushedFollowsTheLastReadAcrossARebuild() {
        settings.push = true;
        Controls first = show(staging("main", 0, 0, false));
        composer.amendTargetRead("pushed", "its upstream");
        first.tickAmend(true);
        assertEquals("Amend commit to main", composer.button(0).label());

        // A rebuild knows nothing of HEAD until its read lands, but the button keeps saying
        // what the last read found rather than offering a push it will not make.
        show(staging("main", 0, 0, false));
        assertEquals("Amend commit to main", composer.button(0).label());
        assertEquals("", composer.amendLine().text(), "while the line says nothing it has not read");
    }

    @Test
    void resolveConflictsFirstCountsThem() {
        assertEquals("Resolve 1 conflict first", CommitComposer.resolveConflictsFirst(1));
        assertEquals("Resolve 4 conflicts first", CommitComposer.resolveConflictsFirst(4));
    }

    // ------------------------------------------------------------------
    // What a press asks for
    // ------------------------------------------------------------------

    @Test
    void aBlankMessageMakesNoRequest() {
        Controls controls = show(staging("main", 1, 0, false));
        assertTrue(composer.request(false).isEmpty());
        controls.typeSummary("   ");
        controls.typeDescription("\n  \n");
        assertTrue(composer.request(true).isEmpty(), "whitespace is no message");
        assertTrue(composer.requestStagingAll().isEmpty());
    }

    @Test
    void aRequestCarriesTheBoxAsItWasWhenPressed() {
        Controls controls = show(staging("main", 1, 0, false));
        controls.typeSummary("  Fix the parser ");
        controls.typeDescription("\nIt dropped the last token.\n");
        controls.tickSkipHooks(true);
        composer.setSignOff(true);

        var request = composer.request(false).orElseThrow();
        assertEquals(new CommitComposer.CommitRequest("Fix the parser\n\nIt dropped the last token.",
                false, true, true, false, false), request);
        assertTrue(composer.request(true).orElseThrow().push(), "Ctrl+Shift+Enter pushes whatever the setting");

        settings.push = true;
        assertTrue(composer.request(false).orElseThrow().push(), "and the setting pushes every commit");
        var stagingAll = composer.requestStagingAll().orElseThrow();
        assertTrue(stagingAll.stageAllFirst());
        assertTrue(stagingAll.push(), "Stage all and commit follows the setting");
        settings.push = false;
        assertFalse(composer.requestStagingAll().orElseThrow().push());
    }

    @Test
    void signingOffAddsTheTrailerWhenCommitting() {
        var plain = new CommitComposer.CommitRequest("Fix it", false, false, true, false, false);
        assertEquals("Fix it\n\nSigned-off-by: Ada <ada@example.com>", plain.signedOffBy("Ada <ada@example.com>"));

        var credited = new CommitComposer.CommitRequest("Fix it\n\nCo-authored-by: Bo <bo@example.com>",
                false, false, true, false, false);
        assertEquals("Fix it\n\nCo-authored-by: Bo <bo@example.com>\nSigned-off-by: Ada <ada@example.com>",
                credited.signedOffBy("Ada <ada@example.com>"), "joined to the trailer block already there");
    }

    @Test
    void offScreenACommitNeitherAmendsNorSkipsHooksButKeepsTheMessage() {
        Controls controls = show(staging("main", 1, 0, false));
        controls.typeSummary("Typed before looking at a commit");
        controls.tickAmend(true);
        controls.tickSkipHooks(true);

        composer.detach();
        var request = composer.request(false).orElseThrow();
        assertEquals("Typed before looking at a commit", request.message());
        assertFalse(request.amend());
        assertFalse(request.skipHooks());
    }

    /**
     * Off screen there is no box to untick, so a shortcut that commits from the commit view
     * skips hooks exactly when the repository says to. It used to commit with hooks run
     * whatever the repository's default, the opposite of the box it had left.
     */
    @Test
    void offScreenACommitFollowsTheRepositorysSkipHooksDefault() {
        settings.skipHooks = true;
        Controls controls = show(staging("main", 1, 0, false));
        controls.typeSummary("Committed from the commit view");
        controls.tickSkipHooks(false);

        composer.detach();
        assertTrue(composer.request(false).orElseThrow().skipHooks(), "the repository's own default");
    }

    /**
     * A press that the button would refuse makes no request: Ctrl+Enter, the window's
     * shortcut and the palette all reach the composer without the button, and they used to
     * commit whatever the button said.
     */
    @Test
    void aPressTheButtonWouldRefuseMakesNoRequest() {
        Controls conflicted = show(staging("main", 1, 2, true));
        composer.operationRead(OperationState.of(OperationState.Kind.MERGING));
        conflicted.typeSummary("Merge topic");
        assertTrue(composer.request(false).isEmpty(), "Resolve 2 conflicts first");
        assertTrue(composer.request(true).isEmpty());
        assertTrue(composer.requestStagingAll().isEmpty(), "staging the rest leaves the conflicts");

        composer.detach();
        show(staging("main", 0, 0, false));
        assertTrue(composer.request(false).isEmpty(), "Nothing staged");
        assertTrue(composer.requestStagingAll().isEmpty(), "and nothing to stage either");

        composer.detach();
        show(onBranch("main"));
        assertTrue(composer.request(false).isEmpty(), "Nothing staged");
        assertEquals("Merge topic", composer.requestStagingAll().orElseThrow().message(),
                "Stage all and commit has a file to stage first");
    }

    /** What a refused press says instead: the button's own words, or that there is no message. */
    @Test
    void aRefusalSaysWhatTheButtonSays() {
        Controls conflicted = show(staging("main", 1, 2, true));
        assertEquals("Resolve 2 conflicts first", composer.refusal(1));
        assertEquals("Resolve 2 conflicts first", composer.refusalStagingAll());
        conflicted.typeSummary("Merge topic");
        assertEquals("Resolve 2 conflicts first", composer.refusal(1), "a message does not resolve them");

        composer.detach();
        Controls clean = show(staging("main", 0, 0, false));
        assertEquals("Nothing staged", composer.refusal(0));
        clean.typeSummary("");
        assertEquals(CommitComposer.MESSAGE_REQUIRED, composer.refusal(1));
        clean.typeSummary("Add b");
        assertEquals(null, composer.refusal(1));
    }

    /** The lists' count of the moment decides, as it does for the button: a stage click is ahead of git. */
    @Test
    void aPressCountsTheListsOfTheMomentNotTheLastRead() {
        Controls controls = show(staging("main", 0, 0, false));
        controls.typeSummary("Staged a moment ago");
        assertTrue(composer.request(false).isEmpty(), "the last read had nothing staged");
        assertEquals("Staged a moment ago", composer.request(false, 1).orElseThrow().message());
    }

    /** Git refuses {@code commit --amend} while a merge is in progress. */
    @Test
    void amendingMidMergeMakesNoRequest() {
        Controls controls = show(staging("main", 1, 0, true));
        composer.operationRead(OperationState.of(OperationState.Kind.MERGING));
        composer.amendTargetRead("the last commit", null);
        controls.typeSummary("Merge topic");
        controls.tickAmend(true);
        assertTrue(composer.request(false).isEmpty());
    }

    /** With HEAD detached there is no branch to push, whatever the setting or the shortcut. */
    @Test
    void aDetachedHeadNeverAsksForAPush() {
        settings.push = true;
        Controls controls = show(staging("", 1, 0, false));
        controls.typeSummary("On no branch");
        assertFalse(composer.request(false).orElseThrow().push(), "the setting");
        assertFalse(composer.request(true).orElseThrow().push(), "Ctrl+Shift+Enter");
        assertFalse(composer.requestStagingAll().orElseThrow().push(), "Stage all and commit");
    }

    /**
     * The key alone is the box's own text, not a message: committed, git got "ABC-123:" and
     * nothing else.
     */
    @Test
    void anIssueKeyAloneIsNoMessage() {
        Controls controls = show(staging(KEYED, 1, 0, false));
        assertEquals("ABC-123: ", controls.summary);
        assertTrue(composer.request(false).isEmpty());
        assertTrue(composer.requestStagingAll().isEmpty());

        controls.typeSummary("ABC-123:");
        assertTrue(composer.request(false).isEmpty(), "nor without the space after it");
        controls.typeSummary("ABC-123: fix the login");
        assertEquals("ABC-123: fix the login", composer.request(false).orElseThrow().message());
    }

    // ------------------------------------------------------------------
    // How long each option lasts
    // ------------------------------------------------------------------

    @Test
    void skipHooksIsOneShot() {
        Controls controls = show(staging("main", 1, 0, false));
        controls.tickSkipHooks(true);
        refresh(staging("main", 2, 0, false));
        assertTrue(composer.skipHooks(), "staging one more file is the same commit");
        assertTrue(controls.skipHooks, "on screen too");

        controls.typeSummary("Skip them this once");
        controls.tickSkipHooks(true);
        composer.committed(composer.request(false).orElseThrow());
        assertFalse(composer.skipHooks(), "and so does a commit");
        assertFalse(controls.skipHooks);
    }

    @Test
    void skipHooksStartsFromTheRepositorysDefault() {
        settings.skipHooks = true;
        Controls controls = show(staging("main", 1, 0, false));
        assertTrue(composer.skipHooks());
        assertTrue(controls.skipHooks);
        controls.tickSkipHooks(false);
        refresh(staging("main", 1, 0, false));
        assertFalse(controls.skipHooks, "unticked to run the hooks once, it stays unticked");
    }

    /**
     * A refresh of the box on screen is no new commit, so it keeps what the user chose.
     * Putting the default back there ticked Skip hooks again behind a user who had unticked
     * it to run the hooks once - after a commit the hooks refused, or any save - and the
     * next commit skipped them without a word.
     */
    @Test
    void aRefreshOfTheSameBoxKeepsTheUsersSkipHooksChoice() {
        settings.skipHooks = true;
        Controls controls = show(staging("main", 1, 0, false));
        controls.tickSkipHooks(false);
        refresh(staging("main", 1, 0, false));
        refresh(staging("main", 2, 0, false));
        assertFalse(composer.skipHooks());
        assertFalse(controls.skipHooks, "on screen too");

        controls.typeSummary("Run the hooks this time");
        assertFalse(composer.request(false).orElseThrow().skipHooks());
    }

    @Test
    void skipHooksStartsAgainFromTheDefaultOnAnotherBranchInANewBoxOrWithANewDefault() {
        settings.skipHooks = true;
        Controls controls = show(staging("main", 1, 0, false));
        controls.tickSkipHooks(false);
        refresh(staging("topic", 1, 0, false));
        assertTrue(controls.skipHooks, "a checkout elsewhere is another commit");

        controls.tickSkipHooks(false);
        composer.detach();
        Controls rebuilt = show(staging("topic", 1, 0, false));
        assertTrue(rebuilt.skipHooks, "and so is a box built after leaving the working tree");

        settings.skipHooks = false;
        refresh(staging("topic", 1, 0, false));
        assertFalse(rebuilt.skipHooks, "a changed default applies at the next refresh");
    }

    @Test
    void amendSurvivesARebuildButNotACommitOrLeaving() {
        Controls first = show(staging("main", 1, 0, false));
        first.typeSummary("Reworded");
        first.tickAmend(true);

        Controls rebuilt = show(staging("main", 2, 0, false));
        assertTrue(rebuilt.amend, "staging one more file is not changing one's mind");

        composer.committed(composer.request(false).orElseThrow());
        assertFalse(composer.amend());
        assertFalse(rebuilt.amend, "unticked on screen");

        rebuilt.tickAmend(true);
        composer.detach();
        assertFalse(show(staging("main", 1, 0, false)).amend, "leaving the working tree ends it");
    }

    @Test
    void signOffLastsAsLongAsTheTab() {
        Controls controls = show(staging("main", 1, 0, false));
        composer.setSignOff(true);
        controls.typeSummary("one");
        composer.committed(composer.request(false).orElseThrow());
        composer.detach();
        show(staging("main", 1, 0, false));
        assertTrue(composer.signOff());
    }

    // ------------------------------------------------------------------
    // The issue key
    // ------------------------------------------------------------------

    @Test
    void anEmptySummaryStartsWithTheBranchsKey() {
        Controls controls = show(onBranch(KEYED));
        assertEquals("ABC-123: ", controls.summary);
        assertEquals(9, controls.caret, "the caret after it, ready to type");
        assertEquals("", messages.draft, "the key alone is not a draft");
    }

    @Test
    void typedTextIsNeverReplaced() {
        Controls controls = show(onBranch(KEYED));
        controls.typeSummary("ABC-123: fix the login");
        settings.format = "[" + BranchIssueKey.KEY_TOKEN + "] ";
        composer.settingsChanged();
        refresh(onBranch("feature/XYZ-9"));
        assertEquals("ABC-123: fix the login", controls.summary);
    }

    @Test
    void theKeyAloneFollowsTheBranchAndTheSettings() {
        Controls controls = show(onBranch(KEYED));

        refresh(onBranch("feature/XYZ-9-other"));
        assertEquals("XYZ-9: ", controls.summary, "a checkout elsewhere replaces it");
        refresh(onBranch("main"));
        assertEquals("", controls.summary, "or empties it, for a branch with none");
        refresh(onBranch(KEYED));
        assertEquals("ABC-123: ", controls.summary);

        settings.format = "[" + BranchIssueKey.KEY_TOKEN + "] ";
        composer.settingsChanged();
        assertEquals("[ABC-123] ", controls.summary, "a new format applies at once");
        settings.issueKeys = false;
        composer.settingsChanged();
        assertEquals("", controls.summary, "and turning the setting off takes it away");
    }

    @Test
    void noKeyMidOperationOrWhileAmending() {
        Controls stopped = show(new StagingState(List.of(), List.of(), Set.of(), Map.of(), KEYED, true));
        assertEquals("", stopped.summary, "git prepared that message");

        composer.detach();
        Controls first = show(onBranch("main"));
        first.tickAmend(true);
        Controls amending = show(onBranch(KEYED));
        assertEquals("", amending.summary, "the message is the amended commit's");
    }

    @Test
    void untickingAmendGivesAnEmptiedSummaryItsKeyBack() {
        reads.head = "The last commit";
        Controls controls = show(onBranch(KEYED));
        controls.tickAmend(true);
        reads.answer();
        assertEquals("The last commit", controls.summary, "the key alone is an empty box");

        controls.typeSummary("");
        controls.tickAmend(false);
        assertEquals("ABC-123: ", controls.summary);
    }

    @Test
    void aDetachedHeadHasNoKey() {
        settings.pattern = "[0-9a-f]{7}";
        assertEquals("", show(onBranch("")).summary, "no branch, so nothing to take a key from");
        composer.detach();
        assertEquals("", show(onBranch(null)).summary);
    }

    /** Proven failing on the code before the composer, through CommitBoxRobotTest. */
    @Test
    void theKeyComesBackExactlyWhenTheBoxIsBuiltAgain() {
        reads.head = "The last commit";
        show(onBranch(KEYED));
        composer.detach();

        Controls rebuilt = show(onBranch(KEYED));
        assertEquals("ABC-123: ", rebuilt.summary, "space and all, not passed through the join");
        rebuilt.tickAmend(true);
        reads.answer();
        assertEquals("The last commit", rebuilt.summary, "and still an empty box to Amend");
        assertEquals("", messages.draft, "nor ever a draft");
    }

    /** Proven failing on the code before the composer, through CommitBoxRobotTest. */
    @Test
    void afterAnAmendTheKeyAloneIsStillAnEmptyBox() {
        Controls controls = show(onBranch(KEYED));
        composer.amendTargetRead("The last commit", null);
        controls.tickAmend(true);
        assertEquals("The last commit", controls.summary);

        composer.committed(composer.request(false).orElseThrow());
        assertEquals("ABC-123: ", controls.summary, "the next commit starts with the key");
        assertFalse(controls.amend);

        controls.tickAmend(true);
        assertEquals("The last commit", controls.summary, "and the key alone is still an empty box");
    }

    /**
     * Mid-merge there is no key, and the branch used to count as worked out all the same:
     * the commit that finished the merge, and the refresh after it, found the same branch
     * and left the next commit's summary empty.
     */
    @Test
    void theKeyComesBackOnceAMergeIsCommitted() {
        reads.merge = "Merge branch 'topic'";
        Controls controls = show(new StagingState(List.of(), List.of("a.txt"), Set.of(), Map.of(), KEYED, true));
        composer.operationRead(OperationState.of(OperationState.Kind.MERGING));
        reads.answer();
        assertEquals("Merge branch 'topic'", controls.summary, "no key mid-merge: git prepared the message");

        composer.committed(composer.request(false).orElseThrow());
        composer.operationRead(OperationState.none());
        refresh(onBranch(KEYED));
        assertEquals("ABC-123: ", controls.summary, "the next commit starts with the key");
    }

    /** An aborted merge takes its prepared message with it, and the key comes back. */
    @Test
    void theKeyComesBackOnceAMergeIsAborted() {
        reads.merge = "Merge branch 'topic'";
        Controls controls = show(new StagingState(List.of("a.txt"), List.of(), Set.of("a.txt"), Map.of(), KEYED,
                true));
        reads.answer();
        assertEquals("Merge branch 'topic'", controls.summary);

        refresh(onBranch(KEYED));
        assertEquals("ABC-123: ", controls.summary, "a merge message with no merge left is stale");
        assertEquals("", controls.description);
        assertEquals("", messages.draft);
    }

    @Test
    void aMessageTypedForAMergeOutlivesIt() {
        reads.merge = "Merge branch 'topic'";
        Controls controls = show(new StagingState(List.of(), List.of("a.txt"), Set.of(), Map.of(), KEYED, true));
        reads.answer();
        controls.typeSummary("Merge topic, keeping our parser");

        refresh(onBranch(KEYED));
        assertEquals("Merge topic, keeping our parser", controls.summary, "typed, so it is the user's");
    }

    // ------------------------------------------------------------------
    // The commit template
    // ------------------------------------------------------------------

    @Test
    void anEmptyBoxGetsTheTemplateWithoutItsCommentLines() {
        reads.template = "# Write the summary in the imperative\nSubject\n\nBody line\n# Explain why\n";
        Controls controls = show(onBranch("main"));
        reads.answer();
        assertEquals("Subject", controls.summary);
        assertEquals("Body line", controls.description);
        assertEquals("", messages.draft, "a template nobody typed is not a draft");
    }

    @Test
    void theKeyStillLeadsTheTemplate() {
        reads.template = "Describe the change";
        Controls controls = show(onBranch(KEYED));
        reads.answer();
        assertEquals("ABC-123: Describe the change", controls.summary);

        composer.committed(new CommitComposer.CommitRequest("x", false, false, false, false, false));
        reads.template = "ABC-123: already keyed";
        refresh(onBranch(KEYED));
        reads.answer();
        assertEquals("ABC-123: already keyed", controls.summary, "not twice");
    }

    @Test
    void aTemplateThatArrivesAfterTypingLeavesTheTextAlone() {
        reads.template = "Template subject\n\nTemplate body";
        Controls controls = show(onBranch("main"));
        controls.typeSummary("Mine");
        reads.answer();
        assertEquals("Mine", controls.summary);
        assertEquals("", controls.description, "nor is its body put under what was typed");
    }

    @Test
    void theTemplateIsOfferedOncePerCommit() {
        reads.template = "Template subject";
        Controls controls = show(onBranch("main"));
        reads.answer();
        controls.typeSummary("");
        refresh(onBranch("main"));
        reads.answer();
        assertEquals(1, reads.templateReads);
        assertEquals("", controls.summary, "a template cleared on purpose stays cleared");

        controls.typeSummary("Done");
        composer.committed(composer.request(false, 1).orElseThrow());
        refresh(onBranch("main"));
        reads.answer();
        assertEquals(2, reads.templateReads, "the next commit is offered it again");
        assertEquals("Template subject", controls.summary);
    }

    @Test
    void aBlankTemplateFillsNothing() {
        reads.template = "# only instructions\n";
        Controls controls = show(onBranch("main"));
        reads.answer();
        assertEquals("", controls.summary);
        assertEquals("", controls.description);
    }

    @Test
    void commentLinesAreThoseStartingWithAHash() {
        assertEquals("keep\n  # indented stays\nlast",
                CommitComposer.withoutCommentLines("#drop\nkeep\n  # indented stays\n# drop too\nlast"));
    }

    // ------------------------------------------------------------------
    // The message git prepared
    // ------------------------------------------------------------------

    @Test
    void aStoppedMergeOffersItsPreparedMessageOnce() {
        reads.merge = "Merge branch 'topic'\n\nConflicts resolved by hand.";
        StagingState stopped = new StagingState(List.of(), List.of("a.txt"), Set.of(), Map.of(), "main", true);
        Controls controls = show(stopped);
        reads.answer();
        assertEquals(List.of(false), reads.preparedReads, "MERGE_MSG");
        assertEquals("Merge branch 'topic'", controls.summary);
        assertEquals("Conflicts resolved by hand.", controls.description);
        assertEquals("", messages.draft);

        controls.typeSummary("");
        controls.typeDescription("");
        refresh(new StagingState(List.of(), List.of("a.txt", "b.txt"), Set.of(), Map.of(), "main", true));
        reads.answer();
        assertEquals("", controls.summary, "cleared, it stays cleared");
    }

    @Test
    void aSquashIsLookedForOnlyWhenSomethingIsStagedOrConflicting() {
        reads.squash = "Squashed commit of the following";
        Controls clean = show(onBranch("main"));
        reads.answer();
        assertEquals(List.of(), reads.preparedReads, "nothing staged: no squash to finish");

        refresh(staging("main", 1, 0, false));
        reads.answer();
        assertEquals(List.of(true), reads.preparedReads, "SQUASH_MSG");
        assertEquals("Squashed commit of the following", clean.summary);
    }

    @Test
    void aPreparedMessageNeverReplacesTypedText() {
        reads.merge = "Merge branch 'topic'";
        Controls controls = show(new StagingState(List.of(), List.of(), Set.of(), Map.of(), "main", true));
        controls.typeSummary("My own words");
        reads.answer();
        assertEquals("My own words", controls.summary);
    }

    /**
     * Git's own commit opens with MERGE_MSG or SQUASH_MSG when there is one, and with the
     * template only when there is not. The box offered the template first, so a merge that
     * stopped on a conflict showed the template and its message never got in.
     */
    @Test
    void aStoppedMergeOffersItsMessageAheadOfTheTemplate() {
        reads.template = "Template subject\n\nTemplate body";
        reads.merge = "Merge branch 'topic'";
        Controls controls = show(new StagingState(List.of(), List.of("a.txt"), Set.of(), Map.of(), "main", true));
        reads.answer();
        assertEquals("Merge branch 'topic'", controls.summary);
        assertEquals("", controls.description);
    }

    @Test
    void aSquashOffersItsMessageAheadOfTheTemplate() {
        reads.template = "Template subject";
        reads.squash = "Squashed commit of the following";
        Controls controls = show(staging("main", 1, 0, false));
        reads.answer();
        assertEquals("Squashed commit of the following", controls.summary);
    }

    @Test
    void withNothingPreparedTheTemplateIsStillOffered() {
        reads.template = "Template subject";
        Controls controls = show(staging("main", 1, 0, false));
        reads.answer();
        assertEquals("Template subject", controls.summary, "no SQUASH_MSG, so the template");
    }

    /** The template in the box was the box's own text, so a merge that stops replaces it. */
    @Test
    void aTemplateInTheBoxGivesWayToAMergeThatStops() {
        reads.template = "Template subject";
        Controls controls = show(onBranch("main"));
        reads.answer();
        assertEquals("Template subject", controls.summary);

        reads.merge = "Merge branch 'topic'";
        refresh(new StagingState(List.of(), List.of("a.txt"), Set.of(), Map.of(), "main", true));
        reads.answer();
        assertEquals("Merge branch 'topic'", controls.summary);
    }

    // ------------------------------------------------------------------
    // Amend
    // ------------------------------------------------------------------

    @Test
    void tickingAmendOverAnEmptyBoxFillsInHeadsMessage() {
        Controls controls = show(onBranch("main"));
        composer.amendTargetRead("Subject\n\nThe body", null);
        controls.tickAmend(true);
        assertEquals("Subject", controls.summary);
        assertEquals("The body", controls.description);
        assertEquals(0, reads.headReads, "known already, so not asked again");
        assertEquals("", messages.draft, "HEAD's message is not a draft");
    }

    @Test
    void beforeHeadIsReadTickingAmendAsksForIt() {
        reads.head = "Read late";
        Controls controls = show(onBranch("main"));
        controls.tickAmend(true);
        assertEquals(1, reads.headReads);
        reads.answer();
        assertEquals("Read late", controls.summary);

        controls.typeSummary("");
        controls.tickAmend(false);
        controls.tickAmend(true);
        controls.tickAmend(false);
        reads.answer();
        assertEquals("", controls.summary, "an answer landing after Amend was unticked fills nothing");
    }

    @Test
    void tickingAmendOverTypedTextKeepsIt() {
        Controls controls = show(onBranch("main"));
        composer.amendTargetRead("HEAD's subject", null);
        controls.typeSummary("Better words");
        controls.tickAmend(true);
        assertEquals("Better words", controls.summary);
    }

    @Test
    void theAmendLineNamesTheCommitAndWarnsWhenItIsPublished() {
        Controls controls = show(onBranch("main"));
        assertEquals(new CommitComposer.AmendLine(false, "", false), composer.amendLine(), "not read yet");

        composer.amendTargetRead("Local work\n\nbody", null);
        assertFalse(composer.amendLine().shown(), "only while amending");
        controls.tickAmend(true);
        assertEquals(new CommitComposer.AmendLine(true, "Amending: Local work", false), composer.amendLine());

        composer.amendTargetRead("A subject long enough that it has to be cut short somewhere", "origin/main");
        assertEquals(new CommitComposer.AmendLine(true, "Already on origin/main - amending rewrites a "
                + "published commit: A subject long enough that it has to be cut s...", true),
                composer.amendLine(), "and a long subject is cut to fit");
    }

    // ------------------------------------------------------------------
    // Co-authors
    // ------------------------------------------------------------------

    @Test
    void aCoAuthorIsATrailerInTheDescription() {
        Controls controls = show(onBranch("main"));
        controls.typeSummary("Pair work");
        composer.addCoAuthor("Ada <ada@example.com>");
        assertEquals("Co-authored-by: Ada <ada@example.com>", controls.description);

        composer.addCoAuthor("Bo <bo@example.com>");
        composer.addCoAuthor("Ada <ada@example.com>");
        assertEquals("Co-authored-by: Ada <ada@example.com>\nCo-authored-by: Bo <bo@example.com>",
                controls.description, "joined to the block, and never twice");

        controls.typeDescription("Why we paired.");
        composer.addCoAuthor("Cy <cy@example.com>");
        assertEquals("Why we paired.\n\nCo-authored-by: Cy <cy@example.com>", controls.description);
        assertEquals("Pair work\n\nWhy we paired.\n\nCo-authored-by: Cy <cy@example.com>", messages.draft,
                "credit is part of the message, so it is kept with the draft");
    }

    // ------------------------------------------------------------------
    // The message history
    // ------------------------------------------------------------------

    @Test
    void steppingThroughTheHistoryAlwaysComesBackToWhatWasTyped() {
        messages.remember("older message");
        messages.remember("newer message\n\nwith a body");
        Controls controls = show(onBranch("main"));
        controls.typeSummary("what I was typing");

        assertTrue(composer.stepHistory(+1));
        assertEquals("newer message", controls.summary);
        assertEquals("with a body", controls.description);
        assertEquals("newer message".length(), controls.caret);
        composer.stepHistory(+1);
        assertEquals("older message", controls.summary);
        composer.stepHistory(+1);
        assertEquals("older message", controls.summary, "the oldest stays put");

        composer.stepHistory(-1);
        composer.stepHistory(-1);
        assertEquals("what I was typing", controls.summary, "past the newest is the user's own text");
        assertEquals("", controls.description);
    }

    @Test
    void withNothingCommittedThereIsNothingToRecall() {
        Controls controls = show(onBranch("main"));
        controls.typeSummary("mine");
        assertFalse(composer.stepHistory(+1));
        assertEquals("mine", controls.summary);
    }

    @Test
    void typingOverARecalledMessageMakesItTheUsersOwn() {
        messages.remember("remembered");
        Controls controls = show(onBranch("main"));
        composer.stepHistory(+1);
        controls.typeSummary("remembered, edited");
        composer.stepHistory(+1);
        composer.stepHistory(-1);
        assertEquals("remembered, edited", controls.summary, "the edit is what comes back");
    }

    /**
     * What comes back past the newest is the box exactly as it was. It came back through the
     * join, which strips: "ABC-123: " returned as "ABC-123:", no longer the key the box put
     * there, so to the box it was typed text - kept as a draft, never replaced, and an empty
     * box no more.
     */
    @Test
    void theKeyAloneIsStillAnEmptyBoxAfterLookingThroughTheHistory() {
        messages.remember("remembered");
        reads.head = "The last commit";
        Controls controls = show(onBranch(KEYED));
        composer.stepHistory(+1);
        composer.stepHistory(-1);
        assertEquals("ABC-123: ", controls.summary, "the key as the box put it there");
        assertEquals("", messages.draft, "still not a draft");

        controls.tickAmend(true);
        reads.answer();
        assertEquals("The last commit", controls.summary, "and still an empty box to Amend");
    }

    @Test
    void theMenuRecallsTheEntryPicked() {
        messages.remember("first");
        messages.remember("second\n\nits body");
        Controls controls = show(onBranch("main"));
        composer.recallHistory(1, composer.history());
        assertEquals("first", controls.summary);
        composer.recallHistory(0, composer.history());
        assertEquals("second", controls.summary);
        assertEquals("its body", controls.description);
    }

    @Test
    void aCommittedMessageJoinsTheHistoryAndTheBoxEmpties() {
        Controls controls = show(onBranch("main"));
        controls.typeSummary("Add b");
        controls.typeDescription("Because.");
        assertEquals("Add b\n\nBecause.", messages.draft);

        composer.committed(composer.request(false, 1).orElseThrow());
        assertEquals(List.of("Add b\n\nBecause."), messages.history);
        assertEquals("", messages.draft);
        assertEquals("", controls.summary);
        assertEquals("", controls.description);
    }

    // ------------------------------------------------------------------
    // The draft
    // ------------------------------------------------------------------

    @Test
    void theDraftComesBackForTheFirstBoxOnly() {
        messages.draft = "half written\n\nand its body";
        Controls first = show(onBranch("main"));
        assertEquals("half written", first.summary);
        assertEquals("and its body", first.description);

        first.typeSummary("");
        first.typeDescription("");
        messages.draft = "something else";
        composer.detach();
        Controls again = show(onBranch("main"));
        assertEquals("", again.summary, "looked for once, when the tab first builds a box");
    }

    @Test
    void textTypedOffScreenIsWhatTheNextBoxShows() {
        Controls first = show(onBranch("main"));
        first.typeSummary("  spaced summary ");
        first.typeDescription("\nbody\n");
        composer.detach();
        Controls again = show(onBranch("main"));
        assertEquals("  spaced summary ", again.summary, "exactly as it was, not re-split");
        assertEquals("\nbody\n", again.description);
        assertEquals("spaced summary\n\nbody", composer.message());
    }

    // ------------------------------------------------------------------
    // No toolkit
    // ------------------------------------------------------------------

    /** Nothing from JavaFX in the composer, so all of the above runs without a toolkit. */
    @Test
    void theComposerImportsNothingFromJavaFx() throws Exception {
        String text = Files.readString(Path.of("src/main/java/com/jfx/git/ui/CommitComposer.java"),
                StandardCharsets.UTF_8);
        assertFalse(text.contains("javafx."), "CommitComposer reaches into JavaFX");
    }

    /**
     * Nor through its own package. A class there needs no import, so the check above passed
     * while the composer joined and split every message through MessageEditor, a JavaFX
     * control. Each class of the package the composer's code names is read for JavaFX too.
     */
    @Test
    void nothingTheComposerUsesFromItsPackageReachesIntoJavaFx() throws Exception {
        Path ui = Path.of("src/main/java/com/jfx/git/ui");
        String code = Files.readString(ui.resolve("CommitComposer.java"), StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\n]*", "");
        List<Path> used = new ArrayList<>();
        try (var files = Files.list(ui)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                if (!name.equals("CommitComposer")
                        && java.util.regex.Pattern.compile("\\b" + name + "\\b").matcher(code).find()) {
                    used.add(file);
                }
            }
        }
        for (Path file : used) {
            assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("javafx."),
                    "CommitComposer reaches into JavaFX through " + file.getFileName());
        }
    }
}
