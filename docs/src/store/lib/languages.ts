/** Language ids → names, from the same extract the docs' language tables use. */
import languages from '../../data/languages.json';

interface Lang {
	id: string;
	name: string;
	english: string;
	script: string;
}

const byId = new Map<string, Lang>((languages as Lang[]).map((l) => [l.id, l]));

export function languageName(id: string): string {
	const l = byId.get(id);
	return l ? l.english : id;
}

export function languageNative(id: string): string {
	return byId.get(id)?.name ?? id;
}

export function isKnownLanguage(id: string): boolean {
	return byId.has(id);
}

export const LANGUAGES: readonly Lang[] = languages as Lang[];
