# Bundled libgit2 binaries

Prebuilt `libgit2` shared libraries, one per platform, loaded at runtime through the Java
Foreign Function & Memory API by
[`Libgit2Loader`](../../java/com/jfx/git/core/libgit2/Libgit2Loader.java).

## What is here

All four are **libgit2 1.9.7**, built from the unmodified upstream source at tag `v1.9.7`
(commit `49e408b3208bc3093757a1c2db938d3590f3f412`) with the workflow's flags. Each one's
recipe is recorded below, so every bundled binary has a known source.

macOS is not a target platform, so no macOS binary is bundled.

| Directory | Platform | Version | Built with | SHA-256 |
|---|---|---|---|---|
| `windows-x86_64`  | Windows, Intel/AMD 64-bit | 1.9.7 | MSVC 14.44 (VS 2022) | `266d2f138559248460b40f9367d00f8fd5c2001cfa7d2f95c4c438e59d360aee` |
| `linux-x86_64`    | Linux, Intel/AMD 64-bit   | 1.9.7 | manylinux_2_28 container | `ad0e44eb2ad4281279f2f0491c845ccdad24316f44dc503c0afebacdaddbec55` |
| `windows-aarch64` | Windows, ARM64            | 1.9.7 | MSVC 14.44 (VS 2022), cross-compiled from x64 | `791d245716313552289066ff702061e57f5c5defa7d8e97aae7dcf39ef29bc44` |
| `linux-aarch64`   | Linux, ARM64              | 1.9.7 | Debian 10 aarch64 cross gcc 8.3, glibc 2.28 | `2a1580dfc876e144a419d7ddbb4584d78af32effa20e27336abddf42e0f3884e` |

What they need at run time:

- **Windows**: only system DLLs (`KERNEL32`, `ADVAPI32`, `WS2_32`, `Secur32`). The C runtime
  is linked statically.
- **Linux**: only `libz` and core libc (`libc`, `libpthread`, `librt`), no glibc symbol newer
  than 2.25, and the upstream `libgit2.so.1.9` soname.
- **None of them has an SSH or HTTPS transport**: the SSH transport the 1.8.7 / 1.9.7 advisory
  concerned is absent from the binaries, not merely unused.

Until 2026-10-21 the two ARM64 directories held 1.8.6 binaries of unrecorded origin
(committed in `b6e2f3d`). The Linux one carried the wheel-repaired name
`libgit2-5853918.so.1.8` and pulled in four OpenSSL sonames, which suggests they were taken
from pygit2 wheels. The builds above replaced them.

Regenerate the checksums with:

```sh
find src/main/resources/native -type f \( -name '*.dll' -o -name '*.so' \) \
  -exec sha256sum {} +
```

## How each was built

`.github/workflows/build-libgit2.yml` builds all four in one run. The local recipes use
exactly its flags (`COMMON_CMAKE_FLAGS`), so a local build matches CI.

| Platform | Recipe |
|---|---|
| `windows-x86_64` | `./build-local.sh v1.9.7` on Windows with Visual Studio 2022 |
| `windows-aarch64` | `./build-local.sh v1.9.7 aarch64` on Windows x64. MSVC cross-compiles; it needs the Visual Studio component "MSVC v143 - VS 2022 C++ ARM64/ARM64EC build tools" (`Microsoft.VisualStudio.Component.VC.Tools.ARM64`). |
| `linux-x86_64` | The workflow's Linux job flags in the `quay.io/pypa/manylinux_2_28_x86_64` container |
| `linux-aarch64` | `./build-cross-linux-aarch64.sh v1.9.7` on any x86-64 machine with Docker. It builds in `debian:buster` (digest `sha256:58ce6f1271ae1c8a2006ff7d3e54e9874d839f573d8009c20154ad0f2fb0a225`) with Debian's aarch64 cross toolchain, whose glibc 2.28 is the floor manylinux_2_28 guarantees. The workflow's `manylinux_2_28_aarch64` image needs qemu on an x86-64 host, which wedged the Docker engine here. Two runs produced byte-identical output. |

## Upgrading to 1.9.7

Upstream ships source only, so these have to be built. Two ways, both using the same
flags:

- **All four platforms**: run `.github/workflows/build-libgit2.yml` from the Actions tab
  (input: the libgit2 tag). Download the `libgit2-native` artifact and unpack it over this
  directory. It emits `BUILD-INFO.txt` with the tag, flags and checksums.
- **One platform at a time, locally**: the recipes in "How each was built" above.

### What has been verified about the upgrade

Checked against the real v1.9.7 headers and a locally built Windows x86-64 binary:

- **All 20 bound functions are unchanged** in signature between 1.8.6 and 1.9.7:
  `git_libgit2_init`/`_shutdown`, `git_error_last`, `git_repository_open`/`_free`/
  `_workdir`/`_path`/`_index`/`_head`, `git_reference_shorthand`/`_free`,
  `git_status_options_init`, `git_status_foreach_ext`, `git_index_add_bypath`/
  `_remove_bypath`/`_write`/`_free`, `git_revparse_single`, `git_object_free`,
  `git_reset_default`. The `git_status_cb` callback shape is unchanged too. The two bound
  since, `git_repository_open_ext` and `git_index_read`, are unchanged since 0.x.
- **Every struct offset this code assumes still holds**: `git_status_options` is still
  `{version, show, flags, …}`, `git_error` still starts with `char *message`,
  `git_signature` is still `{name, email, when}` with `git_time` starting at a 64-bit
  seconds field, and `git_strarray` is still `{char **strings, size_t count}`.
- **The full test suite (457 tests) passes** against a locally built libgit2 1.9.7,
  including the nine libgit2-specific tests.
- **Every binary was checked on its own platform, as far as this machine can reach it.**
  [`smoke-test.c`](smoke-test.c) calls all 22 functions `Libgit2Bindings` looks up, against
  a repository it creates: opening from a subdirectory, status, stage, unstage, index
  re-read, reset and the error path.
  - **Linux x86-64**: passes, run natively in `debian:buster` (glibc 2.28).
  - **Linux ARM64**: passes, run in `debian:buster` for arm64 under Docker's qemu emulation
    (glibc 2.28). The test was cross-compiled; qemu only ran it.
  - **Windows ARM64**: checked statically. It is an ARM64 image with the same imports and the
    same 950 exports as the x64 DLL, including all 22 bound functions, and its version
    resource says 1.9.7. It has not been run: there is no ARM64 Windows machine here.

  To repeat a Linux check, compile the test as its header comment says (cross-compiling
  with `aarch64-linux-gnu-gcc` for ARM64), then run it on the target platform, for example
  `docker run --platform linux/arm64 -v "$PWD:/smoke" debian:buster /smoke/smoke-test /tmp/repo`.

The bindings are written to tolerate trailing struct growth — `git_status_options_init`
fills defaults and only the first three fields are set by hand — so a later 1.9.x should
not need changes either.

### 1.8.7 versus 1.9.7

These are **not** the same release and neither is a mistake. Both were published on
2026-08-13 because the *same* fix to `src/libgit2/transports/ssh_libssh2.c` was released
simultaneously on both supported branches (verified: the diff hunks are byte-identical).

- `v1.8.6 → v1.8.7` is 6 files / 77 insertions — security fix only, same ABI.
- `v1.8.7 → v1.9.7` is 469 files / 15k insertions, touching 90 public headers — a whole
  feature series apart.

So 1.8.7 is the zero-risk bump and 1.9.7 is the forward move; the verification above says
1.9.7 is safe for this code either way.

Note that the build flags above set `-DUSE_SSH=OFF`. Network transport is delegated to the
git CLI and JGit, never to libgit2, so the SSH backend is unused here — and leaving it out
removes the very transport that advisory concerned.

## Licence

libgit2 is distributed under **GPLv2 with a linking exception**, reproduced here as
`LICENSE-libgit2.txt`. That is `COPYING` from the v1.8.6 tag, re-checked byte-identical at
v1.9.7 on 2026-10-21. `AUTHORS-libgit2.txt` is the attribution the licence header points
at, taken from `AUTHORS` at v1.9.7.

The linking exception is what lets this application load libgit2 without taking on the GPL
itself; it does not remove the obligation to reproduce the licence. Note that the only
version of the GPL the project accepts is v2 exactly — not v2.2 or v3.

The GPL also asks a distributor to point to the source of the binaries. All four are
built unmodified from <https://github.com/libgit2/libgit2/tree/v1.9.7>, and the
repository's `THIRD-PARTY-NOTICES.md` says so. When the binaries move to another tag,
update the table above and that file together.

## Runtime notes

The loader extracts the matching binary to `~/.lyra/native/<platform>/` and loads it
from there, because `SymbolLookup.libraryLookup` needs a filesystem path and the binary
lives inside a jar. The copy is skipped when a file of the same size is already present,
which is what lets a second instance start on Windows while the first holds the DLL mapped.

No build links OpenSSL, because none is built with HTTPS. Network transport is delegated to
the git CLI or JGit rather than to libgit2, so this application loses nothing by it.

Loading requires native access to be enabled for the module:

```text
--enable-native-access=com.jfx.git.lyra
```

`mvn javafx:run` and the IDE run configuration both pass it. A jlink-packaged build
would need it in the generated launcher — but `mvn javafx:jlink` does not currently
work here at all, failing with *"automatic module cannot be used with jlink:
org.eclipse.jgit.ssh.apache"*, so there is no packaged build to configure yet.

Without it the loader logs the reason and falls back to the native `git` CLI, then to JGit.
`Libgit2Loader.unavailableReason()` reports which happened, and Preferences → Git Engine
shows it.

**Only the Windows x86-64 binary has been through the application's test suite.** The two
Linux binaries have passed the smoke test, and the Windows ARM64 one has only been checked
statically (see "What has been verified" above).
