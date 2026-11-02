package com.jfx.git.ui.help;

import com.jfx.git.ui.dialogs.Dialogs;

public class About {

    public static void showAboutDialog() {
        // From the jar's manifest. It was a constant here that said "1.0" whatever ran.
        String version = com.jfx.git.core.AppVersion.current();
        String versionLine = com.jfx.git.core.AppVersion.isComparable(version) ? "Version " + version
                : "A " + version;
        // What a bug report needs, in a well with Copy rather than run together in prose.
        String environment = "Java " + System.getProperty("java.version") + "\n"
                + "JavaFX " + System.getProperty("javafx.runtime.version", "unknown") + "\n"
                // Whether SSH remotes will work is invisible until a push fails, so
                // say it here rather than leaving the user to find out the hard way.
                + com.jfx.git.core.SshSessionManager.status();

        Dialogs.info("About")
                .title("Lyra")
                .subtitle(versionLine)
                // It said "provided by Eclipse JGit", from before there were three engines.
                .message("A JavaFX Git client with commit tree visualisation. Git operations run"
                        + " on native git, libgit2 or Eclipse JGit, whichever Settings > Git Engine"
                        + " picks.")
                .code("ENVIRONMENT", environment)
                .answer();
    }
}
