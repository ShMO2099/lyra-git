package com.jfx.git.ui.dialogs;

import com.jfx.git.core.Settings;
import com.jfx.git.core.hosting.FakeOAuthServer;
import com.jfx.git.core.hosting.HostingProvider;
import com.jfx.git.core.hosting.OAuthDeviceFlow;
import com.jfx.git.core.hosting.OAuthLoopbackFlow;
import com.jfx.git.core.hosting.OAuthSpec;
import com.jfx.git.core.hosting.OAuthTokenClient;
import com.jfx.git.core.hosting.ProviderSessions;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RobotWait;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signing in with the browser: the sign-in dialog runs the provider's flow and hands back
 * a credential. What the Integrations page does with it is {@code IntegrationSignInTest}'s.
 */
class ProviderSignInDialogTest {

    @TempDir
    Path tempDir;

    private FakeOAuthServer oauth;
    private ProviderSignInDialog.Flows previousFlows;

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @BeforeEach
    void setUp() throws Exception {
        Settings.useFile(tempDir.resolve("settings.properties"));
        oauth = new FakeOAuthServer();
        previousFlows = ProviderSignInDialog.flows;
    }

    @AfterEach
    void tearDown() {
        ProviderSignInDialog.flows = previousFlows;
        FxHarness.run(() -> new ArrayList<>(javafx.stage.Window.getWindows()).forEach(javafx.stage.Window::hide));
        oauth.close();
        for (HostingProvider p : HostingProvider.values()) ProviderSessions.disconnect(p);
        Settings.useFile(null);
    }

    /** GitHub's device flow, from the code to a credential, without a browser. */
    @Test
    void theDeviceFlowEndsInACredential() throws Exception {
        oauth.on("/login/device/code", 200, "{\"device_code\":\"dc\",\"user_code\":\"WDJB-MJHT\","
                        + "\"verification_uri\":\"https://github.com/login/device\",\"expires_in\":900,\"interval\":5}")
                .on("/login/oauth/access_token", 200, "{\"error\":\"authorization_pending\"}")
                .on("/login/oauth/access_token", 200, "{\"access_token\":\"gho_signed_in\",\"token_type\":\"bearer\","
                        + "\"scope\":\"repo,read:org,workflow\"}");
        ProviderSignInDialog.flows = new ProviderSignInDialog.Flows() {
            @Override
            public OAuthDeviceFlow device(OAuthSpec spec) {
                return new OAuthDeviceFlow(spec, new OAuthTokenClient(), interval -> { });
            }

            @Override
            public OAuthLoopbackFlow loopback(OAuthSpec spec) {
                throw new AssertionError("GitHub signs in with the device flow");
            }
        };
        OAuthSpec spec = new OAuthSpec(OAuthSpec.Flow.DEVICE, oauth.uri("/login/device/code"), null,
                oauth.uri("/login/oauth/access_token"), null, "repo read:org workflow", "app-id", null, 0, false);

        ProviderSignInDialog dialog = FxHarness.call(() -> {
            ProviderSignInDialog d = new ProviderSignInDialog(HostingProvider.GITHUB,
                    URI.create("https://github.com"), spec);
            d.setX(-4000);
            d.setY(-4000);
            d.show();
            return d;
        });
        RobotWait.untilOnFx("the sign-in never finished", () -> !dialog.isShowing());

        ProviderSignInDialog.SignIn signedIn = FxHarness.call(dialog::resultForTest).orElseThrow();
        assertEquals("gho_signed_in", signedIn.credential().accessToken());
        assertTrue(signedIn.credential().isOAuth());
        assertEquals("app-id", oauth.requestsTo("/login/device/code").get(0).form().get("client_id"));
    }
}
