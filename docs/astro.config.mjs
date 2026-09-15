// @ts-check
import { defineConfig } from 'astro/config';
import preact from '@astrojs/preact';
import starlight from '@astrojs/starlight';
import { unified } from '@astrojs/markdown-remark';
import sitemap from '@astrojs/sitemap';
import starlightImageZoom from 'starlight-image-zoom';
import starlightLinksValidator from 'starlight-links-validator';
import starlightThemeBlack from 'starlight-theme-black';

import { SITE_DESCRIPTION, SITE_TITLE, SITE_URL } from './src/site.mjs';

// Link validation is opt-in (`npm run check`) so half-written pages never
// block the dev loop. CI should run `npm run check`.
const plugins = [starlightImageZoom()];
if (process.env.CHECK_LINKS) {
	plugins.push(starlightLinksValidator({ errorOnRelativeLinks: true }));
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
			sidebar: [
				{
					label: 'Getting started',
					items: [{ autogenerate: { directory: 'start' } }],
				},
				{
					label: 'Typing',
					items: [{ autogenerate: { directory: 'typing' } }],
				},
				{
					label: 'Languages',
					items: [{ autogenerate: { directory: 'languages' } }],
				},
				{
					label: 'Suggestions & correction',
					items: [{ autogenerate: { directory: 'smart' } }],
				},
				{
					label: 'Emoji & expression',
					items: [{ autogenerate: { directory: 'emoji' } }],
				},
				{
					label: 'Tools',
					items: [{ autogenerate: { directory: 'tools' } }],
				},
				{
					label: 'Themes & appearance',
					items: [{ autogenerate: { directory: 'themes' } }],
				},
				{
					label: 'Addons',
					items: [{ autogenerate: { directory: 'addons' } }],
				},
				{
					label: 'Plugins',
					badge: { text: 'Lua', variant: 'tip' },
					items: [{ autogenerate: { directory: 'plugins' } }],
				},
				{
					label: 'Privacy & security',
					items: [{ autogenerate: { directory: 'privacy' } }],
				},
				{
					label: 'Accessibility',
					items: [{ autogenerate: { directory: 'accessibility' } }],
				},
				{
					label: 'Reference',
					collapsed: true,
					items: [
						{ autogenerate: { directory: 'reference', collapsed: true } },
					],
				},
				{
					label: 'Development',
					collapsed: true,
					items: [{ autogenerate: { directory: 'development', collapsed: true } }],
				},
			],
		}),
		sitemap({
			// The 404 page has nothing to index.
			filter: (page) => !page.endsWith('/404/'),
			changefreq: 'weekly',
			priority: 0.7,
			serialize(item) {
				// The landing page is the entry point; everything else inherits
				// the defaults above.
				if (item.url === `${SITE_URL}/`) item.priority = 1.0;
				return item;
			},
		}),
	],
});
