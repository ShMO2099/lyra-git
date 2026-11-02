package com.jfx.git.core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Windows DPAPI ({@code CryptProtectData} / {@code CryptUnprotectData} in crypt32.dll),
 * called in-process through the Foreign Function and Memory API.
 *
 * <h2>Why not PowerShell any more</h2>
 * {@link TokenStore} used to reach DPAPI through PowerShell's
 * {@code ConvertFrom-SecureString}: a probe of two PowerShell launches on every start,
 * and one more launch for each remembered token restored - each of them hundreds of
 * milliseconds of process start, and seconds on a machine whose antivirus scans
 * PowerShell as it loads. The same two calls made directly take microseconds.
 *
 * <h2>The stored format is PowerShell's</h2>
 * {@code ConvertFrom-SecureString} without a key is {@code ProtectedData.Protect} over the
 * string's UTF-16LE bytes, for the current user, with no entropy and the UI forbidden,
 * written out as lowercase hex. This produces and reads exactly that, so a token
 * remembered by an older version decrypts here, and one written here still decrypts
 * through the PowerShell path that remains as the fallback.
 */
final class WindowsDpapi {

    private static final Logger LOGGER = Logger.getLogger(WindowsDpapi.class.getName());

    /** {@code CRYPTPROTECT_UI_FORBIDDEN}: fail rather than show a prompt, as .NET passes it. */
    private static final int UI_FORBIDDEN = 0x1;

    /** {@code DATA_BLOB { DWORD cbData; BYTE *pbData; }}, padded to the pointer's alignment. */
    private static final StructLayout DATA_BLOB = ValueLayout.ADDRESS.byteSize() == 8
            ? MemoryLayout.structLayout(ValueLayout.JAVA_INT, MemoryLayout.paddingLayout(4), ValueLayout.ADDRESS)
            : MemoryLayout.structLayout(ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
    private static final long DATA_OFFSET = DATA_BLOB.byteSize() - ValueLayout.ADDRESS.byteSize();

    private static final HexFormat HEX = HexFormat.of();

    private static volatile Bindings bindings;
    private static volatile boolean tried;

    private record Bindings(MethodHandle protect, MethodHandle unprotect, MethodHandle localFree) {
    }

    private WindowsDpapi() {
    }

    /**
     * Whether DPAPI can be called directly here, binding it on the first call. False off
     * Windows, and where native access is not granted or the symbols do not resolve.
     */
    static boolean isAvailable() {
        return bindings() != null;
    }

    private static Bindings bindings() {
        if (!tried) {
            synchronized (WindowsDpapi.class) {
                if (!tried) {
                    bindings = bind();
                    tried = true;
                }
            }
        }
        return bindings;
    }

    private static Bindings bind() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return null;
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup crypt32 = SymbolLookup.libraryLookup("crypt32", Arena.global());
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            var ADDR = ValueLayout.ADDRESS;
            var INT = ValueLayout.JAVA_INT;
            // BOOL CryptProtectData(DATA_BLOB *in, LPCWSTR description, DATA_BLOB *entropy,
            //                       PVOID reserved, PROMPTSTRUCT *prompt, DWORD flags, DATA_BLOB *out)
            FunctionDescriptor sevenArgs = FunctionDescriptor.of(INT, ADDR, ADDR, ADDR, ADDR, ADDR, INT, ADDR);
            MethodHandle protect = linker.downcallHandle(crypt32.findOrThrow("CryptProtectData"), sevenArgs);
            // CryptUnprotectData has the same shape; its description is an out-parameter.
            MethodHandle unprotect = linker.downcallHandle(crypt32.findOrThrow("CryptUnprotectData"), sevenArgs);
            // The output blob is allocated by DPAPI with LocalAlloc and has to go back to LocalFree.
            MethodHandle localFree = linker.downcallHandle(kernel32.findOrThrow("LocalFree"),
                    FunctionDescriptor.of(ADDR, ADDR));
            return new Bindings(protect, unprotect, localFree);
        } catch (Throwable t) {
            // IllegalCallerException when native access is not enabled for this module,
            // IllegalArgumentException when a library or symbol is missing.
            LOGGER.log(Level.FINE, "DPAPI cannot be called directly; PowerShell will be used", t);
            return null;
        }
    }

    /**
     * Encrypts {@code plain} for the current Windows user, in {@code ConvertFrom-SecureString}'s
     * format; null when DPAPI refused.
     */
    static String protect(String plain) {
        Bindings b = bindings();
        if (b == null) return null;
        byte[] out = call(b, b.protect(), plain.getBytes(StandardCharsets.UTF_16LE), true);
        return out == null ? null : HEX.formatHex(out);
    }

    /** The inverse of {@link #protect}; null when the text is not DPAPI hex for this user. */
    static String unprotect(String cipherHex) {
        Bindings b = bindings();
        if (b == null) return null;
        byte[] cipher;
        try {
            cipher = HEX.parseHex(cipherHex.trim());
        } catch (IllegalArgumentException notHex) {
            return null;
        }
        byte[] out = call(b, b.unprotect(), cipher, false);
        return out == null ? null : new String(out, StandardCharsets.UTF_16LE);
    }

    private static byte[] call(Bindings b, MethodHandle function, byte[] input, boolean protecting) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(Math.max(1, input.length));
            MemorySegment.copy(input, 0, data, ValueLayout.JAVA_BYTE, 0, input.length);
            if (protecting) {
                // The caller's copy of the plaintext; the native one is wiped below.
                java.util.Arrays.fill(input, (byte) 0);
            }
            try {
                return invoke(b, function, arena, data, input.length, protecting);
            } finally {
                if (protecting) {
                    data.fill((byte) 0);
                }
            }
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "DPAPI call failed", t);
            return null;
        }
    }

    private static byte[] invoke(Bindings b, MethodHandle function, Arena arena, MemorySegment data,
                                 int length, boolean protecting) throws Throwable {
        MemorySegment in = arena.allocate(DATA_BLOB);
        in.set(ValueLayout.JAVA_INT, 0, length);
        in.set(ValueLayout.ADDRESS, DATA_OFFSET, data);
        MemorySegment out = arena.allocate(DATA_BLOB);
        // .NET passes an empty description when protecting; when unprotecting the
        // argument is where the description would be written back, and NULL declines it.
        MemorySegment description = protecting ? arena.allocate(2) : MemorySegment.NULL;
        int ok = (int) function.invokeExact(in, description, MemorySegment.NULL, MemorySegment.NULL,
                MemorySegment.NULL, UI_FORBIDDEN, out);
        if (ok == 0) {
            return null;
        }
        int produced = out.get(ValueLayout.JAVA_INT, 0);
        MemorySegment result = out.get(ValueLayout.ADDRESS, DATA_OFFSET).reinterpret(produced);
        try {
            return result.toArray(ValueLayout.JAVA_BYTE);
        } finally {
            if (!protecting) {
                result.fill((byte) 0);   // decrypted plaintext, about to be freed
            }
            MemorySegment ignored = (MemorySegment) b.localFree().invokeExact(result);
        }
    }
}
