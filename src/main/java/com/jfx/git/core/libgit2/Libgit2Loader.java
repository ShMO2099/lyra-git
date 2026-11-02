package com.jfx.git.core.libgit2;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Extracts and loads the bundled native {@code libgit2} for the running platform.
 */
public final class Libgit2Loader {

    private static final Logger LOGGER = Logger.getLogger(Libgit2Loader.class.getName());

    private static boolean initialized;
    private static boolean available;
    private static SymbolLookup symbolLookup;
    private static Arena globalArena;
    private static String unavailableReason = "not initialised";

    private Libgit2Loader() {}

    public static synchronized boolean init() {
        if (initialized) return available;
        initialized = true;

        try {
            String platform = detectPlatform();
            String libName = libraryFileName(platform);
            String resourcePath = "/native/" + platform + "/" + libName;

            Path libFile = extractLibrary(resourcePath, platform, libName);
            if (libFile == null) {
                unavailableReason = "no bundled binary for " + platform + " at " + resourcePath;
                LOGGER.warning("libgit2 unavailable: " + unavailableReason);
                return false;
            }

            // A shared arena keeps the library loaded for the life of the JVM; the
            // symbol lookup and every downcall handle derived from it stay valid.
            globalArena = Arena.ofShared();
            symbolLookup = SymbolLookup.libraryLookup(libFile, globalArena);
            LOGGER.info("Loaded bundled libgit2 from " + libFile);

            available = Libgit2Bindings.init(symbolLookup);
            if (available) {
                LOGGER.info("libgit2 FFM backend initialised");
            } else {
                unavailableReason = "bindings could not be initialised (see earlier warnings)";
                closeArena();
            }
        } catch (IllegalCallerException e) {
            unavailableReason = "native access is not enabled for this module; "
                    + "add --enable-native-access=com.jfx.git.lyra";
            LOGGER.warning("libgit2 unavailable: " + unavailableReason);
            available = false;
            closeArena();
        } catch (Throwable t) {
            unavailableReason = t.getMessage() == null ? t.toString() : t.getMessage();
            LOGGER.log(Level.WARNING, "Failed to load libgit2: " + unavailableReason, t);
            available = false;
            closeArena();
        }
        return available;
    }

    private static void closeArena() {
        if (globalArena != null) {
            try {
                globalArena.close();
            } catch (Throwable t) {
                LOGGER.log(Level.FINE, "Could not close libgit2 arena", t);
            }
            globalArena = null;
        }
        symbolLookup = null;
    }

    public static boolean isAvailable() {
        if (!initialized) init();
        return available;
    }

    /** Why {@link #isAvailable()} is false, for the preferences page to show. */
    public static String unavailableReason() {
        if (!initialized) init();
        return available ? "" : unavailableReason;
    }

    public static SymbolLookup getSymbolLookup() {
        if (!initialized) init();
        return symbolLookup;
    }

    static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        // Named, not defaulted: every non-Windows system used to read as Linux, so a Mac
        // extracted the bundled Linux .so and failed to load it. There is no macOS build
        // in the bundle, so a Mac now looks for one, finds none, and says so plainly.
        String osPart = os.contains("win") ? "windows"
                : os.contains("mac") || os.contains("darwin") ? "macos"
                : "linux";
        String archPart = (arch.contains("aarch64") || arch.contains("arm64")) ? "aarch64" : "x86_64";
        return osPart + "-" + archPart;
    }

    static String libraryFileName(String platform) {
        if (platform.startsWith("windows")) return "git2.dll";
        return "libgit2.so";
    }

    /**
     * Copies the bundled library into a cache directory keyed by its content and returns
     * its path.
     *
     * <p>The directory name carries a digest of the bundled bytes, so a different build
     * of the library is a different path rather than a candidate to overwrite. Keying the
     * cache on file size alone meant two builds that happened to be the same size aliased
     * to one file and the stale one was reused for good.
     *
     * <p>Content-addressing also settles the Windows case this cache exists for: a second
     * running instance holds the DLL mapped, so an unconditional overwrite fails with
     * {@code AccessDeniedException} and used to make libgit2 silently unavailable for
     * every instance after the first. A new build now lands beside the mapped one instead
     * of trying to replace it, and an unchanged one is simply reused. The write still goes
     * to a temporary file and is moved into place, so a crash mid-copy cannot leave a
     * truncated library behind.
     */
    private static Path extractLibrary(String resourcePath, String platform, String libName) {
        try (InputStream in = Libgit2Loader.class.getResourceAsStream(resourcePath)) {
            if (in == null) return null;

            Fingerprint bundled = fingerprint(resourcePath);
            if (bundled == null) return null;

            Path cacheDir = Path.of(System.getProperty("user.home"), ".lyra", "native",
                    platform, bundled.digest());
            Files.createDirectories(cacheDir);
            Path target = cacheDir.resolve(libName);

            if (Files.isRegularFile(target) && Files.size(target) == bundled.size()) {
                LOGGER.fine("Reusing cached libgit2 at " + target);
                return target;
            }

            Path temp = Files.createTempFile(cacheDir, libName + ".", ".tmp");
            try {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                Files.deleteIfExists(temp);
                // Losing the race with another instance is fine: the directory is named
                // after the content, so whatever won holds the same bytes.
                if (Files.isRegularFile(target) && Files.size(target) == bundled.size()) {
                    return target;
                }
                throw moveFailed;
            }
            LOGGER.fine("Extracted libgit2 to " + target);
            return target;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not extract " + resourcePath, e);
            return null;
        }
    }

    /** Size and content digest of a bundled resource. */
    private record Fingerprint(long size, String digest) {}

    /**
     * Measures and digests a bundled resource in one pass, or {@code null} when it cannot
     * be read. The digest is truncated to 16 hex characters: this names a cache directory,
     * not a security boundary, and 64 bits is far past accidental collision between two
     * builds of one library.
     */
    private static Fingerprint fingerprint(String resourcePath) {
        try (InputStream in = Libgit2Loader.class.getResourceAsStream(resourcePath)) {
            if (in == null) return null;
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            long total = 0;
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) {
                sha256.update(buffer, 0, read);
                total += read;
            }
            StringBuilder hex = new StringBuilder();
            byte[] digest = sha256.digest();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return new Fingerprint(total, hex.toString());
        } catch (IOException | NoSuchAlgorithmException e) {
            LOGGER.log(Level.FINE, "Could not fingerprint " + resourcePath, e);
            return null;
        }
    }
}
