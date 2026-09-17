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
 * A checkout with no git history (a tarball, or a shallow CI clone with
 * `--depth=1` over a file that changed earlier) yields nothing for some files.
 * That's fine: a missing `lastmod` is correct there, and inventing `Date.now()`
 * would tell crawlers every page changed on every deploy.
 */

import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, join } from 'node:path';

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

/** `Map<'src/content/docs/typing/glide-typing.mdx', '2026-09-15T12:00:00+06:00'>`, keyed relative to docs/. */
export function lastModifiedByFile() {
	if (cache) return cache;
	cache = new Map();

	let out;
	try {
		out = execFileSync(
			'git',
			['log', '--pretty=format:%x00%cI', '--name-only', '--no-merges', '--', 'src/content/docs', 'src/pages'],
			{ cwd: DOCS_DIR, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'] },
		);
	} catch {
		// No git, no history, or not a repository. Every page goes undated.
		return cache;
	}

	// The log is newest-first, so the first date seen for a path is its latest.
	// Renames show under the new path only, which is what we want.
	let date;
	for (const line of out.split('\n')) {
		if (line.startsWith('\0')) {
			date = line.slice(1).trim();
			continue;
		}
		const file = line.trim();
		if (!file || !date) continue;
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
