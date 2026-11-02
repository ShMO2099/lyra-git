package com.jfx.git.core.libgit2;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the bundled {@code libgit2} binaries against the checksums their README records.
 *
 * <h2>Why</h2>
 * These are the only files in the repository that nobody can read. Four prebuilt shared
 * libraries are shipped and loaded into the application's own process, and the sole
 * record of what they are is a table in {@code native/README.md} - which, being a
 * markdown table, can drift from the files beside it without anything noticing. A binary
 * swapped, rebuilt or truncated leaves that table saying what used to be true.
 *
 * <p>All four are built from source, and the README records each one's recipe. This keeps
 * that record honest: whatever the table says a file is, the file still hashes to it, and
 * no binary can enter or change without the table changing with it.
 */
class BundledBinaryProvenanceTest {

    private static final Path NATIVE_ROOT = Path.of("src", "main", "resources", "native");
    private static final Path README = NATIVE_ROOT.resolve("README.md");

    /** {@code | `dir` | platform | version | origin | `sha256` |} */
    private static final Pattern ROW = Pattern.compile(
            "^\\|\\s*`([a-z0-9_-]+)`\\s*\\|[^|]*\\|[^|]*\\|[^|]*\\|\\s*`([0-9a-f]{64})`\\s*\\|",
            Pattern.MULTILINE);

    private static Map<String, String> recordedChecksums() throws IOException {
        Map<String, String> byDirectory = new HashMap<>();
        Matcher matcher = ROW.matcher(Files.readString(README));
        while (matcher.find()) {
            byDirectory.put(matcher.group(1), matcher.group(2));
        }
        return byDirectory;
    }

    private static List<Path> bundledBinaries() throws IOException {
        try (Stream<Path> files = Files.walk(NATIVE_ROOT)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".dll") || name.endsWith(".dylib")
                                || name.contains(".so");
                    })
                    .sorted()
                    .toList();
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(file));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    void everyBundledBinaryMatchesTheChecksumTheReadmeRecords() throws Exception {
        Map<String, String> recorded = recordedChecksums();
        assertFalse(recorded.isEmpty(), "no checksum rows parsed out of " + README);

        List<String> problems = new ArrayList<>();
        for (Path binary : bundledBinaries()) {
            String directory = binary.getParent().getFileName().toString();
            String expected = recorded.get(directory);
            if (expected == null) {
                problems.add(binary + " is shipped but " + README.getFileName()
                        + " records nothing for " + directory);
                continue;
            }
            String actual = sha256(binary);
            if (!expected.equals(actual)) {
                problems.add(binary + "\n    recorded " + expected + "\n    actual   " + actual);
            }
        }

        assertTrue(problems.isEmpty(), () -> "The bundled binaries no longer match what "
                + README + " says they are. Rebuild the table (the command is in that file)"
                + " as part of whatever changed them:\n  " + String.join("\n  ", problems));
    }

    /**
     * The other direction: a platform the README describes but no longer ships is a
     * platform whose users get "no bundled binary for …" at runtime while the
     * documentation promises one.
     */
    @Test
    void everyPlatformTheReadmeDescribesIsActuallyShipped() throws Exception {
        Map<String, String> recorded = recordedChecksums();
        List<String> shippedDirectories = bundledBinaries().stream()
                .map(p -> p.getParent().getFileName().toString())
                .toList();

        for (String directory : recorded.keySet()) {
            assertTrue(shippedDirectories.contains(directory),
                    () -> README + " records " + directory + " but no binary is shipped there");
        }
    }

    /** One per platform {@link Libgit2Loader#detectPlatform()} can ask for. */
    @Test
    void allFourPlatformsAreCovered() throws Exception {
        // .dylib still counts in bundledBinaries(), so a macOS library coming back would
        // fail this rather than slip in unnoticed: macOS is not a target platform.
        assertEquals(4, bundledBinaries().size(),
                "one shared library per supported platform");
        for (String os : List.of("windows", "linux")) {
            for (String arch : List.of("x86_64", "aarch64")) {
                String directory = os + "-" + arch;
                assertTrue(Files.isDirectory(NATIVE_ROOT.resolve(directory)),
                        directory + " should be bundled");
                assertEquals(libraryFileName(os),
                        Libgit2Loader.libraryFileName(directory),
                        "the loader and the bundle must agree on the file name");
            }
        }
    }

    private static String libraryFileName(String os) {
        return switch (os) {
            case "windows" -> "git2.dll";
            default -> "libgit2.so";
        };
    }
}
