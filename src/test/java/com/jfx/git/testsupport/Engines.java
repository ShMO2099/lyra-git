package com.jfx.git.testsupport;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitService;
import com.jfx.git.core.HybridGitBackend;
import com.jfx.git.core.NativeGitBackend;
import com.jfx.git.core.NativeGitRunner;
import com.jfx.git.core.SwitchableGitBackend;
import com.jfx.git.core.libgit2.Libgit2Backend;
import com.jfx.git.core.libgit2.Libgit2Loader;

import org.junit.jupiter.params.provider.Arguments;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The engine matrix: every {@link GitBackend} this machine can provide, for a test that
 * runs one scenario against each.
 *
 * <p>JGit is always there; the CLI and libgit2 are gated on a {@code git} executable and
 * a loadable bundled {@code libgit2}, so a suite checks what it can rather than failing for
 * something the project does not ship. Hybrid and Switchable(Hybrid) are what users get -
 * Hybrid is Auto's choice wherever git is installed, and the UI holds it through
 * Switchable - and the leaf engines alone never exercise the routing between them: a read
 * served by one engine after a write by the other.
 *
 * <p>Each test class used to keep its own list, and most kept two or three of the five,
 * so an engine could misbehave in a scenario only the tests that left it out covered.
 * Use it as the parameter source of a {@code (String name, Supplier<GitBackend> engine)}
 * test: {@code @MethodSource("com.jfx.git.testsupport.Engines#all")}, or from a class's
 * own {@code engines()}. An engine that genuinely cannot do something is asserted by
 * {@code name} in the test, never skipped by returning early.
 */
public final class Engines {

    public static final String JGIT = "JGit";
    public static final String CLI = "Native CLI";
    public static final String LIBGIT2 = "libgit2";
    public static final String HYBRID = "Hybrid";
    public static final String SWITCHABLE = "Switchable(Hybrid)";

    private Engines() {
    }

    /** {@code (name, Supplier<GitBackend>)} for every engine available here. */
    public static Stream<Arguments> all() {
        List<Arguments> engines = new ArrayList<>();
        engines.add(Arguments.of(JGIT, (Supplier<GitBackend>) GitService::new));
        if (NativeGitRunner.isGitInstalled()) {
            engines.add(Arguments.of(CLI, (Supplier<GitBackend>) NativeGitBackend::new));
        }
        if (Libgit2Loader.isAvailable()) {
            engines.add(Arguments.of(LIBGIT2, (Supplier<GitBackend>) Libgit2Backend::new));
        }
        if (NativeGitRunner.isGitInstalled()) {
            engines.add(Arguments.of(HYBRID, (Supplier<GitBackend>) HybridGitBackend::new));
            engines.add(Arguments.of(SWITCHABLE,
                    (Supplier<GitBackend>) () -> new SwitchableGitBackend(new HybridGitBackend())));
        }
        return engines.stream();
    }
}
