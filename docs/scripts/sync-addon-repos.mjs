#!/usr/bin/env node
/**
 * Refreshes the build-time snapshots of the two seeded addon repositories
 * (src/data/addons/*.json). The build fetches the live manifests itself and
 * only falls back to these when the network is down, so the snapshots exist
 * to keep `astro build` deterministic offline, not as the source of truth.
 *
 *   node scripts/sync-addon-repos.mjs
 */
import { writeFile, mkdir } from 'node:fs/promises';
import { SEEDS, fetchSeed } from '../src/lib/seed-repos.mjs';

await mkdir(new URL('../src/data/addons/', import.meta.url), { recursive: true });
for (const seed of SEEDS) {
	const text = await fetchSeed(seed, { timeoutMs: 20_000 });
	const target = new URL(`../src/data/addons/${seed.slug}.json`, import.meta.url);
	await writeFile(target, text.endsWith('\n') ? text : text + '\n');
	console.log(`${seed.slug}: ${JSON.parse(text).addons.length} addons → ${target.pathname}`);
}
