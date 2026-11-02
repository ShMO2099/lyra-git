package com.jfx.git.core.libgit2;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Foreign Function &amp; Memory (FFM) bindings for {@code libgit2}.
 *
 * <h2>Two rules this class exists to enforce</h2>
 *
 * <p><b>1. Every downcall goes through a typed wrapper.</b> {@link MethodHandle#invokeExact}
 * matches the call site's <em>static</em> descriptor against the handle's type, and a bare
 * expression-statement infers a {@code void} return — so calling an {@code int}-returning
 * handle as a statement throws {@link java.lang.invoke.WrongMethodTypeException} at runtime
 * rather than failing to compile. Every native function below is therefore reached only
 * through a small wrapper that assigns the result, which makes the mismatch a compile
 * error instead of a silent runtime fallback.
 *
 * <p><b>2. Every returned pointer is reinterpreted before it is read.</b> Pointers handed
 * back by native code arrive as zero-length segments; dereferencing one throws
 * {@link IndexOutOfBoundsException}. {@link #cString} and the struct readers below call
 * {@link MemorySegment#reinterpret} first.
 */
public final class Libgit2Bindings {

    private static final Logger LOGGER = Logger.getLogger(Libgit2Bindings.class.getName());

    public static final int GIT_OK = 0;
    public static final int GIT_ENOTFOUND = -3;
    /** The repository is owned by another user and not listed in {@code safe.directory}. */
    public static final int GIT_EOWNER = -36;

    /**
     * {@code git_repository_open_ext} flags: none. That means "search upward from the
     * path, stopping at a filesystem boundary" - {@code git rev-parse}'s own default.
     */
    public static final int GIT_REPOSITORY_OPEN_SEARCH = 0;

    // ------------------------------------------------------------------
    // git_status_t flags
    // ------------------------------------------------------------------

    public static final int GIT_STATUS_CURRENT = 0;
    public static final int GIT_STATUS_INDEX_NEW = 1 << 0;
    public static final int GIT_STATUS_INDEX_MODIFIED = 1 << 1;
    public static final int GIT_STATUS_INDEX_DELETED = 1 << 2;
    public static final int GIT_STATUS_INDEX_RENAMED = 1 << 3;
    public static final int GIT_STATUS_INDEX_TYPECHANGE = 1 << 4;

    public static final int GIT_STATUS_WT_NEW = 1 << 7;
    public static final int GIT_STATUS_WT_MODIFIED = 1 << 8;
    public static final int GIT_STATUS_WT_DELETED = 1 << 9;
    public static final int GIT_STATUS_WT_TYPECHANGE = 1 << 10;
    public static final int GIT_STATUS_WT_RENAMED = 1 << 11;
    public static final int GIT_STATUS_WT_UNREADABLE = 1 << 12;

    public static final int GIT_STATUS_IGNORED = 1 << 14;
    public static final int GIT_STATUS_CONFLICTED = 1 << 15;

    /** Any bit meaning "the index differs from HEAD". */
    public static final int STAGED_MASK =
            GIT_STATUS_INDEX_NEW | GIT_STATUS_INDEX_MODIFIED | GIT_STATUS_INDEX_DELETED
                    | GIT_STATUS_INDEX_RENAMED | GIT_STATUS_INDEX_TYPECHANGE;

    /** Any bit meaning "the working tree differs from the index". */
    public static final int UNSTAGED_MASK =
            GIT_STATUS_WT_NEW | GIT_STATUS_WT_MODIFIED | GIT_STATUS_WT_DELETED
                    | GIT_STATUS_WT_TYPECHANGE | GIT_STATUS_WT_RENAMED | GIT_STATUS_WT_UNREADABLE;

    // ------------------------------------------------------------------
    // git_status_options
    // ------------------------------------------------------------------

    public static final int GIT_STATUS_OPTIONS_VERSION = 1;
    public static final int GIT_STATUS_SHOW_INDEX_AND_WORKDIR = 0;

    public static final int GIT_STATUS_OPT_INCLUDE_UNTRACKED = 1 << 0;
    public static final int GIT_STATUS_OPT_EXCLUDE_SUBMODULES = 1 << 3;
    public static final int GIT_STATUS_OPT_RECURSE_UNTRACKED_DIRS = 1 << 4;
    public static final int GIT_STATUS_OPT_RENAMES_HEAD_TO_INDEX = 1 << 7;
    public static final int GIT_STATUS_OPT_RENAMES_INDEX_TO_WORKDIR = 1 << 8;

    /**
     * Byte offsets of the first three {@code git_status_options} fields
     * ({@code version}, {@code show}, {@code flags}). These three have been stable since
     * libgit2 1.0; everything after them is filled in by {@code git_status_options_init}
     * and left untouched, so a newer struct with extra trailing fields still works.
     */
    private static final int OPT_OFFSET_SHOW = 4;
    private static final int OPT_OFFSET_FLAGS = 8;

    /** Generous over-allocation so a newer, larger options struct still fits. */
    private static final long STATUS_OPTIONS_SIZE = 256;

    // ------------------------------------------------------------------
    // git_signature layout: { char *name; char *email; git_time when; }
    // git_time: { int64_t time; int offset; char sign; }
    //
    // These offsets assume 8-byte pointers. Every binary this project bundles is 64-bit,
    // and a 32-bit one would put email at 4 and time at 8 - the reads would succeed and
    // return nonsense rather than fail, so the assumption is asserted at init time
    // (see #assertPointerWidth) instead of being left to be discovered as garbled author
    // names.
    // ------------------------------------------------------------------

    private static final long SIG_OFFSET_NAME = 0;
    private static final long SIG_OFFSET_EMAIL = 8;
    private static final long SIG_OFFSET_TIME = 16;
    private static final long SIG_SIZE = 32;

    /** Big enough for a SHA-256 {@code git_oid} plus its optional type byte. */
    public static final long OID_SIZE = 64;
    /** Hex digits plus NUL, sized for SHA-256. */
    public static final long OID_HEX_BUFFER = 65;

    // ------------------------------------------------------------------
    // Handles
    // ------------------------------------------------------------------

    private static MethodHandle git_libgit2_init;
    private static MethodHandle git_libgit2_shutdown;
    private static MethodHandle git_error_last;

    private static MethodHandle git_repository_open;
    private static MethodHandle git_repository_open_ext;
    private static MethodHandle git_repository_free;
    private static MethodHandle git_repository_workdir;
    private static MethodHandle git_repository_path;
    private static MethodHandle git_repository_index;
    private static MethodHandle git_repository_head;

    private static MethodHandle git_reference_shorthand;
    private static MethodHandle git_reference_free;

    private static MethodHandle git_status_options_init;
    private static MethodHandle git_status_foreach_ext;

    private static MethodHandle git_index_add_bypath;
    private static MethodHandle git_index_remove_bypath;
    private static MethodHandle git_index_write;
    private static MethodHandle git_index_read;
    private static MethodHandle git_index_free;

    private static MethodHandle git_revparse_single;
    private static MethodHandle git_object_free;
    private static MethodHandle git_reset_default;

    private static Linker linker;
    private static boolean ready;

    private Libgit2Bindings() {}

    /**
     * The functions this backend cannot work without. If any is missing the whole binding
     * is refused rather than left half-initialised — the previous version returned
     * "available" as long as {@code git_libgit2_init} resolved, which turned a missing
     * symbol into a {@link NullPointerException} at the first real call.
     */
    private static final List<String> REQUIRED = List.of(
            "git_libgit2_init", "git_error_last",
            "git_repository_open", "git_repository_free", "git_repository_index",
            "git_repository_head", "git_reference_shorthand", "git_reference_free",
            "git_status_options_init", "git_status_foreach_ext",
            "git_index_add_bypath", "git_index_remove_bypath", "git_index_write",
            "git_index_read", "git_index_free", "git_revparse_single", "git_object_free",
            "git_reset_default");

    public static synchronized boolean init(SymbolLookup lookup) {
        if (lookup == null) return false;
        ready = false;
        if (!assertPointerWidth()) return false;

        try {
            linker = Linker.nativeLinker();
            Map<String, MethodHandle> found = new LinkedHashMap<>();

            var ADDR = ValueLayout.ADDRESS;
            var INT = ValueLayout.JAVA_INT;

            git_libgit2_init = bind(found, lookup, "git_libgit2_init", FunctionDescriptor.of(INT));
            git_libgit2_shutdown = bind(found, lookup, "git_libgit2_shutdown", FunctionDescriptor.of(INT));
            git_error_last = bind(found, lookup, "git_error_last", FunctionDescriptor.of(ADDR));

            git_repository_open = bind(found, lookup, "git_repository_open", FunctionDescriptor.of(INT, ADDR, ADDR));
            // int git_repository_open_ext(git_repository **out, const char *path,
            //                             unsigned int flags, const char *ceiling_dirs)
            // Unchanged since 0.x, and present in both bundled versions (1.8.6, 1.9.7).
            git_repository_open_ext = bind(found, lookup, "git_repository_open_ext",
                    FunctionDescriptor.of(INT, ADDR, ADDR, INT, ADDR));
            git_repository_free = bind(found, lookup, "git_repository_free", FunctionDescriptor.ofVoid(ADDR));
            git_repository_workdir = bind(found, lookup, "git_repository_workdir", FunctionDescriptor.of(ADDR, ADDR));
            git_repository_path = bind(found, lookup, "git_repository_path", FunctionDescriptor.of(ADDR, ADDR));
            git_repository_index = bind(found, lookup, "git_repository_index", FunctionDescriptor.of(INT, ADDR, ADDR));
            git_repository_head = bind(found, lookup, "git_repository_head", FunctionDescriptor.of(INT, ADDR, ADDR));

            git_reference_shorthand = bind(found, lookup, "git_reference_shorthand", FunctionDescriptor.of(ADDR, ADDR));
            git_reference_free = bind(found, lookup, "git_reference_free", FunctionDescriptor.ofVoid(ADDR));

            git_status_options_init = bind(found, lookup, "git_status_options_init", FunctionDescriptor.of(INT, ADDR, INT));
            git_status_foreach_ext = bind(found, lookup, "git_status_foreach_ext", FunctionDescriptor.of(INT, ADDR, ADDR, ADDR, ADDR));

            git_index_add_bypath = bind(found, lookup, "git_index_add_bypath", FunctionDescriptor.of(INT, ADDR, ADDR));
            git_index_remove_bypath = bind(found, lookup, "git_index_remove_bypath", FunctionDescriptor.of(INT, ADDR, ADDR));
            git_index_write = bind(found, lookup, "git_index_write", FunctionDescriptor.of(INT, ADDR));
            git_index_read = bind(found, lookup, "git_index_read", FunctionDescriptor.of(INT, ADDR, INT));
            git_index_free = bind(found, lookup, "git_index_free", FunctionDescriptor.ofVoid(ADDR));

            git_revparse_single = bind(found, lookup, "git_revparse_single", FunctionDescriptor.of(INT, ADDR, ADDR, ADDR));
            git_object_free = bind(found, lookup, "git_object_free", FunctionDescriptor.ofVoid(ADDR));
            git_reset_default = bind(found, lookup, "git_reset_default", FunctionDescriptor.of(INT, ADDR, ADDR, ADDR));

            List<String> missing = REQUIRED.stream().filter(n -> found.get(n) == null).toList();
            if (!missing.isEmpty()) {
                LOGGER.warning("libgit2 is missing required symbols, refusing to use it: " + missing);
                return false;
            }

            int count = libgit2Init();
            if (count < 0) {
                LOGGER.warning("git_libgit2_init failed with code " + count);
                return false;
            }
            LOGGER.info("libgit2 initialised (init count: " + count + ")");
            ready = true;
            return true;
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Error initialising libgit2 bindings: " + t.getMessage(), t);
            return false;
        }
    }

    public static boolean isReady() {
        return ready;
    }

    private static MethodHandle bind(Map<String, MethodHandle> found, SymbolLookup lookup,
                                     String name, FunctionDescriptor descriptor) {
        var symbol = lookup.find(name);
        if (symbol.isEmpty()) {
            LOGGER.fine("libgit2 symbol not present: " + name);
            found.put(name, null);
            return null;
        }
        MethodHandle handle = linker.downcallHandle(symbol.get(), descriptor);
        found.put(name, handle);
        return handle;
    }

    // ------------------------------------------------------------------
    // Typed wrappers — the only place invokeExact appears
    // ------------------------------------------------------------------

    public static int libgit2Init() throws Throwable {
        return (int) git_libgit2_init.invokeExact();
    }

    public static int libgit2Shutdown() throws Throwable {
        if (git_libgit2_shutdown == null) return GIT_OK;
        return (int) git_libgit2_shutdown.invokeExact();
    }

    public static int repositoryOpen(MemorySegment repoOut, MemorySegment path) throws Throwable {
        return (int) git_repository_open.invokeExact(repoOut, path);
    }

    /**
     * {@code git_repository_open_ext}: opens the repository containing {@code path},
     * searching upward the way git does, so a subfolder of a work tree opens its
     * repository rather than failing. Falls back to the no-search
     * {@code git_repository_open} on a library without the symbol.
     */
    public static int repositoryOpenExt(MemorySegment repoOut, MemorySegment path, int flags) throws Throwable {
        if (git_repository_open_ext == null) return repositoryOpen(repoOut, path);
        return (int) git_repository_open_ext.invokeExact(repoOut, path, flags, MemorySegment.NULL);
    }

    public static void repositoryFree(MemorySegment repo) throws Throwable {
        git_repository_free.invokeExact(repo);
    }

    public static MemorySegment repositoryWorkdir(MemorySegment repo) throws Throwable {
        if (git_repository_workdir == null) return MemorySegment.NULL;
        return (MemorySegment) git_repository_workdir.invokeExact(repo);
    }

    public static MemorySegment repositoryPath(MemorySegment repo) throws Throwable {
        if (git_repository_path == null) return MemorySegment.NULL;
        return (MemorySegment) git_repository_path.invokeExact(repo);
    }

    /**
     * {@code git_repository_index} — the index <em>of a repository</em>.
     *
     * <p>Not {@code git_index_open}, which takes a {@code const char *index_path} and will
     * happily read a repository pointer as a C string if handed one.
     */
    public static int repositoryIndex(MemorySegment indexOut, MemorySegment repo) throws Throwable {
        return (int) git_repository_index.invokeExact(indexOut, repo);
    }

    public static int repositoryHead(MemorySegment refOut, MemorySegment repo) throws Throwable {
        return (int) git_repository_head.invokeExact(refOut, repo);
    }

    public static MemorySegment referenceShorthand(MemorySegment ref) throws Throwable {
        return (MemorySegment) git_reference_shorthand.invokeExact(ref);
    }

    public static void referenceFree(MemorySegment ref) throws Throwable {
        git_reference_free.invokeExact(ref);
    }

    public static int statusOptionsInit(MemorySegment opts, int version) throws Throwable {
        return (int) git_status_options_init.invokeExact(opts, version);
    }

    public static int statusForeachExt(MemorySegment repo, MemorySegment opts,
                                       MemorySegment callback, MemorySegment payload) throws Throwable {
        return (int) git_status_foreach_ext.invokeExact(repo, opts, callback, payload);
    }

    public static int indexAddByPath(MemorySegment index, MemorySegment path) throws Throwable {
        return (int) git_index_add_bypath.invokeExact(index, path);
    }

    public static int indexRemoveByPath(MemorySegment index, MemorySegment path) throws Throwable {
        return (int) git_index_remove_bypath.invokeExact(index, path);
    }

    public static int indexWrite(MemorySegment index) throws Throwable {
        return (int) git_index_write.invokeExact(index);
    }

    /**
     * {@code git_index_read} — reloads the index from disk.
     *
     * @param force non-zero to reload even when the on-disk index looks unchanged
     */
    public static int indexRead(MemorySegment index, boolean force) throws Throwable {
        return (int) git_index_read.invokeExact(index, force ? 1 : 0);
    }

    public static void indexFree(MemorySegment index) throws Throwable {
        git_index_free.invokeExact(index);
    }

    public static int revparseSingle(MemorySegment objOut, MemorySegment repo, MemorySegment spec) throws Throwable {
        return (int) git_revparse_single.invokeExact(objOut, repo, spec);
    }

    public static void objectFree(MemorySegment obj) throws Throwable {
        git_object_free.invokeExact(obj);
    }

    public static int resetDefault(MemorySegment repo, MemorySegment target, MemorySegment pathspecs) throws Throwable {
        return (int) git_reset_default.invokeExact(repo, target, pathspecs);
    }

    // ------------------------------------------------------------------
    // Struct and string helpers
    // ------------------------------------------------------------------

    /**
     * Reads a NUL-terminated C string from a pointer returned by native code.
     *
     * <p>Such pointers arrive as zero-length segments, so they must be reinterpreted
     * before any read — otherwise every access throws {@link IndexOutOfBoundsException}
     * and, if the caller swallows {@code Throwable}, the failure looks like a normal
     * negative result.
     */
    public static String cString(MemorySegment pointer) {
        if (pointer == null || pointer.address() == 0) return null;
        return pointer.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /** Fills {@code opts} with defaults and applies {@code flags}. */
    public static MemorySegment newStatusOptions(Arena arena, int flags) throws Throwable {
        MemorySegment opts = arena.allocate(STATUS_OPTIONS_SIZE);
        int rc = statusOptionsInit(opts, GIT_STATUS_OPTIONS_VERSION);
        if (rc != GIT_OK) {
            throw new IllegalStateException("git_status_options_init failed: " + getLastError());
        }
        opts.set(ValueLayout.JAVA_INT, OPT_OFFSET_SHOW, GIT_STATUS_SHOW_INDEX_AND_WORKDIR);
        opts.set(ValueLayout.JAVA_INT, OPT_OFFSET_FLAGS, flags);
        return opts;
    }

    /** The callback shape of {@code git_status_cb}. */
    @FunctionalInterface
    public interface StatusCallback {
        int onEntry(String path, int statusFlags);
    }

    /**
     * Wraps {@code callback} in an upcall stub valid for the lifetime of {@code arena}.
     *
     * <p>The body is guarded because an exception unwinding into native code terminates
     * the VM; a failure here degrades to "stop iterating" instead.
     */
    public static MemorySegment statusCallbackStub(Arena arena, StatusCallback callback) throws Throwable {
        StatusCallback guarded = (path, flags) -> {
            try {
                return callback.onEntry(path, flags);
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "libgit2 status callback failed", t);
                return -1;
            }
        };

        MethodHandle target = MethodHandles.lookup()
                .findStatic(Libgit2Bindings.class, "invokeStatusCallback",
                        MethodType.methodType(int.class, StatusCallback.class,
                                MemorySegment.class, int.class, MemorySegment.class))
                .bindTo(guarded);

        return linker.upcallStub(target,
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                arena);
    }

    /** Upcall trampoline; referenced reflectively by {@link #statusCallbackStub}. */
    @SuppressWarnings("unused")
    private static int invokeStatusCallback(StatusCallback callback,
                                            MemorySegment path, int flags, MemorySegment payload) {
        String p = cString(path);
        return p == null ? 0 : callback.onEntry(p, flags);
    }

    /**
     * Refuses to bind on a platform whose pointers are not 8 bytes wide.
     *
     * <p>The hand-written struct offsets in this class - {@code git_signature} above,
     * {@code git_error} in {@link #getLastError()} - assume a 64-bit ABI. On a 32-bit one
     * every read would still succeed and quietly return the wrong field, which is the
     * kind of failure that surfaces as garbled author names weeks later. Every binary
     * this project bundles is 64-bit, so this is a fence around an assumption rather than
     * a case that has to work.
     */
    private static boolean assertPointerWidth() {
        long pointerBytes = ValueLayout.ADDRESS.byteSize();
        if (pointerBytes == 8) return true;
        LOGGER.warning("libgit2 FFM bindings need 8-byte pointers; this platform has "
                + pointerBytes + ". Falling back to the other engines.");
        return false;
    }

    /** Author name, email and epoch-seconds from a {@code git_signature *}. */
    public static Signature readSignature(MemorySegment signaturePointer) {
        if (signaturePointer == null || signaturePointer.address() == 0) return null;
        MemorySegment sig = signaturePointer.reinterpret(SIG_SIZE);
        String name = cString(sig.get(ValueLayout.ADDRESS, SIG_OFFSET_NAME));
        String email = cString(sig.get(ValueLayout.ADDRESS, SIG_OFFSET_EMAIL));
        long when = sig.get(ValueLayout.JAVA_LONG, SIG_OFFSET_TIME);
        return new Signature(name == null ? "" : name, email == null ? "" : email, when);
    }

    public record Signature(String name, String email, long epochSeconds) {}

    /**
     * The last libgit2 error message.
     *
     * <p>{@code git_error_last} returns a {@code git_error *} whose first field is the
     * message pointer. Both pointers need reinterpreting before they can be read.
     */
    public static String getLastError() {
        if (git_error_last == null) return "libgit2 error reporting unavailable";
        try {
            MemorySegment err = (MemorySegment) git_error_last.invokeExact();
            if (err == null || err.address() == 0) return "no libgit2 error recorded";

            // struct git_error { char *message; int klass; }
            MemorySegment struct = err.reinterpret(16);
            String message = cString(struct.get(ValueLayout.ADDRESS, 0));
            return message == null ? "no libgit2 error message" : message;
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "Could not read libgit2 error", t);
            return "could not read libgit2 error: " + t;
        }
    }
}
