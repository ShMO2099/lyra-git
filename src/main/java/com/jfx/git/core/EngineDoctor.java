package com.jfx.git.core;

import com.jfx.git.model.RefInfo;
import com.jfx.git.model.StagingState;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the same scenarios against every engine on this machine and reports where they
 * disagree.
 *
 * <h2>Why a product feature and not just a test</h2>
 * This application's whole shape is three engines behind one interface, which is a claim:
 * that it does not matter which one you pick. A test suite proves that on the machine
 * that ran it, against the git and libgit2 that machine had. The user's machine has a
 * different git - possibly years older, possibly with company config in it - and the
 * claim is being made about <em>that</em> one.
 *
 * <p>So this is the claim made checkable where it matters. It builds a throwaway
 * repository, asks each available engine the same questions, and lists any answer that
 * differs. Nobody else's client can do this, because nobody else's client has more than
 * one engine to disagree with itself.
 *
 * <h2>What it does not do</h2>
 * It does not touch the user's repositories. Every check runs against a temporary
 * repository this class creates and deletes, so a doctor run cannot be the thing that
 * breaks someone's work.
 */
public final class EngineDoctor {

    private static final Logger LOGGER = Logger.getLogger(EngineDoctor.class.getName());

    private EngineDoctor() {
    }

    /**
     * One question, and what each engine answered.
     *
     * @param answers engine label to the answer it gave, in the order they were asked
     */
    public record Check(String question, Map<String, String> answers) {

        /** Whether every engine that answered gave the same answer. */
        public boolean agrees() {
            return answers.values().stream().distinct().count() <= 1;
        }

        /** The shared answer when they agree, or a summary of the disagreement. */
        public String describe() {
            if (answers.isEmpty()) return "no engine could answer";
            if (agrees()) return answers.values().iterator().next();
            return answers.entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue())
                    .reduce((a, b) -> a + "   ·   " + b).orElse("");
        }
    }

    /** Everything the run found. */
    public record Report(List<String> engines, List<Check> checks, String problem) {

        public boolean isOk() {
            return problem == null;
        }

        public List<Check> disagreements() {
            return checks.stream().filter(c -> !c.agrees()).toList();
        }

        /** One line for the top of the dialog. */
        public String summary() {
            if (problem != null) return problem;
            if (engines.size() < 2) {
                return "Only " + String.join(", ", engines) + " is available here, so there is "
                        + "nothing to compare it against.";
            }
            int wrong = disagreements().size();
            return wrong == 0
                    ? "All " + engines.size() + " engines agree on all " + checks.size() + " checks."
                    : wrong + " of " + checks.size() + " checks disagree between engines.";
        }
    }

    /**
     * Builds a fixture, runs every check against every available engine, and cleans up.
     *
     * <p>Blocking, and slow enough to matter - it creates a repository and opens it up to
     * three times - so call it from a worker.
     */
    public static Report run() {
        Path fixture;
        try {
            fixture = Files.createTempDirectory("lyra-doctor");
        } catch (IOException e) {
            return new Report(List.of(), List.of(), "Could not create a temporary repository: "
                    + e.getMessage());
        }
        try {
            String setUp = buildFixture(fixture);
            if (setUp != null) {
                return new Report(List.of(), List.of(), setUp);
            }
            return examine(fixture.toFile());
        } finally {
            deleteRecursively(fixture);
        }
    }

    /**
     * A repository with enough in it for the checks to be about something: two commits,
     * a branch, an annotated tag and a lightweight one, a staged file and an unstaged one.
     *
     * <p>Built with JGit, which is always present - the fixture must not depend on the
     * engines being examined.
     *
     * @return null on success, or what went wrong
     */
    private static String buildFixture(Path dir) {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.init()
                .setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Lyra Doctor");
            config.setString("user", null, "email", "doctor@lyra.invalid");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();

            Files.writeString(dir.resolve("a.txt"), "one\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").call();

            Files.writeString(dir.resolve("a.txt"), "one\ntwo\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("a.txt").call();
            var second = git.commit().setMessage("second").call();

            git.branchCreate().setName("side").call();
            git.tag().setName("annotated").setObjectId(second)
                    .setAnnotated(true).setMessage("an annotated tag").call();
            git.tag().setName("lightweight").setObjectId(second).setAnnotated(false).call();

            // One file staged and one left alone, so the staging checks have both.
            Files.writeString(dir.resolve("staged.txt"), "staged\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("staged.txt").call();
            Files.writeString(dir.resolve("unstaged.txt"), "unstaged\n", StandardCharsets.UTF_8);
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not build the doctor's fixture", e);
            return "Could not build a test repository: " + e.getMessage();
        }
    }

    /** The questions. Each must be answerable as one short string, so answers compare. */
    private static final Map<String, Function<GitBackend, String>> QUESTIONS = questions();

    private static Map<String, Function<GitBackend, String>> questions() {
        Map<String, Function<GitBackend, String>> checks = new LinkedHashMap<>();

        checks.put("Current branch", GitBackend::getCurrentBranch);

        checks.put("Local branches", backend -> backend.getLocalBranches().stream()
                .map(RefInfo::shortName).sorted().reduce((a, b) -> a + ", " + b).orElse("none"));

        // B7's territory: an annotated tag has to report the commit, not the tag object.
        checks.put("Annotated tag points at", backend -> {
            String head = backend.getHeadHash();
            return backend.getTags().stream()
                    .filter(t -> "annotated".equals(t.shortName()))
                    .findFirst()
                    .map(t -> String.valueOf(head != null && head.equals(t.targetHash())
                            ? "the commit (correct)" : "something else: " + t.targetHash()))
                    .orElse("tag not listed");
        });

        checks.put("Staged files", backend -> {
            StagingState state = backend.getStagingState();
            return String.join(", ", state.staged().stream().sorted().toList());
        });

        checks.put("Unstaged files", backend -> {
            StagingState state = backend.getStagingState();
            return String.join(", ", state.unstaged().stream().sorted().toList());
        });

        checks.put("Uncommitted changes", backend -> {
            try {
                return String.valueOf(backend.hasUncommittedChanges());
            } catch (GitOperationException e) {
                return "error: " + e.getMessage();
            }
        });

        checks.put("Commits in history", backend ->
                String.valueOf(backend.getCommitGraphPage(50, com.jfx.git.graph.GraphOrder.current()).rows().stream()
                        .filter(r -> r.getCommit() != null).count()));

        // B14's territory: "before" is the index copy, not HEAD.
        checks.put("Working-tree comparison base", backend -> {
            byte[][] sides = backend.getWorkingTreeSides("staged.txt");
            return sides[0].length == 0 ? "empty (HEAD)" : new String(sides[0], StandardCharsets.UTF_8).trim();
        });

        checks.put("File history lines", backend -> {
            var history = backend.getFileHistoryCommits("a.txt", 0);
            return history.isEmpty() ? "none"
                    : "<hash> - " + history.get(0).getSubject() + " (" + history.get(0).getAuthorName() + ")";
        });

        return checks;
    }

    private static Report examine(File dir) {
        List<String> engines = new ArrayList<>();
        Map<String, Map<String, String>> answersByQuestion = new LinkedHashMap<>();
        QUESTIONS.keySet().forEach(q -> answersByQuestion.put(q, new LinkedHashMap<>()));

        for (var candidate : availableEngines()) {
            GitBackend backend = candidate.getValue().get();
            try {
                if (!backend.openRepository(dir)) continue;
                engines.add(candidate.getKey());
                QUESTIONS.forEach((question, ask) -> {
                    String answer;
                    try {
                        answer = String.valueOf(ask.apply(backend));
                    } catch (Exception e) {
                        answer = "error: " + e.getClass().getSimpleName();
                    }
                    answersByQuestion.get(question).put(candidate.getKey(), answer);
                });
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Engine " + candidate.getKey() + " could not be examined", e);
            } finally {
                try {
                    backend.close();
                } catch (Exception ignored) {
                    // Closing a fixture nobody will look at again.
                }
            }
        }

        List<Check> checks = answersByQuestion.entrySet().stream()
                .map(e -> new Check(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(Check::agrees))
                .toList();
        return new Report(engines, checks, engines.isEmpty() ? "No engine could open a repository" : null);
    }

    /** Every engine this machine can actually provide, in the order to report them. */
    private static List<Map.Entry<String, java.util.function.Supplier<GitBackend>>> availableEngines() {
        List<Map.Entry<String, java.util.function.Supplier<GitBackend>>> engines = new ArrayList<>();
        engines.add(Map.entry("JGit", GitService::new));
        if (NativeGitRunner.isGitInstalled()) {
            engines.add(Map.entry("Native CLI", NativeGitBackend::new));
        }
        if (com.jfx.git.core.libgit2.Libgit2Loader.isAvailable()) {
            engines.add(Map.entry("libgit2",
                    com.jfx.git.core.libgit2.Libgit2Backend::new));
        }
        return engines;
    }

    private static void deleteRecursively(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Windows can hold a pack file; a temp directory is not worth failing over.
                }
            });
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not clean up " + root, e);
        }
    }
}
