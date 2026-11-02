package com.jfx.git.ui.syntax;

import org.eclipse.tm4e.core.internal.oniguruma.OnigRegExp;
import org.eclipse.tm4e.core.internal.oniguruma.OnigResult;
import org.eclipse.tm4e.core.internal.oniguruma.OnigString;
import org.joni.Regex;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The patched TM4E in third-party/tm4e compiles each distinct pattern once.
 *
 * <p>Upstream compiles a pattern again for every rule list that includes it: one
 * TypeScript pattern 75 times, and the copies were 99 MB of a 138 MB live heap once files
 * in seven languages had been opened. This is the test that fails if an unpatched TM4E
 * comes back with a version bump.
 */
class TextMatePatternSharingTest {

    @Test
    void aPatternIsCompiledOnceHoweverManyRuleListsIncludeIt() throws Exception {
        String pattern = "\\b(?:const|let|var)\\s+([$_[:alpha:]][$_[:alnum:]]*)";

        OnigRegExp first = new OnigRegExp(pattern);
        OnigRegExp second = new OnigRegExp(pattern);

        assertSame(compiled(first), compiled(second));
    }

    @Test
    void caseFoldingIsPartOfWhatIsShared() throws Exception {
        OnigRegExp exact = new OnigRegExp("select");
        OnigRegExp folded = new OnigRegExp("select", true);

        assertNotSame(compiled(exact), compiled(folded));
        assertNull(exact.search(OnigString.of("SELECT 1"), 0));
        assertNotNull(folded.search(OnigString.of("SELECT 1"), 0));
    }

    /**
     * A grammar the registry evicts must take the patterns only it used with it, or the
     * shared cache would become the leak the per-language registries were made to avoid.
     */
    @Test
    void aPatternNoLoadedGrammarUsesIsFreed() throws Exception {
        WeakReference<Regex> regex = new WeakReference<>(
                compiled(new OnigRegExp("only-here-" + UUID.randomUUID())));

        for (int i = 0; i < 20 && regex.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }

        assertNull(regex.get(), "nothing uses the pattern any more, so its compiled form must go");
    }

    /** Highlighting runs off the FX thread, and two files of one language can be lexed at once. */
    @Test
    void oneCompiledPatternSearchesCorrectlyFromManyThreads() throws Exception {
        String pattern = "([a-z]+)@([a-z]+)\\.com";
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                String user = "abcdefgh".substring(0, t + 1);
                runs.add(pool.submit(() -> {
                    OnigRegExp regExp = new OnigRegExp(pattern);
                    for (int i = 0; i < 2_000; i++) {
                        String text = " ".repeat(i % 7) + user + "@host.com";
                        OnigResult result = regExp.search(OnigString.of(text), 0);
                        assertNotNull(result, text);
                        assertEquals(i % 7, result.locationAt(1), text);
                        assertEquals(user.length(), result.lengthAt(1), text);
                    }
                    return null;
                }));
            }
            for (Future<?> run : runs) {
                run.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Regex compiled(OnigRegExp regExp) throws ReflectiveOperationException {
        Field field = OnigRegExp.class.getDeclaredField("regex");
        field.setAccessible(true);
        return (Regex) field.get(regExp);
    }
}
