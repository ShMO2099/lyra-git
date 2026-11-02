package com.jfx.git.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * The application's motion: short fades and slides where something appears, leaves or
 * changes place - and none at all when the user has asked for none.
 *
 * <p>One place for it, so "Reduce motion" (Preferences › Appearance) is one check rather
 * than one per animation, and every animation shares a duration and an easing. 150 ms is
 * long enough to show where a thing came from and short enough never to be waited on.
 */
public final class Motion {

    /** How long an appearance or a departure takes. */
    public static final Duration FAST = Duration.millis(150);

    private Motion() {
    }

    /**
     * Whether to animate at all: not when the user has asked for less motion, or - until
     * they have said either way - when their operating system has. Call on the FX thread,
     * as an animation is anyway; elsewhere the system's setting cannot be read.
     */
    public static boolean enabled() {
        return !com.jfx.git.core.AppPreferences.reduceMotion();
    }

    /** Fades {@code node} in from transparent. */
    public static void fadeIn(Node node) {
        if (!enabled()) {
            node.setOpacity(1);
            return;
        }
        node.setOpacity(0);
        FadeTransition fade = new FadeTransition(FAST, node);
        fade.setToValue(1);
        fade.setInterpolator(Interpolator.EASE_OUT);
        fade.play();
    }

    /** Fades {@code node} in while rising a few pixels into place - for something arriving from below. */
    public static void riseIn(Node node) {
        if (!enabled()) {
            node.setOpacity(1);
            node.setTranslateY(0);
            return;
        }
        node.setOpacity(0);
        node.setTranslateY(8);
        FadeTransition fade = new FadeTransition(FAST, node);
        fade.setToValue(1);
        TranslateTransition rise = new TranslateTransition(FAST, node);
        rise.setToY(0);
        ParallelTransition both = new ParallelTransition(fade, rise);
        both.setInterpolator(Interpolator.EASE_OUT);
        both.play();
    }

    /** Fades {@code node} out, then runs {@code then} - immediately, with motion off. */
    public static void fadeOut(Node node, Runnable then) {
        if (!enabled()) {
            if (then != null) then.run();
            return;
        }
        FadeTransition fade = new FadeTransition(FAST, node);
        fade.setToValue(0);
        fade.setInterpolator(Interpolator.EASE_IN);
        fade.setOnFinished(e -> {
            if (then != null) then.run();
        });
        fade.play();
    }

    /** Fades a whole window in: for a popup or the command palette as it opens. */
    public static void fadeIn(javafx.stage.Window window) {
        if (!enabled()) {
            window.setOpacity(1);
            return;
        }
        window.setOpacity(0);
        javafx.animation.Timeline timeline = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(FAST,
                        new javafx.animation.KeyValue(window.opacityProperty(), 1, Interpolator.EASE_OUT)));
        timeline.play();
    }
}
