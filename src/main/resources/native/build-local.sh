#!/usr/bin/env bash
# Builds libgit2 for the *host* platform and drops it into the right directory. On Windows
# it can also cross-compile for ARM64, which MSVC does from an x64 host.
#
# The full four-platform set comes from .github/workflows/build-libgit2.yml; this script
# is for iterating locally, and uses the same flags so a local build matches CI. Linux
# ARM64 on an x86-64 machine is build-cross-linux-aarch64.sh.
#
# Usage:  ./build-local.sh [tag] [aarch64|x86_64]     e.g.  ./build-local.sh v1.9.7
#                                                           ./build-local.sh v1.9.7 aarch64
#
# Windows ARM64 needs the "MSVC v143 - VS 2022 C++ ARM64/ARM64EC build tools" component
# (Microsoft.VisualStudio.Component.VC.Tools.ARM64) in Visual Studio.

set -euo pipefail

TAG="${1:-v1.9.7}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Matches COMMON_CMAKE_FLAGS in the workflow, which says why each is there. Network
# transport is delegated to the git CLI and JGit, so neither the SSH nor the HTTPS backend
# is built.
FLAGS=(
  -DBUILD_SHARED_LIBS=ON
  -DBUILD_TESTS=OFF
  -DBUILD_CLI=OFF
  -DBUILD_EXAMPLES=OFF
  -DUSE_SSH=OFF
  -DUSE_HTTPS=OFF
  -DREGEX_BACKEND=builtin
  -DCMAKE_BUILD_TYPE=Release
)

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) OS=windows ;;
  *)                    OS=linux ;;
esac

case "${2:-$(uname -m)}" in
  arm64|aarch64) ARCH=aarch64 ;;
  *)             ARCH=x86_64 ;;
esac

TARGET="$OS-$ARCH"
echo "Building libgit2 $TAG for $TARGET"

# libgit2's test fixtures run past Windows' 260-character path limit.
git -c core.longpaths=true -c advice.detachedHead=false clone -q --depth 1 --branch "$TAG" \
  https://github.com/libgit2/libgit2.git "$WORK/lg2"
cd "$WORK/lg2"
echo "libgit2 $TAG at $(git rev-parse HEAD)"

if [ "$OS" = windows ]; then
  case "$ARCH" in aarch64) PLATFORM=ARM64 ;; *) PLATFORM=x64 ;; esac
  # The Visual Studio generator locates the toolset itself, so no vcvars is needed.
  cmake -B build -G "Visual Studio 17 2022" -A "$PLATFORM" "${FLAGS[@]}"
  cmake --build build --config Release --parallel
  install -D build/Release/git2.dll "$HERE/$TARGET/git2.dll"
  OUT="$HERE/$TARGET/git2.dll"
else
  if [ "$ARCH" != "$(uname -m | sed 's/arm64/aarch64/')" ]; then
    echo "error: cross-compiling on Linux is build-cross-linux-aarch64.sh" >&2; exit 1
  fi
  # A bare-host Linux build links against the host's glibc. For a redistributable
  # binary use the manylinux container the workflow does.
  cmake -B build "${FLAGS[@]}"
  cmake --build build --parallel
  install -D "$(readlink -f build/libgit2.so)" "$HERE/$TARGET/libgit2.so"
  OUT="$HERE/$TARGET/libgit2.so"
fi

echo
echo "Wrote $OUT"
sha256sum "$OUT" 2>/dev/null || shasum -a 256 "$OUT"
echo
echo "The loader caches an extracted copy; clear it before testing:"
echo "  rm -f ~/.lyra/native/$TARGET/*"
