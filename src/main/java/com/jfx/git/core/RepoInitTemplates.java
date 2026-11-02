package com.jfx.git.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional starter files for a brand-new repository - a {@code .gitignore}, a LICENSE,
 * and/or a README - offered by the New Repository dialog, the same shortcut GitHub's
 * and commercial clients' own init flows both provide. Pure file-writing with no git operations
 * of its own: the caller decides whether/how to stage or commit what this writes, and
 * nothing here ever overwrites a file already present in the target directory.
 *
 * <p>License bodies are bundled under {@code licenses/} rather than embedded as string
 * literals, since two of them (Apache-2.0, GPL-3.0) are long, legally-significant texts
 * fetched verbatim from their own canonical sources (apache.org, gnu.org) rather than
 * retyped - each trimmed at that document's own "END OF TERMS AND CONDITIONS" marker,
 * dropping only the trailing "how to apply this to your own project" instructions/
 * examples that follow it (the same section Apache calls out separately as an
 * "APPENDIX"), since those are guidance for a project's own source-file headers, not
 * part of the license terms themselves, and would otherwise leave unfilled
 * {@code <placeholder>} text sitting in a generated LICENSE file.
 */
public record RepoInitTemplates(String gitignoreTemplate, String licenseTemplate,
                                 String copyrightHolder, boolean addReadme) {

    public static final String NONE = "None";

    /** No templates selected - equivalent to today's bare {@code git init}. */
    public static final RepoInitTemplates NOTHING = new RepoInitTemplates(NONE, NONE, "", false);

    /**
     * The templates on offer, read from the {@code index.txt} beside them.
     *
     * <p>They were two maps in this file, so bundling another language's
     * {@code .gitignore} meant editing source for what is a data change. A resource
     * directory cannot be listed from inside a jar, which is why there is an index and
     * not simply "every file in the folder".
     */
    private static final Map<String, String> GITIGNORE_RESOURCES = index("gitignore");
    private static final Map<String, String> LICENSE_RESOURCES = index("licenses");

    /**
     * Reads {@code <folder>/index.txt}: a display name, a tab, and a file name per
     * line; {@code #} starts a comment. A line that names a file which is not bundled
     * is left out rather than offered and then failing when chosen.
     */
    static Map<String, String> index(String folder) {
        Map<String, String> entries = new LinkedHashMap<>();
        String listing;
        try {
            listing = readResource(folder + "/index.txt");
        } catch (IOException e) {
            return entries;
        }
        for (String line : listing.split("\\r?\\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int tab = line.indexOf('\t');
            if (tab <= 0 || tab == line.length() - 1) continue;
            String resource = folder + "/" + line.substring(tab + 1).trim();
            if (resourceExists(resource)) {
                entries.put(line.substring(0, tab).trim(), resource);
            }
        }
        return entries;
    }

    private static boolean resourceExists(String resourcePath) {
        try {
            readResource(resourcePath);
            return true;
        } catch (IOException missing) {
            return false;
        }
    }

    /** Names for a ".gitignore template" picker, {@link #NONE} always listed first. */
    public static List<String> gitignoreTemplateNames() {
        List<String> names = new ArrayList<>();
        names.add(NONE);
        names.addAll(GITIGNORE_RESOURCES.keySet());
        return names;
    }

    /** Names for a "license template" picker, {@link #NONE} always listed first. */
    public static List<String> licenseTemplateNames() {
        List<String> names = new ArrayList<>();
        names.add(NONE);
        names.addAll(LICENSE_RESOURCES.keySet());
        return names;
    }

    /**
     * Writes whichever of {@code .gitignore}/{@code LICENSE}/{@code README.md} this
     * selection asks for into {@code dir}, skipping any file already present there -
     * running this against a non-empty directory (an existing project being turned
     * into a repository, not just a fresh folder) must never clobber real content.
     * Returns the relative paths actually written, oldest-first, empty if nothing was.
     */
    public List<String> writeInto(File dir, String repoName) throws IOException {
        List<String> written = new ArrayList<>();

        String gitignoreResource = GITIGNORE_RESOURCES.get(gitignoreTemplate);
        if (gitignoreResource != null && writeIfAbsent(dir, ".gitignore", readResource(gitignoreResource))) {
            written.add(".gitignore");
        }

        String licenseResource = LICENSE_RESOURCES.get(licenseTemplate);
        if (licenseResource != null) {
            String holder = (copyrightHolder == null || copyrightHolder.isBlank())
                    ? "the copyright holder" : copyrightHolder.trim();
            String content = readResource(licenseResource)
                    .replace("[year]", String.valueOf(Year.now().getValue()))
                    .replace("[fullname]", holder);
            if (writeIfAbsent(dir, "LICENSE", content)) {
                written.add("LICENSE");
            }
        }

        if (addReadme) {
            String name = (repoName == null || repoName.isBlank()) ? dir.getName() : repoName.trim();
            if (writeIfAbsent(dir, "README.md", "# " + name + "\n")) {
                written.add("README.md");
            }
        }

        return written;
    }

    private static boolean writeIfAbsent(File dir, String fileName, String content) throws IOException {
        File file = new File(dir, fileName);
        if (file.exists()) {
            return false;
        }
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        return true;
    }

    /**
     * {@code licenses/}/{@code gitignore/} hold no classes, so they were never opened
     * for {@code ClassLoader.getResourceAsStream} under JPMS - the exact packaged-app
     * failure mode {@code GrammarRegistry.openGrammarResource} already found and fixed
     * for {@code grammars/}. Reading through the {@link Module} first works
     * unconditionally regardless of "opens"; the classloader stays as a fallback for a
     * classpath-launched run (e.g. this project's own test suite).
     */
    private static String readResource(String resourcePath) throws IOException {
        InputStream moduleStream = RepoInitTemplates.class.getModule().getResourceAsStream(resourcePath);
        InputStream in = moduleStream != null
                ? moduleStream
                : RepoInitTemplates.class.getClassLoader().getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IOException("Missing bundled resource: " + resourcePath);
        }
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
