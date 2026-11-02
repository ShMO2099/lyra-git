package com.jfx.git;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every package that reads JSON with Gson is opened to Gson in {@code module-info.java}.
 *
 * <p>A lint rather than a behaviour test because the failure cannot happen here: the
 * tests run on the classpath, where every package is open, and Gson reads private
 * records happily. The application runs on the module path, where it cannot - the
 * grammar catalogue came up empty in the running app with a {@code JsonIOException}
 * while every grammar test passed. So the rule is read off the source: a file that
 * calls {@code fromJson} lives in a package that {@code opens ... to com.google.gson}.
 */
class GsonModuleOpensTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern OPENS = Pattern.compile(
            "^\\s*opens\\s+([\\w.]+)\\s+to\\s+[^;]*\\bcom\\.google\\.gson\\b[^;]*;", Pattern.MULTILINE);

    @Test
    void everyPackageThatReadsJsonWithGsonIsOpenedToIt() throws IOException {
        String moduleInfo = Files.readString(SOURCES.resolve("module-info.java"));
        Set<String> opened = new TreeSet<>();
        Matcher opens = OPENS.matcher(moduleInfo);
        while (opens.find()) {
            opened.add(opens.group(1));
        }

        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (!source.contains(".fromJson(")) {
                    continue;
                }
                Matcher pkg = PACKAGE.matcher(source);
                if (pkg.find() && !opened.contains(pkg.group(1))) {
                    missing.add(pkg.group(1) + " (" + file.getFileName() + ")");
                }
            }
        }
        assertTrue(missing.isEmpty(), () -> "these read JSON with Gson but are not opened to it in module-info,"
                + " so the read fails in the running app: " + missing);
    }
}
