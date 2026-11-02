package com.jfx.git.core;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Opens a comparison or a conflict in a program of the user's own choosing.
 *
 * <p>The built-in diff and merge views are what most changes need. They are not what
 * someone with ten years of muscle memory in Beyond Compare, Meld or KDiff3 wants for a
 * hard merge, and until now the only route to those was a terminal.
 *
 * <h2>Two ways to name the tool</h2>
 * <ul>
 *   <li>{@link Mode#GIT_CONFIG} hands the whole job to {@code git difftool} and
 *       {@code git mergetool}. Whatever the user configured for the command line -
 *       {@code diff.tool}, {@code merge.tool}, a {@code difftool.<name>.cmd}, one of
 *       git's built-in tool names - works here unchanged, including git's own handling
 *       of temporary files, {@code trustExitCode} and backups. It needs the git
 *       executable, whatever engine the application is otherwise using.</li>
 *   <li>{@link Mode#CUSTOM} is a command line with placeholders, for a tool that is not
 *       in git's configuration. The sides are written to temporary files here and their
 *       paths substituted for {@link #LOCAL}, {@link #REMOTE}, {@link #BASE} and
 *       {@link #MERGED} - the names git itself uses, so a command copied from a tool's
 *       own instructions for git works as written.</li>
 * </ul>
 *
 * <h2>Threads</h2>
 * {@link #openDiff} starts the tool and returns: a diff viewer stays open as long as the
 * user likes. A merge has to wait for the tool to exit, because whether the file came
 * back resolved is the answer - and that wait must not sit on a task lane, where it
 * would hold every other git action in the application for as long as the tool is open.
 * {@link #mergeAsync} arranges that; {@link #openMerge} is the same thing in one blocking
 * call.
 */
public final class ExternalTools {

    private static final Logger LOGGER = Logger.getLogger(ExternalTools.class.getName());

    /** How a tool is chosen. */
    public enum Mode {
        NONE("None"),
        GIT_CONFIG("The tool git is configured with"),
        CUSTOM("Custom command");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** The older side of a comparison; in a merge, the version on the current branch. */
    public static final String LOCAL = "$LOCAL";
    /** The newer side of a comparison; in a merge, the version being merged in. */
    public static final String REMOTE = "$REMOTE";
    /** In a merge, the common ancestor of the two sides. */
    public static final String BASE = "$BASE";
    /** In a merge, the conflicted file in the working tree, which the tool writes its result to. */
    public static final String MERGED = "$MERGED";

    /** The tree every commit has when it has no parent to compare against. */
    private static final String EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";

    private ExternalTools() {
    }

    // ------------------------------------------------------------------
    // What to compare
    // ------------------------------------------------------------------

    /**
     * One side of a comparison.
     *
     * @param revision a commit-ish for a committed version; {@code null} otherwise
     */
    public record Side(Kind kind, String revision) {

        /** Where a side's content lives. */
        public enum Kind { COMMIT, INDEX, WORK_TREE, NOTHING }

        public static Side commit(String revision) {
            return new Side(Kind.COMMIT, revision);
        }

        /** What is staged. */
        public static Side index() {
            return new Side(Kind.INDEX, null);
        }

        /** The file on disk. */
        public static Side workTree() {
            return new Side(Kind.WORK_TREE, null);
        }

        /** No content: the "before" of a repository's first commit. */
        public static Side nothing() {
            return new Side(Kind.NOTHING, null);
        }

        String label() {
            return switch (kind) {
                case COMMIT -> revision.length() > 8 ? revision.substring(0, 8) : revision;
                case INDEX -> "staged";
                case WORK_TREE -> "working";
                case NOTHING -> "empty";
            };
        }
    }

    /**
     * A file compared between two sides.
     *
     * @param path    repository-relative, with forward slashes
     * @param oldSide what the tool shows as the original
     * @param newSide what it shows as changed
     */
    public record DiffTarget(String path, Side oldSide, Side newSide) {

        /** What is not staged yet: the index against the file on disk. */
        public static DiffTarget unstaged(String path) {
            return new DiffTarget(path, Side.index(), Side.workTree());
        }

        /** What is staged: HEAD against the index. */
        public static DiffTarget staged(String path) {
            return new DiffTarget(path, Side.commit("HEAD"), Side.index());
        }

        /**
         * What one commit changed, against its first parent.
         *
         * @param parent the first parent's hash, or {@code null} for a root commit
         */
        public static DiffTarget commit(String path, String parent, String commit) {
            return new DiffTarget(path, parent == null ? Side.nothing() : Side.commit(parent),
                    Side.commit(commit));
        }

        public static DiffTarget between(String path, String older, String newer) {
            return new DiffTarget(path, Side.commit(older), Side.commit(newer));
        }
    }

    // ------------------------------------------------------------------
    // Diff
    // ------------------------------------------------------------------

    /**
     * Opens {@code target} in the configured diff tool and returns once it has started.
     *
     * <p>Reads blobs through {@code backend}, so call it off the FX thread.
     */
    public static GitResult openDiff(GitBackend backend, DiffTarget target) {
        Mode mode = AppPreferences.diffToolMode();
        return switch (mode) {
            case NONE -> GitResult.failed("No external diff tool is set. Choose one in Settings, under"
                    + " External Tools.");
            case GIT_CONFIG -> openDiffWithGit(backend.getWorkTree(), target);
            case CUSTOM -> openDiffWithCommand(backend, target, AppPreferences.diffToolCommand());
        };
    }

    /** The arguments {@code git difftool} needs to show {@code target}; visible for tests. */
    static List<String> difftoolArguments(DiffTarget target) {
        List<String> args = new ArrayList<>(List.of("difftool", "--no-prompt"));
        Side.Kind from = target.oldSide().kind();
        Side.Kind to = target.newSide().kind();
        if (to == Side.Kind.INDEX) {
            // HEAD against the index, or a named commit against it.
            args.add("--cached");
            if (from == Side.Kind.COMMIT && !"HEAD".equals(target.oldSide().revision())) {
                args.add(target.oldSide().revision());
            }
        } else if (to == Side.Kind.WORK_TREE) {
            // The index against the file by default; a commit against the file when named.
            if (from == Side.Kind.COMMIT) {
                args.add(target.oldSide().revision());
            }
        } else {
            args.add(from == Side.Kind.COMMIT ? target.oldSide().revision() : EMPTY_TREE);
            args.add(target.newSide().revision());
        }
        args.add("--");
        args.add(target.path());
        return args;
    }

    private static GitResult openDiffWithGit(File workTree, DiffTarget target) {
        if (!NativeGitRunner.isGitInstalled()) {
            return GitResult.failed("Using git's configured diff tool needs git itself, and no git"
                    + " executable was found. Set a custom command in Settings instead.");
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(difftoolArguments(target));
        return start(command, workTree, "git difftool", target.path());
    }

    private static GitResult openDiffWithCommand(GitBackend backend, DiffTarget target, String template) {
        if (template == null || template.isBlank()) {
            return GitResult.failed("The custom diff tool has no command. Set one in Settings, under"
                    + " External Tools.");
        }
        try {
            Path dir = newScratchDirectory("diff");
            File oldFile = materialise(backend, target.path(), target.oldSide(), dir, "a");
            File newFile = materialise(backend, target.path(), target.newSide(), dir, "b");
            Map<String, String> tokens = new LinkedHashMap<>();
            tokens.put(LOCAL, oldFile.getAbsolutePath());
            tokens.put(REMOTE, newFile.getAbsolutePath());
            // A diff has no ancestor and no result file, but a command written for a
            // merge tool may still name them; they get the sides they most resemble.
            tokens.put(BASE, oldFile.getAbsolutePath());
            tokens.put(MERGED, newFile.getAbsolutePath());
            List<String> command = buildCommand(template, tokens, List.of(LOCAL, REMOTE));
            return start(command, backend.getWorkTree(), command.get(0), target.path());
        } catch (IOException e) {
            return GitResult.failed("Could not prepare " + target.path() + " for the diff tool: " + describe(e));
        }
    }

    /**
     * The file a tool is given for one side.
     *
     * <p>The working tree's side is the real file, so a tool that can edit edits what is
     * on disk. Every other side is a copy in a scratch directory, read-only, under the
     * file's own name - most tools pick their syntax highlighting from the extension.
     */
    private static File materialise(GitBackend backend, String path, Side side, Path dir, String slot)
            throws IOException {
        if (side.kind() == Side.Kind.WORK_TREE) {
            return new File(backend.getWorkTree(), path);
        }
        byte[] bytes = switch (side.kind()) {
            case COMMIT -> backend.getFileBytesAtCommit(side.revision(), path);
            case INDEX -> backend.getStagedBytes(path);
            default -> new byte[0];
        };
        return writeScratch(dir, slot + "-" + side.label(), path, bytes == null ? new byte[0] : bytes);
    }

    // ------------------------------------------------------------------
    // Merge
    // ------------------------------------------------------------------

    /** How a merge tool left a conflicted file. */
    public enum MergeOutcome {
        /** The tool exited cleanly and the file has no conflict markers left. */
        RESOLVED,
        /** The tool exited, but the file still has markers or the tool reported failure. */
        UNRESOLVED,
        /** Resolved, and already staged - what {@code git mergetool} does itself. */
        RESOLVED_AND_STAGED,
        /** The tool could not be run at all. */
        FAILED
    }

    /** @param message what to tell the user; never blank */
    public record MergeResult(MergeOutcome outcome, String message) {
    }

    /**
     * A merge tool about to be run: its command line, and what to check afterwards.
     *
     * @param command   the process to start; empty when {@code failure} is set
     * @param gitDriven whether git itself runs the tool and stages the result
     * @param failure   why no tool can be run, or {@code null}
     */
    public record MergeSession(String path, File workTree, List<String> command, boolean gitDriven,
                               String failure) {

        static MergeSession failed(String path, String why) {
            return new MergeSession(path, null, List.of(), false, why);
        }
    }

    /**
     * Opens the conflicted {@code path} in the configured merge tool and waits for the
     * tool to exit.
     *
     * <p>Blocks for as long as the tool is open, so it is for a thread that can wait.
     * The application uses {@link #mergeAsync}, which keeps that wait off both task lanes.
     */
    public static MergeResult openMerge(GitBackend backend, String path) {
        MergeSession session = prepareMerge(backend, path);
        if (session.failure() != null) {
            return new MergeResult(MergeOutcome.FAILED, session.failure());
        }
        return assessMerge(backend, session, runAndWait(session.command(), session.workTree()));
    }

    /**
     * Runs the merge tool for {@code path} and delivers the outcome on the FX thread.
     *
     * <p>Three steps on three threads. Reading the conflict's sides out of the index is
     * repository work, and goes on the repository's lane. The wait for the tool goes on a
     * thread of its own: on either lane it would hold every other git action, or every
     * clone and provider call, for as long as the user sat in the tool. Reading back
     * whether the conflict is gone is repository work again.
     */
    public static void mergeAsync(GitBackend backend, String path,
                                  java.util.function.Consumer<MergeResult> onDone) {
        java.util.function.Consumer<String> failed =
                why -> onDone.accept(new MergeResult(MergeOutcome.FAILED, why));
        GitTaskRunner.query(backend, () -> prepareMerge(backend, path), session -> {
            if (session.failure() != null) {
                failed.accept(session.failure());
                return;
            }
            Thread waiter = new Thread(() -> {
                Finished finished = runAndWait(session.command(), session.workTree());
                GitTaskRunner.query(backend, () -> assessMerge(backend, session, finished), onDone, failed);
            }, "merge-tool-wait");
            waiter.setDaemon(true);
            waiter.start();
        }, failed);
    }

    /** Works out the command for {@code path}'s conflict, writing its sides out if the tool needs them. */
    static MergeSession prepareMerge(GitBackend backend, String path) {
        Mode mode = AppPreferences.mergeToolMode();
        if (mode == Mode.NONE) {
            return MergeSession.failed(path, "No external merge tool is set. Choose one in Settings,"
                    + " under External Tools.");
        }
        if (mode == Mode.GIT_CONFIG) {
            if (!NativeGitRunner.isGitInstalled()) {
                return MergeSession.failed(path, "Using git's configured merge tool needs git itself, and"
                        + " no git executable was found. Set a custom command in Settings instead.");
            }
            return new MergeSession(path, backend.getWorkTree(),
                    List.of("git", "mergetool", "--no-prompt", "--", path), true, null);
        }
        String template = AppPreferences.mergeToolCommand();
        if (template == null || template.isBlank()) {
            return MergeSession.failed(path, "The custom merge tool has no command. Set one in"
                    + " Settings, under External Tools.");
        }
        try {
            Path dir = newScratchDirectory("merge");
            Map<String, String> tokens = new LinkedHashMap<>();
            tokens.put(BASE, writeScratch(dir, "base", path, backend.getConflictStageBytes(path, 1))
                    .getAbsolutePath());
            tokens.put(LOCAL, writeScratch(dir, "local", path, backend.getConflictStageBytes(path, 2))
                    .getAbsolutePath());
            tokens.put(REMOTE, writeScratch(dir, "remote", path, backend.getConflictStageBytes(path, 3))
                    .getAbsolutePath());
            tokens.put(MERGED, new File(backend.getWorkTree(), path).getAbsolutePath());
            return new MergeSession(path, backend.getWorkTree(),
                    buildCommand(template, tokens, List.of(LOCAL, REMOTE, BASE, MERGED)), false, null);
        } catch (IOException e) {
            return MergeSession.failed(path,
                    "Could not prepare " + path + " for the merge tool: " + describe(e));
        }
    }

    /** What the tool left behind, once it has exited. */
    private static MergeResult assessMerge(GitBackend backend, MergeSession session, Finished finished) {
        String path = session.path();
        String tool = session.gitDriven() ? "git mergetool" : session.command().get(0);
        if (finished.error() != null) {
            return new MergeResult(MergeOutcome.FAILED, "Could not run " + tool + ": " + finished.error());
        }
        if (session.gitDriven()) {
            // git stages the file itself when the tool reports success; whether it did
            // is read back from the repository rather than inferred from the exit code,
            // which git also sets for "no tool configured".
            if (!backend.getConflictingFiles().contains(path)) {
                return new MergeResult(MergeOutcome.RESOLVED_AND_STAGED, "Resolved " + path);
            }
            String said = finished.output().isBlank() ? "" : " git said: " + lastLine(finished.output());
            return new MergeResult(MergeOutcome.UNRESOLVED, path + " is still conflicted." + said);
        }
        if (finished.exitCode() != 0) {
            return new MergeResult(MergeOutcome.UNRESOLVED, tool + " exited with "
                    + finished.exitCode() + ", so " + path + " is left as conflicted.");
        }
        if (hasConflictMarkers(new File(session.workTree(), path))) {
            return new MergeResult(MergeOutcome.UNRESOLVED,
                    path + " still has conflict markers in it, so it is left as conflicted.");
        }
        return new MergeResult(MergeOutcome.RESOLVED, path + " has no conflict markers left.");
    }

    /**
     * Whether a file still carries the markers git writes into a conflict.
     *
     * <p>All three, each at the start of a line: a file that merely contains
     * {@code =======} - a Markdown heading underline, a separator in a log - is not
     * conflicted. A file that cannot be read is treated as still conflicted, since
     * staging it unseen is the one wrong answer.
     */
    public static boolean hasConflictMarkers(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            // ISO-8859-1 maps every byte to a char, so the ASCII markers are found
            // whatever the file's real encoding is.
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            boolean ours = false;
            boolean divider = false;
            for (String line : text.split("\r?\n", -1)) {
                if (line.startsWith("<<<<<<< ")) {
                    ours = true;
                } else if (ours && line.startsWith("=======")) {
                    divider = true;
                } else if (divider && line.startsWith(">>>>>>> ")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Command lines
    // ------------------------------------------------------------------

    /**
     * Splits a command template and puts paths in place of its placeholders.
     *
     * <p>Substituted after splitting, so a path with spaces in it stays one argument
     * whether or not the template quoted the placeholder. A template that names none of
     * {@code appendIfAbsent} has those paths added at the end in that order - what a
     * bare {@code meld} or {@code "C:\...\BCompare.exe"} expects.
     *
     * @return the arguments; empty for a blank template
     */
    static List<String> buildCommand(String template, Map<String, String> tokens, List<String> appendIfAbsent) {
        List<String> parts = AppPreferences.splitCommand(template);
        if (parts.isEmpty()) {
            return List.of();
        }
        boolean substituted = false;
        List<String> command = new ArrayList<>(parts.size() + appendIfAbsent.size());
        for (String part : parts) {
            String replaced = part;
            // Longest first, so $MERGED is not half-eaten by a hypothetical $M.
            List<String> names = new ArrayList<>(tokens.keySet());
            names.sort(Comparator.comparingInt(String::length).reversed());
            for (String name : names) {
                if (replaced.contains(name)) {
                    replaced = replaced.replace(name, tokens.get(name));
                    substituted = true;
                }
            }
            command.add(replaced);
        }
        if (!substituted) {
            for (String name : appendIfAbsent) {
                command.add(tokens.get(name));
            }
        }
        return command;
    }

    private static GitResult start(List<String> command, File directory, String toolName, String path) {
        if (command.isEmpty()) {
            return GitResult.failed("The tool's command is empty");
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory);
            // Nothing reads the tool's output; left attached to pipes, a chatty tool
            // fills the buffer and stops.
            builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.start();
            return GitResult.ok("Opened " + path + " in " + toolName);
        } catch (Exception e) {
            return GitResult.failed("Could not run " + command.get(0) + ": " + describe(e));
        }
    }

    /** @param error why the process could not be run, or {@code null} when it ran */
    private record Finished(int exitCode, String output, String error) {
    }

    private static Finished runAndWait(List<String> command, File directory) {
        if (command.isEmpty()) {
            return new Finished(-1, "", "the command is empty");
        }
        try {
            Process process = new ProcessBuilder(command).directory(directory)
                    .redirectErrorStream(true).start();
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Finished(process.waitFor(), output, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Finished(-1, "", "interrupted");
        } catch (Exception e) {
            return new Finished(-1, "", describe(e));
        }
    }

    private static String lastLine(String output) {
        String[] lines = output.strip().split("\r?\n");
        return lines[lines.length - 1].strip();
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    // ------------------------------------------------------------------
    // Scratch files
    // ------------------------------------------------------------------

    private static final List<Path> SCRATCH = new CopyOnWriteArrayList<>();

    static {
        // A diff tool is left running, so its files cannot go when the call returns.
        // They go when the application does.
        Runtime.getRuntime().addShutdownHook(new Thread(ExternalTools::deleteScratch, "external-tools-cleanup"));
    }

    private static Path newScratchDirectory(String purpose) throws IOException {
        Path dir = Files.createTempDirectory("lyra-" + purpose + "-");
        SCRATCH.add(dir);
        return dir;
    }

    /** Writes one side under {@code dir/slot/<file name>}, read-only. */
    private static File writeScratch(Path dir, String slot, String path, byte[] bytes) throws IOException {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        Path side = Files.createDirectories(dir.resolve(slot));
        Path file = side.resolve(name);
        Files.write(file, bytes == null ? new byte[0] : bytes);
        file.toFile().setReadOnly();
        return file.toFile();
    }

    /** Removes every scratch directory made so far; also run at shutdown. */
    static void deleteScratch() {
        for (Path dir : SCRATCH) {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(file -> {
                    file.toFile().setWritable(true);
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        LOGGER.log(Level.FINE, "Could not delete " + file, e);
                    }
                });
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Could not clear " + dir, e);
            }
            SCRATCH.remove(dir);
        }
    }

    /** The scratch directories currently held; for tests. */
    static List<Path> scratchDirectories() {
        return List.copyOf(SCRATCH);
    }
}
