package com.jfx.git;

import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.Settings;
import com.jfx.git.core.TabSession;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RepoTab;
import com.jfx.git.ui.RobotWait;

import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures startup, for comparing one version with another rather than asserting.
 *
 * <p>Deliberately uses nothing the application added to be measured: the first layout
 * pulse is seen through a listener of the harness's own, so the same class runs against
 * an older build. Per run it prints how long {@code start()} held the FX thread, when
 * the first layout pulse came and how many repository tabs existed by then, when the tab
 * in front had opened, and when the repository lane went quiet - which, before restored
 * tabs became placeholders, included opening every background tab.
 *
 * <pre>
 *   verify.sh StartupTimingTest     (tagged slow: not part of the ordinary build)
 * </pre>
 */
@Tag("slow")
class StartupTimingTest {

    private static final int RUNS = 4;

    @TempDir
    Path tempDir;

    private final List<Stage> stages = new ArrayList<>();
    private final List<LyraApp> apps = new ArrayList<>();

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @AfterEach
    void tearDown() {
        TabSession.setOpenPaths(List.of(), 0);
        Settings.put("repo.last", null);
        RobotWait.drainGitWork();
        FxHarness.run(() -> {
            for (Stage stage : stages) {
                for (Tab tab : tabs(stage)) {
                    if (tab instanceof RepoTab repoTab) repoTab.close();
                }
                stage.close();
            }
        });
    }

    private static List<Tab> tabs(Stage stage) {
        return ((TabPane) ((javafx.scene.layout.BorderPane) ((javafx.scene.layout.Pane)
                stage.getScene().getRoot()).getChildren().get(0)).getCenter()).getTabs();
    }

    private Path repo(String name, int commits) throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve(name));
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            for (int i = 0; i < commits; i++) {
                Files.writeString(dir.resolve("f" + (i % 20) + ".txt"), name + i);
                git.add().addFilepattern(".").call();
                git.commit().setMessage("commit " + i + " in " + name).setAuthor("T", "t@example.com")
                        .setCommitter("T", "t@example.com").setSign(false).call();
            }
        }
        return dir;
    }

    @Test
    void measureStartup() throws Exception {
        List<String> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            five.add(repo("repo" + i, 200).toString());
        }
        String one = repo("single", 200).toString();
        System.out.println("startup timing (ms): start() | first layout pulse | repo tabs at that pulse"
                + " | front tab open | lane quiet");
        for (int run = 0; run < RUNS; run++) {
            measure("0 restored, repo.last  run " + run, List.of(), one);
            measure("5 restored             run " + run, five, null);
        }
    }

    private void measure(String label, List<String> session, String last) {
        TabSession.setOpenPaths(session, session.isEmpty() ? 0 : 2);
        Settings.put("repo.last", last);
        RobotWait.drainGitWork();
        // Every launch builds the SSH transport; without this only the first run would.
        com.jfx.git.core.SshSessionManager.shutdown();

        AtomicLong began = new AtomicLong();
        AtomicLong startReturned = new AtomicLong();
        AtomicLong firstPulse = new AtomicLong();
        AtomicInteger tabsAtPulse = new AtomicInteger(-1);
        Stage[] holder = new Stage[1];
        FxHarness.run(() -> {
            Stage stage = new Stage();
            holder[0] = stage;
            stages.add(stage);
            LyraApp app = new LyraApp();
            apps.add(app);
            began.set(System.nanoTime());
            app.start(stage);
            startReturned.set(System.nanoTime());
            Scene scene = stage.getScene();
            Runnable[] once = new Runnable[1];
            once[0] = () -> {
                scene.removePostLayoutPulseListener(once[0]);
                firstPulse.set(System.nanoTime());
                tabsAtPulse.set((int) tabs(stage).stream().filter(t -> !"＋".equals(t.getText())
                        && !(t instanceof com.jfx.git.ui.WelcomeTab)
                        && !(t instanceof com.jfx.git.ui.ToolTab)).count());
            };
            scene.addPostLayoutPulseListener(once[0]);
        });
        Stage stage = holder[0];
        LyraApp app = apps.get(apps.size() - 1);
        RobotWait.untilOnFx("the front tab never opened", () ->
                app.getActiveRepoTab() != null && app.getActiveRepoTab().getGitService().isOpen());
        long frontOpen = System.nanoTime();
        RobotWait.until("the repository lanes never went quiet", () -> !GitTaskRunner.anyRepositoryWorkWithin(300));
        long quiet = System.nanoTime() - 300_000_000L;
        System.out.printf("%-28s %6d | %6d | %d | %6d | %6d%n", label,
                ms(began, startReturned.get()), ms(began, firstPulse.get()), tabsAtPulse.get(),
                ms(began, frontOpen), ms(began, quiet));
        FxHarness.run(() -> {
            for (Tab tab : tabs(stage)) {
                if (tab instanceof RepoTab repoTab) repoTab.close();
            }
            stage.close();
        });
        stages.remove(stage);
    }

    private static long ms(AtomicLong from, long to) {
        return (to - from.get()) / 1_000_000;
    }
}
