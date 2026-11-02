#!/usr/bin/env bash
# Cross-compiles libgit2 for linux-aarch64 on an x86-64 host, inside a Debian 10 container.
#
# The workflow builds this platform in quay.io/pypa/manylinux_2_28_aarch64 on a native ARM
# runner. On an x86-64 machine that image runs under qemu, which wedged the Docker engine
# here, so this builds natively with Debian 10's aarch64 cross toolchain instead. Debian
# 10's cross libc is glibc 2.28 - the same floor manylinux_2_28 guarantees - and the flags
# are the workflow's COMMON_CMAKE_FLAGS, so the result needs the same libraries (libz and
# core libc) and no newer glibc than the workflow's build would.
#
# Usage:  ./build-cross-linux-aarch64.sh [tag]        e.g.  ./build-cross-linux-aarch64.sh v1.9.7
#
# Needs Docker. Writes linux-aarch64/libgit2.so and prints its checksum, the glibc versions
# it requires and its dynamic section, which are what the README's table records.

set -euo pipefail

TAG="${1:-v1.9.7}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE=debian:buster

OUT="$HERE/linux-aarch64"
mkdir -p "$OUT"
# Docker Desktop on Windows wants a Windows path for the bind mount, and Git Bash would
# otherwise rewrite the container-side path too.
MOUNT="$OUT"
if command -v cygpath > /dev/null; then MOUNT="$(cygpath -w "$OUT")"; fi

docker pull -q "$IMAGE"
echo "Image: $(docker image inspect --format '{{index .RepoDigests 0}}' "$IMAGE")"

MSYS_NO_PATHCONV=1 docker run --rm -e TAG="$TAG" -v "$MOUNT:/out" "$IMAGE" bash -euo pipefail -c '
# Debian 10 is archived; its packages moved to archive.debian.org. The security suite is
# needed too: the image ships zlib1g from it, and zlib1g-dev:arm64 must be that version.
printf "%s\n" "deb http://archive.debian.org/debian buster main" \
  "deb http://archive.debian.org/debian-security buster/updates main" > /etc/apt/sources.list
dpkg --add-architecture arm64
apt-get -qq -o Acquire::Check-Valid-Until=false update
DEBIAN_FRONTEND=noninteractive apt-get -qq install -y --no-install-recommends \
  ca-certificates git cmake make gcc-aarch64-linux-gnu libc6-dev-arm64-cross \
  binutils-aarch64-linux-gnu zlib1g-dev:arm64 > /dev/null

git -c advice.detachedHead=false clone -q --depth 1 --branch "$TAG" \
  https://github.com/libgit2/libgit2.git /src
echo "libgit2 $TAG at $(git -C /src rev-parse HEAD)"

# Matches COMMON_CMAKE_FLAGS in .github/workflows/build-libgit2.yml, plus what a cross
# build needs: the target, its compiler, and the multiarch directory zlib is in.
cmake -S /src -B /build -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
  -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc -DCMAKE_LIBRARY_ARCHITECTURE=aarch64-linux-gnu \
  -DBUILD_SHARED_LIBS=ON -DBUILD_TESTS=OFF -DBUILD_CLI=OFF -DBUILD_EXAMPLES=OFF \
  -DUSE_SSH=OFF -DUSE_HTTPS=OFF -DREGEX_BACKEND=builtin -DCMAKE_BUILD_TYPE=Release \
  > /build.log
grep -E "^-- (Enabled|Disabled) features| \* " /build.log || true
cmake --build /build --parallel >> /build.log

cp "$(readlink -f /build/libgit2.so)" /out/libgit2.so
cd /out
echo
echo "--- compiler ---";       aarch64-linux-gnu-gcc --version | head -1
echo "--- glibc required ---"; aarch64-linux-gnu-objdump -T libgit2.so | grep -oE "GLIBC_[0-9.]+" | sort -Vu | tail -3
echo "--- dynamic section ---"; aarch64-linux-gnu-readelf -d libgit2.so | grep -E "NEEDED|SONAME"
'

echo
echo "Wrote $OUT/libgit2.so"
sha256sum "$OUT/libgit2.so"
