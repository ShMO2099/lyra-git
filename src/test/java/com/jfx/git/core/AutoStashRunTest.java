package com.jfx.git.core;

import com.jfx.git.model.MergeMode;
import com.jfx.git.model.StashEntry;
import com.jfx.git.testsupport.Engines;
import com.jfx.git.ui.FxHarness;

import javafx.application.Platform;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auto-stash through its interface, {@link AutoStash#run}, on every engine against a real
 * repository, with a scripted {@link AutoStash.Asker} in place of the dialog: a refusal
 * for local changes becomes a question, asked on the FX thread; yes stashes, runs the
 * operation again and puts the changes back, every git step on the repository's lane; no
 * leaves everything as it was.
 *
 * <p>{@link AutoStashTest} pins the lane-side steps one by one; this is the whole sequence
 * as a caller sees it.
 */
@Tag("heavy")
class AutoStashRunTest {

    @TempDir
    Path root;

    private final List<GitBackend> opened = new ArrayList<>();
    private int count;

    /** What the scripted askers were asked, and on which thread. */
    private final List<String> asked = new CopyOnWriteArrayList<>();

    /** Every call the operation and auto-stash made on the backend, by method name. */
    private final List<String> calls = new CopyOnWriteArrayList<>();

    /** Calls that were not on the backend's own lane, or were on the FX thread. */
    private final List<String> offLane = new CopyOnWriteArrayList<>();

    /** The work tree of the repository a test made, read and written without the backend. */
    private File workTree;

    @BeforeEach
    void startFx() {
        FxHarness.start();
    }

    @AfterEach
    void close() {
        opened.forEach(GitBackend::close);
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    static Stream<Arguments> engines() {
        return Engines.all();
    }

    private static final String BASE = "one\ntwo\nthree\n";
    private static final String OTHER = BASE + "four\n";

    /**
     * {@code main} with {@code a.txt}; {@code other} one commit ahead, adding a line to
     * {@code a.txt} and a new {@code b.txt}. Opened by the engine under test, wrapped so
     * every call it takes is recorded with the thread it came on.
     */
    private GitBackend fixture(Supplier<GitBackend> factory) throws Exception {
        File dir = root.resolve("repo" + count++).toFile();
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            identity(git);
            Files.writeString(dir.toPath().resolve("a.txt"), BASE);
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").call();
            git.checkout().setCreateBranch(true).setName("other").call();
            Files.writeString(dir.toPath().resolve("a.txt"), OTHER);
            Files.writeString(dir.toPath().resolve("b.txt"), "theirs\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("on other").call();
            git.checkout().setName("main").call();
        }
        workTree = dir;
        return recording(open(factory, dir));
    }

    private GitBackend open(Supplier<GitBackend> factory, File dir) {
        GitBackend backend = factory.get();
        assertTrue(backend.openRepository(dir));
        opened.add(backend);
        return backend;
    }

    private static void identity(Git git) throws Exception {
        var config = git.getRepository().getConfig();
        config.setString("user", null, "name", "Test User");
        config.setString("user", null, "email", "test@example.com");
        config.setString("core", null, "autocrlf", "false");
        config.setBoolean("commit", null, "gpgsign", false);
        config.save();
    }

    /**
     * {@code real}, recording each call: its name in {@link #calls}, and in {@link #offLane}
     * when it ran anywhere but on this backend's own lane. The wrapper is the repository the
     * lane is named after, so a call on its lane sees it as {@link GitTaskRunner#laneRepository}.
     */
    private GitBackend recording(GitBackend real) {
        GitBackend[] self = new GitBackend[1];
        self[0] = (GitBackend) Proxy.newProxyInstance(GitBackend.class.getClassLoader(),
                new Class<?>[]{GitBackend.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "recording " + real;
                        default:
                            break;
                    }
                    calls.add(method.getName());
                    if (Platform.isFxApplicationThread() || GitTaskRunner.laneRepository() != self[0]) {
                        offLane.add(method.getName() + " on " + Thread.currentThread().getName());
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return self[0];
    }

    /** An asker that answers {@code yes} straight away, and records being asked. */
    private AutoStash.Asker answering(boolean yes) {
        return (operation, stash) -> {
            asked.add(operation + (Platform.isFxApplicationThread() ? " on FX" : " off FX"));
            stash.accept(yes);
        };
    }

    /** {@link AutoStash#run}, waited for; the result as {@code done} was handed it. */
    private static GitResult run(GitBackend git, AutoStash.Operation operation, AutoStash.Asker asker,
                                 Supplier<GitResult> work) throws Exception {
        CompletableFuture<GitResult> done = new CompletableFuture<>();
        AutoStash.run(git, operation, asker, work, result -> {
            if (!Platform.isFxApplicationThread()) {
                done.completeExceptionally(new AssertionError("done was called off the FX thread"));
            }
            done.complete(result);
        });
        return done.get(60, TimeUnit.SECONDS);
    }

    /** Reads the test's own work tree directly, so the read is no call on the backend. */
    private String read(GitBackend backend, String path) throws Exception {
        return Files.readString(new File(workTree, path).toPath()).replace("\r\n", "\n");
    }

    private void write(GitBackend backend, String path, String content) throws Exception {
        Files.writeString(new File(workTree, path).toPath(), content);
    }

    /** The stash list read on the backend's lane, as the app would. */
    private static List<StashEntry> stashes(GitBackend backend) throws Exception {
        CompletableFuture<List<StashEntry>> read = new CompletableFuture<>();
        GitTaskRunner.query(backend, backend::getStashEntries, read::complete,
                error -> read.completeExceptionally(new AssertionError(error)));
        return read.get(30, TimeUnit.SECONDS);
    }

    private static String currentBranch(GitBackend backend) throws Exception {
        CompletableFuture<String> read = new CompletableFuture<>();
        GitTaskRunner.query(backend, backend::getCurrentBranch, read::complete,
                error -> read.completeExceptionally(new AssertionError(error)));
        return read.get(30, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------
    // Yes
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aRefusedCheckoutIsAskedAboutOnTheFxThreadThenStashedMadeAndPutBackOnTheLane(
            String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "zero\n" + BASE);

        GitResult result = run(backend, AutoStash.Operation.CHECKOUT, answering(true),
                () -> backend.checkoutBranch("other"));

        assertTrue(result.isOk(), () -> name + ": " + result.getMessage());
        assertTrue(result.getMessage().contains("your changes were re-applied"), result::getMessage);
        assertEquals(List.of("CHECKOUT on FX"), asked, name + ": asked once, on the FX thread");
        assertEquals(List.of(), offLane, name + ": every git step ran on the repository's lane");
        assertEquals(2, calls.stream().filter("checkoutBranch"::equals).count(),
                name + ": refused once, made once: " + calls);
        assertEquals(1, calls.stream().filter("stash"::equals).count(), name + ": one stash: " + calls);
        assertEquals("other", currentBranch(backend));
        assertEquals("zero\n" + OTHER, read(backend, "a.txt"), name + ": the edit is on top of other's version");
        assertEquals(List.of(), stashes(backend), name + ": the stash it made is gone");
    }

    /**
     * An untracked file where the other branch has one: the refusal says so, the stash takes
     * untracked files too, and the user's own bytes come back over the checked-out file.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void anUntrackedFileInTheWayIsStashedAndComesBackOverTheCheckedOutOne(
            String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "b.txt", "mine\n");

        GitResult result = run(backend, AutoStash.Operation.CHECKOUT, answering(true),
                () -> backend.checkoutBranch("other"));

        assertTrue(result.isOk(), () -> name + ": " + result.getMessage());
        assertTrue(result.getMessage().contains("your own b.txt is back in place of the checkout's version"),
                result::getMessage);
        assertEquals("other", currentBranch(backend));
        assertEquals(OTHER, read(backend, "a.txt"));
        assertEquals("mine\n", read(backend, "b.txt"), name + ": the user's own file, byte for byte");
        assertEquals(List.of(), stashes(backend), name);
        assertEquals(List.of(), offLane, name);
    }

    /** A put-back that conflicts: the checkout was made, and the changes wait in the stash. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aPutBackThatConflictsKeepsTheStashAndSaysSo(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", BASE + "mine\n");

        GitResult result = run(backend, AutoStash.Operation.CHECKOUT, answering(true),
                () -> backend.checkoutBranch("other"));

        assertTrue(result.isConflict(), () -> name + ": " + result.getMessage());
        assertEquals(AutoStash.POP_CONFLICTED, result.getMessage());
        assertEquals("other", currentBranch(backend), name + ": the checkout itself happened");
        List<StashEntry> kept = stashes(backend);
        assertEquals(1, kept.size(), name + ": nothing was dropped");
        assertTrue(AutoStash.isAutoStash(kept.get(0)), () -> name + ": " + kept.get(0).message());
    }

    /**
     * JGit's merger stops at the first path in the way, so its refusal names the edit and
     * not the untracked file behind it; the first retry is refused over that file, and the
     * second stashes untracked files too. Through {@link AutoStash#run} that is still one
     * question.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aMergeWhoseRefusalMissesAnUntrackedFileIsStillOneQuestionAndAMerge(
            String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = recording(mergeFixture(factory));
        write(backend, "shared.txt", "line 1\nline 2\nline 3\nline 4\nlocal line 5\n");
        write(backend, "z-added.txt", "my own notes\n");

        GitResult result = run(backend, AutoStash.Operation.MERGE, answering(true),
                () -> backend.merge("incoming", MergeMode.DEFAULT, null));

        assertTrue(result.isOk(), () -> name + ": " + result.getMessage());
        assertEquals(List.of("MERGE on FX"), asked, name);
        assertEquals("theirs line 1\nline 2\nline 3\nline 4\nlocal line 5\n", read(backend, "shared.txt"),
                name + ": the edit re-applied on top of the merged file");
        assertEquals("my own notes\n", read(backend, "z-added.txt"), name + ": the user's own file is back");
        assertEquals(List.of(), stashes(backend), name + ": no stash is left behind");
        assertEquals(List.of(), offLane, name);
    }

    /** {@code main} one commit past where {@code incoming} branched; see AutoStashTest. */
    private GitBackend mergeFixture(Supplier<GitBackend> factory) throws Exception {
        File dir = root.resolve("merge" + count++).toFile();
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            identity(git);
            Files.writeString(dir.toPath().resolve("shared.txt"), "line 1\nline 2\nline 3\nline 4\nline 5\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("base").call();
            git.checkout().setCreateBranch(true).setName("incoming").call();
            Files.writeString(dir.toPath().resolve("shared.txt"),
                    "theirs line 1\nline 2\nline 3\nline 4\nline 5\n");
            Files.writeString(dir.toPath().resolve("z-added.txt"), "added by the merge\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("incoming").call();
            git.checkout().setName("main").call();
            Files.writeString(dir.toPath().resolve("local.txt"), "local\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("local only").call();
        }
        workTree = dir;
        return open(factory, dir);
    }

    /**
     * A pull request checked out before and since moved on: checking it out again switches
     * to its branch and fast-forwards it, and an edit in the way of the fast-forward is
     * offered the same as any other - stashed, caught up, put back.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aPullRequestThatMovedOnIsCaughtUpUnderAnEdit(String name, Supplier<GitBackend> factory) throws Exception {
        File dir = root.resolve("review" + count++).toFile();
        File origin = root.resolve("origin" + count++).toFile();
        Git.init().setDirectory(origin).setBare(true).setInitialBranch("main").call().close();
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            identity(git);
            var config = git.getRepository().getConfig();
            config.setString("remote", "origin", "url", origin.getAbsolutePath());
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            config.save();
            Files.writeString(dir.toPath().resolve("a.txt"), BASE);
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").call();
            git.checkout().setCreateBranch(true).setName("proposal").call();
            Files.writeString(dir.toPath().resolve("proposal.txt"), "first version\n");
            git.add().addFilepattern("proposal.txt").call();
            git.commit().setMessage("propose").call();
            git.checkout().setCreateBranch(true).setName("proposal-2").call();
            Files.writeString(dir.toPath().resolve("a.txt"), BASE + "proposed\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("propose more").call();
            git.checkout().setName("main").call();
            git.push().setRemote("origin").setRefSpecs(
                    new org.eclipse.jgit.transport.RefSpec("refs/heads/proposal:refs/pull/7/head")).call();
        }
        workTree = dir;
        GitBackend real = open(factory, dir);
        GitResult first = real.checkoutPullRequest("origin", 7, false);
        assertTrue(first.isOk(), () -> name + ": " + first.getMessage());
        assertTrue(real.checkoutBranch("main").isOk());
        try (Git git = Git.open(dir)) {
            git.push().setRemote("origin").setForce(true).setRefSpecs(
                    new org.eclipse.jgit.transport.RefSpec("refs/heads/proposal-2:refs/pull/7/head")).call();
        }
        write(real, "a.txt", "zero\n" + BASE);
        GitBackend backend = recording(real);

        GitResult result = run(backend, AutoStash.Operation.CHECKOUT, answering(true),
                () -> backend.checkoutPullRequest("origin", 7, false));

        assertTrue(result.isOk(), () -> name + ": " + result.getMessage());
        assertEquals(List.of("CHECKOUT on FX"), asked, name);
        assertEquals("pr-7", currentBranch(backend));
        try (Git git = Git.open(dir)) {
            assertEquals(git.getRepository().resolve("proposal-2"), git.getRepository().resolve("pr-7"),
                    name + ": pr-7 caught up with the pull request");
        }
        assertEquals("zero\n" + BASE + "proposed\n", read(backend, "a.txt"),
                name + ": the edit is on top of the pull request's version");
        assertEquals(List.of(), stashes(backend), name);
        assertEquals(List.of(), offLane, name);
    }

    // ------------------------------------------------------------------
    // No, and nothing to ask
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void noLeavesEverythingAsItWasAndHandsBackTheRefusal(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "zero\n" + BASE);
        write(backend, "notes.txt", "untracked\n");

        GitResult result = run(backend, AutoStash.Operation.CHECKOUT, answering(false),
                () -> backend.checkoutBranch("other"));

        assertFalse(result.isOk(), name);
        assertInstanceOf(Refusal.LocalChanges.class, result.getRefusal(), () -> name + ": " + result.getMessage());
        assertEquals(List.of("CHECKOUT on FX"), asked, name);
        assertEquals(List.of("checkoutBranch"), calls, name + ": the refused attempt and nothing else");
        assertEquals("main", currentBranch(backend));
        assertEquals("zero\n" + BASE, read(backend, "a.txt"));
        assertEquals("untracked\n", read(backend, "notes.txt"));
        assertEquals(List.of(), stashes(backend), name + ": no stash was made");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void anOutcomeThatIsNoRefusalForLocalChangesIsHandedBackWithoutAQuestion(
            String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "zero\n" + BASE);
        AutoStash.Asker never = (operation, stash) -> {
            throw new AssertionError("nothing to ask");
        };

        GitResult missing = run(backend, AutoStash.Operation.CHECKOUT, never,
                () -> backend.checkoutBranch("no-such-branch"));
        GitResult fine = run(backend, AutoStash.Operation.CHECKOUT, never,
                () -> backend.checkoutBranch("main"));

        assertFalse(missing.isOk(), name);
        assertTrue(fine.isOk(), () -> name + ": the branch already checked out: " + fine.getMessage());
        assertFalse(calls.contains("stash"), name + ": " + calls);
        assertEquals("zero\n" + BASE, read(backend, "a.txt"));
    }

    // ------------------------------------------------------------------
    // The question's timing
    // ------------------------------------------------------------------

    /**
     * The question is asked between two tasks, not inside one: while it waits for an
     * answer, other work on the repository's lane runs - a status refresh is not stuck
     * behind a dialog.
     */
    @Test
    void theLaneIsFreeWhileTheQuestionWaitsAndALaterAnswerStillCarriesOn() throws Exception {
        GitBackend backend = fixture(GitService::new);
        write(backend, "a.txt", "zero\n" + BASE);
        AtomicReference<Consumer<Boolean>> pending = new AtomicReference<>();
        CompletableFuture<Void> askedNow = new CompletableFuture<>();
        CompletableFuture<GitResult> done = new CompletableFuture<>();

        FxHarness.run(() -> AutoStash.run(backend, AutoStash.Operation.CHECKOUT, (operation, stash) -> {
            pending.set(stash);
            askedNow.complete(null);
        }, () -> backend.checkoutBranch("other"), done::complete));
        askedNow.get(30, TimeUnit.SECONDS);

        assertEquals("main", currentBranch(backend), "a query on the lane answers while the question is open");
        assertFalse(done.isDone(), "nothing is decided before the answer");

        Platform.runLater(() -> pending.get().accept(true));
        GitResult result = done.get(60, TimeUnit.SECONDS);

        assertTrue(result.isOk(), result::getMessage);
        assertEquals("other", currentBranch(backend));
        assertEquals("zero\n" + OTHER, read(backend, "a.txt"));
    }

    /** An asker that answers twice stashes once: the second answer is ignored. */
    @Test
    void aSecondAnswerIsIgnored() throws Exception {
        GitBackend backend = fixture(GitService::new);
        write(backend, "a.txt", "zero\n" + BASE);
        List<GitResult> results = new CopyOnWriteArrayList<>();
        CompletableFuture<GitResult> done = new CompletableFuture<>();

        FxHarness.run(() -> AutoStash.run(backend, AutoStash.Operation.CHECKOUT, (operation, stash) -> {
            stash.accept(true);
            stash.accept(true);
            stash.accept(false);
        }, () -> backend.checkoutBranch("other"), result -> {
            results.add(result);
            done.complete(result);
        }));
        GitResult result = done.get(60, TimeUnit.SECONDS);
        // Anything a second answer started would have queued behind the first retry.
        assertEquals("other", currentBranch(backend));

        assertTrue(result.isOk(), result::getMessage);
        assertEquals(1, results.size(), "done is called once: " + results);
        assertEquals(1, calls.stream().filter("stash"::equals).count(), "one stash: " + calls);
        assertSame(result, results.get(0));
    }
}
