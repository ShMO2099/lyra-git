package com.jfx.git.ui.dialogs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How far the shared dialog shell has actually been adopted.
 *
 * <h2>Why a test rather than a note</h2>
 * Twenty-nine dialogs each picked their own dimensions, and the tell was that six landed
 * on {@code 720x380} and three more on {@code 420x320} without anyone deciding they
 * should. {@link JfxDialog} declares those clusters as presets — but a shell that most
 * dialogs ignore is two patterns rather than one, and "we will migrate the rest" is the
 * kind of intention that stays true for years.
 *
 * <p>It started as a ratchet: the unmigrated ones were listed by name and the list could
 * only shrink. The list is now empty and all twenty-eight take a preset, so what this
 * enforces has changed shape — a new dialog that picks its own dimensions fails, and so
 * does one that skips the shell entirely. The migration is finished; this is what stops
 * it coming undone one dialog at a time.
 */
class DialogShellConventionTest {

    private static final Path DIALOGS = Path.of("src/main/java/com/jfx/git/ui/dialogs");

    /**
     * Dialogs that still size themselves.
     *
     * <p>Empty, and it should stay that way. Every dialog in the application now takes a
     * {@link JfxDialog.Size} preset; a new one that picks its own numbers fails the test
     * below rather than quietly starting a fourth cluster.
     */
    private static final Set<String> NOT_YET_MIGRATED = Set.of();

    /** Every dialog class, by simple name. */
    private static List<String> dialogClasses() throws IOException {
        try (var paths = Files.list(DIALOGS)) {
            return paths.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".java"))
                    .map(n -> n.substring(0, n.length() - ".java".length()))
                    // Not dialogs: the shell itself, and the connect prompt left behind when
                    // Integrations became a section of the Settings tab. The provider lists
                    // and their helpers moved to ui.views when they became views in the tab.
                    .filter(n -> !n.equals("JfxDialog") && !n.equals("IntegrationDialog"))
                    // Not dialogs either: the parts dialogs are built from, and the body the
                    // four repository pickers share.
                    .filter(n -> !n.equals("DialogParts") && !n.equals("RepoPicker"))
                    // Nor the merge tool's model of a conflicted file and its conflicts.
                    .filter(n -> !n.equals("ConflictDocument") && !n.equals("ConflictRegions"))
                    .sorted()
                    .toList();
        }
    }

    private static String sourceOf(String name) throws IOException {
        return Files.readString(DIALOGS.resolve(name + ".java"));
    }

    /**
     * Whether a class picks its own <em>window</em> dimensions instead of taking a preset.
     *
     * <p>Deliberately narrow. The first version of this matched any {@code setPrefWidth}
     * in the file and so flagged ten dialogs that had already migrated, because a
     * preferences nav rail is 180px wide and an identity form's label column is 90 - both
     * entirely reasonable, and neither is the dialog choosing how big its window is. A
     * detector that cannot tell those apart would have pushed someone to delete the
     * fixed widths that make those layouts work.
     *
     * <p>So this looks only at the three ways a window sizes itself: a scene built with
     * explicit dimensions, a preferred size set on the dialog pane, and a stage told its
     * own width or height.
     */
    private static boolean sizesItself(String source) {
        boolean sizedScene = java.util.regex.Pattern
                .compile("new\\s+Scene\\([^)]*,\\s*\\d+\\s*,\\s*\\d+")
                .matcher(source).find();
        boolean sizedPane = source.contains("getDialogPane().setPrefSize(")
                || source.contains("getDialogPane().setPrefWidth(")
                || source.contains("getDialogPane().setPrefHeight(");
        boolean sizedStage = java.util.regex.Pattern
                .compile("(?m)^\\s*set(Width|Height)\\(\\d")
                .matcher(source).find();
        return sizedScene || sizedPane || sizedStage;
    }

    @Test
    void everyDialogThatSizesItselfIsOnTheList() throws IOException {
        List<String> unlisted = new ArrayList<>();
        for (String name : dialogClasses()) {
            if (sizesItself(sourceOf(name)) && !NOT_YET_MIGRATED.contains(name)) {
                unlisted.add(name);
            }
        }
        assertTrue(unlisted.isEmpty(), () ->
                "these size themselves instead of taking a JfxDialog.Size preset: " + unlisted
                        + ". Use the shell, or add them to NOT_YET_MIGRATED and say why.");
    }

    /**
     * A dialog that has been migrated must leave the list.
     *
     * <p>Kept now that the list is empty, because it is what would catch someone adding a
     * name back to it to silence a failure rather than fixing the dialog.
     */
    @Test
    void theListHasNoStaleEntries() throws IOException {
        List<String> migrated = new ArrayList<>();
        for (String name : NOT_YET_MIGRATED) {
            if (!sizesItself(sourceOf(name))) {
                migrated.add(name);
            }
        }
        assertTrue(migrated.isEmpty(), () ->
                "these no longer size themselves and should come off NOT_YET_MIGRATED: "
                        + new TreeSet<>(migrated));
    }

    /** Every name on the list has to be a real class, or it is protecting nothing. */
    @Test
    void everyListedNameExists() throws IOException {
        List<String> all = dialogClasses();
        List<String> missing = NOT_YET_MIGRATED.stream()
                .filter(name -> !all.contains(name))
                .sorted()
                .toList();
        assertTrue(missing.isEmpty(), () -> "no such dialog class: " + missing);
    }

    /**
     * Reports the state of the migration, so the number is visible rather than inferred.
     *
     * <p>Two numbers, because for most of this migration they differed: a dialog could be
     * off the list either because it adopted the shell or because it had never sized
     * itself, and only the first was progress. Both are now zero-and-twenty-eight, but the
     * distinction is kept - it is what stopped the count flattering the work while it was
     * still being done.
     */
    @Test
    void theMigrationIsUnderway() throws IOException {
        List<String> all = dialogClasses();
        List<String> onTheShell = new ArrayList<>();
        List<String> merelyCompliant = new ArrayList<>();
        for (String name : all) {
            String source = sourceOf(name);
            if (source.contains("JfxDialog.shell(")) {
                onTheShell.add(name);
            } else if (!sizesItself(source)) {
                merelyCompliant.add(name);
            }
        }

        assertFalse(onTheShell.isEmpty(),
                "at least one dialog should be on the shell, or it is not a shell yet");
        // Every dialog, not merely every dialog that used to size itself. A class that
        // never picked a size still gains the shell's titling and theming, and leaving it
        // off would mean the one convention has two ways of being satisfied.
        assertTrue(merelyCompliant.isEmpty(),
                () -> "these should take a preset too rather than defaulting: "
                        + new TreeSet<>(merelyCompliant));

        System.out.println("Dialog shell: " + onTheShell.size() + " of " + all.size()
                + " on the shell " + new TreeSet<>(onTheShell));
        System.out.println("             " + merelyCompliant.size()
                + " never sized themselves (not yet on the shell) "
                + new TreeSet<>(merelyCompliant));
        System.out.println("             " + NOT_YET_MIGRATED.size() + " still to migrate");
    }
}
