/**
 * The store's state: repositories and their manifests, the active repository,
 * the route, the collection, what's-new bookkeeping and preferences. Signals,
 * so any component can read a slice without prop drilling; every persistent
 * piece is mirrored into localStorage by the functions below, never by
 * components directly.
 *
 * Server-safe: `initStore` only touches props, and `hydrate` (browser only)
 * pulls in localStorage and starts network work.
 */
import { batch, computed, signal } from '@preact/signals';
import { decodeManifest, decodeManifestObject, ManifestError } from './lib/decode';
import { fetchText, MAX_MANIFEST_BYTES, NetError } from './lib/net';
import { resolveManifestUrl } from './lib/resolve';
import { compareSemver, isNewer } from './lib/semver';
import {
	dropCachedManifest,
	loadCachedManifest,
	loadCollection,
	loadPrefs,
	loadRepoState,
	loadSeen,
	MAX_REPOS,
	saveCachedManifest,
	saveCollection,
	savePrefs,
	saveRepoState,
	saveSeen,
	SEEDED_REPOS,
	storageAvailable,
	type Prefs,
	type SeenVersions,
} from './lib/storage';
import { addonKey, type AddonEntry, type AddonKey, type LoadedRepo, type Manifest, type RepoRef } from './lib/types';

/* ---------- routes ---------- */

export type Route =
	| { view: 'home' }
	| { view: 'repo'; repo: string }
	| { view: 'addon'; repo: string; id: string }
	| { view: 'collection' }
	| { view: 'check'; repo?: string }
	| { view: 'compare' }
	| { view: 'new'; type?: string }
	| { view: 'whatsnew' };

export interface StoreInit {
	route: Route;
	/** Manifests the build already fetched (raw JSON, decoded here), keyed by manifest URL. */
	preloaded?: Record<string, unknown>;
	/** Where the preloaded manifests came from, for the stale note. */
	preloadedSource?: 'live' | 'snapshot';
	appVersionCode: number;
	appVersionName: string;
	builtAt: string;
}

/* ---------- signals ---------- */

export const repos = signal<LoadedRepo[]>([]);
export const removedSeeds = signal<string[]>([]);
export const activeRepo = signal<string | null>(null);
export const route = signal<Route>({ view: 'home' });
export const collection = signal<AddonKey[]>([]);
export const seen = signal<SeenVersions>({});
export const prefs = signal<Prefs>({ view: 'grid', sort: 'manifest', autoRefresh: true, introDismissed: false });
export const hydrated = signal(false);
export const storageOk = signal(true);
export const appVersion = signal({ code: 19, name: '0.5.8' });
export const builtAt = signal('');
/** A repository opened from a link that isn't in the list: shown, not saved. */
export const transientRepo = signal<LoadedRepo | null>(null);
export const toast = signal<{ text: string; kind?: 'ok' | 'err'; id: number } | null>(null);
/** True on Android, set after mount so the first client render matches the server's. */
export const onAndroid = signal(false);

export const allRepos = computed<LoadedRepo[]>(() => {
	const t = transientRepo.value;
	return t && !repos.value.some((r) => r.ref.url === t.ref.url) ? [...repos.value, t] : repos.value;
});

export const repoByUrl = computed(() => new Map(allRepos.value.map((r) => [r.ref.url, r])));

export const activeLoaded = computed<LoadedRepo | null>(() => {
	const url = activeRepo.value;
	return url ? (repoByUrl.value.get(url) ?? null) : null;
});

/** Every addon across every listed repository, for "All repositories" and compare. */
export const everyAddon = computed(() =>
	repos.value.flatMap((r) => (r.manifest ? r.manifest.addons.map((entry) => ({ repo: r, entry })) : []))
);

export const collectionSet = computed(() => new Set(collection.value.map(addonKey)));

/* ---------- init ---------- */

let inited: StoreInit | null = null;

export function initStore(init: StoreInit) {
	if (inited === init) return;
	inited = init;
	const preloaded: Record<string, { manifest: Manifest; dropped: LoadedRepo['dropped'] }> = {};
	for (const [url, raw] of Object.entries(init.preloaded ?? {})) {
		try {
			preloaded[url] = decodeManifestObject(raw);
		} catch {
			/* a bad snapshot just means a network fetch later */
		}
	}
	batch(() => {
		appVersion.value = { code: init.appVersionCode, name: init.appVersionName };
		builtAt.value = init.builtAt;
		route.value = init.route;
		repos.value = SEEDED_REPOS.map((ref) => ({
			ref,
			manifest: preloaded[ref.url]?.manifest ?? null,
			error: null,
			fetchedAt: preloaded[ref.url] ? Date.parse(init.builtAt) || null : null,
			loading: false,
			dropped: preloaded[ref.url]?.dropped ?? [],
		}));
		const r = init.route;
		activeRepo.value = 'repo' in r && r.repo ? r.repo : null;
	});
}

/* ---------- hydrate (browser) ---------- */

let hydrateStarted = false;

export function hydrate() {
	if (hydrateStarted || typeof window === 'undefined') return;
	hydrateStarted = true;
	storageOk.value = storageAvailable();
	onAndroid.value = /android/i.test(navigator.userAgent);
	// The static pages are built without their query string (?repo=, ?type=,
	// ?items=); the URL in the browser is the authority once we're here.
	const parsed = parseLocation(location.pathname, location.search);
	if (parsed && parsed.view === route.value.view) route.value = parsed;
	const state = loadRepoState();
	const preloadedByUrl = new Map(repos.value.map((r) => [r.ref.url, r]));
	batch(() => {
		removedSeeds.value = state.removedSeeds;
		repos.value = state.repos.map((ref) => {
			const pre = preloadedByUrl.get(ref.url);
			const cached = loadCachedManifest(ref.url);
			let manifest = pre?.manifest ?? null;
			let fetchedAt = pre?.fetchedAt ?? null;
			let dropped = pre?.dropped ?? [];
			if (cached && (!fetchedAt || cached.fetchedAt > fetchedAt)) {
				try {
					const d = decodeManifest(cached.text);
					manifest = d.manifest;
					dropped = d.dropped;
					fetchedAt = cached.fetchedAt;
				} catch {
					dropCachedManifest(ref.url);
				}
			}
			return { ref, manifest, error: null, fetchedAt, loading: false, dropped };
		});
		collection.value = loadCollection();
		seen.value = loadSeen();
		prefs.value = loadPrefs();
		if (!activeRepo.value) activeRepo.value = state.active && repos.value.some((r) => r.ref.url === state.active) ? state.active : null;
		hydrated.value = true;
	});
	const r = route.value;
	if ('repo' in r && r.repo && !repos.value.some((x) => x.ref.url === r.repo)) {
		openTransient(r.repo);
	}
	if (prefs.value.autoRefresh) void refreshAll(false);
}

function persistRepos() {
	saveRepoState({ repos: repos.value.map((r) => r.ref), removedSeeds: removedSeeds.value, active: activeRepo.value });
}

/* ---------- repositories ---------- */

const inflight = new Map<string, Promise<void>>();

function patchRepo(url: string, patch: Partial<LoadedRepo>) {
	repos.value = repos.value.map((r) => (r.ref.url === url ? { ...r, ...patch } : r));
	const t = transientRepo.value;
	if (t && t.ref.url === url) transientRepo.value = { ...t, ...patch };
}

/** Fetch a manifest and store it; `force` skips the "fresh enough" check. */
export function refreshRepo(url: string, force = true): Promise<void> {
	const existing = inflight.get(url);
	if (existing) return existing;
	const current = repoByUrl.value.get(url);
	if (!current) return Promise.resolve();
	if (!force && current.fetchedAt && Date.now() - current.fetchedAt < 5 * 60_000) return Promise.resolve();
	patchRepo(url, { loading: true });
	const p = (async () => {
		try {
			const text = await fetchText(url, { maxBytes: MAX_MANIFEST_BYTES, accept: 'application/json' });
			const { manifest, dropped } = decodeManifest(text);
			patchRepo(url, { manifest, dropped, error: null, fetchedAt: Date.now(), loading: false });
			saveCachedManifest(url, { text, fetchedAt: Date.now() });
		} catch (e) {
			const message = e instanceof NetError || e instanceof ManifestError ? e.message : `Could not read the repository: ${(e as Error).message}`;
			patchRepo(url, { error: message, loading: false });
		} finally {
			inflight.delete(url);
		}
	})();
	inflight.set(url, p);
	return p;
}

export async function refreshAll(force = true) {
	await Promise.all(repos.value.map((r) => refreshRepo(r.ref.url, force)));
}

export type AddResult = { ok: true; url: string; already: boolean } | { ok: false; error: string };

export async function addRepo(input: string): Promise<AddResult> {
	const url = resolveManifestUrl(input);
	if (!url) return { ok: false, error: 'That is not an address the app can read. Paste an https:// URL, a github.com/user/repo page, or user/repo.' };
	if (repos.value.some((r) => r.ref.url === url)) {
		activeRepo.value = url;
		persistRepos();
		return { ok: true, url, already: true };
	}
	if (repos.value.length >= MAX_REPOS) return { ok: false, error: `The app keeps at most ${MAX_REPOS} repositories; remove one first.` };
	const ref: RepoRef = { url, input: input.trim(), addedAt: Date.now(), seeded: SEEDED_REPOS.some((s) => s.url === url), slug: SEEDED_REPOS.find((s) => s.url === url)?.slug };
	const t = transientRepo.value;
	const loaded: LoadedRepo = t && t.ref.url === url ? { ...t, ref } : { ref, manifest: null, error: null, fetchedAt: null, loading: false, dropped: [] };
	batch(() => {
		repos.value = [...repos.value, loaded];
		removedSeeds.value = removedSeeds.value.filter((u) => u !== url);
		if (t && t.ref.url === url) transientRepo.value = null;
		activeRepo.value = url;
	});
	persistRepos();
	if (!loaded.manifest) {
		await refreshRepo(url);
		const after = repoByUrl.value.get(url);
		if (after?.error && !after.manifest) {
			// The app removes an unreadable repository straight away; do the same.
			removeRepo(url, false);
			return { ok: false, error: after.error };
		}
	}
	return { ok: true, url, already: false };
}

export function removeRepo(url: string, remember = true) {
	batch(() => {
		repos.value = repos.value.filter((r) => r.ref.url !== url);
		if (remember && SEEDED_REPOS.some((s) => s.url === url)) removedSeeds.value = [...new Set([...removedSeeds.value, url])];
		if (activeRepo.value === url) activeRepo.value = null;
	});
	dropCachedManifest(url);
	persistRepos();
}

export function restoreSeeds() {
	const have = new Set(repos.value.map((r) => r.ref.url));
	batch(() => {
		repos.value = [...SEEDED_REPOS.filter((s) => !have.has(s.url)).map((ref) => ({ ref, manifest: null, error: null, fetchedAt: null, loading: false, dropped: [] })), ...repos.value];
		removedSeeds.value = [];
	});
	persistRepos();
	void refreshAll(false);
}

export function setActiveRepo(url: string | null) {
	activeRepo.value = url;
	persistRepos();
}

/** Show a repository from a link without adding it (the app's deep-link stance). */
export function openTransient(url: string) {
	if (repos.value.some((r) => r.ref.url === url)) return;
	const seed = SEEDED_REPOS.find((s) => s.url === url);
	const ref: RepoRef = { url, input: seed?.input ?? url, addedAt: 0, seeded: !!seed, slug: seed?.slug };
	transientRepo.value = { ref, manifest: null, error: null, fetchedAt: null, loading: false, dropped: [] };
	void refreshRepo(url);
}

/* ---------- lookups ---------- */

export function findAddon(repoUrl: string, id: string): { repo: LoadedRepo; entry: AddonEntry } | null {
	const repo = repoByUrl.value.get(repoUrl);
	const entry = repo?.manifest?.addons.find((a) => a.id === id);
	return repo && entry ? { repo, entry } : null;
}

/* ---------- collection ---------- */

export function inCollection(k: AddonKey): boolean {
	return collectionSet.value.has(addonKey(k));
}

export function toggleCollection(k: AddonKey) {
	const key = addonKey(k);
	collection.value = collectionSet.value.has(key) ? collection.value.filter((x) => addonKey(x) !== key) : [...collection.value, k];
	saveCollection(collection.value);
}

export function clearCollection() {
	collection.value = [];
	saveCollection([]);
}

/* ---------- what's new ---------- */

export type Novelty = 'new' | 'updated' | null;

/** null until the repository has been seen once — first sight isn't "new". */
export function noveltyOf(repoUrl: string, entry: AddonEntry): Novelty {
	const s = seen.value[repoUrl];
	if (!s) return null;
	const prev = s[entry.id];
	if (prev === undefined) return 'new';
	return isNewer(entry.version, prev) ? 'updated' : null;
}

export function unseenCount(repo: LoadedRepo): number {
	if (!repo.manifest) return 0;
	return repo.manifest.addons.filter((a) => noveltyOf(repo.ref.url, a) !== null).length;
}

export function markSeen(repoUrl: string) {
	const repo = repoByUrl.value.get(repoUrl);
	if (!repo?.manifest) return;
	const next: Record<string, string> = {};
	for (const a of repo.manifest.addons) next[a.id] = a.version;
	seen.value = { ...seen.value, [repoUrl]: next };
	saveSeen(seen.value);
}

/** First visit to a repository: remember what's there so the next change shows up. */
export function seedSeen(repoUrl: string) {
	if (!seen.value[repoUrl]) markSeen(repoUrl);
}

/* ---------- prefs ---------- */

export function setPrefs(patch: Partial<Prefs>) {
	prefs.value = { ...prefs.value, ...patch };
	savePrefs(prefs.value);
}

/* ---------- sorting ---------- */

export function sortAddons(list: AddonEntry[], sort: Prefs['sort']): AddonEntry[] {
	const out = [...list];
	switch (sort) {
		case 'name':
			return out.sort((a, b) => a.name.localeCompare(b.name));
		case 'version':
			return out.sort((a, b) => compareSemver(b.version, a.version));
		case 'size':
			return out.sort((a, b) => (b.sizeBytes ?? 0) - (a.sizeBytes ?? 0));
		case 'type':
			return out.sort((a, b) => a.type.localeCompare(b.type) || a.name.localeCompare(b.name));
		default:
			return out;
	}
}

/* ---------- toasts ---------- */

let toastSeq = 0;
export function showToast(text: string, kind: 'ok' | 'err' = 'ok') {
	const id = ++toastSeq;
	toast.value = { text, kind, id };
	setTimeout(() => {
		if (toast.value?.id === id) toast.value = null;
	}, 3200);
}

/* ---------- navigation ---------- */

export function hrefFor(r: Route): string {
	const base = '/addons/';
	const slugOf = (url: string) => repoByUrl.value.get(url)?.ref.slug ?? SEEDED_REPOS.find((s) => s.url === url)?.slug;
	const inputOf = (url: string) => repoByUrl.value.get(url)?.ref.input ?? url;
	switch (r.view) {
		case 'home':
			return base;
		case 'repo': {
			const slug = slugOf(r.repo);
			return slug ? `${base}${slug}/` : `${base}?repo=${encodeURIComponent(inputOf(r.repo))}`;
		}
		case 'addon': {
			const slug = slugOf(r.repo);
			return slug ? `${base}${slug}/${encodeURIComponent(r.id)}/` : `${base}?repo=${encodeURIComponent(inputOf(r.repo))}&id=${encodeURIComponent(r.id)}`;
		}
		case 'collection':
			return `${base}collection/`;
		case 'check':
			return r.repo ? `${base}check/?repo=${encodeURIComponent(inputOf(r.repo))}` : `${base}check/`;
		case 'compare':
			return `${base}compare/`;
		case 'new':
			return r.type ? `${base}new/?type=${encodeURIComponent(r.type)}` : `${base}new/`;
		case 'whatsnew':
			return `${base}whats-new/`;
	}
}

/** Parse a location into a route; `null` when it isn't a store URL. */
export function parseLocation(pathname: string, search: string): Route | null {
	const m = pathname.match(/^\/addons\/?(.*)$/);
	if (!m) return null;
	const parts = m[1]!.split('/').filter(Boolean).map(decodeURIComponent);
	const q = new URLSearchParams(search);
	const repoParam = q.get('repo');
	const repoUrl = repoParam ? resolveManifestUrl(repoParam) : null;
	if (parts.length === 0) {
		if (repoUrl && q.get('id')) return { view: 'addon', repo: repoUrl, id: q.get('id')! };
		if (repoUrl) return { view: 'repo', repo: repoUrl };
		return { view: 'home' };
	}
	const [head, second] = parts;
	if (head === 'collection') return { view: 'collection' };
	if (head === 'compare') return { view: 'compare' };
	if (head === 'whats-new') return { view: 'whatsnew' };
	if (head === 'check') return { view: 'check', repo: repoUrl ?? undefined };
	if (head === 'new') return { view: 'new', type: q.get('type') ?? undefined };
	const seed = SEEDED_REPOS.find((s) => s.slug === head);
	if (seed) return second ? { view: 'addon', repo: seed.url, id: second } : { view: 'repo', repo: seed.url };
	return null;
}

export function navigate(r: Route, replace = false) {
	route.value = r;
	if ('repo' in r && r.repo) {
		if (!repoByUrl.value.has(r.repo)) openTransient(r.repo);
	}
	if (typeof history !== 'undefined') {
		const href = hrefFor(r);
		if (replace) history.replaceState({ route: r }, '', href);
		else history.pushState({ route: r }, '', href);
		window.scrollTo({ top: 0 });
	}
}

/** Wire popstate once; the island calls this after hydrating. */
let popWired = false;
export function wireHistory() {
	if (popWired || typeof window === 'undefined') return;
	popWired = true;
	history.replaceState({ route: route.value }, '', location.href);
	window.addEventListener('popstate', (e) => {
		const r = (e.state?.route as Route | undefined) ?? parseLocation(location.pathname, location.search) ?? { view: 'home' };
		route.value = r;
		if ('repo' in r && r.repo && !repoByUrl.value.has(r.repo)) openTransient(r.repo);
	});
}
