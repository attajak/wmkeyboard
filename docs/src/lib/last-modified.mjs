/**
 * When each documentation page last changed, taken from git.
 *
 * Two things want this: the sitemap's `<lastmod>` (a crawl-priority signal, and
 * the one field Search Console reads to decide a recrawl is worth it) and the
 * `dateModified` in each page's JSON-LD. Starlight's own `lastUpdated` already
 * reads git per page, but it isn't exposed to the sitemap integration, which
 * runs outside the Starlight render.
 *
 * One `git log` pass builds the whole map. Asking git per file meant 143
 * processes and about two seconds of the build spent waiting on fork().
 *
 * A checkout with no git history (a tarball, or a shallow clone the history
 * can't be fetched into) yields nothing for some files. That's fine: a missing
 * `lastmod` is correct there, and inventing `Date.now()` would tell crawlers
 * every page changed on every deploy.
 */

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, isAbsolute, join } from 'node:path';

/**
 * The docs directory, found from the working directory rather than from
 * `import.meta.url`.
 *
 * At render time this module is a chunk inside `dist/.prerender/`, so a path
 * built from its own URL points into the bundle: `git log -- src/content/docs`
 * ran against a directory with no such pathspec and quietly returned nothing,
 * which is how every page shipped with `dateModified` missing while the
 * sitemap (which runs from the config, unbundled) had the dates. `astro build`
 * keeps its working directory at the project root for both.
 */
function findDocsDir() {
	let dir = process.cwd();
	for (let i = 0; i < 5; i += 1) {
		if (existsSync(join(dir, 'src/content/docs'))) return dir;
		const parent = dirname(dir);
		if (parent === dir) break;
		dir = parent;
	}
	return process.cwd();
}

const DOCS_DIR = findDocsDir();

let cache;

function git(args, timeout) {
	return execFileSync('git', args, {
		cwd: DOCS_DIR,
		encoding: 'utf8',
		maxBuffer: 64 * 1024 * 1024,
		stdio: ['ignore', 'pipe', 'ignore'],
		timeout,
	});
}

/**
 * Commits a shallow clone's history stops at, or an empty set for a full one.
 *
 * Cloudflare Pages builds from a `--depth=1` clone. Its one commit shows every
 * file in the tree as added, so without this every page in the sitemap carried
 * the deploy's own commit time: 230 identical `<lastmod>`s, which is the
 * "everything changed on every deploy" signal crawlers learn to ignore. So
 * first try to fetch the real history (commits and trees only, no file
 * contents, which is all `--name-only` reads), and whatever stays shallow after
 * that marks the commits whose dates can't be trusted.
 */
function shallowBoundary() {
	try {
		if (git(['rev-parse', '--is-shallow-repository']).trim() !== 'true') return new Set();
	} catch {
		return new Set();
	}
	try {
		git(['fetch', '--quiet', '--unshallow', '--filter=blob:none', 'origin'], 180_000);
	} catch {
		// No remote, no network, or a server without partial clone. Fall through
		// and leave the boundary's files undated.
	}
	try {
		const file = git(['rev-parse', '--git-path', 'shallow']).trim();
		return new Set(
			readFileSync(isAbsolute(file) ? file : join(DOCS_DIR, file), 'utf8')
				.split('\n')
				.map((line) => line.trim())
				.filter(Boolean),
		);
	} catch {
		// No shallow file: the fetch above made the clone whole.
		return new Set();
	}
}

/** `Map<'src/content/docs/typing/glide-typing.mdx', '2026-09-15T12:00:00+06:00'>`, keyed relative to docs/. */
export function lastModifiedByFile() {
	if (cache) return cache;
	cache = new Map();

	const boundary = shallowBoundary();
	let out;
	try {
		out = git([
			'log',
			'--pretty=format:%x00%H %cI',
			'--name-only',
			'--no-merges',
			'--',
			'src/content/docs',
			'src/pages',
		]);
	} catch {
		// No git, no history, or not a repository. Every page goes undated.
		return cache;
	}

	// The log is newest-first, so the first date seen for a path is its latest.
	// Renames show under the new path only, which is what we want. A path first
	// seen in a shallow boundary commit is claimed with no date: that commit
	// lists it only because the history before it is missing.
	let date;
	for (const line of out.split('\n')) {
		if (line.startsWith('\0')) {
			const [hash, when] = line.slice(1).trim().split(' ');
			date = boundary.has(hash) ? null : when;
			continue;
		}
		const file = line.trim();
		if (!file || date === undefined) continue;
		// `git log` prints paths from the repository root; the docs site is a
		// subdirectory of it.
		const key = file.replace(/^docs\//, '');
		if (!cache.has(key)) cache.set(key, date);
	}

	return cache;
}

/** ISO date for a docs entry id ('typing/glide-typing'), or undefined. */
export function lastModifiedForDoc(id) {
	const map = lastModifiedByFile();
	for (const ext of ['.mdx', '.md']) {
		const hit = map.get(`src/content/docs/${id}${ext}`);
		if (hit) return hit;
	}
	// A directory index is stored as `<dir>/index.mdx` but has the bare id.
	return map.get(`src/content/docs/${id}/index.mdx`) ?? map.get(`src/content/docs/${id}/index.md`);
}

/** ISO date for a custom Astro page, by its path under src/pages/. */
export function lastModifiedForPage(relative) {
	return lastModifiedByFile().get(`src/pages/${relative}`);
}
