// @ts-check
import { readdirSync } from 'node:fs';
import { defineConfig } from 'astro/config';
import preact from '@astrojs/preact';
import starlight from '@astrojs/starlight';
import { unified } from '@astrojs/markdown-remark';
import sitemap from '@astrojs/sitemap';
import starlightImageZoom from 'starlight-image-zoom';
import starlightLinksValidator from 'starlight-links-validator';
import starlightThemeBlack from 'starlight-theme-black';

import { SECTIONS, SITE_DESCRIPTION, SITE_TITLE, SITE_URL } from './src/site.mjs';
import { crawlWeight, isNoindex } from './src/lib/robots.mjs';
import { lastModifiedForDoc, lastModifiedForPage } from './src/lib/last-modified.mjs';

// Link validation is opt-in (`npm run check`) so half-written pages never
// block the dev loop. CI should run `npm run check`.
const plugins = [starlightImageZoom()];
if (process.env.CHECK_LINKS) {
	// The addon store under /addons/ and the /open/ doorway are custom Astro
	// pages (src/pages/), which the validator cannot check. The Starlight
	// guides sharing the /addons/ prefix (src/content/docs/addons) stay
	// validated, so a glob won't do.
	const addonGuides = new Set(
		readdirSync(new URL('./src/content/docs/addons/', import.meta.url)).map((file) =>
			file.replace(/\.mdx?$/, ''),
		),
	);
	plugins.push(
		starlightLinksValidator({
			errorOnRelativeLinks: true,
			exclude: ({ link }) => {
				// The /open/ doorway is a custom Astro page too.
				if (/^\/open(?:[/?#]|$)/.test(link)) return true;
				const match = /^\/addons(?:\/([^/?#]*)|(?=[?#]|$))/.exec(link);
				return match !== null && !addonGuides.has(match[1] ?? '');
			},
		}),
	);
}

// starlight-theme-black appends its stylesheets after `customCss`, so it owns
// the `--sl-color-*` palette and the fonts (Geist). Keep it last so it also
// wins over the other plugins' styles.
plugins.push(
	starlightThemeBlack({
		navLinks: [
			{ label: 'Get started', link: '/start/installation/' },
			{ label: 'Addons', link: '/addons/' },
			{ label: 'Reference', link: '/reference/settings/' },
			{ label: 'Development', link: '/development/building/' },
		],
		// Drops the "Copy page" / "open in an AI chat" control from page titles.
		docs: { showMarkdownActions: false },
	})
);

/**
 * The source file behind a built URL, so the sitemap can date it.
 *
 * Content pages map straight onto `src/content/docs/<id>.mdx`. The store's
 * routes are generated from seed manifests, so they take the date of the Astro
 * page that generates them, which is the closest honest answer available.
 */
function lastmodFor(pathname) {
	const id = pathname.replace(/^\/|\/$/g, '');
	if (id === '') return lastModifiedForDoc('index');

	const fromDocs = lastModifiedForDoc(id);
	if (fromDocs) return fromDocs;

	// /addons/official/twemoji/ -> the [id].astro that prerenders it.
	if (/^addons\/[^/]+\/[^/]+$/.test(id)) return lastModifiedForPage('addons/[slug]/[id].astro');
	if (/^addons\/[^/]+$/.test(id)) {
		return (
			lastModifiedForPage(`${id}.astro`) ?? lastModifiedForPage('addons/[slug]/index.astro')
		);
	}
	return lastModifiedForPage(`${id}/index.astro`) ?? lastModifiedForPage(`${id}.astro`);
}

export default defineConfig({
	site: SITE_URL,
	markdown: {
		// starlight-image-zoom doesn't support Astro 7's Sätteri processor yet:
		// https://github.com/HiDeoo/starlight-image-zoom/issues/63
		processor: unified(),
	},
	vite: {
		// Let pages import keyboard layout JSON straight from the app's assets
		// (app/src/main/assets/layouts/) so the docs never carry stale copies.
		server: { fs: { allow: ['..'] } },
	},
	integrations: [
		// The addon store (/addons/) is a Preact island; nothing else uses it.
		preact(),
		starlight({
			title: SITE_TITLE,
			description: SITE_DESCRIPTION,
			logo: {
				src: './src/assets/logo-mark.png',
				alt: SITE_TITLE,
			},
			favicon: '/favicon.png',
			social: [
				{ icon: 'github', label: 'GitHub', href: 'https://github.com/wasi-master/wmkeyboard' },
			],
			editLink: {
				// TODO: point at the real repo/branch once the docs land.
				baseUrl: 'https://github.com/wasi-master/wmkeyboard/edit/main/docs/',
			},
			lastUpdated: true,
			// starlight-theme-black loads Geist and points --sl-font at it, so
			// the Inter/JetBrains imports that used to live here would only ship
			// bytes nothing renders with.
			customCss: ['./src/styles/custom.css'],
			components: {
				// Head and PageTitle wrap starlight-theme-black's own override
				// rather than replacing it — the theme skips (and warns about)
				// any component we've already claimed here. Sidebar genuinely
				// replaces the theme's, which can't nest or collapse. Hero
				// replaces it on the homepage only (the live theme deck) and
				// delegates back to the theme everywhere else, 404 included.
				Head: './src/components/Head.astro',
				Hero: './src/components/Hero.astro',
				PageTitle: './src/components/PageTitle.astro',
				Sidebar: './src/components/Sidebar.astro',
			},
			plugins,
			// One group per SECTIONS entry (src/site.mjs), which is also what the
			// SEO layer reads to name a page's section. The labels used to live
			// here as well, so renaming a group renamed it in the sidebar only.
			sidebar: SECTIONS.map(({ label, directory, badge, collapsed }) => ({
				label,
				...(badge ? { badge } : {}),
				...(collapsed ? { collapsed: true } : {}),
				items: [{ autogenerate: { directory, ...(collapsed ? { collapsed: true } : {}) } }],
			}))
		}),
		sitemap({
			// Anything a crawler is told not to index has no business being
			// advertised here — the 404 page, and the store routes that are an
			// empty frame until the visitor's own state fills them in. One list
			// answers both questions (src/lib/robots.mjs).
			filter: (page) => !isNoindex(new URL(page).pathname),
			serialize(item) {
				const { pathname } = new URL(item.url);
				Object.assign(item, crawlWeight(pathname));

				// `lastmod` from the commit that last touched the page's source, so
				// a deploy that changed three pages doesn't claim all 224 are new.
				const modified = lastmodFor(pathname);
				if (modified) item.lastmod = modified;
				return item;
			},
		}),
	],
});
