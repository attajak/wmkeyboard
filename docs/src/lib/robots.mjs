/**
 * Which routes stay out of the index, and the crawl weight of the rest.
 *
 * One list, three readers: `Head.astro` (the `robots` meta tag),
 * `astro.config.mjs` (the sitemap, which must not advertise a page it also
 * tells crawlers not to index — Search Console reports that pair as an error
 * against the whole site, not a note against the page) and `llms.txt`.
 *
 * Before this list each store page passed its own `noindex` flag to
 * `StoreShell`, the sitemap knew nothing about it, and all six noindex pages
 * shipped in the sitemap while emitting two contradictory `robots` tags.
 */

/**
 * Routes with nothing for a crawler to read.
 *
 * Most are an empty frame until the island hydrates and the visitor's own state
 * (a starred collection, a repository URL they pasted, a draft addon) fills it
 * in. The 404 page is here because it is a Starlight page like any other, so it
 * was publishing a TechArticle about itself and an `index, follow` — harmless
 * while the host answers it with a 404 status, and wrong the moment anything
 * links to `/404/` directly.
 */
export const NOINDEX_PATHS = [
	'/404/',
	'/addons/check/',
	'/addons/collection/',
	'/addons/compare/',
	'/addons/new/',
	'/addons/whats-new/',
	'/open/',
];

/** Trailing slash either way, and query strings ignored. */
const normalise = (pathname) => (pathname.endsWith('/') ? pathname : `${pathname}/`);

export const isNoindex = (pathname) => NOINDEX_PATHS.includes(normalise(pathname));

/**
 * Crawl priority, most specific prefix first.
 *
 * The numbers rank pages against each other within this site and nothing else —
 * their only job is to spend a crawl budget on the pages a new visitor arrives
 * on (install, setup, the feature pages) before the 27 settings-reference pages
 * and the build instructions. A flat 0.7 everywhere, which is what the sitemap
 * emitted before, says the build guide deserves the same attention as the
 * landing page.
 */
const PRIORITIES = [
	['/start/', 0.9, 'monthly'],
	['/typing/', 0.8, 'monthly'],
	['/smart/', 0.8, 'monthly'],
	['/languages/', 0.8, 'monthly'],
	['/tools/', 0.8, 'monthly'],
	['/emoji/', 0.8, 'monthly'],
	['/themes/', 0.8, 'monthly'],
	['/privacy/', 0.7, 'monthly'],
	['/accessibility/', 0.7, 'monthly'],
	['/plugins/', 0.7, 'monthly'],
	// The store's index and its guides change as repositories publish; an
	// individual addon page rarely does.
	['/addons/official/', 0.6, 'monthly'],
	['/addons/', 0.8, 'weekly'],
	['/reference/settings/', 0.5, 'monthly'],
	['/reference/', 0.6, 'monthly'],
	['/development/', 0.4, 'monthly'],
];

/** `{ priority, changefreq }` for a site-relative path. */
export function crawlWeight(pathname) {
	const path = normalise(pathname);
	if (path === '/') return { priority: 1.0, changefreq: 'weekly' };
	const hit = PRIORITIES.find(([prefix]) => path.startsWith(prefix));
	return hit ? { priority: hit[1], changefreq: hit[2] } : { priority: 0.5, changefreq: 'monthly' };
}
