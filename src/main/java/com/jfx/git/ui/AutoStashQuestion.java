package com.jfx.git.ui;

import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.AutoStash;
import com.jfx.git.ui.dialogs.Dialogs;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Tooltip;

import java.util.function.Consumer;

/**
 * The question every auto-stash offer in the app asks ({@link AutoStash.Asker}): whether to
 * stash, run the refused operation again and put the changes back. "Always do this"
 * ({@link AppPreferences#autoStashOnCheckoutPull}) answers yes from then on, for every
 * operation; otherwise a dialog asks.
 *
 * <p>Pass {@code AutoStashQuestion::ask} to {@link AutoStash#run}. What happens on each
 * answer is the core's; this is only the asking.
 */
public final class AutoStashQuestion {

    /** The dialog's answer: "Always do this" is a yes that also sets the preference. */
    enum Answer { CANCEL, ONCE, ALWAYS }

    private AutoStashQuestion() {
    }

    /** Asks, on the FX thread, and answers {@code stash} before returning. */
    public static void ask(AutoStash.Operation operation, Consumer<Boolean> stash) {
        Answer answer = AppPreferences.autoStashOnCheckoutPull() ? Answer.ALWAYS : askWithDialog(operation);
        if (answer == Answer.ALWAYS) {
            AppPreferences.setAutoStashOnCheckoutPull(true);
        }
        stash.accept(answer != Answer.CANCEL);
    }

    /**
     * The question. The verb is a button of its own, named for the operation ("Stash and
     * pull"), and "Always do this" - {@link AppPreferences#autoStashOnCheckoutPull}, which
     * {@link #ask} turns on - sits at the footer's left, as a preference rather than part of
     * the answer.
     */
    private static Answer askWithDialog(AutoStash.Operation operation) {
        ButtonType proceed = new ButtonType(operation.action(), ButtonBar.ButtonData.OK_DONE);
        CheckBox always = new CheckBox("Always do this");
        always.getStyleClass().add("autostash-always");
        always.setTooltip(new Tooltip("Stash, retry and re-apply without asking, for checkouts, pulls"
                + " and merges. Turn it off again in Settings, under General."));
        ButtonType chosen = Dialogs.confirm("Uncommitted changes")
                .icon(Icons.stash())
                .title("Stash your changes and " + operation.verb() + "?")
                .subtitle("Your uncommitted changes would be overwritten")
                .message("They are stashed, the " + operation.noun() + " runs, and they are put back."
                        + " If anything conflicts, they stay in the stash, so nothing is lost.")
                .buttons(proceed, ButtonType.CANCEL)
                .footerLeft(always)
                .answer();
        if (chosen != proceed) return Answer.CANCEL;
        return always.isSelected() ? Answer.ALWAYS : Answer.ONCE;
    }
}
