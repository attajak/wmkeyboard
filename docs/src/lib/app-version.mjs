/**
 * The app's current versionCode/versionName, read from the repository's
 * gradle.properties at build time so the store's "needs a newer app" hints
 * never drift from what ships. Falls back to the last known values when the
 * docs are built outside the monorepo.
 */
import { readFileSync } from 'node:fs';

const FALLBACK = { versionCode: 19, versionName: '0.5.8' };

export function appVersion() {
	try {
		const text = readFileSync(new URL('../../../gradle.properties', import.meta.url), 'utf8');
		const code = text.match(/^wmkb\.versionCode=(\d+)/m);
		const name = text.match(/^wmkb\.versionName=(\S+)/m);
		return {
			versionCode: code ? Number(code[1]) : FALLBACK.versionCode,
			versionName: name ? name[1] : FALLBACK.versionName,
		};
	} catch {
		return FALLBACK;
	}
}
