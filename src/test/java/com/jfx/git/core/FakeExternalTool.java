package com.jfx.git.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * A stand-in for a diff or merge tool, run as a real child process by
 * {@link ExternalToolsTest}.
 *
 * <p>A separate program rather than a mock, because what is under test is the command
 * line: how many arguments arrive, whether a path with a space in it arrives as one, and
 * what is in the files those paths name by the time the tool reads them.
 *
 * <pre>
 * record &lt;out&gt; args...       writes the remaining arguments to &lt;out&gt;, one per line
 * takeLocal &lt;local&gt; &lt;merged&gt; resolves by copying the local side over the merged file
 * leave                      exits 0 having changed nothing
 * fail                       exits 3
 * </pre>
 */
public final class FakeExternalTool {

    private FakeExternalTool() {
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "record" -> {
                Path out = Path.of(args[1]);
                Path partial = out.resolveSibling(out.getFileName() + ".partial");
                Files.write(partial, Arrays.asList(args).subList(2, args.length), StandardCharsets.UTF_8);
                // Appears whole or not at all, so a test polling for it never reads half.
                Files.move(partial, out, StandardCopyOption.ATOMIC_MOVE);
            }
            case "takeLocal" -> {
                Path merged = Path.of(args[2]);
                merged.toFile().setWritable(true);
                Files.write(merged, Files.readAllBytes(Path.of(args[1])));
            }
            case "leave" -> {
            }
            case "fail" -> System.exit(3);
            default -> System.exit(64);
        }
    }
}
