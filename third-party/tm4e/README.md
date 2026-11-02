# Patched TM4E core

The TextMate engine behind syntax highlighting, [TM4E](https://github.com/eclipse-tm4e/tm4e)
(`org.eclipse.tm4e.core`, EPL-2.0), with one class changed. The build in
`third-party/maven-repo` is what `pom.xml` uses.

| | |
|---|---|
| Upstream build | `0.18.0-20260827.121906-2` (SHA-1 `91a3eed07783574ef6d25bad7d42f645e94f0e88`) |
| Upstream source | commit `45d6d5c5b35bd29be7c8948d0e2e8d3af5f6f80c` |
| This build | `0.18.0-20260827.121906-2-jfxgit1` |
| Changed | `org.eclipse.tm4e.core.internal.oniguruma.OnigRegExp`, full source in `src/` |

## What the change does

A grammar builds a scanner for every rule list, plus up to four anchor variants of each,
and every scanner compiled each of its patterns with joni afresh. One pattern is included
in many lists, so the same regex was compiled over and over. In the app, after diffs in
seven languages, 6,346 compiled regexes came from only 745 distinct patterns; one TypeScript
pattern was compiled 75 times, and the copies held 82 MB of joni bytecode.

The patched `OnigRegExp` keeps one compiled `org.joni.Regex` per (pattern, ignore case) and
shares it between every rule list, every grammar and every thread:

- A joni `Regex` is not changed by the forward searches TM4E makes; each search creates its
  own `Matcher`. joni builds one table lazily, the backward Boyer-Moore skip, and only a
  backward search reaches it.
- The cache holds each regex weakly, so when `GrammarRegistry` evicts a grammar, the patterns
  no other loaded grammar uses are freed with it.
- Each `OnigRegExp` keeps its own last-search cache, as before.

The same nine files in seven languages, lexed through `GrammarRegistry`:

| | Upstream | Patched |
|---|---|---|
| Live heap after GC | 137.7 MB | 46.0 MB |
| `int[]` (joni bytecode) | 99.1 MB | 8.7 MB |
| Compiled `org.joni.Regex` | 7,127 | 747 |
| Open and lex, total | 4,041 ms | 3,809 ms |

The highlighting is identical: a digest over every span of every file matched.
`TextMatePatternSharingTest` fails if an unpatched TM4E comes back.

## Rebuilding

    third-party/tm4e/build.sh

It checks the upstream jar against the SHA-1 above, compiles `src/` against it, and writes
the jar, pom and checksums into `third-party/maven-repo`. With the same JDK the jar comes out
byte for byte the same.

- **Compile-only annotation.** `compile-only/` holds a stand-in for Eclipse's
  `@Nullable` annotation, which is used only by Eclipse's own null analysis. It is not
  packaged.
- **No signature.** Upstream's jar is signed, and a changed class no longer matches the
  signature, so the signature files and the manifest's per-entry digests are dropped.
  The rest of the manifest, including `Automatic-Module-Name`, is upstream's.
- **Same dependencies.** The pom declares upstream's compile-scope dependencies unchanged,
  so the patch puts exactly what upstream does on the classpath.

**Any change to the jar needs a new version.** Bump the `-jfxgitN` suffix in `build.sh`
and `pom.xml` (and in this README). Maven copies a release version into `~/.m2` once and
never reads it again, so a rebuilt jar under the old version is silently ignored on every
machine that has built the project before.

To move to a newer TM4E: update `UPSTREAM_VERSION` and `UPSTREAM_SHA1` in `build.sh`,
start `src/` from that build's `OnigRegExp.java`, reapply the change (the cache and the
static helpers), bump the suffix, and rebuild.
