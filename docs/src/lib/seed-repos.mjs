/**
 * The two repositories the app seeds on first launch (AddonStore.kt), as the
 * build sees them. Shared by the prerendered /addons/<slug>/ pages and the
 * snapshot script; the client copy of the same list lives in
 * src/store/lib/storage.ts (SEEDED_REPOS) — keep the URLs identical.
 */
import { readFile } from 'node:fs/promises';

/**
 * @typedef {{ slug: string, input: string, url: string }} Seed
 * @typedef {{ manifest: any, source: 'live' | 'snapshot' }} Loaded
 * @typedef {Record<string, Loaded & { seed: Seed }>} SeedMap
 */

/** @type {Seed[]} */
export const SEEDS = [
	{
		slug: 'official',
		input: 'https://github.com/wasi-master/wmkeyboard-addon-repository',
		url: 'https://raw.githubusercontent.com/wasi-master/wmkeyboard-addon-repository/HEAD/wmkeyboard-repo.json',
	},
	{
		slug: 'sounds',
		input: 'https://github.com/wasi-master/wmkeyboard-monkeytype-sounds',
		url: 'https://raw.githubusercontent.com/wasi-master/wmkeyboard-monkeytype-sounds/HEAD/wmkeyboard-repo.json',
	},
];

/** Live manifest text, or throws. */
/** @param {Seed} seed */
export async function fetchSeed(seed, { timeoutMs = 12_000 } = {}) {
	const ctl = new AbortController();
	const t = setTimeout(() => ctl.abort(), timeoutMs);
	try {
		const res = await fetch(seed.url, { signal: ctl.signal, headers: { 'User-Agent': 'wmkeyboard-docs-build' } });
		if (!res.ok) throw new Error(`${seed.url}: HTTP ${res.status}`);
		const text = await res.text();
		const json = JSON.parse(text);
		if (json.format !== 'wmkeyboard-repo') throw new Error(`${seed.url}: not a wmkeyboard-repo manifest`);
		return text;
	} finally {
		clearTimeout(t);
	}
}

/**
 * Live manifest when reachable, the committed snapshot otherwise. Returns the
 * parsed JSON plus where it came from, so the page can say when it's stale.
 */
/** @param {Seed} seed @returns {Promise<Loaded>} */
export async function loadSeed(seed) {
	try {
		const text = await fetchSeed(seed);
		return { manifest: JSON.parse(text), source: 'live' };
	} catch (e) {
		const snapshot = new URL(`../data/addons/${seed.slug}.json`, import.meta.url);
		const text = await readFile(snapshot, 'utf8');
		console.warn(`[addons] ${seed.slug}: live fetch failed (${e.message}); using snapshot`);
		return { manifest: JSON.parse(text), source: 'snapshot' };
	}
}

/** @returns {Promise<SeedMap>} */
export async function loadAllSeeds() {
	/** @type {SeedMap} */
	const out = {};
	for (const seed of SEEDS) out[seed.slug] = { seed, ...(await loadSeed(seed)) };
	return out;
}
