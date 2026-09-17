/**
 * /og/<page>.png — one social card per documentation page.
 *
 * Prerendered alongside the pages themselves, so `og:image` points at a real
 * static file rather than at something rendered on request. The card's text
 * comes from the page's own frontmatter (src/lib/og-card.mjs draws it), which
 * is what keeps the two from drifting: there is no second copy of a title to
 * forget about.
 *
 * `/og/index.png` is the landing page's. The committed `public/og-card.png`
 * stays as the fallback for the store's routes and anything without an entry.
 */

import type { APIRoute, GetStaticPaths } from 'astro';
import { getCollection } from 'astro:content';
import { fileURLToPath } from 'node:url';

import { renderCard } from '../../lib/og-card.mjs';
import { isNoindex } from '../../lib/robots.mjs';
import { SITE_DESCRIPTION, sectionFor } from '../../site.mjs';

const LOGO = fileURLToPath(new URL('../../assets/logo-mark.png', import.meta.url));

export const getStaticPaths: GetStaticPaths = async () => {
	const docs = await getCollection('docs');
	return docs
		.filter((doc) => !isNoindex(`/${doc.id}/`))
		.map((doc) => ({
			params: { page: doc.id },
			props: {
				title: String(doc.data.title),
				description: String(doc.data.description ?? SITE_DESCRIPTION),
				// The landing page's card has no section to name; it names the app.
				kicker: doc.id === 'index' ? 'Documentation' : (sectionFor(doc.id) ?? 'Documentation'),
				// The URL a reader would type, so the card says where it leads.
				footer: doc.id === 'index' ? 'wmkeyboard.pages.dev' : `wmkeyboard.pages.dev/${doc.id}/`,
			},
		}));
};

export const GET: APIRoute = async ({ props }) => {
	const png = await renderCard({
		kicker: props.kicker,
		title: props.title,
		description: props.description,
		footer: props.footer,
		logoPath: LOGO,
	});

	return new Response(new Uint8Array(png), {
		headers: {
			'Content-Type': 'image/png',
			'Cache-Control': 'public, max-age=604800',
		},
	});
};
