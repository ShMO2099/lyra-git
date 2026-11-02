package com.jfx.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * U12: run shipped classes with the image's own reduced JDK, rather than the build JDK.
 * Set -Dlyra.test.packagedImage=/path/to/Lyra after packaging. Without an image the
 * ordinary source-only test run cannot exercise this platform-dependent boundary.
 * GUI interaction is covered separately by the headless application/RepoTab tests.
 */
@EnabledOnOs(OS.WINDOWS)
class PackagedRuntimeIntegrationTest {
    @TempDir Path temporary;

    private Path image() {
        String configured = System.getProperty("lyra.test.packagedImage", "");
        Path candidate = configured.isBlank() ? Path.of("target/dist/Lyra") : Path.of(configured);
        if (configured.isBlank()) {
            assumeTrue(Files.isDirectory(candidate), "No packaged image; set lyra.test.packagedImage to test it");
        }
        assertTrue(Files.isDirectory(candidate), "Configured packaged image is missing: " + candidate);
        return candidate.toAbsolutePath();
    }

    @Test
    void packagedRuntimeLoadsDependenciesAndReadsRealCommitHistoryAndDiffs() throws Exception {
        Path image = image();
        Path runtime = image.resolve("runtime/bin/java.exe");
        assertTrue(Files.isRegularFile(runtime), "Image must contain its own Java runtime");
        List<String> jars;
        try (var files = Files.list(image.resolve("app"))) {
            jars = files.filter(p -> p.toString().endsWith(".jar")).sorted().map(Path::toString).toList();
        }
        assertFalse(jars.isEmpty(), "Image must contain application and dependency jars");
        String classpath = String.join(File.pathSeparator, jars);
        Path source = temporary.resolve("PackagedQaProbe.java");
        Files.writeString(source, PROBE, StandardCharsets.UTF_8);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Compile the probe with the build JDK, not the packaged JRE");
        ByteArrayOutputStream compilation = new ByteArrayOutputStream();
        assertEquals(0, compiler.run(null, compilation, compilation,
                "-cp", classpath, "-d", temporary.toString(), source.toString()),
                () -> compilation.toString(StandardCharsets.UTF_8));

        List<String> arguments = new ArrayList<>(List.of(runtime.toString(), "-Xmx256m",
                "--enable-native-access=ALL-UNNAMED,javafx.graphics",
                "--module-path", image.resolve("app/fx").toString(), "--add-modules", "javafx.controls",
                "-Duser.home=" + temporary, "-Dlyra.settings=" + temporary.resolve("settings.properties"),
                "-cp", classpath + File.pathSeparator + temporary, "PackagedQaProbe",
                temporary.resolve("repository").toString()));
        ProcessResult result = run(arguments, Duration.ofSeconds(45), false);
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("PACKAGED_QA_OK"), result.output());
    }

    @Test
    void packagedAskpassLauncherStartsItsBridgeClientWithoutOpeningAnAuthenticationDialog() throws Exception {
        Path helper = image().resolve("LyraAskpass.exe");
        assertTrue(Files.isRegularFile(helper), "Image must ship its Askpass launcher");
        ProcessResult result = run(List.of(helper.toString(), "probe"), Duration.ofSeconds(15), true);
        assertEquals(1, result.exit(), "No bridge means a silent refusal, not successful authentication");
        assertTrue(result.output().isBlank(), "A missing launcher class/runtime reports an error: " + result.output());
    }

    private ProcessResult run(List<String> arguments, Duration timeout, boolean clearBridge) throws Exception {
        Path output = Files.createTempFile(temporary, "process-", ".log");
        ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(output.toFile());
        if (clearBridge) {
            builder.environment().remove("LYRA_ASKPASS_PORT");
            builder.environment().remove("LYRA_ASKPASS_TOKEN");
        }
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS),
                    "Packaged child did not exit within " + timeout);
            return new ProcessResult(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private record ProcessResult(int exit, String output) { }

    private static final String PROBE = """
        import com.jfx.git.core.GitService;
        import com.jfx.git.core.github.GitHubClient;
        import com.jfx.git.core.gitlab.GitLabClient;
        import com.jfx.git.core.bitbucket.BitbucketClient;
        import com.jfx.git.core.libgit2.Libgit2Loader;
        import com.jfx.git.ui.syntax.GrammarRegistry;
        import com.jfx.git.graph.GraphOrder;
        import org.eclipse.jgit.api.Git;
        import java.nio.file.*;

        public class PackagedQaProbe {
            public static void main(String[] args) throws Exception {
                new GitHubClient(); new GitLabClient(); new BitbucketClient();
                require(GrammarRegistry.instance().languageForExtension("java") != null, "Missing Java grammar");
                require(Libgit2Loader.init(), "Bundled native library unavailable: " + Libgit2Loader.unavailableReason());
                Path directory = Files.createDirectories(Path.of(args[0]));
                String before, after;
                try (Git git = Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call()) {
                    var config = git.getRepository().getConfig();
                    config.setBoolean("core", null, "autocrlf", false);
                    config.setBoolean("commit", null, "gpgsign", false); config.save();
                    Files.writeString(directory.resolve("work.txt"), "before\\n");
                    git.add().addFilepattern(".").call();
                    before = git.commit().setMessage("Before").setAuthor("QA", "qa@example.invalid")
                        .setCommitter("QA", "qa@example.invalid").setSign(false).call().name();
                    Files.writeString(directory.resolve("work.txt"), "after\\n");
                    git.add().addFilepattern(".").call();
                    after = git.commit().setMessage("After").setAuthor("QA", "qa@example.invalid")
                        .setCommitter("QA", "qa@example.invalid").setSign(false).call().name();
                }
                GitService service = new GitService();
                try {
                    require(service.openRepository(directory.toFile()), "Could not open packaged repository");
                    require(service.getCommitGraphPage(10, GraphOrder.CHRONOLOGICAL).rows().size() == 2, "Wrong graph rows");
                    require(service.getFileContentAtCommit(before, "work.txt").equals("before\\n"), "Wrong historical content");
                    require(service.getFileContentAtCommit(after, "work.txt").equals("after\\n"), "Wrong selected content");
                    String diff = new String(service.getDiffBytesBetweenCommits(before, after, "work.txt", false),
                        java.nio.charset.StandardCharsets.UTF_8);
                    require(diff.contains("-before") && diff.contains("+after"), "Wrong historical diff");
                } finally { service.close(); }
                System.out.println("PACKAGED_QA_OK");
            }
            private static void require(boolean condition, String message) {
                if (!condition) throw new AssertionError(message);
            }
        }
        """;
}
