package com.jfx.git.core;

import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.util.SystemReader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Git's {@code safe.directory} rule: a repository owned by somebody else is not opened
 * unless the user has said they trust it.
 *
 * <h2>Why it matters here</h2>
 * The rule is git's answer to CVE-2022-24765. Git walks up from the current directory to
 * find a repository, so another user who can create {@code C:\.git} gets their config -
 * and their hooks - run by anyone who opens a folder beneath it. Git and libgit2 both
 * refuse; JGit does not check at all. This class is the check for the engines that lack
 * it, the recognition of the refusal for the ones that have it, and the one-click fix
 * git's own message recommends.
 */
public final class SafeDirectory {

    private static final Logger LOGGER = Logger.getLogger(SafeDirectory.class.getName());

    /** "git config --global --add safe.directory 'C:/path'", with or without quotes. */
    private static final Pattern SUGGESTED =
            Pattern.compile("safe\\.directory\\s+'?([^'\\r\\n]+?)'?\\s*$", Pattern.MULTILINE);

    /** The owner of a file this process creates - who "the current user" is to the filesystem. */
    private static volatile UserPrincipal self;

    private SafeDirectory() {
    }

    /**
     * Whether {@code message} is a dubious-ownership refusal: git's "detected dubious
     * ownership", or libgit2's "is not owned by current user".
     */
    public static boolean isDubiousOwnership(String message) {
        if (message == null) return false;
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("dubious ownership") || m.contains("not owned by current user");
    }

    /**
     * The path git's refusal says to trust, exactly as git spelled it - which on Windows
     * is forward-slashed, and for a network share carries git's own {@code %(prefix)/}
     * form that no path this application builds would match.
     */
    public static Optional<String> suggestedPath(String message) {
        if (message == null) return Optional.empty();
        Matcher m = SUGGESTED.matcher(message);
        return m.find() ? Optional.of(m.group(1).trim()) : Optional.empty();
    }

    /** {@code dir} as git writes it in {@code safe.directory}: absolute, forward slashes. */
    public static String gitPath(File dir) {
        File canonical;
        try {
            canonical = dir.getCanonicalFile();
        } catch (IOException e) {
            canonical = dir.getAbsoluteFile();
        }
        return canonical.getPath().replace('\\', '/');
    }

    /**
     * Adds {@code path} to the user's global {@code safe.directory} list - what git's
     * own refusal tells the user to run. Asking first is the caller's job: this is a
     * security exception, and it is permanent.
     */
    public static GitResult trust(String path) {
        return trust(path, Map.of());
    }

    /** @param environment extra variables for git, e.g. {@code GIT_CONFIG_GLOBAL} under test */
    static GitResult trust(String path, Map<String, String> environment) {
        if (path == null || path.isBlank()) return GitResult.failed("No folder to trust");
        if (NativeGitRunner.isGitInstalled()) {
            NativeGitRunner git = new NativeGitRunner(null);
            var existing = git.runWithEnvironment(environment,
                    List.of("config", "--global", "--get-all", "safe.directory"));
            if (existing.isOk() && existing.stdout().lines().anyMatch(path::equals)) {
                return GitResult.ok(path + " is already trusted");
            }
            var added = git.runWithEnvironment(environment,
                    List.of("config", "--global", "--add", "safe.directory", path));
            return added.isOk()
                    ? GitResult.ok("Trusted " + path)
                    : GitResult.failed("Could not trust " + path + ": " + added.stderr());
        }
        try {
            StoredConfig user = SystemReader.getInstance().getUserConfig();
            List<String> entries = new ArrayList<>(List.of(user.getStringList("safe", null, "directory")));
            if (!entries.contains(path)) {
                entries.add(path);
                user.setStringList("safe", null, "directory", entries);
                user.save();
            }
            return GitResult.ok("Trusted " + path);
        } catch (Exception e) {
            return GitResult.failed("Could not trust " + path + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // The check itself, for JGit
    // ------------------------------------------------------------------

    /**
     * Git's refusal for this repository, or {@code null} when it may be opened.
     *
     * <p>Cheap in the common case: a repository whose directories this user owns is
     * decided by two {@code stat}s. Only a mismatch asks git - which applies the rules
     * this would otherwise have to reimplement, such as a Windows administrator trusting
     * a repository owned by the Administrators group - and, without git, the
     * {@code safe.directory} list as JGit reads it.
     *
     * @param workTree the work tree, or {@code null} for a bare repository
     * @param gitDir   the repository's git directory
     */
    static String ownershipProblem(File workTree, File gitDir) {
        List<File> paths = new ArrayList<>();
        if (workTree != null) paths.add(workTree);
        if (gitDir != null) paths.add(gitDir);
        try {
            boolean allMine = true;
            for (File path : paths) {
                if (!ownedByCurrentUser(path.toPath())) {
                    allMine = false;
                    break;
                }
            }
            if (allMine) return null;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // A filesystem with no notion of an owner (FAT, some network shares) is one
            // git does not check either.
            LOGGER.log(Level.FINE, "Could not read the owner of " + paths, e);
            return null;
        }

        File probe = workTree != null ? workTree : gitDir;
        if (NativeGitRunner.isGitInstalled()) {
            var answer = new NativeGitRunner(probe).run("rev-parse", "--git-dir");
            return !answer.isOk() && isDubiousOwnership(answer.stderr()) ? answer.stderr() : null;
        }
        if (isListed(safeDirectoryEntries(), probe)) return null;
        String path = gitPath(probe);
        return "detected dubious ownership in repository at '" + path + "'\n"
                + "To add an exception for this directory, call:\n\n"
                + "\tgit config --global --add safe.directory '" + path + "'";
    }

    private static boolean ownedByCurrentUser(Path path) throws IOException {
        UserPrincipal owner = Files.getOwner(path);
        if (owner.equals(currentUser())) return true;
        // An elevated Windows process owns what it creates as "Administrators", so the
        // principal comparison alone would call the user's own repository foreign.
        String name = owner.getName();
        String bare = name.substring(name.lastIndexOf('\\') + 1);
        return bare.equalsIgnoreCase(System.getProperty("user.name", ""));
    }

    private static UserPrincipal currentUser() throws IOException {
        UserPrincipal known = self;
        if (known != null) return known;
        Path probe = Files.createTempFile("lyra-owner", ".tmp");
        try {
            known = Files.getOwner(probe);
        } finally {
            Files.deleteIfExists(probe);
        }
        self = known;
        return known;
    }

    /** {@code safe.directory} from the user and system config - the only places git reads it. */
    private static List<String> safeDirectoryEntries() {
        List<String> entries = new ArrayList<>();
        try {
            entries.addAll(List.of(SystemReader.getInstance().getSystemConfig()
                    .getStringList("safe", null, "directory")));
            entries.addAll(List.of(SystemReader.getInstance().getUserConfig()
                    .getStringList("safe", null, "directory")));
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not read safe.directory", e);
        }
        return entries;
    }

    /**
     * Whether git would trust {@code dir} given these {@code safe.directory} values, in
     * the order git read them: {@code *} trusts everything, an empty value forgets the
     * entries before it, and a trailing {@code /*} trusts everything beneath a path.
     */
    static boolean isListed(List<String> entries, File dir) {
        boolean windows = File.separatorChar == '\\';
        String target = gitPath(dir);
        String targetKey = windows ? target.toLowerCase(Locale.ROOT) : target;
        boolean listed = false;
        for (String raw : entries) {
            String entry = raw == null ? "" : raw.trim();
            if (entry.isEmpty()) {
                listed = false;
                continue;
            }
            if (entry.equals("*")) {
                listed = true;
                continue;
            }
            String normalised = entry.replace('\\', '/');
            if (windows) normalised = normalised.toLowerCase(Locale.ROOT);
            if (normalised.endsWith("/*")) {
                String prefix = normalised.substring(0, normalised.length() - 1);
                if (targetKey.startsWith(prefix)) listed = true;
            } else {
                while (normalised.length() > 1 && normalised.endsWith("/")) {
                    normalised = normalised.substring(0, normalised.length() - 1);
                }
                if (normalised.equals(targetKey)) listed = true;
            }
        }
        return listed;
    }
}
