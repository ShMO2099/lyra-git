package com.jfx.git.core;

import com.jfx.git.model.Profile;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named git identities the user can switch a repository between, and which one each
 * repository is currently on.
 *
 * <p>Commercial clients gate this behind a paid plan; nothing here does, so it exists purely as
 * "the global identity, but you can have more than one and pick which repository gets
 * which" - the global identity (Settings, Profiles) remains the single default identity a
 * repository starts on until a profile is explicitly applied to it.
 *
 * <h2>Storage shape</h2>
 * Mirrors {@link TabSession}'s alias map exactly, for the same reason: {@link Settings}
 * is a {@code Properties} file, so records are newline-separated and their fields
 * tab-separated rather than folding a path or a name into the key, which would be
 * unreadable in the file and fragile the moment either contains a character
 * {@code Properties} treats specially. A record that does not parse is dropped rather
 * than repaired - the cost of losing one profile is recreating it, the cost of guessing
 * at a half-written one is applying the wrong identity to a repository.
 */
public final class ProfileStore {

    private static final String KEY_PROFILES = "profiles.list";
    private static final String KEY_ACTIVE = "profiles.activeByRepo";

    private static final String RECORD = "\n";
    private static final String FIELD = "\t";

    private ProfileStore() {
    }

    // ------------------------------------------------------------------
    // Profiles themselves
    // ------------------------------------------------------------------

    /** Every stored profile, in the order they were created. */
    public static List<Profile> profiles() {
        List<Profile> profiles = new ArrayList<>();
        for (String record : split(Settings.get(KEY_PROFILES, ""))) {
            Profile parsed = parseProfile(record);
            if (parsed != null) {
                profiles.add(parsed);
            }
        }
        return List.copyOf(profiles);
    }

    /** The stored profile named {@code name}, or {@code null} if none matches. */
    public static Profile profile(String name) {
        if (name == null) return null;
        for (Profile profile : profiles()) {
            if (profile.name().equals(name)) return profile;
        }
        return null;
    }

    /**
     * Adds a new profile or replaces the stored one with the same name.
     *
     * <p>Replacing by name rather than requiring the caller to delete-then-add keeps
     * editing a profile from silently reordering it in the switcher menu - it stays
     * wherever it was, updated in place.
     */
    public static void save(Profile profile) {
        if (profile == null || profile.name() == null || profile.name().isBlank()
                || containsSeparator(profile.name()) || containsSeparator(profile.sshKeyPath())) {
            return;
        }
        List<Profile> all = new ArrayList<>(profiles());
        int existing = indexOfByName(all, profile.name());
        if (existing >= 0) {
            all.set(existing, profile);
        } else {
            all.add(profile);
        }
        writeProfiles(all);
    }

    /**
     * Removes the profile named {@code name}. Repositories it was active on keep
     * remembering that name - see {@link #activeProfileName(String)} - so the switcher
     * can show "was: X (deleted)" rather than silently forgetting what was chosen,
     * should the same name be recreated later.
     */
    public static void delete(String name) {
        if (name == null) return;
        List<Profile> all = new ArrayList<>(profiles());
        all.removeIf(p -> p.name().equals(name));
        writeProfiles(all);
    }

    private static void writeProfiles(List<Profile> profiles) {
        if (profiles.isEmpty()) {
            Settings.remove(KEY_PROFILES);
            return;
        }
        List<String> records = new ArrayList<>(profiles.size());
        for (Profile p : profiles) {
            records.add(String.join(FIELD,
                    p.name(),
                    nullToEmpty(p.gitName()),
                    nullToEmpty(p.gitEmail()),
                    nullToEmpty(p.signingKey()),
                    Boolean.toString(p.gpgSign()),
                    nullToEmpty(p.sshKeyPath()).trim()));
        }
        Settings.put(KEY_PROFILES, String.join(RECORD, records));
    }

    private static Profile parseProfile(String record) {
        String[] fields = record.split(FIELD, -1);
        // name, gitName, gitEmail, signingKey, gpgSign - a record missing any is corrupt.
        // A sixth field, the SSH key, came later: a record written before it has five
        // and names no key, which is what it meant then.
        if ((fields.length != 5 && fields.length != 6) || fields[0].isBlank()) {
            return null;
        }
        return new Profile(fields[0], fields[1], fields[2], fields[3], Boolean.parseBoolean(fields[4]),
                fields.length == 6 ? fields[5] : "");
    }

    private static int indexOfByName(List<Profile> profiles, String name) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).name().equals(name)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Which profile is active on which repository
    // ------------------------------------------------------------------

    /** The profile name active on the repository at {@code path}, or {@code null} if none was chosen. */
    public static String activeProfileName(String path) {
        return path == null ? null : activeByRepo().get(path);
    }

    /** Remembers that {@code profileName} is active on the repository at {@code path}. */
    public static void setActiveProfileName(String path, String profileName) {
        if (path == null || path.isBlank() || containsSeparator(path)) {
            return;
        }
        Map<String, String> all = new LinkedHashMap<>(activeByRepo());
        if (profileName == null || profileName.isBlank()) {
            all.remove(path);
        } else {
            all.put(path, profileName);
        }

        if (all.isEmpty()) {
            Settings.remove(KEY_ACTIVE);
            return;
        }
        List<String> records = new ArrayList<>(all.size());
        for (Map.Entry<String, String> entry : all.entrySet()) {
            records.add(entry.getKey() + FIELD + entry.getValue());
        }
        Settings.put(KEY_ACTIVE, String.join(RECORD, records));
    }

    private static Map<String, String> activeByRepo() {
        Map<String, String> all = new LinkedHashMap<>();
        for (String record : split(Settings.get(KEY_ACTIVE, ""))) {
            int tab = record.indexOf(FIELD);
            if (tab <= 0 || tab == record.length() - 1) {
                continue;
            }
            all.put(record.substring(0, tab), record.substring(tab + 1));
        }
        return all;
    }

    // ------------------------------------------------------------------
    // Which repositories belong to a profile's own remembered tab set
    // ------------------------------------------------------------------

    private static final String KEY_TABS = "profiles.tabsByProfile";

    /**
     * The repository paths {@code profileName} has previously been applied to, in the
     * order each was added - what {@code LyraApp}'s "Open '&lt;profile&gt;' tabs"
     * action reopens. Membership is additive and explicit (a path joins only when
     * {@link #setActiveProfileName} succeeds for it, never removed automatically), the
     * deliberately scoped-down half of the commercial clients' "tabs per profile": they
     * close every tab not belonging to the profile being switched to, but this app
     * already lets two repositories sit on two different profiles at once (see the
     * class Javadoc), so closing tabs on switch would silently undo that on every use
     * of the switcher. This gives the useful half - reopening a remembered group -
     * without the destructive one.
     */
    public static List<String> tabPaths(String profileName) {
        if (profileName == null) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (String record : split(Settings.get(KEY_TABS, ""))) {
            int tab = record.indexOf(FIELD);
            if (tab <= 0 || tab == record.length() - 1) {
                continue;
            }
            if (record.substring(0, tab).equals(profileName)) {
                paths.add(record.substring(tab + 1));
            }
        }
        return List.copyOf(paths);
    }

    /** Associates {@code path} with {@code profileName}'s own tab set, if it is not there already. */
    public static void addTabPath(String profileName, String path) {
        if (profileName == null || profileName.isBlank() || path == null || path.isBlank()
                || containsSeparator(profileName) || containsSeparator(path)) {
            return;
        }
        List<String> records = new ArrayList<>(split(Settings.get(KEY_TABS, "")));
        String record = profileName + FIELD + path;
        if (records.contains(record)) {
            return;
        }
        records.add(record);
        Settings.put(KEY_TABS, String.join(RECORD, records));
    }

    // ------------------------------------------------------------------

    /** The canonical form of {@code dir}, matching {@link TabSession#key(File)} exactly. */
    public static String key(File dir) {
        return TabSession.key(dir);
    }

    private static boolean containsSeparator(String value) {
        return value.contains(RECORD) || value.contains(FIELD);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static List<String> split(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<String> records = new ArrayList<>();
        for (String record : stored.split(RECORD)) {
            if (!record.isBlank()) {
                records.add(record);
            }
        }
        return records;
    }
}
