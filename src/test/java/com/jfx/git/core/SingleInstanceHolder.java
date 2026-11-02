package com.jfx.git.core;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Started by {@link SingleInstanceTest} as a separate process: claims the lock file it is
 * given, prints {@code claimed} or {@code refused}, and holds the claim until it is killed
 * - so the test can see what a lock held by another process does, and what a crash leaves.
 */
public final class SingleInstanceHolder {

    private SingleInstanceHolder() {
    }

    public static void main(String[] args) throws Exception {
        Optional<SingleInstance> claim = SingleInstance.claim(Path.of(args[0]));
        System.out.println(claim.isPresent() ? "claimed" : "refused");
        System.out.flush();
        // Long enough for any test; the test kills it well before.
        Thread.sleep(120_000);
    }
}
