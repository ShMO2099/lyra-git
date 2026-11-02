package com.jfx.git.ui.settings;

import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.Settings;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.Motion;
import com.jfx.git.ui.SettingsSection;

import javafx.application.Platform;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Reduce motion" follows the operating system until the user says otherwise: what the
 * Appearance page shows, what ticking it stores, and what {@link Motion} then does.
 *
 * <p>The platform's answer is a stand-in throughout. The headless toolkit these tests run
 * on reports no preferences, so it always says "motion"; the system asking for less is a
 * case only a seam can reach. {@code AppPreferencesTest} covers the rule itself.
 */
class ReduceMotionPreferenceTest {

    /** The stored key, by name: renaming it would drop everyone's choice back to the system's. */
    private static final String KEY = "ui.reduceMotion";

    private SettingsTab tab;

    @BeforeEach
    void startAsAFreshInstallDoes() {
        // Other suites leave an explicit choice behind them, and "never chosen" is what is under test.
        Settings.remove(KEY);
        AppPreferences.usePlatformReducedMotion(null);
    }

    @AfterEach
    void restoreDefaults() {
        AppPreferences.usePlatformReducedMotion(null);
        Settings.remove(KEY);
        // The page is FX state, so it goes on the FX thread; the settings are not.
        FxHarness.run(() -> {
            if (tab != null) tab.dispose();
        });
        FxHarness.closeShownStages();
    }

    /** The Appearance page on screen; its "Reduce motion" box. Call on the FX thread. */
    private CheckBox box() {
        tab = new SettingsTab();
        SettingsTabs.at(SettingsTabs.show(tab), tab, SettingsSection.APPEARANCE);
        return (CheckBox) tab.page(SettingsSection.APPEARANCE).lookup("#reduce-motion");
    }

    @Test
    void theBoxIsTickedWhenTheSystemAsksForLessMotionAndNothingIsChosen() {
        AppPreferences.usePlatformReducedMotion(() -> true);

        boolean ticked = FxHarness.call(() -> box().isSelected());

        assertTrue(ticked, "it shows what is in effect, which is the system's setting");
        assertNull(Settings.get(KEY, null), "and showing it is not choosing it");
    }

    @Test
    void theBoxIsClearWhenTheSystemKeepsMotionAndNothingIsChosen() {
        AppPreferences.usePlatformReducedMotion(() -> false);

        boolean ticked = FxHarness.call(() -> box().isSelected());

        assertFalse(ticked);
        assertNull(Settings.get(KEY, null));
    }

    @Test
    void theBoxShowsAChoiceToKeepMotionOverASystemThatAsksForLess() {
        AppPreferences.usePlatformReducedMotion(() -> true);
        AppPreferences.setReduceMotion(false);

        assertFalse(FxHarness.call(() -> box().isSelected()));
    }

    @Test
    void theBoxShowsAChoiceToReduceMotionOverASystemThatLeavesItOn() {
        AppPreferences.usePlatformReducedMotion(() -> false);
        AppPreferences.setReduceMotion(true);

        assertTrue(FxHarness.call(() -> box().isSelected()));
    }

    @Test
    void tickingItStoresAChoice() {
        AppPreferences.usePlatformReducedMotion(() -> false);

        FxHarness.run(() -> box().setSelected(true));

        assertEquals("true", Settings.get(KEY, null));
        assertTrue(AppPreferences.reduceMotion());
    }

    /** The case that matters: a system that asks for less, and a user who wants the fades. */
    @Test
    void clearingItStoresAChoiceThatBeatsTheSystem() {
        AppPreferences.usePlatformReducedMotion(() -> true);

        FxHarness.run(() -> {
            CheckBox box = box();
            assertTrue(box.isSelected());
            box.setSelected(false);
        });

        assertEquals("false", Settings.get(KEY, null));
        assertFalse(AppPreferences.reduceMotion(), "an explicit no is not the same as never having said");
    }

    @Test
    void theHelperTextSaysItFollowsTheSystemUntilChanged() {
        String hint = FxHarness.call(() -> {
            CheckBox box = box();
            VBox page = (VBox) box.getParent();
            Label below = (Label) page.getChildren().get(page.getChildren().indexOf(box) + 1);
            return below.getText();
        });

        assertTrue(hint.contains("Follows your system's setting until you change it here"), hint);
    }

    @Test
    void motionIsOffWhenTheSystemAsksForLessAndTheUserHasNotSaidOtherwise() {
        AppPreferences.usePlatformReducedMotion(() -> true);

        assertFalse(Motion.enabled());
        FxHarness.run(() -> {
            Region node = new Region();
            node.setOpacity(0.3);
            Motion.fadeIn(node);
            assertEquals(1.0, node.getOpacity(), 0.001, "shown at once, as with the box ticked");
        });
    }

    @Test
    void motionIsOnWhenTheUserHasAskedForItOverASystemThatAsksForLess() {
        AppPreferences.usePlatformReducedMotion(() -> true);
        AppPreferences.setReduceMotion(false);

        assertTrue(Motion.enabled());
    }

    /**
     * On the FX thread the platform really is asked - nothing is stubbed here - and what it
     * says is what comes back. The value itself is the machine's (the headless toolkit says
     * "motion"), so this compares the two rather than naming one.
     */
    @Test
    void onTheFxThreadTheRealPlatformIsAsked() {
        boolean[] answers = FxHarness.call(() -> new boolean[]{
                AppPreferences.reduceMotion(),
                Platform.getPreferences().isReducedMotion()});

        assertEquals(answers[1], answers[0]);
    }
}
