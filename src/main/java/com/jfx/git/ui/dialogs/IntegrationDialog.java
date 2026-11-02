package com.jfx.git.ui.dialogs;

import com.jfx.git.ui.AppNavigator;
import com.jfx.git.ui.SettingsSection;
import com.jfx.git.ui.UiScale;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * What is left of the Integrations window: the prompt a provider list shows when no
 * account is connected.
 *
 * <p>The window itself is the Integrations section of the Settings tab now
 * ({@code com.jfx.git.ui.settings.IntegrationsPane}). This class stays, under its old
 * name, because the provider lists reach the prompt as {@code IntegrationDialog.connectPrompt}
 * and they are being reworked on their own; it is not a dialog, and opens none.
 */
public final class IntegrationDialog {

    private IntegrationDialog() {
    }

    /**
     * What a provider list shows with no account connected: the reason, and the button
     * that fixes it - which opens Settings at Integrations rather than naming a menu to go
     * and find.
     */
    public static Node connectPrompt(String provider) {
        Label why = new Label("Connect a " + provider + " account to see this.");
        why.setWrapText(true);
        Button connect = new Button("Connect " + provider + "…");
        connect.setOnAction(e -> AppNavigator.openSettings(SettingsSection.INTEGRATIONS));
        VBox box = new VBox(UiScale.STEP, why, connect);
        box.setAlignment(Pos.CENTER);
        return box;
    }
}
