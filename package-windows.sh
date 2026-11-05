#!/usr/bin/env bash
# Builds a self-contained Windows application into target/dist/Lyra.
#
# Usage:  ./package-windows.sh [--installer] [--aot] [--no-bss]
#
#   (no flag)     app-image  -> target/dist/Lyra/Lyra.exe, runnable in place
#   --installer   also two MSIs -> target/dist/Lyra-1.0.msi (per machine) and
#                 target/dist/Lyra-1.0-per-user.msi (no administrator needed); see installers below
#   --aot         also train an AOT cache (JDK 25) into the image; see aot_train below
#   --no-bss      leave the stylesheets as text; see binary_stylesheets below
#
# ---------------------------------------------------------------------------
# Why jpackage and not `mvn javafx:jlink`
# ---------------------------------------------------------------------------
# jlink refuses filename-based automatic modules, and roughly thirteen of this
# project's dependencies are exactly that: JGit and its two SSH artifacts, sshd
# ×2, jna ×2, slf4j ×2, richtextfx, reactfx, undofx, flowless and wellbehavedfx.
# Giving each a generated module-info is possible but fragile, and buys nothing
# the application needs.
#
# So the runtime image is built from JDK modules only -- where no automatic
# module can appear -- and the application runs from the classpath, where the
# module system is not consulted at all.
#
# JavaFX is the exception and stays on the module path. The toolkit checks that
# javafx.graphics is a named module before it will start; loaded from the
# classpath it fails with "JavaFX runtime components are missing and are required
# to run this application".
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

APP_NAME=Lyra
# Single-sourced from the pom, so the packaged image and the build never disagree -
# unless APP_VERSION is set: the release workflow passes the version from its tag.
APP_VERSION=${APP_VERSION:-$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout |
              sed 's/-SNAPSHOT$//')}
MAIN_CLASS=com.jfx.git.LyraApp

INSTALLER=false
AOT=false
BSS=true
for arg in "$@"; do
  case "$arg" in
    --installer) INSTALLER=true ;;
    --aot) AOT=true ;;
    --no-bss) BSS=false ;;
    *) echo "error: unknown option $arg (expected --installer, --aot, --no-bss)" >&2; exit 1 ;;
  esac
done

for tool in mvn jlink jpackage; do
  command -v "$tool" > /dev/null || { echo "error: $tool is not on PATH" >&2; exit 1; }
done

echo "==> Building jar and collecting runtime dependencies"
# jpackage leaves the launchers it writes read-only, which mvn clean cannot delete.
[ ! -d target/dist ] || chmod -R u+w target/dist
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
    # Platform-specific JavaFX jars are the real modules.
    javafx-*-win.jar|javafx-*-linux.jar) cp "$jar" target/pkg-input/fx/ ;;
    # The classifier-less JavaFX artifacts are empty aggregators; including them
    # would put an empty javafx.controls ahead of the real one on the module path.
    javafx-*.jar) ;;
    *) cp "$jar" target/pkg-input/ ;;
  esac
done

# The licences and notices go in the image (app/legal), because the bundled
# components' licences require them in a binary distribution:
#   LICENSE                 Lyra's own licence, Apache-2.0 - and the Apache-2.0 text that
#                           MINA SSHD, Commons Codec, Gson and the other Apache-licensed
#                           jars require a copy of
#   NOTICE                  those jars' NOTICE files
#   THIRD-PARTY-NOTICES.md  every component, its licence, copyright lines and source
#   EPL-2.0.txt             TM4E core
#   *-libgit2.txt           libgit2 and the code it bundles (also in the jar, under native/)
# The JDK's and OpenJFX's licence, GPL-2.0 with the Classpath Exception, is in the
# runtime's own legal directory, which jlink writes.
mkdir -p target/pkg-input/legal
cp LICENSE NOTICE THIRD-PARTY-NOTICES.md third-party/tm4e/EPL-2.0.txt \
  src/main/resources/native/LICENSE-libgit2.txt src/main/resources/native/AUTHORS-libgit2.txt \
  target/pkg-input/legal/

# JavaFX's native libraries, unpacked next to the jars. Left inside the jars, JavaFX
# extracts them to ~/.openjfx/cache on first launch and, on every launch after, reads
# each one back out of its jar to checksum it against the cached copy before loading it.
# NativeLibLoader looks beside the jar first - on Windows in ../bin relative to it,
# so app/bin for the jars in app/fx - and with the libraries there it loads them in
# place and never opens the cache. Should this step be skipped, nothing breaks: the
# extraction is still there as the fallback.
if command -v unzip > /dev/null; then
  mkdir -p target/pkg-input/bin
  for jar in target/pkg-input/fx/javafx-*.jar; do
    # unzip exits 11 for a jar with no native libraries in it (javafx-base).
    unzip -qq -o -j "$jar" '*.dll' -d target/pkg-input/bin > /dev/null 2>&1 || [ $? -eq 11 ]
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
  fx_cp="$(printf '%s;' target/pkg-input/fx/javafx-*.jar)"
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
# Only JDK modules, so no application dependency can block jlink.
#   java.naming, java.management, java.scripting  required by JGit and sshd
#   java.net.http                                 HttpClient, in every provider client
#   jdk.crypto.ec                                 TLS for https:// remotes
#   jdk.httpserver                                the one-shot 127.0.0.1 listener that
#                                                 OAuth sign-in redirects back to
#   jdk.unsupported                               sun.misc.Unsafe, used by several deps
#   jdk.zipfs                                     jar/zip filesystem provider
#
# This list is the whole contract. The application runs from the classpath, so its
# own module-info is never consulted and a module missing here is not a build
# failure - it is a NoClassDefFoundError the first time a screen needs it.
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

# ---------------------------------------------------------------------------
# Code signing
# ---------------------------------------------------------------------------
# Unsigned, SmartScreen shows the MSI as an unknown publisher and asks the user
# to confirm they meant to run software from nobody in particular - which is
# exactly the habit an installer should not be teaching.
#
# Signing is also what lets a managed machine run Lyra at all. Under an application-
# control policy (AppLocker, App Control for Business) a standard user runs only what
# the policy allows, and the default rules allow Program Files and Windows and nothing
# a user can write to: an unsigned Lyra.exe unpacked from the zip is "blocked by your
# system administrator". IT can allow a signed build by its publisher, one rule that
# outlives every release; an unsigned one only by path, or by hash, again for each
# release.
#
# Signing needs a certificate this repository cannot carry, so it is opt-in, with
# signtool.exe (Windows SDK) on PATH and one of:
#
#   SIGNTOOL_CERT=~/certs/lyra.pfx SIGNTOOL_PASSWORD=...        a .pfx file
#   SIGNTOOL_CERT=<thumbprint> SIGNTOOL_USE_STORE=1             a certificate in the store,
#                                                               or on a hardware token
#   SIGNTOOL_DLIB=.../Azure.CodeSigning.Dlib.dll                a signing service's signtool
#   SIGNTOOL_DMDF=.../metadata.json                             plug-in - Azure Artifact
#                                                               Signing, as the release does
#
# A publicly trusted certificate issued since June 2023 keeps its key on a hardware
# token or in a cloud HSM, so for one of those the store or the dlib is the way in;
# the README's Windows packaging section has the options. Without any of them the
# build says plainly that it produced unsigned output rather than pretending otherwise.
#
# The timestamp URL matters: a signature without one stops verifying the day the
# certificate expires, including on copies already installed. Artifact Signing's
# certificates last three days, so for it the timestamp is the whole signature's life.
SIGNTOOL_TIMESTAMP_URL="${SIGNTOOL_TIMESTAMP_URL:-http://timestamp.digicert.com}"

signing() { [ -n "${SIGNTOOL_CERT:-}${SIGNTOOL_DLIB:-}" ]; }

# Signs the files named, in one signtool run, and verifies each.
sign() {
  if ! signing; then
    echo "    (unsigned: set SIGNTOOL_CERT or SIGNTOOL_DLIB to sign - SmartScreen will warn about this build)"
    return 0
  fi
  command -v signtool > /dev/null || {
    echo "error: signing is configured but signtool is not on PATH" >&2; exit 1; }

  local args=(sign /fd SHA256 /tr "$SIGNTOOL_TIMESTAMP_URL" /td SHA256)
  if [ -n "${SIGNTOOL_DLIB:-}" ]; then
    [ -n "${SIGNTOOL_DMDF:-}" ] || {
      echo "error: SIGNTOOL_DLIB is set but SIGNTOOL_DMDF, its metadata file, is not" >&2; exit 1; }
    args+=(/dlib "$SIGNTOOL_DLIB" /dmdf "$SIGNTOOL_DMDF")
  elif [ "${SIGNTOOL_USE_STORE:-}" = "1" ]; then
    args+=(/sha1 "$SIGNTOOL_CERT")            # certificate thumbprint in the local store
  else
    args+=(/f "$SIGNTOOL_CERT")
    [ -n "${SIGNTOOL_PASSWORD:-}" ] && args+=(/p "$SIGNTOOL_PASSWORD")
  fi

  echo "==> Signing $# file(s)"
  # Git Bash rewrites an argument that looks like a POSIX path for a native program,
  # and signtool's switches (/fd, /tr, /q) look like one.
  MSYS_NO_PATHCONV=1 signtool "${args[@]}" "$@"
  MSYS_NO_PATHCONV=1 signtool verify /pa /q "$@"
}

# Signs every executable and DLL in the image that its vendor has not: the two
# launchers, and JavaFX's libraries, which OpenJFX ships unsigned. The runtime's
# (Eclipse Foundation's) and the VC++ runtime's (Microsoft's) keep the signatures they
# came with. App Control for Business checks each DLL a process loads, not only the
# .exe it started from, so a launcher signed on its own would still be stopped at
# glass.dll.
sign_image() {
  signing || { sign; return 0; }
  local file unsigned=()
  while IFS= read -r -d '' file; do
    MSYS_NO_PATHCONV=1 signtool verify /pa /q "$file" > /dev/null 2>&1 || unsigned+=("$file")
  done < <(find "target/dist/$APP_NAME" -type f \( -iname '*.exe' -o -iname '*.dll' \) -print0)
  [ ${#unsigned[@]} -gt 0 ] || { echo "    (every executable in the image is already signed)"; return 0; }
  # jpackage leaves its launchers read-only, and the signature is written into the file.
  chmod u+w "${unsigned[@]}"
  sign "${unsigned[@]}"
}

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
  # Windows' own OpenSSH starts SSH_ASKPASS through CreateProcess, so it can run only a
  # real executable - not askpass.sh, and never a .cmd (cmd.exe cuts ssh's multi-line
  # host-key question at the first newline, and mis-parses quotes a server controls).
  # AskpassBridge looks for this launcher beside Lyra.exe.
  --add-launcher "LyraAskpass=target/askpass-launcher.properties"
)

# The helper prints one answer and exits, so it gets none of the main launcher's
# options: loading JavaFX's modules would only make every prompt slower to appear.
mkdir -p target
cat > target/askpass-launcher.properties <<'PROPS'
main-class=com.jfx.git.core.AskpassMain
win-console=true
java-options=-XX:TieredStopAtLevel=1
PROPS

jpackage --type app-image "${JPACKAGE_ARGS[@]}"
echo "    app-image: target/dist/$APP_NAME/$APP_NAME.exe"

# ---------------------------------------------------------------------------
# Packaged-image smoke test
# ---------------------------------------------------------------------------
# The Maven test job runs against the build JDK, which has every module. It can
# therefore never notice that JLINK_MODULES above is short one entry - that only
# shows up in the shipped image, at the moment a user opens the screen that needs
# it. So the image is exercised here, with its own runtime, before it is signed.
#
# Headless on purpose: this checks that the classes the application depends on
# resolve and construct inside the packaged runtime, which is what the module set
# decides. Starting the JavaFX window is a separate, interactive check.
smoke_image() {
  local image="target/dist/$APP_NAME"
  local runtime_java="$image/runtime/bin/java"
  [ -x "$runtime_java" ] || runtime_java="$image/runtime/bin/java.exe"
  [ -x "$runtime_java" ] || { echo "error: no runtime java in $image" >&2; exit 1; }

  local cp
  cp="$(printf '%s;' "$image"/app/*.jar)"

  mkdir -p target/smoke
  cat > target/smoke/PackagedImageSmoke.java <<'JAVA'
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitService;
import com.jfx.git.core.bitbucket.BitbucketClient;
import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.gitlab.GitLabClient;
import com.jfx.git.core.libgit2.Libgit2Loader;
import com.jfx.git.ui.syntax.GrammarRegistry;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs inside the packaged runtime image. Every check here failed at least once
 * because a JDK module was missing from the jlink list, so each one is a module
 * the shipped image has to carry, not a test of application logic.
 */
public final class PackagedImageSmoke {

    private static int failures;

    public static void main(String[] args) throws Exception {
        // java.net.http: constructing the client builds an HttpClient eagerly.
        check("provider clients (java.net.http)", () -> {
            new GitHubClient();
            new GitLabClient();
            new BitbucketClient();
        });

        // java.base resources: the grammars are jar resources read through the
        // application class loader.
        check("syntax grammars", () -> {
            if (GrammarRegistry.instance().languageForExtension("java") == null) {
                throw new IllegalStateException("no grammar for .java");
            }
        });

        // FFM native loading: allowed to be unavailable, not allowed to throw.
        check("native library loading", () -> {
            Libgit2Loader.init();
            if (!Libgit2Loader.isAvailable()) {
                System.out.println("      (libgit2 unavailable: " + Libgit2Loader.unavailableReason() + ")");
            }
        });

        // A real local git operation, which pulls in JGit and everything it needs.
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
        // Best effort: a leftover temp directory is not worth failing the build over.
        file.delete();
    }
}
JAVA

  echo "==> Smoke-testing the packaged image"
  # Compiled by the build JDK, run by the packaged runtime: the point is which
  # modules the *runtime* has, so it must be the one that executes the classes.
  javac -nowarn -cp "$cp" -d target/smoke target/smoke/PackagedImageSmoke.java
  "$runtime_java" -cp "$cp""target/smoke" PackagedImageSmoke

  # The askpass launcher: with no bridge to talk to, AskpassMain exits 1 and prints
  # nothing. A launcher that cannot find its class also exits non-zero, but says so on
  # stderr - so it is the silence that proves the helper actually started.
  local helper="$image/LyraAskpass.exe"
  [ -f "$helper" ] || { echo "error: $helper was not packaged" >&2; exit 1; }
  local helper_err status
  helper_err="$(env -u LYRA_ASKPASS_PORT -u LYRA_ASKPASS_TOKEN "$helper" "probe" 2>&1 >/dev/null)" && status=0 || status=$?
  if [ "$status" -ne 1 ] || [ -n "$helper_err" ]; then
    echo "error: $helper did not run AskpassMain (exit $status): $helper_err" >&2
    exit 1
  fi
  echo "    ok   askpass launcher"
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
#   JAVA_TOOL_OPTIONS='-Xlog:aot*=info:file=aot.log' ./target/dist/Lyra/Lyra.exe
# A rejected cache costs nothing but the log line: the JVM runs without it. It is tied to
# the jars it was trained with, so it has to be trained again for every build.
aot_train() {
  [ "$AOT" = true ] || return 0
  echo "==> Training the AOT cache"
  local app_dir="target/dist/$APP_NAME/app"
  local cfg="$app_dir/$APP_NAME.cfg"
  [ -f "$cfg" ] || { echo "error: no launcher configuration at $cfg" >&2; exit 1; }
  local app_abs
  app_abs="$(cd "$app_dir" && pwd -W)"

  local key value word main_class="" classpath="" options=()
  while IFS='=' read -r key value; do
    value="${value%$'\r'}"
    case "$key" in
      app.mainclass) main_class="$value" ;;
      app.classpath) classpath="${classpath:+$classpath;}${value//\$APPDIR/$app_abs}" ;;
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
  "target/dist/$APP_NAME/runtime/bin/java" "${options[@]}" \
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

# ---------------------------------------------------------------------------
# Installers (--installer)
# ---------------------------------------------------------------------------
# Two MSIs from the one image, for the two ways Windows installs software:
#
#   Lyra-<version>.msi           per machine, into Program Files, for every user. Needs
#                                an administrator: a UAC prompt, or IT deploying it
#                                (msiexec /i Lyra-<version>.msi /qn). On a machine under
#                                AppLocker or App Control this is the one that runs,
#                                because those policies allow Program Files and block
#                                folders a user can write to.
#   Lyra-<version>-per-user.msi  for the current user only, into %LOCALAPPDATA%\Lyra,
#                                with Start menu and desktop shortcuts of their own. No
#                                administrator and no UAC prompt, for a standard
#                                account on a machine that lets it run what it installs.
#
# Each is a product line of its own, with its own upgrade code: Windows Installer
# looks for the version to upgrade only within one install context, so a per-user
# install never sees a per-machine one, and the other way round. The per-machine code
# is jpackage's default for this name and vendor ("UpgradeCode/Unknown/Lyra"), pinned
# so that setting a vendor some day cannot orphan the 1.0.x installations.
MACHINE_UPGRADE_UUID=A620F59F-253F-3A55-A8DC-622AFA858564
USER_UPGRADE_UUID=33784CAC-1E7C-47A6-B22E-4DA8CD42F334

# A name-based UUID, as java.util.UUID.nameUUIDFromBytes makes them - the way jpackage
# derives its codes (uuid3 "UpgradeCode/Unknown/Lyra" is MACHINE_UPGRADE_UUID).
uuid3() {
  local h
  h="$(printf '%s' "$1" | md5sum | cut -c1-32)"
  printf '%s-%s-3%s-%x%s-%s\n' "${h:0:8}" "${h:8:4}" "${h:13:3}" \
    $(( (0x${h:16:1} & 0x3) | 0x8 )) "${h:17:3}" "${h:20:12}" | tr a-f A-F
}

installers() {
  [ "$INSTALLER" = true ] || return 0
  # Needs WiX Toolset v3 on PATH; jpackage says so plainly if it is missing.
  # Packaged from the finished app-image rather than rebuilt from the inputs: a rebuild
  # would drop the signatures just put on the image, and the trained AOT cache with it.
  local msi=(jpackage --type msi --app-image "target/dist/$APP_NAME"
             --name "$APP_NAME" --app-version "$APP_VERSION"
             --win-dir-chooser --win-menu --win-shortcut)
  local machine_msi="target/dist/$APP_NAME-$APP_VERSION.msi"
  local user_msi="target/dist/$APP_NAME-$APP_VERSION-per-user.msi"

  echo "==> MSI, per machine"
  "${msi[@]}" --dest target/dist --win-upgrade-uuid "$MACHINE_UPGRADE_UUID"

  # jpackage derives the ProductCode from vendor, name and version alone, so both MSIs
  # of a version would carry the same one, and Windows Installer, finding that code
  # installed per machine already, would refuse the per-user MSI as "another version of
  # this product".
  # overrides.wxi, which jpackage's main.wxs includes ahead of its Product element,
  # gives the per-user one its own, derived like jpackage's so that a rebuild of a
  # version keeps it.
  echo "==> MSI, per user"
  local res=target/msi-per-user
  rm -rf "$res" && mkdir -p "$res/resources" "$res/out"
  cat > "$res/resources/overrides.wxi" <<WXI
<?xml version="1.0" encoding="utf-8"?>
<Include xmlns="http://schemas.microsoft.com/wix/2006/wi">
  <?ifndef JpProductCode ?>
  <?error jpackage no longer defines JpProductCode: package-windows.sh's per-user ProductCode needs another way in ?>
  <?endif ?>
  <?undef JpProductCode ?>
  <?define JpProductCode = "{$(uuid3 "ProductCode/$APP_NAME/per-user/$APP_VERSION")}" ?>
</Include>
WXI
  "${msi[@]}" --dest "$res/out" --win-upgrade-uuid "$USER_UPGRADE_UUID" \
    --win-per-user-install --resource-dir "$res/resources"
  mv "$res/out/$APP_NAME-$APP_VERSION.msi" "$user_msi"

  echo "    installers: $machine_msi, $user_msi"
  sign "$machine_msi" "$user_msi"
}

smoke_image
aot_train
sign_image
installers

echo
echo "Done. Size: $(du -sh "target/dist/$APP_NAME" | cut -f1)"
echo "Run it with: ./target/dist/$APP_NAME/$APP_NAME.exe"
