/** Small helpers shared by the store and the creator. */
import type { AddonEntry, RepoRef } from './types';
import { resolveAsset } from './resolve';

export async function sha256Hex(bytes: Uint8Array): Promise<string> {
	const digest = await crypto.subtle.digest('SHA-256', bytes as BufferSource);
	return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/* ---------- deep links (AddonDeepLink.kt) ---------- */

export function appLinkAddons(): string {
	return 'wmkeyboard://addons';
}

/** `wmkeyboard://repo?url=` — opens the app on the Addons screen with the repo prefilled. Never adds it. */
export function appLinkRepo(repoInput: string): string {
	return `wmkeyboard://repo?url=${encodeURIComponent(repoInput)}`;
}

/** `wmkeyboard://addon?repo=&id=` — opens the addon's page in the app. Never installs. */
export function appLinkAddon(repoInput: string, addonId: string): string {
	return `wmkeyboard://addon?repo=${encodeURIComponent(repoInput)}&id=${encodeURIComponent(addonId)}`;
}

/* ---------- web URLs ---------- */

export const STORE_BASE = '/addons/';

export function webUrlRepo(repo: RepoRef): string {
	if (repo.slug) return `${STORE_BASE}${repo.slug}/`;
	return `${STORE_BASE}?repo=${encodeURIComponent(repo.input)}`;
}

export function webUrlAddon(repo: RepoRef, addonId: string): string {
	if (repo.slug) return `${STORE_BASE}${repo.slug}/${encodeURIComponent(addonId)}/`;
	return `${STORE_BASE}?repo=${encodeURIComponent(repo.input)}&id=${encodeURIComponent(addonId)}`;
}

export function absoluteUrl(path: string): string {
	if (typeof location === 'undefined') return `https://wmkeyboard.pages.dev${path}`;
	return new URL(path, location.origin).toString();
}

/* ---------- platform ---------- */

export function isAndroid(): boolean {
	return typeof navigator !== 'undefined' && /android/i.test(navigator.userAgent);
}

export function canWebShare(): boolean {
	return typeof navigator !== 'undefined' && typeof navigator.share === 'function';
}

/* ---------- entries ---------- */

export function previewUrls(manifestUrl: string, entry: AddonEntry): string[] {
	return entry.previews.map((p) => resolveAsset(manifestUrl, p)).filter((u): u is string => !!u);
}

export function payloadUrl(manifestUrl: string, entry: AddonEntry): string | null {
	return resolveAsset(manifestUrl, entry.path);
}

export function payloadFileName(entry: AddonEntry): string {
	const last = entry.path.split('/').pop() ?? entry.id;
	return last.split('?')[0] || entry.id;
}

export function matchesQuery(entry: AddonEntry, q: string): boolean {
	const needle = q.trim().toLowerCase();
	if (!needle) return true;
	const hay = [entry.name, entry.description ?? '', entry.author ?? '', entry.id, ...entry.tags].join('\n').toLowerCase();
	return needle.split(/\s+/).every((word) => hay.includes(word));
}

export function fmtDate(iso: string | undefined): string {
	if (!iso) return '';
	const d = new Date(iso);
	if (Number.isNaN(d.getTime())) return iso;
	return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

export function relTime(ts: number | null): string {
	if (!ts) return 'never';
	const s = Math.round((Date.now() - ts) / 1000);
	if (s < 45) return 'just now';
	if (s < 3600) return `${Math.round(s / 60)} min ago`;
	if (s < 86400) return `${Math.round(s / 3600)} h ago`;
	return `${Math.round(s / 86400)} d ago`;
}

export function pluralize(n: number, one: string, many = one + 's'): string {
	return `${n} ${n === 1 ? one : many}`;
}

export async function copyText(text: string): Promise<boolean> {
	try {
		await navigator.clipboard.writeText(text);
		return true;
	} catch {
		return false;
	}
}

export function downloadBlob(name: string, blob: Blob) {
	const url = URL.createObjectURL(blob);
	const a = document.createElement('a');
	a.href = url;
	a.download = name;
	document.body.appendChild(a);
	a.click();
	a.remove();
	setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

export function slugify(s: string): string {
	return s
		.toLowerCase()
		.normalize('NFKD')
		.replace(/[̀-ͯ]/g, '')
		.replace(/[^a-z0-9._-]+/g, '-')
		.replace(/^-+|-+$/g, '')
		.slice(0, 64);
}

export function clamp(n: number, lo: number, hi: number): number {
	return Math.min(hi, Math.max(lo, n));
}

export function uid(): string {
	return Math.random().toString(36).slice(2, 10);
}

export function classes(...xs: (string | false | null | undefined)[]): string {
	return xs.filter(Boolean).join(' ');
}

/** The `wmkeyboard://` scheme only opens on Android; elsewhere we tell people what it is. */
export function openAppLink(link: string) {
	location.href = link;
}
