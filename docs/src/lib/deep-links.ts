/**
 * Building and reading `wmkeyboard://` links, off the same rules the app
 * applies when one arrives. `explain()` is a port of `SettingsDeepLink.parse`
 * and `AddonDeepLink.routeFor` (app/src/main/java/com/wasimaster/wmkeyboard/app/),
 * `SettingsRoutes.resolve` included, so what the builder says a link will do
 * is what the app does with it. Change the Kotlin → change this.
 *
 * Pure functions, no DOM: `npx esbuild src/lib/deep-links.ts --bundle
 * --platform=node --format=cjs` then `node` is enough to try one.
 */
import { ROUTES } from './deep-link-routes';
import { resolveManifestUrl } from '../store/lib/resolve';

export const SCHEME = 'wmkeyboard';
export const PACKAGE = 'com.wasimaster.wmkeyboard';

/** `SettingsDeepLink.settingName`: the shape `aapt` gives a resource name. */
export const SETTING_NAME = /^[a-z][a-z0-9_]{0,127}$/;

/** `SettingsDeepLink.version`: what `since=` accepts. */
export const VERSION_NAME = /^[0-9]{1,4}(\.[0-9]{1,6}){0,3}$/;

/* ---------- building ---------- */

/** Fill a route pattern's `{arg}` segments. Arguments are percent-encoded, since `navigate()` decodes them. */
export function fillRoute(pattern: string, args: Record<string, string>): string {
	return pattern
		.split('/')
		.map((part) => {
			const m = /^\{(\w+)\}$/.exec(part);
			return m ? encodeRouteArg(args[m[1]!] ?? '') : part;
		})
		.join('/');
}

/**
 * One path segment. `encodeURIComponent` leaves `!'()*` alone, which a URI
 * parser also leaves alone, so that is enough; a slash inside a value has to
 * become `%2F` or it would read as a segment break, which it does.
 */
export function encodeRouteArg(value: string): string {
	return encodeURIComponent(value);
}

export interface SettingsLinkInput {
	/** A filled route, `typing/corrections`; empty for the home list. */
	route: string;
	/** A row's resource name, optional. */
	setting?: string;
	/** The first version that has the screen or row, optional. See settings-since.ts. */
	since?: string;
}

/** `wmkeyboard://settings/<route>[?setting=<name>][&since=<version>]`. */
export function settingsLink(input: SettingsLinkInput): string {
	const route = input.route.replace(/^\/+|\/+$/g, '');
	const base = `${SCHEME}://settings${route ? `/${route}` : ''}`;
	const params: string[] = [];
	if (input.setting) params.push(`setting=${encodeURIComponent(input.setting)}`);
	if (input.since) params.push(`since=${input.since}`);
	return params.length ? `${base}?${params.join('&')}` : base;
}

/** `wmkeyboard://setting/<name>[?since=<version>]`: the row, on whichever screen holds it. */
export function settingLink(name: string, since?: string): string {
	return `${SCHEME}://setting/${encodeURIComponent(name)}${since ? `?since=${since}` : ''}`;
}

/** `wmkeyboard://addons`. */
export function addonsLink(): string {
	return `${SCHEME}://addons`;
}

/** `wmkeyboard://repo?url=<address>`. */
export function repoLink(address: string): string {
	return `${SCHEME}://repo?url=${encodeURIComponent(address.trim())}`;
}

/** `wmkeyboard://addon?repo=<address>&id=<addonId>`. */
export function addonLink(address: string, id: string): string {
	return `${SCHEME}://addon?repo=${encodeURIComponent(address.trim())}&id=${encodeURIComponent(id.trim())}`;
}

/** A link assembled by hand: any host, path and query. */
export function customLink(host: string, path: string, query: [string, string][]): string {
	const p = path.replace(/^\/+/, '');
	const q = query
		.filter(([k]) => k.trim())
		.map(([k, v]) => `${encodeURIComponent(k.trim())}=${encodeURIComponent(v)}`)
		.join('&');
	return `${SCHEME}://${host.trim()}${p ? `/${p}` : ''}${q ? `?${q}` : ''}`;
}

/* ---------- other spellings of the same link ---------- */

/** The opaque form the parser also accepts: `wmkeyboard:settings/themes`. */
export function opaqueForm(link: string): string {
	return link.replace(/^wmkeyboard:\/\//i, 'wmkeyboard:');
}

/**
 * Chrome's intent URL. Chrome on Android turns it into the same VIEW intent a
 * plain link makes, but names the package, so it never asks which app, and
 * falls back to a web page when the app is not installed.
 */
export function intentUrl(link: string, fallback?: string): string {
	const rest = link.replace(/^wmkeyboard:\/\//i, '');
	const parts = [`scheme=${SCHEME}`, `package=${PACKAGE}`];
	if (fallback) parts.push(`S.browser_fallback_url=${encodeURIComponent(fallback)}`);
	return `intent://${rest}#Intent;${parts.join(';')};end`;
}

/** The adb command that sends the link from a computer. */
export function adbCommand(link: string): string {
	return `adb shell am start -a android.intent.action.VIEW -d "${link}"`;
}

/** The explicit-intent form for a settings link: the same extras an app would set. */
export function adbExtrasCommand(route: string, setting: string): string | null {
	if (!route && !setting) return null;
	const parts = [`adb shell am start -n ${PACKAGE}/.app.MainActivity`];
	if (route) parts.push(`--es open_route ${shellQuote(route)}`);
	if (setting) parts.push(`--es open_setting ${shellQuote(setting)}`);
	return parts.join(' \\\n  ');
}

function shellQuote(s: string): string {
	return /^[\w./-]+$/.test(s) ? s : `'${s.replace(/'/g, `'\\''`)}'`;
}

/* ---------- reading ---------- */

export type Explanation =
	| { kind: 'invalid'; reason: string }
	| { kind: 'nowhere'; reason: string }
	| { kind: 'settings'; route: string; pattern: string; label: string; setting: string; since: string; note?: string }
	| { kind: 'setting'; setting: string; since: string }
	| { kind: 'addons' }
	| { kind: 'repo'; input: string; manifest: string }
	| { kind: 'addon'; input: string; manifest: string; id: string }
	| { kind: 'oauth' };

/** `SettingsRoutes.resolve`: the graph's spelling for a raw link path, or null. */
export function resolveRoute(path: string): { route: string; pattern: string; label: string } | null {
	const segments = path.replace(/^\/+|\/+$/g, '').split('/');
	if (segments.some((s) => s === '')) return null;
	if (segments.some((s) => s === '.' || s === '..')) return null;
	const byDepth = [...ROUTES].sort((a, b) => count(a.pattern) - count(b.pattern));
	for (const spec of byDepth) {
		const pattern = spec.pattern.split('/');
		if (pattern.length !== segments.length) continue;
		const filled: string[] = [];
		let matched = true;
		for (let i = 0; i < pattern.length; i++) {
			const part = pattern[i]!;
			if (part.startsWith('{')) filled.push(segments[i]!);
			else if (part.toLowerCase() === segments[i]!.toLowerCase()) filled.push(part);
			else {
				matched = false;
				break;
			}
		}
		if (matched) return { route: filled.join('/'), pattern: spec.pattern, label: spec.label };
	}
	return null;
}

function count(pattern: string): number {
	return (pattern.match(/\{/g) ?? []).length;
}

/** One query parameter out of a raw `a=1&b=2` string, undecoded. */
function rawParam(query: string, name: string): string | null {
	for (const pair of query.split('&')) {
		const eq = pair.indexOf('=');
		const key = eq < 0 ? pair : pair.slice(0, eq);
		if (key !== name) continue;
		const value = eq < 0 ? '' : pair.slice(eq + 1);
		return value.trim() ? value : null;
	}
	return null;
}

function decode(value: string): string | null {
	try {
		return decodeURIComponent(value.replace(/\+/g, '%20'));
	} catch {
		return null;
	}
}

/** `SettingsDeepLink.settingName`. */
function settingName(raw: string | null): string | null {
	if (raw === null) return null;
	const decoded = decode(raw);
	return decoded !== null && SETTING_NAME.test(decoded) ? decoded : null;
}

/** `SettingsDeepLink.version`: the raw value when it reads as a version, else empty. */
function sinceParam(query: string): string {
	const raw = rawParam(query, 'since');
	return raw !== null && VERSION_NAME.test(raw) ? raw : '';
}

/**
 * What the app does with [link]. The order of checks and every refusal
 * mirror the two Kotlin parsers; the wording is this page's.
 */
export function explain(link: string): Explanation {
	const text = link.trim();
	if (!text) return { kind: 'invalid', reason: 'Empty.' };

	const m = /^([a-zA-Z][a-zA-Z0-9+.-]*):(.*)$/s.exec(text);
	if (!m) return { kind: 'invalid', reason: 'Not a URI: a scheme and a colon come first.' };
	const [, scheme, rest] = m;
	if (scheme!.toLowerCase() !== SCHEME) {
		return { kind: 'invalid', reason: `The scheme is ${scheme}, and only wmkeyboard: links reach the app.` };
	}
	if (/\s/.test(text)) return { kind: 'invalid', reason: 'A space inside a URI breaks the parse. Percent-encode it as %20.' };

	let host: string;
	let path: string;
	const query = text.includes('?') ? text.slice(text.indexOf('?') + 1) : '';
	if (rest!.startsWith('//')) {
		const authorityAndPath = rest!.slice(2).split('?')[0]!.split('#')[0]!;
		const slash = authorityAndPath.indexOf('/');
		host = slash < 0 ? authorityAndPath : authorityAndPath.slice(0, slash);
		path = slash < 0 ? '' : authorityAndPath.slice(slash);
	} else {
		// The opaque form, wmkeyboard:settings/themes.
		const body = rest!.split('?')[0]!.split('#')[0]!;
		const slash = body.indexOf('/');
		host = slash < 0 ? body : body.slice(0, slash);
		path = slash < 0 ? '' : body.slice(slash + 1);
	}
	const hostLower = host.toLowerCase();
	const since = sinceParam(query);

	if (hostLower === 'setting') {
		const name = settingName(path.replace(/^\/+|\/+$/g, ''));
		if (!name) {
			return {
				kind: 'nowhere',
				reason: 'The row name after wmkeyboard://setting/ is not a resource name (lowercase letters, digits and underscores, starting with a letter).',
			};
		}
		return { kind: 'setting', setting: name, since };
	}

	if (hostLower === 'settings') {
		const rawSetting = rawParam(query, 'setting');
		const setting = settingName(rawSetting) ?? '';
		const body = path.replace(/^\/+|\/+$/g, '');
		const note = rawSetting !== null && !setting ? 'The ?setting= value is not a resource name, so it is ignored.' : undefined;
		if (!body) {
			if (setting) return { kind: 'setting', setting, since };
			return { kind: 'settings', route: 'home', pattern: 'home', label: 'The settings home list', setting: '', since, note };
		}
		const resolved = resolveRoute(body);
		if (!resolved) {
			return { kind: 'nowhere', reason: `No screen is called "${body}". The app opens nothing rather than guessing.` };
		}
		return { kind: 'settings', ...resolved, setting, since, note };
	}

	if (hostLower === 'addons' || (hostLower === '' && path.replace(/\//g, '').toLowerCase() === 'addons')) {
		return { kind: 'addons' };
	}

	if (hostLower === 'repo') {
		const raw = rawParam(query, 'url');
		if (raw === null) return { kind: 'addons' };
		const input = decode(raw);
		const manifest = input === null ? null : resolveManifestUrl(input);
		if (!input || !manifest) {
			return { kind: 'nowhere', reason: 'The url= value is not an https repository address the app can resolve.' };
		}
		return { kind: 'repo', input, manifest };
	}

	if (hostLower === 'addon') {
		const rawRepo = rawParam(query, 'repo');
		const rawId = rawParam(query, 'id');
		if (rawRepo === null || rawId === null) {
			return { kind: 'nowhere', reason: 'An addon link needs both repo= and id=.' };
		}
		const input = decode(rawRepo);
		const id = decode(rawId);
		const manifest = input === null ? null : resolveManifestUrl(input);
		if (!input || !id || !manifest) {
			return { kind: 'nowhere', reason: 'The repo= value is not an https repository address the app can resolve.' };
		}
		return { kind: 'addon', input, manifest, id };
	}

	if (hostLower === 'oauth') return { kind: 'oauth' };

	return {
		kind: 'invalid',
		reason: `No part of the app listens for wmkeyboard://${host}. The hosts are settings, setting, addons, repo and addon.`,
	};
}
