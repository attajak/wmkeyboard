#!/usr/bin/env node
/**
 * Tell IndexNow (Bing, Yandex, Seznam, Naver) which docs pages changed.
 *
 * Reads the live sitemap rather than the build output, so it only ever submits
 * what is actually deployed, and picks pages by their `<lastmod>`, which is the
 * git date of each page's source (src/lib/last-modified.mjs).
 *
 *   node scripts/indexnow.mjs --all
 *       every URL in the sitemap; for the first submission, or after a move.
 *   node scripts/indexnow.mjs --since 2026-10-06T12:00:00Z [--expect <iso>]
 *       URLs whose lastmod is after --since. With --expect, first waits (up to
 *       20 minutes) for the deployed sitemap to carry a lastmod at least that
 *       new, since Cloudflare Pages builds after the push that triggered us.
 *   --dry-run prints the list and sends nothing.
 */

import { INDEXNOW_KEY, SITE_URL } from '../src/site.mjs';

const args = process.argv.slice(2);
const flag = (name) => args.includes(name);
const option = (name) => {
	const at = args.indexOf(name);
	return at === -1 ? undefined : args[at + 1];
};

const all = flag('--all');
const since = option('--since');
const expect = option('--expect');
const dryRun = flag('--dry-run');

if (!all && !since) {
	console.error('usage: indexnow.mjs --all | --since <iso> [--expect <iso>] [--dry-run]');
	process.exit(2);
}

async function text(url) {
	const res = await fetch(url, { headers: { 'cache-control': 'no-cache' } });
	if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
	return res.text();
}

const locs = (xml) => [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]);

/** `[{ url, lastmod: Date | undefined }]` across every child sitemap. */
async function readSitemap() {
	const entries = [];
	for (const child of locs(await text(`${SITE_URL}/sitemap-index.xml`))) {
		for (const [, body] of (await text(child)).matchAll(/<url>([\s\S]*?)<\/url>/g)) {
			const url = /<loc>([^<]+)<\/loc>/.exec(body)?.[1];
			const lastmod = /<lastmod>([^<]+)<\/lastmod>/.exec(body)?.[1];
			if (url) entries.push({ url, lastmod: lastmod ? new Date(lastmod) : undefined });
		}
	}
	return entries;
}

const newest = (entries) => Math.max(0, ...entries.map((e) => e.lastmod?.getTime() ?? 0));

let entries = await readSitemap();

if (expect) {
	const target = new Date(expect).getTime();
	const deadline = Date.now() + 20 * 60_000;
	while (newest(entries) < target) {
		if (Date.now() > deadline) {
			console.error(`Deployed sitemap never reached ${expect}; the Pages build may have failed.`);
			process.exit(1);
		}
		console.log(`Waiting for the deploy (sitemap newest ${new Date(newest(entries)).toISOString()})…`);
		await new Promise((resolve) => setTimeout(resolve, 30_000));
		entries = await readSitemap();
	}
}

const cutoff = since ? new Date(since).getTime() : -Infinity;
const urls = entries.filter((e) => all || (e.lastmod && e.lastmod.getTime() > cutoff)).map((e) => e.url);

console.log(`${urls.length} URL(s) to submit`);
for (const url of urls) console.log(`  ${url}`);
if (urls.length === 0 || dryRun) process.exit(0);

// The API takes up to 10,000 URLs per request; the site is nowhere near that.
const res = await fetch('https://api.indexnow.org/indexnow', {
	method: 'POST',
	headers: { 'content-type': 'application/json; charset=utf-8' },
	body: JSON.stringify({
		host: new URL(SITE_URL).host,
		key: INDEXNOW_KEY,
		keyLocation: `${SITE_URL}/${INDEXNOW_KEY}.txt`,
		urlList: urls,
	}),
});

// 200 = accepted, 202 = accepted but the key file is still being verified.
console.log(`IndexNow: HTTP ${res.status} ${await res.text()}`);
if (res.status !== 200 && res.status !== 202) process.exit(1);
