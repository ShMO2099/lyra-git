#!/usr/bin/env node
/**
 * Refreshes the grammar catalogue under src/main/resources/grammars/tm from a checkout of
 * shikijs/textmate-grammars-themes (the repository the `tm-grammars` npm package is
 * published from).
 *
 *   node scripts/update-grammars.mjs ../textmate-grammars-themes
 *
 * What it writes:
 *   src/main/resources/grammars/tm/<name>.json   the grammars, byte-for-byte as upstream
 *   src/main/resources/grammars/tm/index.json    what GrammarRegistry loads: id, display
 *                                                name, scope, file, extensions, file names
 *   src/main/resources/grammars/tm/NOTICE.txt    upstream's third-party notices for exactly
 *                                                the files bundled, plus the commit they came from
 *   src/test/resources/grammar-samples/<name>.sample   upstream's samples, for GrammarCatalogueTest
 *
 * What goes in: every grammar that maps to a file extension or file name below, whose
 * licence is one this project redistributes (MIT, BSD, ISC, Apache-2.0, MPL-2.0), plus
 * the grammars those embed (transitively) so that JavaScript inside HTML, or a fenced
 * block inside Markdown, highlights too. Grammars with no licence, an unknown licence or
 * a copyleft one (GPL) are left out, and a short list of grammars that load under TM4E
 * but do not tokenise is excluded by name.
 *
 * The fourteen curated grammars in src/main/resources/grammars (java, python, json, cpp,
 * csharp, go, rust, swift, javascript, typescript, yaml, markdown, xml, shellscript) are
 * registered in Java with per-grammar notes and are not part of this catalogue; their
 * scopes are what embedded references to those languages resolve to.
 */
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { execSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(here, '..');
const checkout = resolve(process.argv[2] ?? resolve(projectRoot, '..', 'textmate-grammars-themes'));
const pkg = join(checkout, 'packages', 'tm-grammars');
if (!existsSync(join(pkg, 'index.js'))) {
  console.error(`No tm-grammars package at ${pkg}; pass the checkout's path as the first argument.`);
  process.exit(2);
}
// `grammars` are languages; `injections` are the helper grammars some of them embed
// (Vue's directives, Markdown-in-Vue, Jinja's HTML). Both are files under grammars/.
const { grammars, injections } = await import(pathToFileURL(join(pkg, 'index.js')).href);
const everything = [...grammars, ...(injections ?? [])];

const outDir = join(projectRoot, 'src', 'main', 'resources', 'grammars', 'tm');
const samplesDir = join(projectRoot, 'src', 'test', 'resources', 'grammar-samples');

/** Licences this project redistributes grammar files under. */
const ACCEPTED_LICENCES = new Set(['MIT', 'Apache-2.0', 'BSD-3-Clause', 'BSD-2-Clause', 'ISC', 'MPL-2.0']);

/** Curated in Java, with their own files; see GrammarRegistry. Their scopes still resolve embeddings. */
const CURATED = new Set(['java', 'python', 'json', 'cpp', 'csharp', 'go', 'rust', 'swift', 'javascript',
  'typescript', 'yaml', 'markdown', 'xml', 'shellscript']);

/**
 * Licences confirmed by reading the upstream repository's licence file where the
 * package's own metadata left the field blank or undetermined (NOASSERTION), with the
 * date read. Re-check when upstream's `license` field changes.
 */
const LICENCE_CONFIRMED = {
  'elixir': 'Apache-2.0',                  // elixir-editors/elixir-tmbundle LICENSE, 2026-10-04
  'sass': 'MIT',                           // TheRealSyler/vscode-sass-indented LICENSE, 2026-10-04
  'nim': 'MIT',                            // pragmagic/vscode-nim LICENSE, 2026-10-04
  'luau': 'MIT',                           // JohnnyMorganz/Luau.tmLanguage LICENSE.md, 2026-10-04
  'purescript': 'MIT',                     // nwolverson/vscode-language-purescript LICENSE.md, 2026-10-04
  'asciidoc': 'MIT',                       // asciidoctor/asciidoctor-vscode LICENSE, 2026-10-04
  'cue': 'MIT',                            // cue-lang/vscode-cue LICENSE, 2026-10-04
  'apache': 'BSD-2-Clause',                // colinta/ApacheConf.tmLanguage LICENSE, 2026-10-04
  'llvm': 'Apache-2.0 WITH LLVM-exception', // llvm/llvm-project LICENSE.TXT, 2026-10-04
  'nsis': 'Apache-2.0',                    // idleberg/vscode-nsis LICENSE, 2026-10-04
  'wolfram': 'Apache-2.0',                 // WolframResearch/vscode-wolfram LICENSE, 2026-10-04
  'apex': 'BSD-3-Clause',                  // forcedotcom/apex-tmLanguage LICENSE, 2026-10-04
  // The TextMate project's own bundles carry its permissive licence in their README
  // ("Permission to copy, use, modify, sell and distribute this software is granted"),
  // which has no SPDX id; its text is written into NOTICE.txt below. Read 2026-10-04.
  'toml': 'LicenseRef-TextMate-Bundle',
  'applescript': 'LicenseRef-TextMate-Bundle',
  'erb': 'LicenseRef-TextMate-Bundle',
  'po': 'LicenseRef-TextMate-Bundle',
  'mipsasm': 'LicenseRef-TextMate-Bundle',
  'logo': 'LicenseRef-TextMate-Bundle',
};

const TEXTMATE_BUNDLE_LICENCE = [
  'Permission to copy, use, modify, sell and distribute this software is granted. This',
  'software is provided "as is" without express or implied warranty, and with no claim as',
  'to its suitability for any purpose.',
].join('\n');

/**
 * Look-behinds in microsoft/TypeScript-TmLanguage (TypeScript, TSX, JavaScript, JSX) that
 * nest a group of alternatives, which joni rejects though each alternative is fixed-length.
 */
const TS_FAMILY_PATCHES = [{
  from: String.raw`(?<!(?:^|[^$._[:alnum:]])const)`,
  to: String.raw`(?<!^const|[^$._[:alnum:]]const)`,
  why: 'a look-behind nesting a group of alternatives, at the end of a "const" declaration; they are now the look-behind\'s own, which joni accepts, and the meaning is unchanged',
}, {
  from: String.raw`(?<!(?:^|[^$._[:alnum:]])import)`,
  to: String.raw`(?<!^import|[^$._[:alnum:]]import)`,
  why: 'the same look-behind, at the end of an "import" declaration',
}, {
  from: String.raw`(?<!(?:^|[^$._[:alnum:]]|^await\s+|[^$._[:alnum:]]await\s+)using)`,
  to: String.raw`(?<!^using|[^$._[:alnum:]]using)`,
  why: 'the same look-behind for "using"; its two variable-length "await\\s+" alternatives are dropped because the whitespace before "using" already satisfies [^$._[:alnum:]], so the meaning is unchanged',
}];

/** What may precede a Typst clause's opening bracket without ending the clause. */
const TYPST_CLAUSE_OPERATORS = ['while', 'and', 'not', 'if', 'or', 'in', '!=', '==', '<=', '>=', '[-*+/<>]',
  '=', '=>', String.raw`\+=`, '-=', String.raw`\*=`, '/='];

/**
 * Repairs to individual patterns, applied to the copied file and recorded in NOTICE.txt.
 * Each replaces a construct the joni engine rejects (TM4E then aborts the whole line and
 * the grammar emits nothing) with the nearest one it accepts. `from` and `to` are the
 * regular expression's own text; the JSON escaping is handled when applying.
 */
const PATCHES = {
  'css': [{
    from: String.raw`(?<=^|(^|[^:])\s|[;{}]|\*/|\\(?:\h{1,6}|.))`,
    to: String.raw`(?:^|(?<=[\s;{}/]))`,
    why: 'a look-behind mixing a zero-width alternative, a group and a bounded quantifier; a selector now starts at the line start or after whitespace, a semicolon, a brace or a comment end',
  }, {
    from: String.raw`(?<=^|(^|[^:])\s|[{}]|\*/|\\(?:\h{1,6}|.))`,
    to: String.raw`(?:^|(?<=[\s{}/]))`,
    why: 'the same look-behind without the semicolon, before element names',
  }, {
    from: String.raw`(?<=^|[(\s]|\*/)`,
    to: String.raw`(?:^|(?<=[(\s/]))`,
    why: 'a look-behind with a zero-width alternative, before property values',
  }, {
    from: String.raw`(?<=^|[,\s]|\*/)`,
    to: String.raw`(?:^|(?<=[,\s/]))`,
    why: 'the same, after a comma',
  }, {
    from: String.raw`(?<=^|[:\s]|\*/)`,
    to: String.raw`(?:^|(?<=[:\s/]))`,
    why: 'the same, after a colon',
  }],
  'gdscript': [{
    from: String.raw`(?<!/\s*)`,
    to: String.raw`(?<!/)`,
    why: 'a variable-length negative look-behind; now only an immediately preceding slash is excluded',
  }],
  // The curated TypeScript and JavaScript grammars carry the same three; grammars/NOTICE.md.
  'tsx': TS_FAMILY_PATCHES.map(p => ({ ...p })),   // copies: patched() records a count on each
  'jsx': TS_FAMILY_PATCHES.map(p => ({ ...p })),
  'apex': [{
    from: String.raw`(?<=\s*\))`,
    to: String.raw`(?<=\))`,
    why: 'a variable-length look-behind; \\s* can match nothing, so a preceding ")" is the whole condition and the meaning is unchanged',
  }],
  'marko': [{
    from: String.raw`(?<=</(?:>|html-comment>))`,
    to: String.raw`(?<=</>|</html-comment>)`,
    why: 'a look-behind holding a group of alternatives of different lengths; they are now the look-behind\'s own, which joni accepts, and the meaning is unchanged',
  }],
  'hy': [{
    from: String.raw`(?<=["}]\N*?)\{`,
    to: String.raw`\{`,
    why: 'an unbounded look-behind requiring a quote or brace earlier on the line; a string\'s own opening quote or brace always precedes on its first line, so only a "{" on a continuation line of a multi-line string now ends it too',
  }],
  'typst': [{
    from: String.raw`(?<!\bcontext\s*)`,
    to: String.raw`(?<!\bcontext|\bcontext\s|\bcontext\s\s)`,
    why: 'a variable-length negative look-behind; "context" followed by up to two whitespace characters is now recognised, three or more no longer is',
  }, {
    from: String.raw`(?<!(?:while|and|not|if|or|in|!=|==|<=|>=|[-*+/<>]|=>?|\+=|-=|\*=|/=)\s*)`,
    to: `(?<!${['', String.raw`\s`, String.raw`\s\s`].flatMap(ws => TYPST_CLAUSE_OPERATORS.map(op => op + ws)).join('|')})`,
    why: 'a variable-length negative look-behind ending "if", "for" and "while" clauses; an operator or keyword followed by up to two whitespace characters is now recognised, three or more no longer is',
  }],
};

/**
 * Grammars left out by name, with the reason. The common one: a regular expression the
 * joni engine (what TM4E runs on) rejects - usually a variable-length look-behind that
 * Oniguruma proper accepts - which makes TM4E abort the line, so the grammar loads and
 * emits nothing. GrammarCatalogueTest finds these; this list records the finding.
 */
const EXCLUDED = new Map([
  ['crystal', 'a look-behind with alternatives of different lengths, which joni rejects'],
  ['desktop', 'an unbounded look-behind ((?<=^Categories.*)), which joni rejects'],
  ['ocaml', 'a look-behind with a zero-width alternative, which joni rejects'],
  ['ahk', 'emits no coloured scope under TM4E (GrammarCatalogueTest)'],
  ['common-lisp', 'emits no coloured scope under TM4E (GrammarCatalogueTest)'],
  ['stata', 'emits no coloured scope under TM4E (GrammarCatalogueTest)'],
  ['system-verilog', 'emits no coloured scope under TM4E (GrammarCatalogueTest)'],
  ['wikitext', 'emits no coloured scope under TM4E (GrammarCatalogueTest)'],
]);

/**
 * File extensions per grammar, lower case, no dot. Only grammars listed here (or in
 * FILE_NAMES) are languages a file can open in; everything else that gets bundled is an
 * embedded dependency. Where two languages share an extension the more common one in
 * source repositories wins, and the note says which lost.
 */
const EXTENSIONS = {
  'abap': ['abap'],
  'actionscript-3': ['as'],
  'ahk': ['ahk'],
  'apex': ['trigger'],                 // .cls is LaTeX's too; neither gets it
  'apl': ['apl', 'dyalog'],
  'applescript': ['applescript'],
  'asciidoc': ['adoc', 'asciidoc'],
  'asm': ['asm', 's', 'nasm'],
  'astro': ['astro'],
  'awk': ['awk'],
  'ballerina': ['bal'],
  'bat': ['bat', 'cmd'],
  'beancount': ['beancount', 'bean'],
  'berry': ['be'],
  'bibtex': ['bib'],
  'bicep': ['bicep', 'bicepparam'],
  'bsl': ['bsl', 'os'],
  'c': ['c'],                          // .h stays with the curated C++ grammar
  'c3': ['c3'],
  'cadence': ['cdc'],
  'cairo': ['cairo'],
  'chapel': ['chpl'],
  'clarity': ['clar'],
  'clojure': ['clj', 'cljs', 'cljc', 'edn'],
  'cmake': ['cmake'],
  'cobol': ['cob', 'cbl', 'cpy'],
  'codeql': ['ql', 'qll'],
  'coffee': ['coffee'],
  'common-lisp': ['lisp', 'lsp', 'cl'],
  'crystal': ['cr'],
  'css': ['css'],
  'csv': ['csv'],
  'cue': ['cue'],
  'cypher': ['cypher', 'cyp'],
  'd': ['d', 'di'],
  'dart': ['dart'],
  'dax': ['dax'],
  'desktop': ['desktop'],
  'diff': ['diff', 'patch'],
  'docker': ['dockerfile'],
  'dotenv': ['env'],
  'dream-maker': ['dm', 'dme'],
  'edge': ['edge'],
  'elixir': ['ex', 'exs'],
  'elm': ['elm'],
  'emacs-lisp': ['el'],
  'erb': ['erb'],
  'erlang': ['erl', 'hrl'],
  'fennel': ['fnl'],
  'fish': ['fish'],
  'fluent': ['ftl'],
  'fortran-fixed-form': ['f', 'f77', 'for'],
  'fortran-free-form': ['f90', 'f95', 'f03', 'f08', 'f18'],
  'fsharp': ['fs', 'fsi', 'fsx', 'fsscript'],   // .fs is GLSL's fragment shader too; F# is the commoner file
  'gdresource': ['tres', 'tscn'],
  'gdscript': ['gd'],
  'gdshader': ['gdshader'],
  'genie': ['gs'],
  'gherkin': ['feature'],
  'gleam': ['gleam'],
  'glimmer-js': ['gjs'],
  'glimmer-ts': ['gts'],
  'glsl': ['glsl', 'vert', 'frag', 'geom', 'comp', 'tesc', 'tese', 'vsh', 'fsh'],
  'gn': ['gn', 'gni'],
  'gnuplot': ['gnuplot', 'gp', 'plt'],
  'graphql': ['graphql', 'gql'],
  'groovy': ['groovy', 'gvy', 'gradle'],
  'hack': ['hack'],
  'haml': ['haml'],
  'handlebars': ['hbs', 'handlebars', 'mustache'],
  'haskell': ['hs', 'lhs'],
  'haxe': ['hx'],
  'hcl': ['hcl'],
  'hjson': ['hjson'],
  'hlsl': ['hlsl', 'fx', 'fxh', 'hlsli'],
  'html': ['html', 'htm', 'xhtml', 'shtml'],
  'http': ['http', 'rest'],
  'hurl': ['hurl'],
  'hxml': ['hxml'],
  'hy': ['hy'],
  'imba': ['imba'],
  'ini': ['ini', 'cfg', 'properties'],
  'jinja': ['jinja', 'jinja2', 'j2'],
  'jison': ['jison'],
  'json5': ['json5'],
  'jsonl': ['jsonl', 'ndjson'],
  'jsonnet': ['jsonnet', 'libsonnet'],
  'jssm': ['jssm'],
  'jsx': ['jsx'],
  'julia': ['jl'],
  'just': ['just'],
  'kdl': ['kdl'],
  'kotlin': ['kt', 'kts'],
  'kusto': ['kql', 'csl'],
  'latex': ['tex', 'latex', 'sty', 'ltx'],
  'lean': ['lean'],
  'less': ['less'],
  'liquid': ['liquid'],
  'llvm': ['ll'],
  'log': ['log'],
  'logo': ['logo', 'lgo'],
  'lua': ['lua'],
  'luau': ['luau'],
  'make': ['mk', 'mak', 'make'],
  'marko': ['marko'],
  'mdc': ['mdc'],
  'mdx': ['mdx'],
  'mermaid': ['mermaid', 'mmd'],
  'mipsasm': ['mips'],
  'mojo': ['mojo'],
  'moonbit': ['mbt'],
  'move': ['move'],
  'narrat': ['narrat', 'nar'],
  'nextflow': ['nf'],
  'nim': ['nim', 'nims', 'nimble'],
  'nix': ['nix'],
  'nsis': ['nsi', 'nsh'],
  'nushell': ['nu'],
  'objective-c': ['m'],                // MATLAB's too; Objective-C is the commoner .m in repositories
  'objective-cpp': ['mm'],
  'ocaml': ['ml', 'mli'],
  'odin': ['odin'],
  'openscad': ['scad'],
  'org': ['org'],
  'pascal': ['pas', 'dpr', 'lpr'],
  'perl': ['pl', 'pm', 'pod'],
  'php': ['php', 'phtml'],
  'pkl': ['pkl'],
  'plsql': ['pls', 'pck', 'pkb', 'pks', 'plsql'],
  'po': ['po', 'pot'],
  'polar': ['polar'],
  'postcss': ['pcss', 'postcss'],
  'powerquery': ['pq'],
  'powershell': ['ps1', 'psm1', 'psd1'],
  'prisma': ['prisma'],
  'prolog': ['prolog'],                // .pl is Perl's
  'proto': ['proto'],
  'pug': ['pug', 'jade'],
  'puppet': ['pp'],                    // Free Pascal's too; Puppet manifests are the commoner .pp
  'purescript': ['purs'],
  'qml': ['qml'],
  'qss': ['qss'],
  'r': ['r'],
  'racket': ['rkt'],
  'raku': ['raku', 'rakumod', 'rakutest', 'p6', 'pl6', 'pm6'],
  'razor': ['razor', 'cshtml'],
  'rbs': ['rbs'],
  'reg': ['reg'],
  'rel': ['rel'],
  'ron': ['ron'],
  'rst': ['rst'],
  'ruby': ['rb', 'rake', 'gemspec', 'ru'],
  'sas': ['sas'],
  'sass': ['sass'],
  'scala': ['scala', 'sc', 'sbt'],
  'scheme': ['scm', 'ss'],
  'scss': ['scss'],
  'sdbl': ['sdbl'],
  'shaderlab': ['shader', 'cginc'],
  'smalltalk': ['st'],
  'smithy': ['smithy'],
  'solidity': ['sol'],
  'soy': ['soy'],
  'sparql': ['sparql', 'rq'],
  'splunk': ['spl'],
  'sql': ['sql', 'ddl', 'dml'],
  'stata': ['do', 'ado'],
  'stylus': ['styl'],
  'surrealql': ['surql'],
  'svelte': ['svelte'],
  'system-verilog': ['sv', 'svh'],
  'systemd': ['service', 'socket', 'timer', 'target', 'mount', 'automount', 'slice', 'netdev', 'network'],
  'talonscript': ['talon'],
  'tasl': ['tasl'],
  'tcl': ['tcl', 'tk'],
  'templ': ['templ'],
  'terraform': ['tf', 'tfvars'],
  'toml': ['toml'],
  'tsv': ['tsv'],
  'tsx': ['tsx'],
  'turtle': ['ttl'],
  'twig': ['twig'],
  'typespec': ['tsp'],
  'typst': ['typ'],
  'vala': ['vala', 'vapi'],
  'vb': ['vb', 'vbs', 'bas'],
  'verilog': ['v', 'vh'],              // V and Coq use .v too; Verilog is the commoner one
  'vhdl': ['vhd', 'vhdl'],
  'viml': ['vim'],
  'vue': ['vue'],
  'vyper': ['vy'],
  'wasm': ['wat', 'wast'],
  'wenyan': ['wy'],
  'wgsl': ['wgsl'],
  'wikitext': ['wiki', 'mediawiki'],
  'wit': ['wit'],
  'wolfram': ['wl', 'wls'],
  'xsl': ['xsl', 'xslt'],
  'zenscript': ['zs'],
  'zig': ['zig'],
};

/** Exact file names (compared case-insensitively) for files with no telling extension. */
const FILE_NAMES = {
  'apache': ['.htaccess', 'httpd.conf', 'apache2.conf'],
  'cmake': ['CMakeLists.txt'],
  'codeowners': ['CODEOWNERS'],
  'docker': ['Dockerfile', 'Containerfile'],
  'dotenv': ['.env'],
  'git-commit': ['COMMIT_EDITMSG', 'MERGE_MSG', 'TAG_EDITMSG'],
  'git-rebase': ['git-rebase-todo'],
  'groovy': ['Jenkinsfile'],
  'ini': ['.editorconfig', '.gitconfig', '.gitmodules', '.npmrc'],
  'just': ['justfile', 'Justfile'],
  'make': ['Makefile', 'GNUmakefile', 'makefile'],
  'nginx': ['nginx.conf'],
  'qmldir': ['qmldir'],
  'ruby': ['Gemfile', 'Rakefile', 'Vagrantfile', 'Podfile', 'Brewfile', 'Guardfile'],
  'toml': ['Cargo.lock', 'Pipfile', 'poetry.lock', 'uv.lock'],
  'viml': ['.vimrc', '_vimrc'],
};

const byName = new Map(everything.map(g => [g.name, g]));
const warn = [];
for (const name of [...Object.keys(EXTENSIONS), ...Object.keys(FILE_NAMES)]) {
  if (!byName.has(name)) warn.push(`no upstream grammar named '${name}'`);
  if (CURATED.has(name)) warn.push(`'${name}' is curated; its extensions live in GrammarRegistry`);
}

function licenceOf(g) {
  return LICENCE_CONFIRMED[g.name] ?? g.license;
}

function accepted(g) {
  const licence = licenceOf(g);
  return ACCEPTED_LICENCES.has(licence) || licence === 'Apache-2.0 WITH LLVM-exception'
      || licence === 'LicenseRef-TextMate-Bundle';
}

/** `text` with every `from` of `patches` replaced; throws if a patch no longer matches. */
function patched(name, text, patches) {
  // As the text appears inside a JSON string: backslashes and quotes escaped.
  const jsonEscaped = s => JSON.stringify(s).slice(1, -1);
  for (const p of patches) {
    const needle = jsonEscaped(p.from);
    const count = text.split(needle).length - 1;
    if (count === 0) throw new Error(`${name}: patch '${p.from}' matches nothing any more`);
    text = text.split(needle).join(jsonEscaped(p.to));
    p.count = count;
  }
  return text;
}

// Languages: mapped, licensed, not curated, not excluded.
const included = new Map();
const skipped = [];
for (const name of new Set([...Object.keys(EXTENSIONS), ...Object.keys(FILE_NAMES)])) {
  const g = byName.get(name);
  if (!g || CURATED.has(name)) continue;
  if (EXCLUDED.has(name)) { skipped.push(`${name}: ${EXCLUDED.get(name)}`); continue; }
  if (!accepted(g)) { skipped.push(`${name}: licence ${licenceOf(g) ?? 'unknown'}`); continue; }
  included.set(name, g);
}
// Their embedded dependencies, transitively, when licensed.
const queue = [...included.keys()];
while (queue.length) {
  const g = byName.get(queue.pop());
  for (const dep of g.embedded ?? []) {
    if (included.has(dep) || CURATED.has(dep)) continue;
    const d = byName.get(dep);
    if (!d) { warn.push(`${g.name} embeds unknown '${dep}'`); continue; }
    if (EXCLUDED.has(dep)) continue;
    if (!accepted(d)) { skipped.push(`${dep} (embedded by ${g.name}): licence ${licenceOf(d) ?? 'unknown'}`); continue; }
    included.set(dep, d);
    queue.push(dep);
  }
}

// Scopes must be unique, and no extension may be claimed twice.
const scopes = new Map();
for (const g of included.values()) {
  if (scopes.has(g.scopeName)) warn.push(`scope ${g.scopeName} is both ${scopes.get(g.scopeName)} and ${g.name}`);
  scopes.set(g.scopeName, g.name);
}
const extensionOwner = new Map();
for (const [name, exts] of Object.entries(EXTENSIONS)) {
  if (!included.has(name)) continue;
  for (const ext of exts) {
    if (extensionOwner.has(ext)) warn.push(`extension .${ext} is both ${extensionOwner.get(ext)} and ${name}`);
    extensionOwner.set(ext, name);
  }
}

// Patch first, so a patch that no longer matches stops the run before anything is deleted.
const patchedTexts = new Map([...included.values()].filter(g => PATCHES[g.name]).map(g =>
  [g.name, patched(g.name, readFileSync(join(pkg, 'grammars', `${g.name}.json`), 'utf8'), PATCHES[g.name])]));

// Write the files.
rmSync(outDir, { recursive: true, force: true });
mkdirSync(outDir, { recursive: true });
mkdirSync(samplesDir, { recursive: true });
for (const f of readdirSync(samplesDir)) if (f.endsWith('.sample')) rmSync(join(samplesDir, f));

const index = [];
let samples = 0;
for (const g of [...included.values()].sort((a, b) => a.name.localeCompare(b.name))) {
  const file = `${g.name}.json`;
  if (patchedTexts.has(g.name)) {
    writeFileSync(join(outDir, file), patchedTexts.get(g.name));
  } else {
    copyFileSync(join(pkg, 'grammars', file), join(outDir, file));
  }
  index.push({
    id: g.name,
    displayName: g.displayName ?? g.name,
    scopeName: g.scopeName,
    file,
    extensions: EXTENSIONS[g.name] ?? [],
    // Matched case-insensitively, so "justfile" and "Justfile" are one name.
    fileNames: [...new Set((FILE_NAMES[g.name] ?? []).map(n => n.toLowerCase()))],
    aliases: g.aliases ?? [],
    embedded: (g.embedded ?? []).filter(d => included.has(d) || CURATED.has(d)),
    license: licenceOf(g),
    source: g.source,
  });
  const sample = join(checkout, 'samples', `${g.name}.sample`);
  if (existsSync(sample)) { copyFileSync(sample, join(samplesDir, `${g.name}.sample`)); samples++; }
}
writeFileSync(join(outDir, 'index.json'), JSON.stringify(index, null, 2) + '\n');

// Notices: upstream's own sections for exactly the files bundled.
let commit = 'unknown';
try { commit = execSync('git rev-parse HEAD', { cwd: checkout }).toString().trim(); } catch { /* not a checkout */ }
const upstreamNotice = readFileSync(join(pkg, 'NOTICE'), 'utf8');
const sections = upstreamNotice.split(/^=+\s*$/m);
const kept = [];
for (const section of sections) {
  const files = /^Files:\s+(.+)$/m.exec(section);
  if (!files) continue;
  const names = files[1].split(/[,\s]+/).filter(Boolean);
  if (names.some(n => included.has(n.replace(/\.json$/, '')))) kept.push(section.trim());
}
// Sections of our own for files whose licence upstream's NOTICE does not record.
const rule = '=========================================================================================================';
for (const g of [...included.values()].sort((a, b) => a.name.localeCompare(b.name))) {
  const licence = licenceOf(g);
  if (licence === g.license) continue;   // upstream knew it; its section is above
  const text = licence === 'LicenseRef-TextMate-Bundle' ? TEXTMATE_BUNDLE_LICENCE
      : `See the licence file of ${g.source.replace(/\/blob\/.*$/, '')} (${licence}).`;
  kept.push([`Files:   ${g.name}.json`, `License: ${g.source.replace(/\/blob\/.*$/, '')}`, `SPDX:    ${licence}`,
    '---------------------------------------------------------------------------------------------------------', text].join('\n'));
}
const deviations = Object.entries(PATCHES).filter(([name]) => included.has(name)).flatMap(([name, patches]) =>
  patches.map(p => `  ${name}.json: '${p.from}' -> '${p.to}' (${p.count} occurrence${p.count === 1 ? '' : 's'}): ${p.why}.`));
const header = [
  'THIRD-PARTY NOTICES FOR THE GRAMMAR CATALOGUE (src/main/resources/grammars/tm)',
  '',
  `These grammar files are bundled from the tm-grammars package of`,
  `https://github.com/shikijs/textmate-grammars-themes at commit ${commit},`,
  `regenerated by scripts/update-grammars.mjs on ${new Date().toISOString().slice(0, 10)}.`,
  'Each file is the work of its upstream project and is licensed under the terms recorded',
  'below: the sections of that package\'s own NOTICE that concern the files bundled here,',
  'followed by sections written here for files whose licence that NOTICE does not record.',
  'The samples under src/test/resources/grammar-samples come from the same repository (MIT).',
  '',
  'Every file is byte-for-byte upstream\'s except for these documented repairs, each replacing',
  'a regular-expression construct the joni engine rejects with the nearest one it accepts:',
  ...(deviations.length ? deviations : ['  (none)']),
  '',
  rule,
].join('\n');
writeFileSync(join(outDir, 'NOTICE.txt'), header + '\n' + kept.join(`\n\n${rule}\n`) + '\n');

// Report.
const languages = index.filter(e => e.extensions.length || e.fileNames.length).length;
console.log(`bundled ${index.length} grammars (${languages} languages, ${index.length - languages} embedded-only), ${samples} samples, ${kept.length} notice sections, upstream ${commit.slice(0, 7)}`);
if (skipped.length) console.log(`skipped:\n  ${skipped.sort().join('\n  ')}`);
if (warn.length) console.log(`warnings:\n  ${warn.join('\n  ')}`);
