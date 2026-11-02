package com.jfx.git.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R1: where Push sends a branch, by the rules {@code git push} follows. The engines'
 * end-to-end behaviour is pinned in {@link RemoteConformanceTest}; this covers the
 * precedence table on its own, without a repository.
 */
class PushTargetTest {

    private final Map<String, String> config = new HashMap<>();

    private PushTarget resolve(String branch, String explicit, String... remotes) {
        return PushTarget.resolve(branch, explicit, List.of(remotes),
                (section, sub, name) -> config.get(section + "." + (sub == null ? "" : sub + ".") + name));
    }

    @Test
    void anUnconfiguredBranchIsPublishedToOrigin() {
        PushTarget target = resolve("feature", null, "origin", "upstream");

        assertEquals(new PushTarget("origin", "feature", true), target);
    }

    @Test
    void theOnlyRemoteIsUsedWhateverItIsCalled() {
        assertEquals("github", resolve("main", null, "github").remote());
    }

    @Test
    void precedenceIsPushRemoteThenPushDefaultThenTheUpstream() {
        config.put("branch.feature.remote", "upstream");
        config.put("branch.feature.merge", "refs/heads/main");
        assertEquals("upstream", resolve("feature", null, "origin", "upstream").remote());

        config.put("remote.pushDefault", "fork");
        assertEquals("fork", resolve("feature", null, "origin", "upstream", "fork").remote());

        config.put("branch.feature.pushRemote", "mine");
        assertEquals("mine", resolve("feature", null, "origin", "upstream", "fork", "mine").remote());

        assertEquals("explicit", resolve("feature", "explicit", "origin").remote(),
                "a remote the caller names wins over configuration");
    }

    @Test
    void aBranchWithAnUpstreamIsNotRepublished() {
        config.put("branch.feature.remote", "upstream");
        config.put("branch.feature.merge", "refs/heads/main");
        config.put("remote.pushDefault", "origin");

        PushTarget target = resolve("feature", null, "origin", "upstream");

        assertFalse(target.publish(), "-u would re-point the configured upstream");
        assertEquals("feature", target.remoteBranch(), "a triangular push keeps the branch's own name");
    }

    @Test
    void pushDefaultUpstreamWritesTheTrackedBranch() {
        config.put("branch.fix.remote", "origin");
        config.put("branch.fix.merge", "refs/heads/main");
        config.put("push.default", "upstream");

        PushTarget target = resolve("fix", null, "origin");

        assertEquals("main", target.remoteBranch());
        assertEquals("refs/remotes/origin/main", target.trackingRef(), "the lease is taken here");
    }

    /**
     * Under git's default {@code simple}, a branch made from {@code origin/main} must not
     * push to main by default; it goes to its own name instead.
     */
    @Test
    void simpleNeverPushesToADifferentlyNamedUpstream() {
        config.put("branch.fix.remote", "origin");
        config.put("branch.fix.merge", "refs/heads/main");

        assertEquals("fix", resolve("fix", null, "origin").remoteBranch());
    }

    @Test
    void aBranchTrackingALocalBranchIsPushedToOrigin() {
        config.put("branch.fix.remote", ".");
        config.put("branch.fix.merge", "refs/heads/main");

        assertEquals("origin", resolve("fix", null, "origin", "other").remote());
    }
}
