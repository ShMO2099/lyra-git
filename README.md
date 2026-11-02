# Lyra

A Git desktop client in JavaFX, backed by swappable git engines.

![Java](https://img.shields.io/badge/Java-25-blue) ![JavaFX](https://img.shields.io/badge/JavaFX-27-blue) ![License](https://img.shields.io/badge/license-Apache--2.0-blue)

## What it is

Lyra is a visual Git client: a commit graph with branch lanes, a staging panel,
hunk- and line-level staging, an interactive rebase editor, worktrees and submodules
in the sidebar, a 50-step undo driven by the reflog, and a built-in file editor with
TextMate-grammar syntax highlighting. A commit's changed files read as a flat path list
or as a folder tree, the composer commits or stashes under a name you choose, and every
operation lands in an activity log with its duration (`Ctrl+Shift+L`). The interface
vocabulary and gestures follow the commercial Git clients.

## Building & running

Requirements: **JDK 25** and Maven (`./mvnw` wrapper included).

```bash
mvnw compile          # build
mvnw javafx:run       # run the app
mvnw test             # full test suite (headless; no display needed)
mvnw verify           # tests + checks
```

The app launches with `module-info` on the module path. Tests run on the classpath —
if something works in tests but not in the app (or vice versa), module-path resource
loading and JPMS resolution are the first suspects (see `GrammarRegistry.openGrammarResource`
for a worked example).

### Packaging (Windows)

```bash
bash package-windows.sh              # app image -> target/dist/Lyra/Lyra.exe
bash package-windows.sh --installer  # + MSI
```

Uses jpackage over JDK modules only; JavaFX stays on the module path. Requires `jlink`
and `jpackage` on PATH.

`mvn javafx:jlink` is deliberately not configured: jlink refuses filename-based automatic
modules and this project has two load-bearing ones (the JGit SSH transport and
`org.eclipse.tm4e.core`), so the goal could only ever fail. `package-windows.sh` links a
JDK-only runtime image instead and runs the application from the classpath, where no
automatic module is consulted. Set `SIGNTOOL_CERT` to have it sign what it produces.

### Packaging (Linux)

```bash
./package-linux.sh              # app image -> target/dist/Lyra/bin/Lyra
./package-linux.sh --deb --rpm  # + .deb and/or .rpm
```

The same approach as on Windows, run on Linux (jpackage builds only for the platform it
runs on). `--deb` needs `dpkg-deb` and `fakeroot`, `--rpm` needs `rpmbuild`. Both scripts
smoke-test the packaged image with its own runtime.

### Releases

Pushing a version tag runs `.github/workflows/release.yml`, which tests, packages on Windows
and Linux, and publishes a GitHub release with the MSI, a Windows zip, the `.deb`, a Linux
tarball, `SHA256SUMS.txt`, and the source of the GPL-licensed components the packages carry:

```bash
git tag -a v1.0.0 -m "Lyra 1.0.0"
git push origin v1.0.0
```

A suffix (`v1.0.0-beta.1`) makes it a pre-release. For a dry run, start the workflow from the
Actions tab (Release > Run workflow) with a version: it builds the same assets into a draft
release, which creates no tag and stays hidden until someone publishes it.

## Architecture

```
com.jfx.git
├── core        git engines + services (no UI)
│   ├── GitBackend           ~90-method engine interface
│   ├── GitService           JGit implementation
│   ├── NativeGitBackend     git CLI via process runner
│   ├── libgit2/Libgit2Backend   bundled native libgit2 (FFM API)
│   ├── HybridGitBackend     routes reads/writes across engines
│   ├── SwitchableGitBackend hot-swaps engines under live UI
│   ├── GitTaskRunner        single worker thread; all git work off the FX thread
│   └── ActivityLog          bounded timeline of what ran, and how long it took
├── graph       commit graph model + canvas rendering (lanes, refs, stashes)
├── model       CommitInfo, RefInfo, ResetMode...
└── ui          DiffViewer/editor, panels, sidebars, dialogs, palette
    └── syntax  TextMate grammar engine (TM4E) + registry
```

**Swappable engines.** Every git call goes through one interface with three
implementations. The CLI honours your gitconfig, credential helpers and hooks; JGit is
fast for history walks; libgit2 does in-process work with no git installed. Switch
live in Preferences → Git Engine.

**One worker per repository.** Each open repository's git operations serialize through
a background thread of its own and post results back to the FX thread — JGit's
repository objects are not safe for concurrent callers. Two tabs' work runs side by
side, so a slow fetch or commit in one never holds up another.

## Testing

Three rungs, by cost:

1. **Unit** — models, parsers, core logic (pure JVM).
2. **FxHarness** — real controls on a real toolkit, off-screen; layout without robots.
3. **Robot** — TestFX driving real clicks/keys, rendered headless via Monocle.

Run everything with `mvnw test`. Robot failures that smell like races usually are:
rerun before digging. Settings during tests go to a throwaway file under `target/`.

**One Maven invocation at a time.** The robot tests share one Monocle toolkit and one
`target/` directory, so a second `mvnw` run against the same checkout will fail tests
that are perfectly fine - and a `clean` under a running suite deletes the classes out
from under its JVM. Both produce failures that look like product bugs and are not.

**Memory is capped.** Maven's own JVM runs with `-Xmx1g` (`.mvn/jvm.config`; javac peaks
at about 450 MB of heap), and the suite's JVM with `-Xmx1g` too (`-Dtest.heap=1500m` to
change it). The suite needs far less once nothing leaks between classes, so an
`OutOfMemoryError` there is a leak to find, not a limit to raise: the heap dump lands in
`target/`. A test that shows a window closes it, and a static listener is weak.

## Bundled grammars

Syntax highlighting is TextMate-only. Fourteen curated grammars live in
`src/main/resources/grammars/` (registered by hand in `GrammarRegistry.CURATED`); the
other ~180 languages are the catalogue under `grammars/tm/`, generated by
`node scripts/update-grammars.mjs <checkout of shikijs/textmate-grammars-themes>` with
attribution in `grammars/NOTICE.md` and `grammars/tm/NOTICE.txt`. One grammar's include of
another (JavaScript in HTML, a fenced block in Markdown) resolves across both sets. Files
are picked by extension or by exact name (`Dockerfile`, `Makefile`, `.bashrc`). Only
the twelve most recently used languages stay compiled (`GrammarRegistry.MAX_LOADED`): a
compiled grammar is a few megabytes, and all of them together were half a gigabyte.
Adding a language = map it in the script and regenerate, or for a curated one: add the
grammar file + one `LanguageDefinition` entry + a case in `GrammarTokenizationTest`.
Either way `GrammarPatternsCompileTest` compiles every pattern the way TM4E does: its
joni engine rejects some look-behinds, and a line that reaches such a rule comes out
unhighlighted. Repairs go in the script's `PATCHES` or, for a curated file,
`grammars/NOTICE.md`.

## Themes

Twelve built-in themes - dark: Dark, Dark Green, Midnight, Nord Dark, Solarized Dark, Gruvbox Dark;
light: Light, Light Green, Paper, Nord Light, Solarized Light, Gruvbox Light - each a
constant in `ThemeManager.Theme`: a label, a `Base` (dark or light: which AtlantaFX Primer
stylesheet it is written over, and the colour scheme of the native title bar) and a token
file `src/main/resources/css/theme-<name>.css`. The saved choice is the constant's name, so
none may be renamed. Dark and Light only name the colours Primer has no name for; the others
restate Primer's palette as well, because they are grounds of their own.

Adding one is a constant, a token file with every token the others define (copy the nearest;
`ThemeTokensTest` lists what is missing), an entry in `GraphPalette.PALETTES` (the commit
graph is painted on a canvas, which CSS cannot reach), and the syntax block:
`node scripts/derive-theme-syntax.mjs` recolours GitHub's per-scope colours into the theme's own
(add its colour map there first). `ThemeReadabilityTest` then measures its contrast, and in a
dark theme how much white its grounds carry: a grey ground lighter than Midnight's reads as a
white haze over the whole window (`ThemeGroundsOnScreenTest` measures the rendered window). To look at
it: `mvnw -o test -Dtest=UiTourTest -DexcludedTestGroups= -Dtour.themes=paper` (and
`DialogTourTest` with the same option) writes screenshots under `target/`.

## Key documents

| Document | Contents |
|---|---|
| `THIRD-PARTY-NOTICES.md` | Every bundled third-party component: version, license, copyright notices, where its source is |
| `src/main/resources/grammars/NOTICE.md` | Grammar provenance & licenses |
| `src/main/resources/native/README.md` | Bundled libgit2 binaries: upstream version, build recipe and checksums |
| `create-git-repo.sh` | Builds a branchy throwaway repository for looking at the graph by hand. Nothing runs it; the test suites build their own fixtures |

## License

Lyra is licensed under the [Apache License 2.0](LICENSE). The third-party components it
bundles keep their own licenses: [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) lists them
with their copyright notices and says where each one's source is, and [`NOTICE`](NOTICE)
carries the notices the Apache-licensed ones require. The packaging scripts copy `LICENSE`,
`NOTICE`, `THIRD-PARTY-NOTICES.md` and the EPL-2.0 and libgit2 licence texts into the
application image, under `app/legal`; the Java runtime keeps its own licence in
`runtime/legal`.
