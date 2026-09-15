/**
 * Everything the store remembers lives in localStorage under `wm.addons.*`,
 * every access wrapped: a private window, a blocked origin or a full quota
 * must never take the page down. Manifests are cached too (1 MB cap each in
 * the app, so a handful fit), evicted oldest-first when the quota bites.
 */
import type { AddonKey, RepoRef } from './types';

const NS = 'wm.addons.';

function read<T>(key: string, fallback: T): T {
	try {
		const raw = localStorage.getItem(NS + key);
		return raw == null ? fallback : (JSON.parse(raw) as T);
	} catch {
		return fallback;
	}
}

function write(key: string, value: unknown): boolean {
	try {
		localStorage.setItem(NS + key, JSON.stringify(value));
		return true;
	} catch {
		return false;
	}
}

function remove(key: string) {
	try {
		localStorage.removeItem(NS + key);
	} catch {
		/* ignore */
	}
}

/* ---------- repositories ---------- */

export const SEEDED_REPOS: RepoRef[] = [
	{
		url: 'https://raw.githubusercontent.com/wasi-master/wmkeyboard-addon-repository/HEAD/wmkeyboard-repo.json',
		input: 'https://github.com/wasi-master/wmkeyboard-addon-repository',
		seeded: true,
		slug: 'official',
		addedAt: 0,
	},
	{
		url: 'https://raw.githubusercontent.com/wasi-master/wmkeyboard-monkeytype-sounds/HEAD/wmkeyboard-repo.json',
		input: 'https://github.com/wasi-master/wmkeyboard-monkeytype-sounds',
		seeded: true,
		slug: 'sounds',
		addedAt: 0,
	},
];

/** The app's `MAX_REPOS`. */
export const MAX_REPOS = 30;

interface RepoState {
	repos: RepoRef[];
	/** Seeded URLs the user removed on purpose; sticky like the app's marker file. */
	removedSeeds: string[];
	active: string | null;
}

export function loadRepoState(): RepoState {
	const s = read<Partial<RepoState>>('repos', {});
	const removed = new Set(s.removedSeeds ?? []);
	const user = (s.repos ?? []).filter((r) => r && typeof r.url === 'string');
	const known = new Set(user.map((r) => r.url));
	const seeded = SEEDED_REPOS.filter((r) => !removed.has(r.url) && !known.has(r.url));
	// Seeded first, then the user's in the order they were added.
	const repos = [...seeded, ...user.map((r) => ({ ...r, seeded: SEEDED_REPOS.some((s) => s.url === r.url) ? true : r.seeded, slug: SEEDED_REPOS.find((s) => s.url === r.url)?.slug ?? r.slug }))];
	return { repos, removedSeeds: [...removed], active: s.active ?? null };
}

export function saveRepoState(state: RepoState) {
	write('repos', {
		repos: state.repos.filter((r) => !r.seeded),
		removedSeeds: state.removedSeeds,
		active: state.active,
	});
}

/* ---------- manifest cache ---------- */

export interface CachedManifest {
	text: string;
	fetchedAt: number;
	etag?: string;
}

const CACHE_INDEX = 'cache.index';

export function loadCachedManifest(url: string): CachedManifest | null {
	return read<CachedManifest | null>('cache.' + url, null);
}

export function saveCachedManifest(url: string, entry: CachedManifest) {
	const index = read<string[]>(CACHE_INDEX, []).filter((u) => u !== url);
	index.push(url);
	// Evict until it fits; a quota error on the index itself is harmless.
	for (let attempt = 0; attempt < 8; attempt++) {
		if (write('cache.' + url, entry)) break;
		const oldest = index.shift();
		if (!oldest || oldest === url) break;
		remove('cache.' + oldest);
	}
	write(CACHE_INDEX, index);
}

export function dropCachedManifest(url: string) {
	remove('cache.' + url);
	write(CACHE_INDEX, read<string[]>(CACHE_INDEX, []).filter((u) => u !== url));
}

/* ---------- collection ---------- */

export function loadCollection(): AddonKey[] {
	return read<AddonKey[]>('collection', []).filter((k) => k && typeof k.repo === 'string' && typeof k.id === 'string');
}

export function saveCollection(items: AddonKey[]) {
	write('collection', items);
}

/* ---------- what's new ---------- */

/** Per repository: the version of every addon the user has already seen. */
export type SeenVersions = Record<string, Record<string, string>>;

export function loadSeen(): SeenVersions {
	return read<SeenVersions>('seen', {});
}

export function saveSeen(seen: SeenVersions) {
	write('seen', seen);
}

/* ---------- preferences ---------- */

export interface Prefs {
	view: 'grid' | 'list';
	sort: 'manifest' | 'name' | 'version' | 'size' | 'type' | 'updated';
	autoRefresh: boolean;
	/** The user has seen the "this is a preview of the app's store" note. */
	introDismissed: boolean;
}

const DEFAULT_PREFS: Prefs = { view: 'grid', sort: 'manifest', autoRefresh: true, introDismissed: false };

export function loadPrefs(): Prefs {
	return { ...DEFAULT_PREFS, ...read<Partial<Prefs>>('prefs', {}) };
}

export function savePrefs(p: Prefs) {
	write('prefs', p);
}

/* ---------- creator drafts ---------- */

export function loadDraft<T>(kind: string, fallback: T): T {
	return read<T>('draft.' + kind, fallback);
}

export function saveDraft(kind: string, value: unknown): boolean {
	return write('draft.' + kind, value);
}

export function dropDraft(kind: string) {
	remove('draft.' + kind);
}

export function storageAvailable(): boolean {
	try {
		const k = NS + '__probe';
		localStorage.setItem(k, '1');
		localStorage.removeItem(k);
		return true;
	} catch {
		return false;
	}
}
