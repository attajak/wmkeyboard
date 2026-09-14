#!/usr/bin/env node
// Lists every <SettingsPath> breadcrumb in the docs that does not resolve to
// the exact screen or row it names, so a typo in a chip, or a row the app
// renamed, shows up here instead of as a link that lands one screen too high.
//
// Run from docs/:  node scripts/check-settings-links.mjs
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { resolveSettingsLink } from '../src/lib/settings-links.mjs';

function* walk(dir) {
	for (const name of readdirSync(dir)) {
		const full = join(dir, name);
		if (statSync(full).isDirectory()) yield* walk(full);
		else if (/\.mdx?$/.test(name)) yield full;
	}
}

const seen = new Map();
for (const file of walk(new URL('../src/content/docs', import.meta.url).pathname)) {
	const src = readFileSync(file, 'utf8');
	for (const m of src.matchAll(/<SettingsPath\s+path="([^"]*)"/g)) {
		const list = seen.get(m[1]) ?? [];
		list.push(file.replace(/.*\/src\/content\/docs\//, ''));
		seen.set(m[1], list);
	}
}

let exact = 0;
const fallback = [];
const none = [];
for (const [path, files] of [...seen].sort()) {
	const link = resolveSettingsLink(path);
	if (!link) none.push([path, files]);
	else if (link.exact) exact++;
	else fallback.push([path, link.href, files]);
}

console.log(`${seen.size} distinct paths: ${exact} exact, ${fallback.length} fall back to a screen above, ${none.length} unresolved`);
if (fallback.length) {
	console.log('\nFall back to the screen above:');
	for (const [path, href, files] of fallback) console.log(`  ${path}  →  ${href}\n      ${[...new Set(files)].join(', ')}`);
}
if (none.length) {
	console.log('\nUnresolved:');
	for (const [path, files] of none) console.log(`  ${path}\n      ${[...new Set(files)].join(', ')}`);
}
process.exit(none.length ? 1 : 0);
