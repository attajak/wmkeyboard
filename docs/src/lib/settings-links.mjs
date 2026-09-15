/**
 * Turns a `<SettingsPath path="…">` breadcrumb into the `wmkeyboard://` link
 * that opens it on a phone.
 *
 * The data is the app's own settings search index, dumped to
 * `src/data/settings-links.json` by `scripts/extract_settings_links.sh`. Every
 * entry there is a screen (`screen: true`, `route` opens it) or a row on a
 * screen (`route` opens the screen, `name` is what `?setting=` scrolls to),
 * with `screens` naming the path above it, outermost first, home omitted.
 *
 * Resolution walks the breadcrumb from the end:
 *
 *   1. The whole path names a screen → `settings/<route>`.
 *   2. The last crumb names a row on the screen the rest of the path names →
 *      `settings/<route>?setting=<name>`.
 *   3. Otherwise drop the last crumb and try again, so a chip pointing at a
 *      group heading ("Typing / Backspace") or a row the index does not carry
 *      still opens the screen that holds it.
 *
 * A crumb matches an entry by normalised title; the crumbs above it have to
 * appear, in order, somewhere in the entry's own path, which is what tells
 * "Tools / Clipboard" (the tool page) from "Clipboard" (the settings screen).
 */

import entries from '../data/settings-links.json' with { type: 'json' };

/** Case, "&"/"and", curly quotes and runs of space are not differences. */
export function normalizeCrumb(text) {
	return text
		.toLowerCase()
		.replace(/&/g, ' and ')
		.replace(/[’']/g, "'")
		.replace(/…/g, '')
		.replace(/[\s ]+/g, ' ')
		.trim();
}

/**
 * Screens the app reaches through more than one door. The index files each
 * under one; a chip written from the other door is rewritten to it.
 */
const ALSO_UNDER = new Map([
	// None today. Key layouts had a second door on Languages until Your
	// layouts moved whole to Layout & size (6ae3c9cd).
]);

const screensByTitle = new Map();
const rowsByTitle = new Map();
for (const entry of entries) {
	const bucket = entry.screen ? screensByTitle : rowsByTitle;
	const key = normalizeCrumb(entry.title);
	const list = bucket.get(key) ?? [];
	list.push({ ...entry, screensNorm: entry.screens.map(normalizeCrumb) });
	bucket.set(key, list);
}

/** True when every item of `needles` appears in `haystack`, in order. */
function isSubsequence(needles, haystack) {
	let i = 0;
	for (const item of haystack) if (i < needles.length && item === needles[i]) i++;
	return i === needles.length;
}

/**
 * The best candidate for `crumbs` (normalised, last one is the title being
 * looked up) among `list`, or null. An exact path beats a partial one; among
 * partial ones the shortest path wins, since it has the least unaccounted for.
 */
function pick(list, crumbs) {
	if (!list) return null;
	const above = crumbs.slice(0, -1);
	let best = null;
	let bestScore = -Infinity;
	for (const entry of list) {
		const path = entry.screensNorm;
		let score;
		if (path.length === above.length && path.every((p, i) => p === above[i])) score = 1000;
		// The chip left screens out ("Tools / Options" for a row on Tools / Camera).
		else if (isSubsequence(above, path)) score = 100 - (path.length - above.length);
		// The chip added crumbs the app has no screen for, usually a group heading
		// on the screen ("Emoji / Skin tone / Default skin tone").
		else if (isSubsequence(path, above)) score = 100 - (above.length - path.length);
		else continue;
		if (score > bestScore) {
			best = entry;
			bestScore = score;
		}
	}
	return best;
}

/**
 * The link for a breadcrumb such as "Typing / Automatic corrections", or null
 * when not even its first crumb names a screen.
 *
 * Returns `{ href, exact }`: `exact` is false when the link had to fall back
 * to a screen above the crumb the path actually ends on.
 */
export function resolveSettingsLink(path) {
	const crumbs = path
		.split('/')
		.map(normalizeCrumb)
		.filter((c) => c.length > 0);
	for (const [door, filed] of ALSO_UNDER) {
		const doorCrumbs = door.split(' / ');
		if (doorCrumbs.every((c, i) => crumbs[i] === c)) {
			crumbs.splice(0, doorCrumbs.length, ...filed.split(' / '));
			break;
		}
	}
	for (let n = crumbs.length; n > 0; n--) {
		const head = crumbs.slice(0, n);
		const exact = n === crumbs.length;
		const screen = pick(screensByTitle.get(head[n - 1]), head);
		if (screen) return { href: `wmkeyboard://settings/${screen.route}`, exact };
		const row = pick(rowsByTitle.get(head[n - 1]), head);
		if (row) {
			return {
				href: `wmkeyboard://settings/${row.route}?setting=${encodeURIComponent(row.name)}`,
				exact,
			};
		}
	}
	return null;
}
