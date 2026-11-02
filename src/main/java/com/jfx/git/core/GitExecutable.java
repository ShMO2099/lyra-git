package com.jfx.git.core;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Which program to start for "git", and what it needs in its environment.
 *
 * <h2>Why not just "git"</h2>
 * On Windows the {@code git} found on PATH is usually {@code Git/cmd/git.exe}, which is
 * not git: it is Git for Windows' launcher, whose only job is to put Git's own folders at
 * the front of PATH, set {@code MSYSTEM}, and start the real {@code git.exe} under
 * {@code Git/<mingw>/bin}. That is a second process for every command. Measured from Java
 * on this project's development machine, {@code git --version} took 47 ms through the
 * launcher and 36 ms run directly - about ten milliseconds on every one of the dozens of
 * git commands a refresh runs.
 *
 * <p>So when the launcher is what PATH finds, this starts the real program and does the
 * launcher's work itself, for the git processes it starts only - the application's own
 * PATH is left alone, as Git for Windows asks: its folders hold DLLs other software on
 * the machine would otherwise pick up. Anywhere else - another platform, a git that is not
 * Git for Windows' layout, a launcher with no real git beside it - it is plain "git" with
 * nothing added, exactly as before.
 */
final class GitExecutable {

    /**
     * @param command    what to start
     * @param pathPrefix put in front of PATH for that process, or {@code null}
     * @param msystem    {@code MSYSTEM} for that process, or {@code null}
     */
    record Resolved(String command, String pathPrefix, String msystem) {
        static final Resolved PLAIN = new Resolved("git", null, null);

        /** Applies what the launcher would have set to {@code env}. */
        void applyTo(Map<String, String> env) {
            if (pathPrefix != null) {
                String pathKey = env.keySet().stream()
                        .filter(key -> key.equalsIgnoreCase("PATH"))
                        .findFirst()
                        .orElse("PATH");
                String path = env.get(pathKey);
                env.put(pathKey, path == null || path.isEmpty()
                        ? pathPrefix
                        : pathPrefix + File.pathSeparator + path);
            }
            if (msystem != null) {
                env.putIfAbsent("MSYSTEM", msystem);
            }
        }
    }

    /** The environments a Git for Windows install may keep its real git under, newest first. */
    private static final List<String[]> ENVIRONMENTS = List.of(
            new String[]{"ucrt64", "UCRT64"},
            new String[]{"mingw64", "MINGW64"},
            new String[]{"clangarm64", "CLANGARM64"},
            new String[]{"mingw32", "MINGW32"});

    private static volatile Resolved resolved;

    private GitExecutable() {
    }

    /** Worked out once; PATH does not change under a running application. */
    static Resolved get() {
        Resolved known = resolved;
        if (known == null) {
            boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                    .startsWith("windows");
            known = resolve(System.getenv("PATH"), windows);
            resolved = known;
        }
        return known;
    }

    /**
     * The real git behind the launcher PATH finds first, if that is what it finds.
     *
     * @param path    the PATH to search, in order
     * @param windows whether this is Windows, where the launcher exists
     */
    static Resolved resolve(String path, boolean windows) {
        if (!windows || path == null) return Resolved.PLAIN;
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            File dir = new File(entry.trim().replace("\"", ""));
            if (!new File(dir, "git.exe").isFile()) continue;
            // The first git.exe on PATH is the one "git" would start; only its install
            // is considered. A launcher lives in <Git>/cmd or <Git>/bin, beside <Git>/usr.
            String name = dir.getName().toLowerCase(java.util.Locale.ROOT);
            File root = dir.getParentFile();
            if (!(name.equals("cmd") || name.equals("bin")) || root == null
                    || !new File(root, "usr\\bin").isDirectory()) {
                return Resolved.PLAIN;
            }
            for (String[] environment : ENVIRONMENTS) {
                File bin = new File(root, environment[0] + "\\bin");
                File git = new File(bin, "git.exe");
                if (git.isFile()) {
                    return new Resolved(git.getAbsolutePath(),
                            bin.getAbsolutePath() + File.pathSeparator
                                    + new File(root, "usr\\bin").getAbsolutePath(),
                            environment[1]);
                }
            }
            return Resolved.PLAIN;
        }
        return Resolved.PLAIN;
    }
}
