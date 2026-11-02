package com.jfx.git.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.List;

/**
 * Notifications that come and go in a corner of the window, stacked newest last.
 *
 * <p>The status bar is one line, overwritten by whatever comes next: a failed push
 * reported there was replaced a second later by "Refreshed", and the only trace of it was
 * the activity log. A notification stays until it is read - an error until dismissed, a
 * warning for a while - and can carry what to do next: Undo after a delete, Details for a
 * long git message, a way to the log.
 *
 * <p>Not modal and not in the way: it sits over the bottom-right of the content, takes no
 * clicks outside its own cards, and never more than {@link #MAX_SHOWN} are up at once.
 */
public final class ToastHost extends VBox {

    /** How a notification is coloured, and so how much it asks for attention. */
    public enum Kind { INFO, SUCCESS, WARNING, ERROR }

    /** A button on a notification; running it also dismisses the notification. */
    public record Action(String label, Runnable run) {
    }

    /** The oldest goes when another would make more than this. */
    static final int MAX_SHOWN = 4;

    /** How long a notification that clears itself stays, unless the pointer is on it. */
    public static final Duration SHORT = Duration.seconds(6);
    public static final Duration LONG = Duration.seconds(12);

    public ToastHost() {
        super(UiScale.STEP);
        getStyleClass().add("toast-host");
        setAlignment(Pos.BOTTOM_RIGHT);
        setFillWidth(false);
        // Only the cards take clicks; the empty column over the content must not.
        setPickOnBounds(false);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
    }

    /**
     * Shows a notification.
     *
     * @param autoHide how long until it goes by itself; {@code null} for one that stays
     *                 until dismissed
     * @return the card, for tests and for {@link #dismiss}
     */
    public Node show(Kind kind, String message, Duration autoHide, List<Action> actions) {
        Region mark = new Region();
        mark.getStyleClass().add("toast-mark");

        Label text = new Label(message);
        text.getStyleClass().add("toast-message");
        text.setWrapText(true);
        text.setMaxWidth(360);
        text.setMinHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox card = new HBox(UiScale.STEP);
        card.getStyleClass().addAll("toast", "toast-" + kind.name().toLowerCase(java.util.Locale.ROOT));
        card.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().addAll(mark, text);
        for (Action action : actions) {
            Button button = new Button(action.label());
            button.getStyleClass().addAll("btn-sm", "toast-action");
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.setOnAction(e -> {
                dismiss(card);
                action.run().run();
            });
            card.getChildren().add(button);
        }
        Button close = Icons.button(Icons.close(), "Dismiss", "Dismiss");
        close.getStyleClass().addAll("btn-sm", "btn-ghost", "toast-close");
        close.setOnAction(e -> dismiss(card));
        card.getChildren().add(close);
        card.setAccessibleText(kind.name().toLowerCase(java.util.Locale.ROOT) + ": " + message);

        if (autoHide != null) {
            PauseTransition timer = new PauseTransition(autoHide);
            timer.setOnFinished(e -> dismiss(card));
            // Not while it is being read.
            card.setOnMouseEntered(e -> timer.pause());
            card.setOnMouseExited(e -> timer.play());
            card.getProperties().put("toast-timer", timer);
            timer.play();
        }

        while (getChildren().size() >= MAX_SHOWN) {
            remove(getChildren().get(0));
        }
        getChildren().add(card);
        Motion.riseIn(card);
        return card;
    }

    /** Takes a notification away, fading it out. */
    public void dismiss(Node card) {
        if (!getChildren().contains(card) || card.getProperties().containsKey("toast-leaving")) return;
        card.getProperties().put("toast-leaving", true);
        Motion.fadeOut(card, () -> remove(card));
    }

    private void remove(Node card) {
        if (card.getProperties().get("toast-timer") instanceof PauseTransition timer) {
            timer.stop();
        }
        getChildren().remove(card);
    }

    /** The notifications showing now, oldest first. */
    public List<Node> shown() {
        return List.copyOf(getChildren());
    }
}
