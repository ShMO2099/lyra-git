#!/usr/bin/env bash
# Builds a self-contained Linux application into target/dist/Lyra.
#
# Usage:  ./package-linux.sh [--deb] [--rpm] [--aot] [--no-bss]
#
#   (no flag)   app-image -> target/dist/Lyra/bin/Lyra, runnable in place
#   --deb       also a Debian package  -> target/dist/lyra_<version>_amd64.deb
#   --rpm       also an RPM package    -> target/dist/lyra-<version>-1.x86_64.rpm
#   --aot       also train an AOT cache (JDK 25) into the image; see aot_train in
#               package-windows.sh, which says why; needs a display (xvfb-run)
#   --no-bss    leave the stylesheets as text
#
# The counterpart of package-windows.sh, and deliberately the same shape: a runtime
# image built from JDK modules only, the application on the classpath, JavaFX on the
# module path. The reasons are written out there and are not repeated here; what
# differs is listed below.
#
# ---------------------------------------------------------------------------
# What is different on Linux
# ---------------------------------------------------------------------------
#  - The classpath separator is ':' and the launcher is bin/Lyra, with the
#    application under lib/app and the runtime under lib/runtime.
#  - The askpass helper needs no native launcher. OpenSSH on Linux runs SSH_ASKPASS
#    as an ordinary program, so the shell script AskpassBridge already writes is
#    enough; there is no CreateProcess to satisfy.
#  - jpackage needs dpkg-deb (fakeroot) for --deb and rpmbuild for --rpm. It says so
#    plainly when one is missing, and the app-image is built either way.
#  - Nothing is code-signed. Distribution packages are signed by the repository that
#    carries them, not by the build.
#
# This script has to be run on Linux: jpackage builds only for the platform it runs
# on, and the JavaFX and libgit2 natives bundled are the ones Maven resolves here.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

case "$(uname -s)" in
  Linux) ;;
  *) echo "error: package-linux.sh has to be run on Linux (this is $(uname -s))" >&2; exit 1 ;;
esac

APP_NAME=Lyra
# Single-sourced from the pom, so the packaged image and the build never disagree -
# unless APP_VERSION is set: the release workflow passes the version from its tag.
APP_VERSION=${APP_VERSION:-$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout |
              sed 's/-SNAPSHOT$//')}
MAIN_CLASS=com.jfx.git.LyraApp

DEB=false
RPM=false
AOT=false
BSS=true
for arg in "$@"; do
  case "$arg" in
    --deb) DEB=true ;;
    --rpm) RPM=true ;;
    --aot) AOT=true ;;
    --no-bss) BSS=false ;;
    *) echo "error: unknown option $arg (expected --deb, --rpm, --aot, --no-bss)" >&2; exit 1 ;;
  esac
done

for tool in mvn jlink jpackage; do
  command -v "$tool" > /dev/null || { echo "error: $tool is not on PATH" >&2; exit 1; }
done

echo "==> Building jar and collecting runtime dependencies"
mvn -q clean package -DskipTests
mvn -q dependency:copy-dependencies -DoutputDirectory=target/deps -DincludeScope=runtime

MAIN_JAR="$(cd target && ls lyra-*.jar | head -1)"
[ -n "$MAIN_JAR" ] || { echo "error: no application jar in target/" >&2; exit 1; }

echo "==> Laying out the image input"
rm -rf target/pkg-input target/dist target/runtime
mkdir -p target/pkg-input/fx
cp "target/$MAIN_JAR" target/pkg-input/

for jar in target/deps/*.jar; do
  case "$(basename "$jar")" in
    # The Linux JavaFX jars are the real modules here.
    javafx-*-linux.jar) cp "$jar" target/pkg-input/fx/ ;;
    # Another platform's natives are dead weight, and the classifier-less artifacts
    # are empty aggregators that would shadow the real modules on the module path.
    javafx-*.jar) ;;
    *) cp "$jar" target/pkg-input/ ;;
  esac
done

# The licences and notices go in the image (lib/app/legal); see package-windows.sh.
mkdir -p target/pkg-input/legal
cp LICENSE NOTICE THIRD-PARTY-NOTICES.md third-party/tm4e/EPL-2.0.txt \
  src/main/resources/native/LICENSE-libgit2.txt src/main/resources/native/AUTHORS-libgit2.txt \
  target/pkg-input/legal/

ls target/pkg-input/fx/javafx-*-linux.jar > /dev/null 2>&1 || {
  echo "error: no Linux JavaFX jars were resolved; is this a Linux build of the dependencies?" >&2
  exit 1
}

# JavaFX's native libraries, unpacked next to the jars. Left inside the jars, JavaFX
# extracts them to ~/.openjfx/cache on first launch and, on every launch after, reads
# each one back out of its jar to checksum it against the cached copy before loading it.
# NativeLibLoader looks beside the jar first - on Linux in the jar's own directory,
# app/fx - and with the libraries there it loads them in place and never opens the
# cache. Should this step be skipped, nothing breaks: the extraction is still there as
# the fallback.
if command -v unzip > /dev/null; then
  mkdir -p target/pkg-input/fx
  for jar in target/pkg-input/fx/javafx-*.jar; do
    # unzip exits 11 for a jar with no native libraries in it (javafx-base).
    unzip -qq -o -j "$jar" '*.so' -d target/pkg-input/fx > /dev/null 2>&1 || [ $? -eq 11 ]
  done
else
  echo "    (unzip not found: JavaFX will extract its natives to ~/.openjfx/cache at run time)"
fi

# ---------------------------------------------------------------------------
# Binary stylesheets
# ---------------------------------------------------------------------------
# JavaFX loads a .bss in place of a .css beside it (its binary.css switch defaults to
# on). The application's five stylesheets took 145 ms to parse at a cold start and 50 ms
# to load as binary, and AtlantaFX already ships its theme as .bss - so with these the
# CSS parser is not loaded at startup at all. BinaryStylesheetTest checks that every
# sheet converts without losing a rule and that the binary is what loads.
#
# Generated here, into the packaged jar only, not into target/classes during the
# Maven build: a .bss left in target/classes would shadow any later edit to its .css
# made through an IDE build, which copies the .css and leaves the stale binary in front.
binary_stylesheets() {
  [ "$BSS" = true ] || { echo "    (--no-bss: stylesheets stay as text)"; return 0; }
  echo "==> Binary stylesheets"
  local work=target/bss fx_cp sheets
  rm -rf "$work" && mkdir -p "$work"
  fx_cp="$(printf '%s:' target/pkg-input/fx/javafx-*.jar)"
  sheets="$(jar tf "target/pkg-input/$MAIN_JAR" | grep '\.css$' || true)"
  [ -n "$sheets" ] || { echo "    (no stylesheets in $MAIN_JAR)"; return 0; }
  (cd "$work" && jar xf "../pkg-input/$MAIN_JAR" $sheets)
  local sheet added=()
  for sheet in $sheets; do
    # Css2Bin is JavaFX's own converter (Stylesheet.convertToBinary); on the class
    # path its package needs no --add-exports.
    java -cp "$fx_cp" com.sun.javafx.css.parser.Css2Bin "$work/$sheet"
    # jar's -C applies to the one file after it, so each binary gets its own.
    added+=(-C "$work" "${sheet%.css}.bss")
  done
  jar uf "target/pkg-input/$MAIN_JAR" "${added[@]}"
  echo "    $((${#added[@]} / 3)) stylesheets"
}
binary_stylesheets

# ---------------------------------------------------------------------------
# OAuth client secrets
# ---------------------------------------------------------------------------
# Bitbucket's sign-in needs its OAuth client secret, which is deliberately not in the
# repository (see src/main/resources/com/jfx/git/core/hosting/oauth-clients.properties).
# A release build writes it into the packaged jar here, from
# LYRA_BITBUCKET_CLIENT_SECRET (in CI, the repository secret of that name). It is
# never echoed, and the file holding it is removed again. Without it, Bitbucket's card
# offers tokens only - the build does not fail over it.
oauth_secrets() {
  local entry=com/jfx/git/core/hosting/oauth-secrets.properties
  if [ -z "${LYRA_BITBUCKET_CLIENT_SECRET:-}" ]; then
    if jar tf "target/pkg-input/$MAIN_JAR" | grep -qx "$entry"; then
      echo "==> OAuth secrets: using the local $entry from src/main/resources"
    else
      echo "==> OAuth secrets: LYRA_BITBUCKET_CLIENT_SECRET not set - Bitbucket sign-in disabled"
    fi
    return 0
  fi
  echo "==> OAuth secrets"
  local work=target/oauth-secrets
  rm -rf "$work" && mkdir -p "$work/$(dirname "$entry")"
  (umask 077 && printf 'bitbucket.clientSecret=%s\n' "$LYRA_BITBUCKET_CLIENT_SECRET" > "$work/$entry")
  jar uf "target/pkg-input/$MAIN_JAR" -C "$work" "$entry"
  rm -rf "$work"
  echo "    Bitbucket client secret added to $MAIN_JAR"
}
oauth_secrets

# ---------------------------------------------------------------------------
# Jar signatures
# ---------------------------------------------------------------------------
# JGit (three jars) and tm4e arrive signed by Eclipse. A signed jar costs at every
# launch - the JVM checks the signature when the jar is opened and hashes each class
# against the manifest as it loads it - and the AOT cache refuses its classes outright
# ("Skipping ...: Signed JAR"), which left JGit, the engine every repository opens
# through, out of it. Inside an application image the signatures protect nothing: the
# image is one artefact, signed (when it is) as a whole. So they are taken out here;
# the classes are untouched.
unsign_jars() {
  mkdir -p target/unsign
  cat > target/unsign/Unsign.java <<'JAVA'
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Rewrites each signed jar named without its signature files and per-entry digests. */
public final class Unsign {
    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            Path jar = Path.of(arg);
            try (JarFile file = new JarFile(jar.toFile(), false)) {
                if (file.stream().noneMatch(e -> isSignature(e.getName()))) continue;
            }
            Path copy = jar.resolveSibling(jar.getFileName() + ".unsigned");
            try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar));
                 ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(copy))) {
                for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                    if (isSignature(entry.getName())) continue;
                    byte[] data = in.readAllBytes();
                    if (entry.getName().equalsIgnoreCase(JarFile.MANIFEST_NAME)) {
                        Manifest manifest = new Manifest(new ByteArrayInputStream(data));
                        manifest.getEntries().clear();
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        manifest.write(bytes);
                        data = bytes.toByteArray();
                    }
                    ZipEntry kept = new ZipEntry(entry.getName());
                    kept.setTime(entry.getTime());
                    out.putNextEntry(kept);
                    out.write(data);
                    out.closeEntry();
                }
            }
            Files.move(copy, jar, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("    unsigned " + jar.getFileName());
        }
    }

    private static boolean isSignature(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/") || upper.indexOf('/', 9) >= 0) return false;
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                || upper.endsWith(".EC") || upper.startsWith("META-INF/SIG-");
    }
}
JAVA
  java target/unsign/Unsign.java target/pkg-input/*.jar
}
unsign_jars

echo "==> Building a JDK-only runtime"
# The same list as package-windows.sh, for the same reasons. Keep the two in step: a
# module missing here is a NoClassDefFoundError the first time a screen needs it.
JLINK_MODULES=java.base,java.desktop,java.logging,java.prefs,java.naming,java.management,java.scripting,java.net.http,jdk.crypto.ec,jdk.httpserver,jdk.unsupported,jdk.zipfs

# --compress=zip-0: the image is compressed once, by the installer. Compressed jimage
# entries are inflated again on every class load, at every launch, for the life of the
# installation - startup paid for a smaller download the installer already gives.
#
# --generate-cds-archive: the JDK's own classes as a class-data-sharing archive in the
# runtime, as a full JDK ships one. Without it a jlinked runtime starts with no CDS at
# all and loads and verifies java.base from scratch on every launch.
jlink \
  --add-modules "$JLINK_MODULES" \
  --strip-debug --no-header-files --no-man-pages --compress=zip-0 \
  --generate-cds-archive \
  --output target/runtime

# jlink writes the archives for the default object layout only. The launcher runs with
# -XX:+UseCompactObjectHeaders (below), which cannot map them - CDS silently switches
# off, and startup is slower than with neither flag. So the compact-headers archive
# (classes_coh.jsa) is dumped into the runtime too. The uncompressed-oops archive goes:
# it serves only heaps over 32 GB without compact headers, which no launcher here runs
# (15 MB). The default one stays for the askpass helper and the smoke test below.
target/runtime/bin/java -XX:+UseCompactObjectHeaders -Xshare:dump > /dev/null
rm -f target/runtime/bin/server/classes_nocoops.jsa target/runtime/lib/server/classes_nocoops.jsa

echo "==> jpackage"
JPACKAGE_ARGS=(
  --name "$APP_NAME"
  --app-version "$APP_VERSION"
  --input target/pkg-input
  --main-jar "$MAIN_JAR"
  --main-class "$MAIN_CLASS"
  --runtime-image target/runtime
  --dest target/dist
  # $APPDIR is substituted by the generated launcher at run time.
  --java-options '--module-path $APPDIR/fx'
  --java-options '--add-modules javafx.controls,javafx.graphics,javafx.base'
  # The application is on the classpath, so its FFM calls come from the unnamed
  # module; JavaFX loads its own native libraries from its named module.
  --java-options '--enable-native-access=ALL-UNNAMED,javafx.graphics'
  # Memory. A desktop client sits idle for hours, and G1 only hands heap back to the
  # OS after a collection - which an idle application never triggers. A periodic
  # collection every five minutes of idleness returns it. String deduplication
  # shares the char arrays of the many identical strings a repository produces
  # (paths, author names, ref names). Compact object headers (a product flag since
  # JDK 25, JEP 519) take 4 bytes off every object, and a commit graph is a great
  # many small objects.
  #
  # A heap ceiling of 1 GB, about eight times the 120 MB live with two tabs open or
  # 30,000 rows of a 100,000-commit history loaded. Without one the JVM takes a quarter
  # of the machine's memory as its limit, G1 keeps garbage in a heap several times the
  # live set (900 MB private on that history, 713 MB capped), and JGit's pack cache -
  # a twentieth of the limit, see LyraApp.start - sized itself at 204 MB.
  #
  # G1 also keeps up to 70% of its heap free after a collection by default, and on a
  # 100 MB live set that free part is most of what the heap adds to the process. Free
  # ratios of 10-30% hold it to about a third above the live set. With desktop-plus
  # (42,000 commits) open and diffs from 20 commits read in seven languages, Task
  # Manager showed 393 MB without them and 322 MB with them (means of 3 and 4 runs),
  # and no pause went past 12 ms either way. Measured and not taken: a one-minute
  # periodic collection (no reliable gain when idle, and a wake-up a minute), 4 or 2
  # GC threads (20 MB less, pauses to 69 and 103 ms), SerialGC (86 ms pauses, no
  # saving) and a 512 MB ceiling (no saving: the heap never came near it).
  #
  # The IntelliJ run configuration (.idea/runConfigurations/LyraApp.xml) passes the
  # same flags, so a development run uses the memory a shipped one would. Without them
  # it had a 4 GB ceiling on a 16 GB machine; keep the lists in step.
  --java-options '-Xmx1g'
  --java-options '-XX:G1PeriodicGCInterval=300000'
  --java-options '-XX:+UseStringDeduplication'
  --java-options '-XX:+UseCompactObjectHeaders'
  --java-options '-XX:MinHeapFreeRatio=10'
  --java-options '-XX:MaxHeapFreeRatio=30'
)

jpackage --type app-image "${JPACKAGE_ARGS[@]}"
echo "    app-image: target/dist/$APP_NAME/bin/$APP_NAME"

# ---------------------------------------------------------------------------
# Packaged-image smoke test
# ---------------------------------------------------------------------------
# As on Windows: the Maven tests run against the build JDK, which has every module,
# so only the shipped image can show that JLINK_MODULES is short one. The same
# checks, run by the image's own runtime.
smoke_image() {
  local image="target/dist/$APP_NAME"
  local runtime_java="$image/lib/runtime/bin/java"
  [ -x "$runtime_java" ] || { echo "error: no runtime java in $image" >&2; exit 1; }

  local cp
  cp="$(printf '%s:' "$image"/lib/app/*.jar)"

  mkdir -p target/smoke
  cat > target/smoke/PackagedImageSmoke.java <<'JAVA'
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitService;
import com.jfx.git.core.TerminalLauncher;
import com.jfx.git.core.bitbucket.BitbucketClient;
import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.gitlab.GitLabClient;
import com.jfx.git.core.libgit2.Libgit2Loader;
import com.jfx.git.ui.syntax.GrammarRegistry;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs inside the packaged runtime image. Each check is a JDK module the shipped image
 * has to carry, or a platform lookup that must not throw, not a test of application
 * logic.
 */
public final class PackagedImageSmoke {

    private static int failures;

    public static void main(String[] args) throws Exception {
        check("provider clients (java.net.http)", () -> {
            new GitHubClient();
            new GitLabClient();
            new BitbucketClient();
        });

        check("syntax grammars", () -> {
            if (GrammarRegistry.instance().languageForExtension("java") == null) {
                throw new IllegalStateException("no grammar for .java");
            }
        });

        // FFM native loading: allowed to be unavailable, not allowed to throw. On Linux
        // this is also what says whether the bundled libgit2 .so was found.
        check("native library loading", () -> {
            Libgit2Loader.init();
            if (!Libgit2Loader.isAvailable()) {
                System.out.println("      (libgit2 unavailable: " + Libgit2Loader.unavailableReason() + ")");
            }
        });

        check("local git operation", () -> {
            Path dir = Files.createTempDirectory("lyra-smoke");
            try {
                GitResult init = GitService.initRepository(dir.toFile());
                if (!init.isOk()) throw new IllegalStateException(init.getMessage());
                GitService git = new GitService();
                if (!git.openRepository(dir.toFile())) {
                    throw new IllegalStateException("could not reopen the initialised repository");
                }
                git.close();
            } finally {
                deleteTree(dir.toFile());
            }
        });

        // Which terminal would be opened is a PATH search; it has to answer, with a
        // name or with nothing, on a build machine that may well have no terminal.
        check("terminal detection", () -> {
            String found = TerminalLauncher.describeDetected();
            System.out.println("      (terminal: " + (found.isBlank() ? "none found" : found) + ")");
        });

        if (failures > 0) {
            System.err.println("smoke test: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("    smoke test: all checks passed");
    }

    private interface Check { void run() throws Exception; }

    private static void check(String name, Check check) {
        try {
            check.run();
            System.out.println("    ok   " + name);
        } catch (Throwable t) {
            failures++;
            System.out.println("    FAIL " + name + ": " + t);
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
JAVA

  echo "==> Smoke-testing the packaged image"
  # Compiled by the build JDK, run by the packaged runtime: the point is which
  # modules the *runtime* has, so it must be the one that executes the classes.
  javac -nowarn -cp "$cp" -d target/smoke target/smoke/PackagedImageSmoke.java
  "$runtime_java" -cp "$cp""target/smoke" PackagedImageSmoke
}

# ---------------------------------------------------------------------------
# AOT cache (opt-in: --aot)
# ---------------------------------------------------------------------------
# JDK 25's ahead-of-time cache (JEP 483, 514, 515) holds the application's classes
# already loaded and linked, and method profiles, from a training run - so a launch
# skips most class loading and warms up sooner. The training run is the packaged image
# itself, started with -Dlyra.training=<repository>: it opens that repository, waits
# for its first page to be drawn and the repository lane to go quiet, and exits; the
# JVM writes app.aot as it exits. AOT_TRAINING_REPO picks the repository (default:
# this checkout). It needs a display - on a headless Linux build machine, run the
# script under xvfb-run.
#
# A cache is only used by a JVM with the same class path and the same flags, so the
# training run takes both from the .cfg jpackage wrote for the launcher, and the
# launcher is told about the cache only once it exists.
#
# To check it is used, run the image with the AOT log on and look for the cache being
# opened and its classes used, or for the reason it was rejected:
#   JAVA_TOOL_OPTIONS='-Xlog:aot*=info:file=aot.log' ./target/dist/Lyra/bin/Lyra
# A rejected cache costs nothing but the log line: the JVM runs without it. It is tied to
# the jars it was trained with, so it has to be trained again for every build.
aot_train() {
  [ "$AOT" = true ] || return 0
  echo "==> Training the AOT cache"
  local app_dir="target/dist/$APP_NAME/lib/app"
  local cfg="$app_dir/$APP_NAME.cfg"
  [ -f "$cfg" ] || { echo "error: no launcher configuration at $cfg" >&2; exit 1; }
  local app_abs
  app_abs="$(cd "$app_dir" && pwd)"

  local key value word main_class="" classpath="" options=()
  while IFS='=' read -r key value; do
    value="${value%$'\r'}"
    case "$key" in
      app.mainclass) main_class="$value" ;;
      app.classpath) classpath="${classpath:+$classpath:}${value//\$APPDIR/$app_abs}" ;;
      java-options)
        for word in $value; do options+=("${word//\$APPDIR/$app_abs}"); done ;;
    esac
  done < <(tr -d '\r' < "$cfg")
  [ -n "$main_class" ] || main_class="$MAIN_CLASS"

  local repo="${AOT_TRAINING_REPO:-$HERE}"
  local scratch
  scratch="$(mktemp -d)"
  rm -f "$app_dir/app.aot"
  # Its own settings file: the training run must not read or rewrite the user's.
  "target/dist/$APP_NAME/lib/runtime/bin/java" "${options[@]}" \
    -XX:AOTCacheOutput="$app_abs/app.aot" \
    -Dlyra.training="$repo" -Dlyra.settings="$scratch/settings.properties" \
    -cp "$classpath" "$main_class"
  rm -rf "$scratch"
  [ -f "$app_dir/app.aot" ] || { echo "error: the training run wrote no app.aot" >&2; exit 1; }

  # Only now that the file exists: a launcher pointed at a missing cache prints an error
  # on every start before carrying on without it.
  awk -v line='java-options=-XX:AOTCache=$APPDIR/app.aot' \
    '{ print } /^\[JavaOptions\]\r?$/ { print line (($0 ~ /\r$/) ? "\r" : "") }' \
    "$cfg" > "$cfg.tmp" && mv "$cfg.tmp" "$cfg"
  echo "    app.aot: $(du -h "$app_dir/app.aot" | cut -f1)"
}

smoke_image
aot_train

# Menu entry and package metadata, shared by both package types.
LINUX_ARGS=(
  --linux-shortcut
  --linux-menu-group "Development"
  --linux-app-category "devel"
  --linux-package-name lyra
  # git is what the Native engine runs; the application still starts without it and
  # falls back to JGit, so it is a recommendation in spirit - but jpackage has only
  # hard dependencies, and a git client without git is not what anyone installs.
  --linux-package-deps git
)

# Packaged from the finished app-image rather than rebuilt from the inputs, so the
# packages carry exactly what was smoke-tested - and the trained AOT cache, which a
# rebuild would leave out.
INSTALLER_ARGS=(
  --app-image "target/dist/$APP_NAME"
  --name "$APP_NAME"
  --app-version "$APP_VERSION"
  --dest target/dist
)

if [ "$DEB" = true ]; then
  jpackage --type deb "${INSTALLER_ARGS[@]}" "${LINUX_ARGS[@]}"
  echo "    package: $(ls target/dist/*.deb 2>/dev/null | head -1)"
fi

if [ "$RPM" = true ]; then
  jpackage --type rpm "${INSTALLER_ARGS[@]}" "${LINUX_ARGS[@]}"
  echo "    package: $(ls target/dist/*.rpm 2>/dev/null | head -1)"
fi

echo
echo "Done. Size: $(du -sh "target/dist/$APP_NAME" | cut -f1)"
echo "Run it with: ./target/dist/$APP_NAME/bin/$APP_NAME"
