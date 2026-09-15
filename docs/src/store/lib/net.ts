/**
 * Fetching with the app's caps and with errors a person can act on. Nothing
 * here ever goes through a proxy: a host that doesn't allow browser reads is
 * reported as exactly that.
 */

export const MB = 1024 * 1024;
/** `AddonRepoCodec.MAX_MANIFEST_BYTES`. */
export const MAX_MANIFEST_BYTES = 1 * MB;
/** `AddonDownloadManager.PREVIEW_MAX_BYTES`: the most the app reads to preview. */
export const PREVIEW_MAX_BYTES = 12 * MB;
/** `AddonDownloadManager.MAX_TEXT_BYTES`: licence files. */
export const MAX_TEXT_BYTES = 256 * 1024;

export type NetErrorKind = 'cors' | 'http' | 'offline' | 'too-large' | 'aborted' | 'other';

export class NetError extends Error {
	constructor(
		public kind: NetErrorKind,
		message: string,
		public status?: number
	) {
		super(message);
	}
}

export interface FetchOptions {
	maxBytes: number;
	signal?: AbortSignal;
	onProgress?: (received: number, total: number | null) => void;
	/** Sent as a hint only; raw hosts mostly ignore it. */
	accept?: string;
}

function explain(url: string, e: unknown): NetError {
	if (e instanceof NetError) return e;
	if (e instanceof DOMException && e.name === 'AbortError') return new NetError('aborted', 'Cancelled.');
	if (typeof navigator !== 'undefined' && navigator.onLine === false) {
		return new NetError('offline', 'You are offline.');
	}
	let host = url;
	try {
		host = new URL(url).hostname;
	} catch {
		/* keep the raw string */
	}
	// A TypeError from fetch() is the browser's way of saying the response
	// never reached the page: CORS, DNS, TLS. CORS is by far the usual one.
	return new NetError(
		'cors',
		`${host} did not let this page read the file. Browsers need the host to send an Access-Control-Allow-Origin header; GitHub, GitLab and Codeberg raw files do, most plain web hosts don't. The app itself is not affected, only this site.`
	);
}

/** Fetch up to `maxBytes`, aborting mid-stream past the cap the way the app does. */
export async function fetchBytes(url: string, opts: FetchOptions): Promise<Uint8Array> {
	let res: Response;
	try {
		res = await fetch(url, {
			signal: opts.signal,
			headers: opts.accept ? { Accept: opts.accept } : undefined,
			// Raw hosts send short max-age; let the browser cache do its thing.
			cache: 'default',
		});
	} catch (e) {
		throw explain(url, e);
	}
	if (!res.ok) {
		throw new NetError(
			'http',
			res.status === 404
				? 'Not found (404). The file is not at that address.'
				: `The server answered ${res.status}${res.statusText ? ` ${res.statusText}` : ''}.`,
			res.status
		);
	}
	const lengthHeader = res.headers.get('content-length');
	const total = lengthHeader ? Number(lengthHeader) : null;
	if (total !== null && total > opts.maxBytes) {
		throw new NetError('too-large', `${fmtBytes(total)} is over the ${fmtBytes(opts.maxBytes)} cap.`);
	}
	if (!res.body) {
		const buf = new Uint8Array(await res.arrayBuffer());
		if (buf.byteLength > opts.maxBytes) {
			throw new NetError('too-large', `${fmtBytes(buf.byteLength)} is over the ${fmtBytes(opts.maxBytes)} cap.`);
		}
		return buf;
	}
	const reader = res.body.getReader();
	const chunks: Uint8Array[] = [];
	let received = 0;
	try {
		for (;;) {
			const { done, value } = await reader.read();
			if (done) break;
			received += value.byteLength;
			if (received > opts.maxBytes) {
				await reader.cancel();
				throw new NetError('too-large', `Stopped at ${fmtBytes(received)}: over the ${fmtBytes(opts.maxBytes)} cap.`);
			}
			chunks.push(value);
			opts.onProgress?.(received, total);
		}
	} catch (e) {
		throw explain(url, e);
	}
	const out = new Uint8Array(received);
	let offset = 0;
	for (const c of chunks) {
		out.set(c, offset);
		offset += c.byteLength;
	}
	return out;
}

export async function fetchText(url: string, opts: FetchOptions): Promise<string> {
	const bytes = await fetchBytes(url, opts);
	return new TextDecoder('utf-8').decode(bytes);
}

/** A HEAD probe for the health check: status + size without the body. Falls back to a ranged GET. */
export async function probe(url: string, signal?: AbortSignal): Promise<{ ok: boolean; status: number; size: number | null; compressed: boolean; error?: string }> {
	try {
		let res = await fetch(url, { method: 'HEAD', signal, cache: 'no-store' });
		if (res.status === 405 || res.status === 403) {
			res = await fetch(url, { method: 'GET', signal, headers: { Range: 'bytes=0-0' }, cache: 'no-store' });
			const range = res.headers.get('content-range');
			const m = range?.match(/\/(\d+)$/);
			return { ok: res.ok, status: res.status, size: m ? Number(m[1]) : null, compressed: false };
		}
		// Content-Length is the on-the-wire size: for a gzip/br transfer it is
		// smaller than the file. The header saying so is not CORS-safelisted, so
		// it is usually invisible; callers treat a smaller-than-declared size on
		// a text file as "probably compressed".
		const enc = res.headers.get('content-encoding');
		const len = res.headers.get('content-length');
		return { ok: res.ok, status: res.status, size: len ? Number(len) : null, compressed: !!enc && enc !== 'identity' };
	} catch (e) {
		const err = explain(url, e);
		return { ok: false, status: 0, size: null, compressed: false, error: err.message };
	}
}

export function fmtBytes(n: number | null | undefined): string {
	if (n == null || !Number.isFinite(n)) return '—';
	if (n < 1024) return `${n} B`;
	if (n < MB) return `${(n / 1024).toFixed(n < 10 * 1024 ? 1 : 0)} KB`;
	if (n < 1024 * MB) return `${(n / MB).toFixed(n < 10 * MB ? 1 : 0)} MB`;
	return `${(n / (1024 * MB)).toFixed(1)} GB`;
}

export function gunzipMaybe(bytes: Uint8Array, name: string): Promise<Uint8Array> {
	const gz = name.toLowerCase().endsWith('.gz') || (bytes[0] === 0x1f && bytes[1] === 0x8b);
	if (!gz) return Promise.resolve(bytes);
	return import('fflate').then(({ gunzipSync }) => gunzipSync(bytes));
}
