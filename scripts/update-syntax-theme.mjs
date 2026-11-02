#!/usr/bin/env node
/**
 * Generates the syntax colours from GitHub's own editor themes, as shipped in the
 * tm-themes package of shikijs/textmate-grammars-themes:
 *
 *   node scripts/update-syntax-theme.mjs ../textmate-grammars-themes
 *
 * The app's chrome is AtlantaFX Primer (GitHub's palette), so GitHub Dark Default and
 * GitHub Light Default are the themes whose token colours sit naturally on it. Each
 * theme rule names scopes (`keyword`, `entity.name.function`, ...) and a colour; the
 * tokenizer gives every token the classes of its innermost scope's dot-prefixes
 * (`tm-entity`, `tm-entity-name`, `tm-entity-name-function`), so one CSS rule per scope,
 * emitted shallowest first so the most specific scope wins the cascade, reproduces the
 * theme's matching for plain selectors. Descendant selectors (`string variable`) have no
 * equivalent in a class list and are skipped.
 *
 * Written between markers in three files, which must already carry them:
 *   src/main/resources/css/theme-dark.css    -jfx-tm-<scope>: <colour>;   (dark)
 *   src/main/resources/css/theme-light.css   the same tokens, light colours
 *   src/main/resources/diff-styles.css       .code-area .text.tm-<scope> { -fx-fill: -jfx-tm-<scope>; ... }
 * Colours live only in the theme files (the app stylesheets may hold no hex colour), and
 * both themes define exactly the same token set (a scope one theme lacks takes that
 * theme's default foreground).
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { execSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(here, '..');
const checkout = resolve(process.argv[2] ?? resolve(projectRoot, '..', 'textmate-grammars-themes'));
const themesDir = join(checkout, 'packages', 'tm-themes', 'themes');
const THEMES = { dark: 'github-dark-default', light: 'github-light-default' };
for (const name of Object.values(THEMES)) {
  if (!existsSync(join(themesDir, `${name}.json`))) {
    console.error(`No theme ${name}.json under ${themesDir}; pass the checkout's path as the first argument.`);
    process.exit(2);
  }
}

const BEGIN = '/* --- generated: syntax theme (scripts/update-syntax-theme.mjs) - do not edit by hand --- */';
const END = '/* --- end generated --- */';

/** `name -> {dark, light, style}` for every plain scope either theme names. */
const scopes = new Map();
const defaults = {};
for (const [mode, name] of Object.entries(THEMES)) {
  const theme = JSON.parse(readFileSync(join(themesDir, `${name}.json`), 'utf8'));
  defaults[mode] = theme.colors['editor.foreground'];
  for (const entry of theme.tokenColors) {
    const list = Array.isArray(entry.scope) ? entry.scope : String(entry.scope ?? '').split(',');
    for (const raw of list) {
      const scope = raw.trim();
      if (!scope || /\s/.test(scope)) continue;        // descendant selectors: no class-list equivalent
      const row = scopes.get(scope) ?? { style: '' };
      // Later entries override earlier ones, as they do in the theme.
      if (entry.settings.foreground) row[mode] = entry.settings.foreground.toLowerCase();
      if (entry.settings.fontStyle !== undefined) row.style = entry.settings.fontStyle;
      scopes.set(scope, row);
    }
  }
}

const className = scope => 'tm-' + scope.replace(/\./g, '-');
const tokenName = scope => '-jfx-' + className(scope);
const ordered = [...scopes.entries()].sort((a, b) => a[0].split('.').length - b[0].split('.').length);

function tokensBlock(mode) {
  const lines = [`    ${BEGIN}`];
  for (const [scope, row] of ordered) {
    if (!row.dark && !row.light) continue;             // style-only scope: no colour token
    lines.push(`    ${tokenName(scope)}: ${row[mode] ?? defaults[mode]};`);
  }
  lines.push(`    ${END}`);
  return lines.join('\n');
}

function rulesBlock() {
  const lines = [BEGIN];
  for (const [scope, row] of ordered) {
    const decls = [];
    if (row.dark || row.light) decls.push(`-fx-fill: ${tokenName(scope)};`);
    const style = row.style ?? '';
    if (/\bitalic\b/.test(style)) decls.push('-fx-font-style: italic;');
    if (/\bbold\b/.test(style)) decls.push('-fx-font-weight: bold;');
    if (/\bunderline\b/.test(style)) decls.push('-fx-underline: true;');
    if (/\bstrikethrough\b/.test(style)) decls.push('-fx-strikethrough: true;');
    if (!decls.length) continue;
    lines.push(`.code-area .text.${className(scope)} { ${decls.join(' ')} }`);
  }
  lines.push(END);
  return lines.join('\n');
}

function replaceBetweenMarkers(path, block) {
  const text = readFileSync(path, 'utf8');
  const begin = text.indexOf(BEGIN);
  const end = text.indexOf(END);
  if (begin < 0 || end < 0 || end < begin) {
    throw new Error(`${path}: expected the markers\n  ${BEGIN}\n  ${END}`);
  }
  const lineStart = text.lastIndexOf('\n', begin) + 1;
  const updated = text.slice(0, lineStart) + block + text.slice(end + END.length);
  writeFileSync(path, updated);
}

replaceBetweenMarkers(join(projectRoot, 'src/main/resources/css/theme-dark.css'), tokensBlock('dark'));
replaceBetweenMarkers(join(projectRoot, 'src/main/resources/css/theme-light.css'), tokensBlock('light'));
replaceBetweenMarkers(join(projectRoot, 'src/main/resources/diff-styles.css'), rulesBlock());

let commit = 'unknown';
try { commit = execSync('git rev-parse --short HEAD', { cwd: checkout }).toString().trim(); } catch { /* not a checkout */ }
const coloured = ordered.filter(([, r]) => r.dark || r.light).length;
console.log(`wrote ${ordered.length} scope rules (${coloured} with a colour token) from ${THEMES.dark} / ${THEMES.light}, upstream ${commit}`);
