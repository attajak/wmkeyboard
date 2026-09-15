/**
 * Manifest decoding with the app's tolerance (`AddonRepoCodec.decode`):
 * `format` must match, `repo.id` must be non-blank, and an entry with a blank
 * id/path or an unknown type is dropped rather than failing the whole
 * repository. The reasons for every drop are kept for the health check.
 */
import { REPO_FORMAT, isAddonType, type AddonEntry, type Manifest, type RepoInfo } from './types';

export class ManifestError extends Error {}

type Json = Record<string, unknown>;

const str = (v: unknown): string | undefined => (typeof v === 'string' ? v : undefined);
const num = (v: unknown): number | undefined =>
	typeof v === 'number' && Number.isFinite(v) ? v : undefined;
const strList = (v: unknown): string[] =>
	Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];

export interface DecodeResult {
	manifest: Manifest;
	dropped: { index: number; reason: string }[];
}

export function decodeManifest(text: string): DecodeResult {
	let raw: unknown;
	try {
		raw = JSON.parse(text);
	} catch (e) {
		throw new ManifestError(`Not JSON: ${(e as Error).message}`);
	}
	return decodeManifestObject(raw);
}

export function decodeManifestObject(raw: unknown): DecodeResult {
	if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
		throw new ManifestError('The manifest is not a JSON object.');
	}
	const o = raw as Json;
	if (o.format !== REPO_FORMAT) {
		throw new ManifestError(
			o.format === undefined
				? `No "format" field. A repository manifest must carry "format": "${REPO_FORMAT}".`
				: `"format" is ${JSON.stringify(o.format)}, expected "${REPO_FORMAT}".`
		);
	}
	const repoRaw = (o.repo && typeof o.repo === 'object' ? o.repo : {}) as Json;
	const id = str(repoRaw.id)?.trim() ?? '';
	if (!id) throw new ManifestError('"repo.id" is missing or blank.');
	const repo: RepoInfo = {
		id,
		name: str(repoRaw.name)?.trim() || id,
		description: str(repoRaw.description),
		author: str(repoRaw.author),
		homepage: str(repoRaw.homepage),
		icon: str(repoRaw.icon),
		updatedAt: str(repoRaw.updatedAt),
	};
	const dropped: { index: number; reason: string }[] = [];
	const addons: AddonEntry[] = [];
	const list = Array.isArray(o.addons) ? o.addons : [];
	list.forEach((item, index) => {
		if (!item || typeof item !== 'object') {
			dropped.push({ index, reason: 'not an object' });
			return;
		}
		const a = item as Json;
		const aid = str(a.id)?.trim() ?? '';
		const path = str(a.path)?.trim() ?? '';
		const type = str(a.type) ?? '';
		if (!aid) return void dropped.push({ index, reason: 'blank id' });
		if (!path) return void dropped.push({ index, reason: `"${aid}": blank path` });
		if (!isAddonType(type)) {
			return void dropped.push({ index, reason: `"${aid}": unknown type "${type}" (an older app ignores it)` });
		}
		addons.push({
			id: aid,
			type,
			name: str(a.name)?.trim() || aid,
			version: str(a.version)?.trim() || '0.0.0',
			author: str(a.author),
			description: str(a.description),
			tags: strList(a.tags),
			path,
			sha256: str(a.sha256)?.toLowerCase(),
			sizeBytes: num(a.sizeBytes),
			previews: strList(a.previews),
			minAppVersion: num(a.minAppVersion),
			langId: str(a.langId),
			langIds: strList(a.langIds),
			license: str(a.license),
			licenseText: str(a.licenseText),
			licenseFile: str(a.licenseFile),
			requires: strList(a.requires),
		});
	});
	const known = new Set(['format', 'version', 'repo', 'addons', '$schema']);
	const extra: Json = {};
	for (const [k, v] of Object.entries(o)) if (!known.has(k)) extra[k] = v;
	return {
		manifest: {
			format: REPO_FORMAT,
			version: num(o.version) ?? 1,
			repo,
			addons,
			extra: Object.keys(extra).length ? extra : undefined,
		},
		dropped,
	};
}
