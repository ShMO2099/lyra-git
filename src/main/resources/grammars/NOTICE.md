# Grammar Attribution

The TextMate grammar files in this directory are third-party components, bundled
verbatim (byte-for-byte, unmodified) unless a row below records a deviation. Each file's
own `information_for_contributors` field (JSON) or upstream `version` field records its
original source; fixes should go to that upstream project, not to the copies here.

| File                        | Original component                                                              | License | Copyright                |
|-----------------------------|-----------------------------------------------------------------------------------|---------|--------------------------|
| `java.tmLanguage.json`      | [redhat-developer/vscode-java](https://github.com/redhat-developer/vscode-java) via [microsoft/vscode](https://github.com/microsoft/vscode) `extensions/java` | MIT | Copyright (c) Red Hat |
| `MagicPython.tmLanguage.json` | [MagicStack/MagicPython](https://github.com/MagicStack/MagicPython) via microsoft/vscode `extensions/python` | MIT | Copyright (c) MagicStack contributors |
| `JSON.tmLanguage.json`      | [microsoft/vscode-JSON.tmLanguage](https://github.com/microsoft/vscode-JSON.tmLanguage) | MIT | Copyright (c) Microsoft |
| `cpp.tmLanguage.json`       | [jeff-hykin/better-cpp-syntax](https://github.com/jeff-hykin/better-cpp-syntax) via microsoft/vscode `extensions/cpp` | MIT | Copyright (c) jeff-hykin |
| `csharp.tmLanguage.json`    | [dotnet/csharp-tmLanguage](https://github.com/dotnet/csharp-tmLanguage) via microsoft/vscode `extensions/csharp` | MIT | .NET Foundation |
| `go.tmLanguage.json`        | [worlpaker/go-syntax](https://github.com/worlpaker/go-syntax) via microsoft/vscode `extensions/go` | MIT | See upstream |
| `rust.tmLanguage.json`      | [dustypomerleau/rust-syntax](https://github.com/dustypomerleau/rust-syntax) via microsoft/vscode `extensions/rust` | MIT | Copyright (c) dustypomerleau |
| `swift.tmLanguage.json`     | [jtbandes/swift-tmlanguage](https://github.com/jtbandes/swift-tmlanguage) via microsoft/vscode `extensions/swift` | MIT | Copyright (c) Jacob Bandes-Storch |
| `JavaScript.tmLanguage.json` | [microsoft/TypeScript-TmLanguage](https://github.com/microsoft/TypeScript-TmLanguage) via microsoft/vscode `extensions/javascript` | MIT | Copyright (c) Microsoft |
| `TypeScript.tmLanguage.json` | [microsoft/TypeScript-TmLanguage](https://github.com/microsoft/TypeScript-TmLanguage) via microsoft/vscode `extensions/typescript-basics` | MIT | Copyright (c) Microsoft |
| `yaml.tmLanguage.plist`     | TextMate YAML bundle, vendored as a test fixture by [eclipse-tm4e/tm4e](https://github.com/eclipse-tm4e/tm4e) | MIT | Original TextMate authors |
| `markdown.tmLanguage.json`  | [microsoft/vscode-markdown-tm-grammar](https://github.com/microsoft/vscode-markdown-tm-grammar) | MIT | Copyright (c) Microsoft 2018 |
| `xml.tmLanguage.json`       | [atom/language-xml](https://github.com/atom/language-xml)                        | MIT | Copyright (c) Atom/GitHub |
| `shell.tmLanguage.json`     | [jeff-hykin/better-shell-syntax](https://github.com/jeff-hykin/better-shell-syntax) | MIT | Copyright (c) jeff-hykin  |

All microsoft/vscode-sourced files are re-published as part of vscode's `extensions/`
directory (MIT-licensed); see each file's own `information_for_contributors` array for
the exact upstream path and commit.

On 2026-10-04 the Java, Python, JSON, C#, Go, Rust, Swift, JavaScript, TypeScript and
Markdown files were refreshed to the versions the `tm-grammars` package carries at
shikijs/textmate-grammars-themes commit `37edd1b` (same upstream projects, same licences).
`cpp.tmLanguage.json` and `shell.tmLanguage.json` were kept at their previous versions:
the newer grammars fail `GrammarTokenizationTest`'s token expectations under TM4E.

## Documented deviations from verbatim bundling

- **`xml.tmLanguage.json`** — one repaired pattern. Upstream's JSP-comment rule nests
  `end` and `name` keys *inside* its `captures` object (a malformed merge that VS Code's
  lenient parser ignores). TM4E iterates every captures entry strictly and crashes with a
  `ClassCastException`, which took out all XML highlighting. The repair moves those two
  keys up to the pattern level — unambiguously the upstream intent — and is the only
  edit. `GrammarTokenizationTest` fails if the file drifts back into an unparsable form.
- **`TypeScript.tmLanguage.json`** and **`JavaScript.tmLanguage.json`** — three repaired
  look-behinds each, the same three in both (from the shared microsoft/TypeScript-TmLanguage
  source). Each nests a group of alternatives inside a look-behind, which the joni engine
  TM4E runs on rejects even though every alternative is fixed-length. TM4E then aborts the
  whole line, so a line such as `const x: number = 1;` came out with no highlighting at all.
  Each repair lifts the alternatives up to be the look-behind's own, which joni accepts,
  and leaves the meaning unchanged:
  - `(?<!(?:^|[^$._[:alnum:]])const)` → `(?<!^const|[^$._[:alnum:]]const)`, ending a
    `const` declaration;
  - `(?<!(?:^|[^$._[:alnum:]])import)` → `(?<!^import|[^$._[:alnum:]]import)`, ending an
    `import` declaration;
  - `(?<!(?:^|[^$._[:alnum:]]|^await\s+|[^$._[:alnum:]]await\s+)using)` →
    `(?<!^using|[^$._[:alnum:]]using)`, ending a `using` declaration. The two `await\s+`
    alternatives are variable-length and are dropped. That changes nothing, because the
    whitespace right before `using` already satisfies `[^$._[:alnum:]]`.
- **`rust.tmLanguage.json`** — one repaired look-behind, before `::` in a path:
  `(?<!s(?:uper|elf))` → `(?<!super|self)`, for the same reason and with the same
  meaning. Without it a line such as `fn main() { let x = 1; }` came out unhighlighted.

All four repairs were compared with the originals under an engine that accepts both,
from every position of a corpus of lines, and matched identically.
`GrammarPatternsCompileTest` compiles every pattern of every bundled grammar, curated and
catalogued, the way TM4E does, and fails on any joni rejects. A refreshed grammar that
brings such a pattern back needs the same kind of repair, recorded here.

## Replaced grammars

- **YAML**: the previously bundled RedCMD/YAML-Syntax-Highlighter JSON grammar loads but
  emits only its root scope under TM4E — no tokens at all — so YAML was silently
  unhighlighted. Replaced by the plist grammar TM4E itself tests against.

## MIT License (representative text)

Permission is hereby granted, free of charge, to any person obtaining a copy of this
software and associated documentation files, to deal in the software without
restriction, including without limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of the Software, subject to including
the above copyright notice and this permission notice in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.

## The catalogue: `tm/`

The fourteen grammars above are the curated set, registered by hand in
`GrammarRegistry.CURATED`. Everything else - the hundred and eighty-odd other languages -
is the *catalogue* under `tm/`, generated by `scripts/update-grammars.mjs` from a checkout
of [shikijs/textmate-grammars-themes](https://github.com/shikijs/textmate-grammars-themes)
(the source of the `tm-grammars` npm package):

- `tm/index.json` lists each grammar with its scope, file extensions and file names; the
  registry reads it at startup, and resolves one grammar's `include` of another through
  the scopes of both sets.
- `tm/NOTICE.txt` carries, per file, the licence sections of that package's own NOTICE,
  the upstream commit, and every repair made to a file (each replaces a regular-expression
  construct the joni engine rejects - TM4E aborts the line otherwise). Only grammars under
  MIT, BSD, ISC, Apache-2.0 or MPL-2.0 are bundled; the script records, per grammar, the
  licences it confirmed by reading the upstream repository where the package's metadata
  left them blank, and the grammars left out and why.
- `GrammarCatalogueTest` tokenises every catalogued language's upstream sample
  (`src/test/resources/grammar-samples/`, MIT) and fails on one that loads but does not
  tokenise. A sample reaches only some rules, so `GrammarPatternsCompileTest` also
  compiles every pattern of every file; a rejected one goes into the script's `PATCHES`.

To refresh: `node scripts/update-grammars.mjs <checkout>`, then run the grammar tests.

## Syntax colours

The colours the grammars' scopes are painted in are GitHub's own editor themes - GitHub
Dark Default and GitHub Light Default from
[primer/github-vscode-theme](https://github.com/primer/github-vscode-theme) (MIT,
Copyright (c) GitHub Inc.), taken from the `tm-themes` package of the same checkout.
`scripts/update-syntax-theme.mjs` writes one `-jfx-tm-<scope>` token per scope into
`css/theme-dark.css` and `css/theme-light.css`, and one rule per scope into
`diff-styles.css`, between marked lines.

## Adding another grammar

For a language the catalogue already has, map it in `EXTENSIONS` or `FILE_NAMES` in
`scripts/update-grammars.mjs` and regenerate. For one it does not:

1. Confirm the source grammar's actual license (not assumed) before bundling.
2. Download the `.tmLanguage.json` (or plist) file into this directory.
3. Add a row to the table above with its origin, license, and copyright holder.
4. Add one `LanguageDefinition` entry to `GrammarRegistry.CURATED`
   (`src/main/java/com/jfx/git/ui/syntax/GrammarRegistry.java`).
5. Add a case to `GrammarTokenizationTest` asserting representative tokens — a grammar
   can load cleanly and still not tokenise, so "it loads" is not evidence.
6. Run `GrammarPatternsCompileTest`, which covers the new file automatically. Repair any
   pattern it reports and record the repair under "Documented deviations" above.
