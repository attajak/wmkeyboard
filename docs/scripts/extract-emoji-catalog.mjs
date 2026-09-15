#!/usr/bin/env node
/**
 * Writes src/data/emoji-catalog.json from the app's built-in emoji catalog
 * (app/src/main/assets/emoji/catalog.tsv): emoji, category, English
 * keywords and name. The emoji-keyword builder uses it as its picker and as
 * the "start from the built-in pack" source. Rerun when the asset changes.
 */
import { readFile, writeFile } from 'node:fs/promises';

const src = new URL('../../app/src/main/assets/emoji/catalog.tsv', import.meta.url);
const text = await readFile(src, 'utf8');
const rows = [];
for (const line of text.split(/\r?\n/)) {
	if (!line.trim() || line.startsWith('# ')) continue;
	const [emoji, category, en, , parent, name] = line.split('\t');
	if (!emoji) continue;
	rows.push({ e: emoji, c: category ?? '', k: (en ?? '').split(',').map((s) => s.trim()).filter(Boolean), n: (name ?? '').trim(), ...(parent ? { p: parent } : {}) });
}
await writeFile(new URL('../src/data/emoji-catalog.json', import.meta.url), JSON.stringify(rows) + '\n');
console.log(`${rows.length} emoji → src/data/emoji-catalog.json`);
