#!/usr/bin/env node
/**
 * Fills the per-scope syntax colours (-jfx-tm-*) of the themes that are not GitHub's.
 *
 *   node scripts/derive-theme-syntax.mjs
 *
 * theme-dark.css and theme-light.css carry GitHub Dark / Light Default's colours, generated
 * from the tm-themes package by scripts/update-syntax-theme.mjs. The other built-in themes
 * need the same token set (the stylesheets read every one of them) but not GitHub's colours,
 * so each takes the GitHub block of its own base and recolours it: GitHub's eleven or so
 * role colours ("keyword red", "string pale blue") each map to one of the theme's own. That
 * keeps every scope's role - what is a keyword, a string, a tag - exactly where GitHub's
 * theme put it, which is the part that took a theme author's judgement, and changes only
 * the paint.
 *
 * Run it after update-syntax-theme.mjs: the blocks it writes are derived from the ones that
 * script writes. Each theme file must already carry the markers.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'css');

const BEGIN = '/* --- generated: syntax theme (scripts/derive-theme-syntax.mjs) - do not edit by hand --- */';
const END = '/* --- end generated --- */';
// The block the other script writes carries its own marker text.
const SOURCE_BEGIN = '/* --- generated: syntax theme (scripts/update-syntax-theme.mjs) - do not edit by hand --- */';

/**
 * GitHub's role colour -> this theme's. Dark themes recolour GitHub Dark Default, light
 * ones GitHub Light Default. A colour not listed is left as it is, and the test that reads
 * these files fails on a colour that does not fit its ground, so a role missed here shows.
 */
const THEMES = {
  'theme-dark-green.css': { from: 'theme-dark.css', map: {
    '#ff7b72': '#c678dd',   // keyword, storage
    '#79c0ff': '#56b6c2',   // constants, entities, support
    '#a5d6ff': '#98c379',   // strings
    '#ffa657': '#e5c07b',   // variables, entity names
    '#d2a8ff': '#61afef',   // functions
    '#7ee787': '#73c7a4',   // tags, JSON keys, inserted
    '#ffa198': '#e06c75',   // invalid, deleted
    '#8b949e': '#7f8794',   // comments, brackets
    '#e6edf3': '#d5d8de',   // the default foreground
    '#f0f6fc': '#eceef2',
    '#161b22': '#111216',   // ignored / untracked: the list's own ground
  } },
  'theme-midnight.css': { from: 'theme-dark.css', map: {
    '#ff7b72': '#c792ea',
    '#79c0ff': '#89ddff',
    '#a5d6ff': '#c3e88d',
    '#ffa657': '#ffcb6b',
    '#d2a8ff': '#82aaff',
    '#7ee787': '#7fdbca',
    '#ffa198': '#ff6b81',
    '#8b949e': '#7480a6',
    '#e6edf3': '#c9d2e6',
    '#f0f6fc': '#e8edf8',
    '#161b22': '#0f1526',
  } },
  'theme-light-green.css': { from: 'theme-light.css', map: {
    '#cf222e': '#8e24aa',
    '#0550ae': '#00748f',
    '#0a3069': '#2f7d32',
    '#953800': '#9a5b00',
    '#8250df': '#1a56b8',
    '#116329': '#0f7a5a',
    '#82071e': '#c62828',
    '#6e7781': '#6b7280',
    '#57606a': '#5f6672',
    '#1f2328': '#2b2f36',
    '#f6f8fa': '#f1f3f6',
    '#eaeef2': '#e8ebef',
  } },
  'theme-paper.css': { from: 'theme-light.css', map: {
    '#cf222e': '#9c3f7a',
    '#0550ae': '#1f6a7a',
    '#0a3069': '#4f7a2a',
    '#953800': '#955f0a',
    '#8250df': '#2a5ea8',
    '#116329': '#2f6e55',
    '#82071e': '#a8322a',
    '#6e7781': '#7a7160',
    '#57606a': '#645c4a',
    '#1f2328': '#3a3427',
    '#f6f8fa': '#f6f1e4',
    '#eaeef2': '#e4dbc5',
  } },
  'theme-nord-dark.css': { from: 'theme-dark.css', map: {
    '#ff7b72': '#81a1c1',   // keyword, storage
    '#79c0ff': '#8fbcbb',   // constants, entities, support
    '#a5d6ff': '#a3be8c',   // strings
    '#ffa657': '#d68c74',   // variables, entity names
    '#d2a8ff': '#88c0d0',   // functions
    '#7ee787': '#8fc9a6',   // tags, JSON keys, inserted
    '#ffa198': '#e0838c',   // invalid, deleted
    '#8b949e': '#7b88a1',   // comments, brackets
    '#e6edf3': '#d8dee9',   // the default foreground
    '#f0f6fc': '#eceff4',
    '#161b22': '#0c121d',   // ignored / untracked: the list's own ground
  } },
  'theme-solarized-dark.css': { from: 'theme-dark.css', map: {
    '#ff7b72': '#9aae00',   // keyword, storage
    '#79c0ff': '#4aa3e0',   // constants, entities, support
    '#a5d6ff': '#35b5aa',   // strings
    '#ffa657': '#d0a000',   // variables, entity names
    '#d2a8ff': '#8f94e0',   // functions
    '#7ee787': '#6ec27a',   // tags, JSON keys, inserted
    '#ffa198': '#f0625f',   // invalid, deleted
    '#8b949e': '#7d9496',   // comments, brackets
    '#e6edf3': '#c5d7d6',   // the default foreground
    '#f0f6fc': '#eee8d5',
    '#161b22': '#00232d',   // ignored / untracked: the list's own ground
  } },
  'theme-gruvbox-dark.css': { from: 'theme-dark.css', map: {
    '#ff7b72': '#fb5a46',   // keyword, storage
    '#79c0ff': '#83a598',   // constants, entities, support
    '#a5d6ff': '#b8bb26',   // strings
    '#ffa657': '#fe8019',   // variables, entity names
    '#d2a8ff': '#fabd2f',   // functions
    '#7ee787': '#8ec07c',   // tags, JSON keys, inserted
    '#ffa198': '#fb6a5c',   // invalid, deleted
    '#8b949e': '#928374',   // comments, brackets
    '#e6edf3': '#ebdbb2',   // the default foreground
    '#f0f6fc': '#fbf1c7',
    '#161b22': '#0f1314',   // ignored / untracked: the list's own ground
  } },
  'theme-nord-light.css': { from: 'theme-light.css', map: {
    '#cf222e': '#3f64a0',   // keyword, storage
    '#0550ae': '#27727f',   // constants, entities, support
    '#0a3069': '#44703a',   // strings
    '#953800': '#9f4a29',   // variables, entity names
    '#8250df': '#8a4f86',   // functions
    '#116329': '#337069',   // tags, JSON keys, inserted
    '#82071e': '#b0404c',   // invalid, deleted
    '#6e7781': '#5f6b83',   // comments
    '#57606a': '#4c566a',   // brackets
    '#1f2328': '#2e3440',   // the default foreground
    '#f6f8fa': '#e5e9f0',
    '#eaeef2': '#dde2eb',   // ignored / untracked: the list's own ground
  } },
  'theme-solarized-light.css': { from: 'theme-light.css', map: {
    '#cf222e': '#5f6e00',   // keyword, storage
    '#0550ae': '#1b66a6',   // constants, entities, support
    '#0a3069': '#17756e',   // strings
    '#953800': '#7f5c00',   // variables, entity names
    '#8250df': '#5a5fb0',   // functions
    '#116329': '#2a734c',   // tags, JSON keys, inserted
    '#82071e': '#bd2f2c',   // invalid, deleted
    '#6e7781': '#60757c',   // comments
    '#57606a': '#50656c',   // brackets
    '#1f2328': '#283a40',   // the default foreground
    '#f6f8fa': '#f3ecd8',
    '#eaeef2': '#e4ddc6',   // ignored / untracked: the list's own ground
  } },
  'theme-gruvbox-light.css': { from: 'theme-light.css', map: {
    '#cf222e': '#9d0006',   // keyword, storage
    '#0550ae': '#076678',   // constants, entities, support
    '#0a3069': '#6f6a0c',   // strings
    '#953800': '#a63a03',   // variables, entity names
    '#8250df': '#8f3f71',   // functions
    '#116329': '#3b6e50',   // tags, JSON keys, inserted
    '#82071e': '#b3201c',   // invalid, deleted
    '#6e7781': '#78695e',   // comments
    '#57606a': '#625850',   // brackets
    '#1f2328': '#3c3836',   // the default foreground
    '#f6f8fa': '#f2e5bc',
    '#eaeef2': '#e6d5a8',   // ignored / untracked: the list's own ground
  } },
};

/** The lines between the markers of {@code text}, as written, or throws. */
function between(text, begin, end, file) {
  const from = text.indexOf(begin);
  const to = text.indexOf(end);
  if (from < 0 || to < from) throw new Error(`${file}: expected the markers\n  ${begin}\n  ${end}`);
  return { from, to, inner: text.slice(from + begin.length, to) };
}

for (const [file, { from, map }] of Object.entries(THEMES)) {
  const source = between(readFileSync(join(root, from), 'utf8'), SOURCE_BEGIN, END, from).inner;
  const unmapped = new Set();
  const recoloured = source.replace(/#[0-9a-fA-F]{6}\b/g, hex => {
    const target = map[hex.toLowerCase()];
    if (!target) unmapped.add(hex.toLowerCase());
    return target ?? hex;
  });
  const path = join(root, file);
  const text = readFileSync(path, 'utf8');
  const block = between(text, BEGIN, END, file);
  // The checkout's line endings, whichever they are: the source block is read in its own.
  const eol = text.includes('\r\n') ? '\r\n' : '\n';
  writeFileSync(path, text.slice(0, block.from) + BEGIN + recoloured.replace(/\r?\n/g, eol) + text.slice(block.to));
  console.log(`${file}: recoloured ${from}'s block${unmapped.size ? ` (left alone: ${[...unmapped].join(', ')})` : ''}`);
}
