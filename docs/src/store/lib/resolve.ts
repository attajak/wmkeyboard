/**
 * Ports of `AddonRepoCodec.resolveManifestUrl` / `resolveAsset` and
 * `RepoLocation.fromPageUrl` (core/common GitForge.kt), so a URL the store
 * accepts is one the app accepts, and the raw address they both fetch is the
 * same. Keep these in step with the Kotlin.
 */
import { MANIFEST_NAME } from './types';

type Forge = 'github' | 'forgejo' | 'gitlab' | 'sourcehut' | 'bitbucket';

interface RepoLocation {
	forge: Forge;
	host: string;
	owner: string;
	repo: string;
	ref: string;
}

const KNOWN_HOSTS: Record<string, Forge> = {
	'github.com': 'github',
	'www.github.com': 'github',
	'codeberg.org': 'forgejo',
	'gitea.com': 'forgejo',
	'gitlab.com': 'gitlab',
	'git.sr.ht': 'sourcehut',
	'bitbucket.org': 'bitbucket',
};

const DEFAULT_REF: Record<Forge, string> = {
	github: 'HEAD',
	gitlab: 'HEAD',
	forgejo: 'main',
	sourcehut: 'main',
	bitbucket: 'main',
};

const COMMIT_ID = /^[0-9a-fA-F]{40}$/;

function rawUrl(loc: RepoLocation, path: string): string {
	const file = path.replace(/^\/+/, '');
	const owner = loc.owner.replace(/^\/+|\/+$/g, '');
	const repo = loc.repo.replace(/^\/+|\/+$/g, '').replace(/\.git$/, '');
	const ref = loc.ref.trim() || DEFAULT_REF[loc.forge];
	const host = loc.host;
	switch (loc.forge) {
		case 'github':
			return host === 'github.com' || host === 'www.github.com'
				? `https://raw.githubusercontent.com/${owner}/${repo}/${ref}/${file}`
				: `https://${host}/${owner}/${repo}/raw/${ref}/${file}`;
		case 'forgejo': {
			const kind = COMMIT_ID.test(ref) ? 'commit' : 'branch';
			return `https://${host}/${owner}/${repo}/raw/${kind}/${ref}/${file}`;
		}
		case 'gitlab':
			return `https://${host}/${owner}/${repo}/-/raw/${ref}/${file}`;
		case 'sourcehut':
			return `https://${host}/~${owner.replace(/^~/, '')}/${repo}/blob/${ref}/${file}`;
		case 'bitbucket':
			return `https://${host}/${owner}/${repo}/raw/${ref}/${file}`;
	}
}

/** `RepoLocation.fromPageUrl`: a browser-copied repository page → raw location + path inside. */
function fromPageUrl(pasted: string): [RepoLocation, string] | null {
	const trimmed = pasted.trim().replace(/\/+$/, '');
	const schemeEnd = trimmed.indexOf('://');
	if (schemeEnd > 0 && trimmed.slice(0, schemeEnd).toLowerCase() !== 'https') return null;
	const rest = schemeEnd > 0 ? trimmed.slice(schemeEnd + 3) : trimmed;
	const slash = rest.indexOf('/');
	const host = (slash < 0 ? rest : rest.slice(0, slash)).toLowerCase();
	if (!host || host.includes(':') || !host.includes('.')) return null;
	const parts = (slash < 0 ? '' : rest.slice(slash + 1)).split('/').filter(Boolean);
	if (parts.length < 2) return null;
	const tail = (from: number) => parts.slice(from).join('/');

	const dash = parts.indexOf('-');
	if (dash >= 2 && ['tree', 'blob', 'raw'].includes(parts[dash + 1] ?? '') && dash + 2 < parts.length) {
		return [
			{ forge: 'gitlab', host, owner: parts.slice(0, dash - 1).join('/'), repo: parts[dash - 1]!, ref: parts[dash + 2]! },
			tail(dash + 3),
		];
	}
	if (parts[0]!.startsWith('~')) {
		const ref = ['tree', 'blob'].includes(parts[2] ?? '') ? (parts[3] ?? '') : '';
		return [{ forge: 'sourcehut', host, owner: parts[0]!.slice(1), repo: parts[1]!, ref }, ref ? tail(4) : ''];
	}
	const owner = parts[0]!;
	const repo = parts[1]!.replace(/\.git$/, '');
	const kind = parts[2];
	if (kind === 'src' && ['branch', 'commit', 'tag'].includes(parts[3] ?? '') && parts.length >= 5) {
		return [{ forge: 'forgejo', host, owner, repo, ref: parts[4]! }, tail(5)];
	}
	if (host === 'bitbucket.org') {
		const ref = kind === 'src' ? (parts[3] ?? '') : '';
		return [{ forge: 'bitbucket', host, owner, repo, ref }, ref ? tail(4) : ''];
	}
	if ((kind === 'tree' || kind === 'blob') && parts.length >= 4) {
		return [{ forge: KNOWN_HOSTS[host] ?? 'github', host, owner, repo, ref: parts[3]! }, tail(4)];
	}
	if (parts.length === 2) {
		const forge = KNOWN_HOSTS[host];
		if (!forge) return null;
		return [{ forge, host, owner, repo, ref: '' }, ''];
	}
	return null;
}

/**
 * What the user pasted → the manifest URL, or null when the app would refuse
 * it too. Mirrors `AddonRepoCodec.resolveManifestUrl` case for case.
 */
export function resolveManifestUrl(pasted: string): string | null {
	const trimmed = pasted.trim().replace(/\/+$/, '');
	if (!trimmed) return null;

	const schemeEnd = trimmed.indexOf('://');
	const hasScheme = schemeEnd > 0;
	if (hasScheme && trimmed.slice(0, schemeEnd).toLowerCase() !== 'https') return null;
	if (!hasScheme) {
		const beforeSlash = trimmed.split('/')[0] ?? '';
		if (beforeSlash.includes(':')) return null;
	}

	const withScheme = hasScheme ? trimmed : `https://${trimmed}`;
	let url: URL;
	try {
		url = new URL(withScheme);
	} catch {
		return null;
	}
	const host = url.hostname.toLowerCase();
	if (!host) return null;
	const path = url.pathname.replace(/^\/+|\/+$/g, '');

	if (host === 'github.com' || host === 'www.github.com') {
		const parts = path.split('/').filter(Boolean);
		if (parts.length < 2) return null;
		const user = parts[0]!;
		const repo = parts[1]!.replace(/\.git$/, '');
		const ref = parts.length >= 4 && (parts[2] === 'tree' || parts[2] === 'blob') ? parts[3]! : 'HEAD';
		return `https://raw.githubusercontent.com/${user}/${repo}/${ref}/${MANIFEST_NAME}`;
	}

	if (path.toLowerCase().endsWith('.json')) return withScheme;

	const page = fromPageUrl(withScheme);
	if (page) {
		const [loc, dir] = page;
		const complete = loc.host && !loc.host.includes('/') && loc.owner && loc.repo;
		if (complete) return rawUrl(loc, dir ? `${dir}/${MANIFEST_NAME}` : MANIFEST_NAME);
	}
	return `${withScheme}/${MANIFEST_NAME}`;
}

/**
 * `AddonRepoCodec.resolveAsset`: an entry's `path`, a preview, an icon or a
 * licence file → an https URL, or null when the app would refuse it.
 */
export function resolveAsset(manifestUrl: string, reference: string): string | null {
	const value = reference.trim();
	if (!value) return null;
	if (value.includes('://')) {
		return value.toLowerCase().startsWith('https://') ? value : null;
	}
	if (value.startsWith('//') || value.startsWith('/')) return null;
	let base: URL;
	let resolved: URL;
	try {
		base = new URL(manifestUrl);
		resolved = new URL(value, base);
	} catch {
		return null;
	}
	if (resolved.protocol !== 'https:') return null;
	if (resolved.hostname.toLowerCase() !== base.hostname.toLowerCase()) return null;
	const baseDir = new URL('.', base).pathname;
	if (!resolved.pathname.startsWith(baseDir)) return null;
	return resolved.toString();
}

/** A friendlier name for a manifest URL: the forge page it came from when we can tell. */
export function describeManifestUrl(url: string): { host: string; label: string; page: string | null } {
	try {
		const u = new URL(url);
		const host = u.hostname;
		if (host === 'raw.githubusercontent.com') {
			const [user, repo, ref] = u.pathname.split('/').filter(Boolean);
			if (user && repo) {
				const page = `https://github.com/${user}/${repo}${ref && ref !== 'HEAD' ? `/tree/${ref}` : ''}`;
				return { host: 'github.com', label: `${user}/${repo}`, page };
			}
		}
		const m = u.pathname.match(/^\/([^/]+)\/([^/]+)\/(?:raw|-\/raw|blob)\//);
		if (m) return { host, label: `${m[1]}/${m[2]}`, page: `https://${host}/${m[1]}/${m[2]}` };
		return { host, label: `${host}${u.pathname.replace(/\/wmkeyboard-repo\.json$/, '')}`, page: null };
	} catch {
		return { host: '', label: url, page: null };
	}
}
