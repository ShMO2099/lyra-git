#!/usr/bin/env bash
# Rebuilds the patched TM4E core in third-party/maven-repo from the upstream build it
# patches. See README.md beside this script for what the patch does and why.
#
#   third-party/tm4e/build.sh
#
# Needs a JDK (javac, jar) and the joni and jcodings jars the pom pins, which any Maven
# build of the project has already put in the local repository. The upstream jar is taken
# from the local repository too, or downloaded from Eclipse's snapshot repository, and is
# checked against the hash recorded below either way.
#
# The jar is reproducible: rebuilt with the same JDK it comes out byte for byte the same,
# so a rebuild that changes nothing shows no change in git.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

UPSTREAM_VERSION=0.18.0-20260827.121906-2
UPSTREAM_SHA1=91a3eed07783574ef6d25bad7d42f645e94f0e88
VERSION=$UPSTREAM_VERSION-jfxgit1
JONI_VERSION=2.2.1
JCODINGS_VERSION=1.0.58
# The upstream build's own timestamp, for every entry: no build time ends up in the jar.
ENTRY_DATE=2026-08-27T12:19:06Z

M2="${M2_REPO:-$HOME/.m2/repository}"
UPSTREAM_JAR="$M2/org/eclipse/org.eclipse.tm4e.core/0.18.0-SNAPSHOT/org.eclipse.tm4e.core-$UPSTREAM_VERSION.jar"
UPSTREAM_URL="https://repo.eclipse.org/content/repositories/tm4e-snapshots/org/eclipse/org.eclipse.tm4e.core/0.18.0-SNAPSHOT/org.eclipse.tm4e.core-$UPSTREAM_VERSION.jar"
JONI_JAR="$M2/org/jruby/joni/joni/$JONI_VERSION/joni-$JONI_VERSION.jar"
JCODINGS_JAR="$M2/org/jruby/jcodings/jcodings/$JCODINGS_VERSION/jcodings-$JCODINGS_VERSION.jar"
OUT_DIR="$ROOT/third-party/maven-repo/org/eclipse/org.eclipse.tm4e.core/$VERSION"
OUT_BASE="$OUT_DIR/org.eclipse.tm4e.core-$VERSION"

for tool in javac jar sha1sum md5sum; do
  command -v "$tool" > /dev/null || { echo "error: $tool is not on PATH" >&2; exit 1; }
done

# javac and jar are Windows programs under Git Bash: they need Windows paths and ';'.
if command -v cygpath > /dev/null; then
  native() { cygpath -m "$1"; }
  SEP=';'
else
  native() { printf '%s' "$1"; }
  SEP=':'
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [ ! -f "$UPSTREAM_JAR" ]; then
  echo "==> Downloading the upstream build"
  UPSTREAM_JAR="$WORK/upstream.jar"
  curl -fsSL -o "$UPSTREAM_JAR" "$UPSTREAM_URL"
fi
actual="$(sha1sum "$UPSTREAM_JAR" | cut -d' ' -f1)"
[ "$actual" = "$UPSTREAM_SHA1" ] || {
  echo "error: $UPSTREAM_JAR has SHA-1 $actual, expected $UPSTREAM_SHA1" >&2; exit 1; }
for dep in "$JONI_JAR" "$JCODINGS_JAR"; do
  [ -f "$dep" ] || { echo "error: $dep is missing; run any Maven build of the project first" >&2; exit 1; }
done

echo "==> Compiling the patched classes"
# The stand-in for Eclipse's null-analysis annotation is compiled for javac's sake only;
# the annotation is class-retention and nothing reads it at run time.
mkdir -p "$WORK/annotation" "$WORK/classes"
javac --release 21 -nowarn -d "$(native "$WORK/annotation")" \
  "$(native "$HERE/compile-only/org/eclipse/jdt/annotation/Nullable.java")"
javac --release 21 -Xlint:all,-dangling-doc-comments -d "$(native "$WORK/classes")" \
  -cp "$(native "$UPSTREAM_JAR")$SEP$(native "$JONI_JAR")$SEP$(native "$JCODINGS_JAR")$SEP$(native "$WORK/annotation")" \
  "$(native "$HERE/src/org/eclipse/tm4e/core/internal/oniguruma/OnigRegExp.java")"

echo "==> Assembling the jar"
# Upstream's jar is signed. A changed class no longer matches the signature, and a JVM
# refuses a package whose classes disagree about their signer, so the signature goes:
# its files, and the per-entry digest sections of the manifest that only it used. The
# main section - Automatic-Module-Name above all - stays as upstream wrote it.
mkdir -p "$WORK/jar"
(cd "$WORK/jar" && jar --extract --file "$(native "$UPSTREAM_JAR")")
rm -f "$WORK"/jar/META-INF/*.SF "$WORK"/jar/META-INF/*.RSA "$WORK"/jar/META-INF/*.DSA "$WORK"/jar/META-INF/*.EC
tr -d '\r' < "$WORK/jar/META-INF/MANIFEST.MF" | awk 'NF == 0 { exit } { print }' > "$WORK/MANIFEST.MF"
rm "$WORK/jar/META-INF/MANIFEST.MF"
cp -R "$WORK/classes/." "$WORK/jar/"
mkdir -p "$OUT_DIR"
rm -f "$OUT_BASE.jar"
jar --create --file "$(native "$OUT_BASE.jar")" --manifest "$(native "$WORK/MANIFEST.MF")" \
  --date="$ENTRY_DATE" -C "$(native "$WORK/jar")" .

# Upstream's compile-scope dependencies, unchanged, so this build puts exactly what
# upstream does on the classpath. The provided- and test-scope ones never reach a
# consumer and are left out.
cat > "$OUT_BASE.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<!--
  org.eclipse.tm4e.core $UPSTREAM_VERSION with OnigRegExp patched to compile each
  distinct pattern once. Built by third-party/tm4e/build.sh; see the README there.
-->
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.eclipse</groupId>
  <artifactId>org.eclipse.tm4e.core</artifactId>
  <version>$VERSION</version>
  <licenses>
    <license>
      <name>EPL-2.0</name>
      <url>https://www.eclipse.org/legal/epl-v20.html</url>
    </license>
  </licenses>
  <dependencies>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <version>3.27.6</version>
    </dependency>
    <dependency>
      <groupId>net.bytebuddy</groupId>
      <artifactId>byte-buddy</artifactId>
      <version>1.18.1</version>
    </dependency>
    <dependency>
      <groupId>com.google.code.gson</groupId>
      <artifactId>gson</artifactId>
      <version>2.13.2</version>
    </dependency>
    <dependency>
      <groupId>commons-io</groupId>
      <artifactId>commons-io</artifactId>
      <version>2.21.0</version>
    </dependency>
    <dependency>
      <groupId>commons-logging</groupId>
      <artifactId>commons-logging</artifactId>
      <version>1.2</version>
    </dependency>
    <dependency>
      <groupId>org.snakeyaml</groupId>
      <artifactId>snakeyaml-engine</artifactId>
      <version>2.10</version>
    </dependency>
  </dependencies>
</project>
POM

# Maven checks a download against these and warns when they are missing.
for f in "$OUT_BASE.jar" "$OUT_BASE.pom"; do
  sha1sum "$f" | cut -d' ' -f1 | tr -d '\n' > "$f.sha1"
  md5sum "$f" | cut -d' ' -f1 | tr -d '\n' > "$f.md5"
done
echo "    $OUT_BASE.jar"
