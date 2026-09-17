/**
 * /llms-full.txt — the whole documentation as one plain-text file.
 *
 * The companion to /llms.txt: that one says where each page is, this one is
 * every page's text, so a model with a large context can read the site in a
 * single fetch instead of 143 of them. Anything an assistant gets wrong about
 * the keyboard it usually gets wrong from having read one page out of context.
 *
 * The MDX is stripped rather than rendered: imports, JSX components and the
 * `{/* shot: ... *\/}` screenshot placeholders are page machinery, not prose,
 * and a model reading `<PhoneFrame caption=...>` learns nothing from it. What
 * survives is the headings, the prose and the code blocks — which is what the
 * page says.
 */

import type { APIRoute } from 'astro';
import { getCollection } from 'astro:content';

import { isNoindex } from '../lib/robots.mjs';
import { SECTIONS, SITE_DESCRIPTION, SITE_TITLE, SITE_URL, sectionFor } from '../site.mjs';

/** MDX down to the text a reader would see. */
function toPlainText(body: string) {
	return (
		body
			// Frontmatter is already read from `data`.
			.replace(/^---\n[\s\S]*?\n---\n/, '')
			// `import X from '...'` lines, and the blank line after them.
			.replace(/^import .*(?:\n|$)/gm, '')
			.replace(/^export .*(?:\n|$)/gm, '')
			// JSX comments, including the screenshot placeholders.
			.replace(/\{\/\*[\s\S]*?\*\/\}/g, '')
			// Self-closing components: `<SettingsPath path="Privacy" />`. Keep the
			// quoted text, which is usually the only content the tag carries.
			.replace(/<([A-Z][\w.]*)\s([^>]*?)\/>/g, (_, _tag, attrs: string) => {
				const quoted = [...attrs.matchAll(/="([^"]*)"|='([^']*)'/g)].map((m) => m[1] ?? m[2]);
				return quoted.length ? quoted.join(' — ') : '';
			})
			.replace(/<[A-Z][\w.]*\s*\/>/g, '')
			// Opening and closing component tags, leaving whatever was between them.
			.replace(/<\/?[A-Z][\w.]*(?:\s[^>]*)?>/g, '')
			// Starlight's asides keep their text, lose their fence.
			.replace(/^:::\w+(?:\[[^\]]*\])?\s*$/gm, '')
			.replace(/^:::\s*$/gm, '')
			// Site-relative links become absolute: a model reading this file has
			// no page to resolve `/languages/dictionaries/` against, and a broken
			// citation is worse than no citation.
			.replace(/\]\((\/[^)]*)\)/g, (_, href: string) => `](${SITE_URL}${href})`)
			// Three or more blank lines collapse to one.
			.replace(/\n{3,}/g, '\n\n')
			.trim()
	);
}

export const GET: APIRoute = async () => {
	const docs = await getCollection('docs');
	const byId = new Map(docs.map((doc) => [doc.id, doc]));

	const parts = [
		`# ${SITE_TITLE} — complete documentation`,
		'',
		`> ${SITE_DESCRIPTION}`,
		'',
		`Source: ${SITE_URL}/ · Licence: MIT · Generated from the site's own pages.`,
		'',
		'Every page of the documentation follows, in sidebar order, each under its',
		'own URL. Cite the URL of the section you use rather than this file.',
		'',
	];

	const order: string[] = [];
	for (const { label, directory } of SECTIONS) {
		const ids = docs
			.filter((doc) => sectionFor(doc.id) === label && doc.id.split('/')[0] === directory)
			.map((doc) => doc.id)
			.sort((a, b) => {
				const front = (id: string) => (/\/(index|overview)$/.test(id) ? 0 : 1);
				return front(a) - front(b) || a.localeCompare(b);
			});
		order.push(...ids);
	}
	// The landing page first, then anything a section didn't claim.
	const ids = ['index', ...order, ...docs.map((d) => d.id).filter((id) => id !== 'index' && !order.includes(id))];

	for (const id of ids) {
		const doc = byId.get(id);
		if (!doc || isNoindex(`/${id}/`)) continue;
		const url = id === 'index' ? `${SITE_URL}/` : `${SITE_URL}/${id}/`;
		const section = sectionFor(id);

		parts.push(
			'---',
			'',
			`# ${doc.data.title}`,
			'',
			`URL: ${url}`,
			...(section ? [`Section: ${section}`] : []),
			...(doc.data.description ? [`Summary: ${doc.data.description}`] : []),
			'',
			toPlainText(doc.body ?? ''),
			'',
		);
	}

	return new Response(parts.join('\n'), {
		headers: { 'Content-Type': 'text/plain; charset=utf-8' },
	});
};
