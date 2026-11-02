package com.jfx.git.core;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Reading {@code git config --list -z}, and deciding which {@code includeIf} could apply (E3). */
class EffectiveConfigTest {

    @Test
    void parsesNulSeparatedEntriesAndLetsTheLastValueWin() {
        byte[] output = ("user.name\nPersonal\0user.email\nme@home\0commit.gpgsign\0"
                + "user.name\nWork Self\0includeif.onbranch:Main.path\nx\0")
                .getBytes(StandardCharsets.UTF_8);

        Map<String, List<String>> parsed = EffectiveConfig.parse(output);

        assertEquals(List.of("Personal", "Work Self"), parsed.get("user.name"));
        assertNull(parsed.get("commit.gpgsign").get(0), "a key with no '=' has no value");
        assertTrue(parsed.containsKey("includeif.onbranch:Main.path"), "subsection case is kept");
    }

    @Test
    void readsGitBooleans() {
        assertTrue(EffectiveConfig.parseBoolean("yes", false));
        assertTrue(EffectiveConfig.parseBoolean("On", false));
        assertTrue(EffectiveConfig.parseBoolean("1", false));
        assertFalse(EffectiveConfig.parseBoolean("", true));
        assertFalse(EffectiveConfig.parseBoolean("off", true));
        assertTrue(EffectiveConfig.parseBoolean("garbage", true));
    }

    @Test
    void gitdirConditionsAreMatchedAgainstTheRepository() {
        File gitDir = new File(System.getProperty("java.io.tmpdir"), "work/project/.git").getAbsoluteFile();
        String work = new File(System.getProperty("java.io.tmpdir"), "work").getAbsolutePath().replace('\\', '/');

        assertTrue(EffectiveConfig.couldApply("gitdir:" + work + "/", gitDir));
        assertFalse(EffectiveConfig.couldApply("gitdir:" + work + "-other/", gitDir));
        assertTrue(EffectiveConfig.couldApply("gitdir:project/.git", gitDir), "relative patterns match anywhere");
        assertTrue(EffectiveConfig.couldApply("gitdir/i:" + work.toUpperCase() + "/", gitDir));
        assertTrue(EffectiveConfig.couldApply("onbranch:main", gitDir), "not evaluable here, so it could apply");
        assertTrue(EffectiveConfig.couldApply("hasconfig:remote.*.url:https://corp/**", gitDir));
    }
}
