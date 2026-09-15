/**
 * sfnt surgery in the browser: read tables, drop tables, rewrite the name
 * table, decode WOFF 1 into a plain TTF/OTF, and read the cmap to say which
 * scripts a face covers (which is what the app's `langIds` claim is about).
 * The app accepts exactly what `FontFile.kt` accepts: 0x00010000, 'OTTO',
 * 'true', 'ttcf' — never WOFF/WOFF2 — under 32 MiB.
 */
import { unzlibSync } from 'fflate';
import { LANGUAGES } from './languages';

export interface SfntTable {
	tag: string;
	data: Uint8Array;
	checksum?: number;
}

export interface Sfnt {
	/** 0x00010000, 'OTTO' or 'true' as a 4-byte number. */
	version: number;
	tables: SfntTable[];
}

const TAG = (s: string) => (s.charCodeAt(0) << 24) | (s.charCodeAt(1) << 16) | (s.charCodeAt(2) << 8) | s.charCodeAt(3);
const tagOf = (n: number) => String.fromCharCode((n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255);

export const SFNT_TTF = 0x00010000;
export const SFNT_OTTO = TAG('OTTO');
export const SFNT_TRUE = TAG('true');
export const SFNT_TTCF = TAG('ttcf');
export const WOFF1 = TAG('wOFF');
export const WOFF2 = TAG('wOF2');

export function sfntKind(bytes: Uint8Array): 'ttf' | 'otf' | 'ttc' | 'woff' | 'woff2' | 'unknown' {
	if (bytes.length < 4) return 'unknown';
	const v = new DataView(bytes.buffer, bytes.byteOffset, 4).getUint32(0);
	if (v === SFNT_TTF || v === SFNT_TRUE) return 'ttf';
	if (v === SFNT_OTTO) return 'otf';
	if (v === SFNT_TTCF) return 'ttc';
	if (v === WOFF1) return 'woff';
	if (v === WOFF2) return 'woff2';
	return 'unknown';
}

/** Parse a single-face sfnt (or the first face of a collection). */
export function readSfnt(bytes: Uint8Array): Sfnt {
	const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
	let base = 0;
	let version = dv.getUint32(0);
	if (version === SFNT_TTCF) {
		base = dv.getUint32(12);
		version = dv.getUint32(base);
	}
	if (version !== SFNT_TTF && version !== SFNT_OTTO && version !== SFNT_TRUE) throw new Error('Not a TrueType/OpenType font.');
	const num = dv.getUint16(base + 4);
	const tables: SfntTable[] = [];
	for (let i = 0; i < num; i++) {
		const rec = base + 12 + i * 16;
		const tag = tagOf(dv.getUint32(rec));
		const checksum = dv.getUint32(rec + 4);
		const off = dv.getUint32(rec + 8);
		const len = dv.getUint32(rec + 12);
		if (off + len > bytes.byteLength) throw new Error(`Table ${tag} runs past the end of the file.`);
		tables.push({ tag, data: bytes.subarray(off, off + len), checksum });
	}
	return { version, tables };
}

/** Decode WOFF 1.0 into a plain sfnt (zlib per table via fflate). */
export function readWoff1(bytes: Uint8Array): Sfnt {
	const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
	if (dv.getUint32(0) !== WOFF1) throw new Error('Not a WOFF 1.0 file.');
	const version = dv.getUint32(4);
	const num = dv.getUint16(12);
	const tables: SfntTable[] = [];
	for (let i = 0; i < num; i++) {
		const rec = 44 + i * 20;
		const tag = tagOf(dv.getUint32(rec));
		const off = dv.getUint32(rec + 4);
		const compLen = dv.getUint32(rec + 8);
		const origLen = dv.getUint32(rec + 12);
		const raw = bytes.subarray(off, off + compLen);
		const data = compLen < origLen ? unzlibSync(raw) : raw;
		if (data.byteLength !== origLen) throw new Error(`Table ${tag} did not inflate to its declared size.`);
		tables.push({ tag, data });
	}
	return { version, tables };
}

function checksumOf(data: Uint8Array): number {
	let sum = 0;
	const dv = new DataView(data.buffer, data.byteOffset, data.byteLength);
	const whole = data.byteLength & ~3;
	for (let i = 0; i < whole; i += 4) sum = (sum + dv.getUint32(i)) >>> 0;
	if (whole < data.byteLength) {
		let last = 0;
		for (let i = 0; i < 4; i++) last = (last << 8) | (whole + i < data.byteLength ? data[whole + i]! : 0);
		sum = (sum + (last >>> 0)) >>> 0;
	}
	return sum;
}

/** Serialise an sfnt with tables sorted by tag, 4-byte padding and a fresh head checksumAdjustment. */
export function writeSfnt(font: Sfnt): Uint8Array {
	const tables = [...font.tables].sort((a, b) => (a.tag < b.tag ? -1 : a.tag > b.tag ? 1 : 0));
	const num = tables.length;
	let entrySelector = 0;
	while (1 << (entrySelector + 1) <= num) entrySelector++;
	const searchRange = (1 << entrySelector) * 16;
	const rangeShift = num * 16 - searchRange;
	const headerLen = 12 + num * 16;
	let total = headerLen;
	for (const t of tables) total += (t.data.byteLength + 3) & ~3;
	const out = new Uint8Array(total);
	const dv = new DataView(out.buffer);
	dv.setUint32(0, font.version);
	dv.setUint16(4, num);
	dv.setUint16(6, searchRange);
	dv.setUint16(8, entrySelector);
	dv.setUint16(10, rangeShift);
	let off = headerLen;
	let headOff = -1;
	tables.forEach((t, i) => {
		const rec = 12 + i * 16;
		dv.setUint32(rec, TAG(t.tag));
		if (t.tag === 'head') {
			// Zero the adjustment before checksumming, per spec.
			const head = new Uint8Array(t.data);
			new DataView(head.buffer).setUint32(8, 0);
			out.set(head, off);
			headOff = off;
		} else out.set(t.data, off);
		dv.setUint32(rec + 4, checksumOf(out.subarray(off, off + t.data.byteLength)));
		dv.setUint32(rec + 8, off);
		dv.setUint32(rec + 12, t.data.byteLength);
		off += (t.data.byteLength + 3) & ~3;
	});
	if (headOff >= 0) {
		const whole = checksumOf(out);
		dv.setUint32(headOff + 8, (0xb1b0afba - whole) >>> 0);
	}
	return out;
}

/* ---------- name table ---------- */

export interface NameRecord {
	platform: number;
	encoding: number;
	language: number;
	nameId: number;
	value: string;
}

export const NAME_IDS: Record<number, string> = {
	0: 'Copyright',
	1: 'Family',
	2: 'Subfamily',
	3: 'Unique id',
	4: 'Full name',
	5: 'Version',
	6: 'PostScript name',
	7: 'Trademark',
	8: 'Manufacturer',
	9: 'Designer',
	10: 'Description',
	11: 'Vendor URL',
	12: 'Designer URL',
	13: 'Licence',
	14: 'Licence URL',
	16: 'Typographic family',
	17: 'Typographic subfamily',
};

export function readNames(font: Sfnt): NameRecord[] {
	const t = font.tables.find((x) => x.tag === 'name');
	if (!t) return [];
	const d = t.data;
	const dv = new DataView(d.buffer, d.byteOffset, d.byteLength);
	const count = dv.getUint16(2);
	const strOff = dv.getUint16(4);
	const out: NameRecord[] = [];
	for (let i = 0; i < count; i++) {
		const r = 6 + i * 12;
		const platform = dv.getUint16(r);
		const encoding = dv.getUint16(r + 2);
		const language = dv.getUint16(r + 4);
		const nameId = dv.getUint16(r + 6);
		const len = dv.getUint16(r + 8);
		const off = strOff + dv.getUint16(r + 10);
		if (off + len > d.byteLength) continue;
		const slice = d.subarray(off, off + len);
		let value: string;
		if (platform === 3 || platform === 0) {
			let s = '';
			for (let j = 0; j + 1 < slice.length; j += 2) s += String.fromCharCode((slice[j]! << 8) | slice[j + 1]!);
			value = s;
		} else value = new TextDecoder('latin1').decode(slice);
		out.push({ platform, encoding, language, nameId, value });
	}
	return out;
}

/** Rewrite the name table: every record with a given nameId gets the new value (UTF-16BE for Windows/Unicode, Mac Roman ASCII for Mac). */
export function writeNames(font: Sfnt, overrides: Record<number, string>): Sfnt {
	const records = readNames(font).map((r) => (overrides[r.nameId] !== undefined ? { ...r, value: overrides[r.nameId]! } : r));
	// Make sure the Windows Unicode English record exists for every overridden id.
	for (const [id, value] of Object.entries(overrides)) {
		const nameId = Number(id);
		if (!records.some((r) => r.platform === 3 && r.nameId === nameId)) records.push({ platform: 3, encoding: 1, language: 0x409, nameId, value });
	}
	records.sort((a, b) => a.platform - b.platform || a.encoding - b.encoding || a.language - b.language || a.nameId - b.nameId);
	const strings: Uint8Array[] = [];
	const encoded = records.map((r) => {
		let bytes: Uint8Array;
		if (r.platform === 1) bytes = new TextEncoder().encode(r.value.replace(/[^\x00-\x7f]/g, '?'));
		else {
			bytes = new Uint8Array(r.value.length * 2);
			for (let i = 0; i < r.value.length; i++) {
				const c = r.value.charCodeAt(i);
				bytes[i * 2] = c >> 8;
				bytes[i * 2 + 1] = c & 255;
			}
		}
		return bytes;
	});
	let strLen = 0;
	const offsets = encoded.map((b) => {
		const o = strLen;
		strings.push(b);
		strLen += b.byteLength;
		return o;
	});
	const headerLen = 6 + records.length * 12;
	const out = new Uint8Array(headerLen + strLen);
	const dv = new DataView(out.buffer);
	dv.setUint16(0, 0);
	dv.setUint16(2, records.length);
	dv.setUint16(4, headerLen);
	records.forEach((r, i) => {
		const rec = 6 + i * 12;
		dv.setUint16(rec, r.platform);
		dv.setUint16(rec + 2, r.encoding);
		dv.setUint16(rec + 4, r.language);
		dv.setUint16(rec + 6, r.nameId);
		dv.setUint16(rec + 8, encoded[i]!.byteLength);
		dv.setUint16(rec + 10, offsets[i]!);
	});
	let p = headerLen;
	for (const s of strings) {
		out.set(s, p);
		p += s.byteLength;
	}
	return { ...font, tables: font.tables.map((t) => (t.tag === 'name' ? { tag: 'name', data: out } : t)) };
}

/* ---------- cmap coverage → scripts → language ids ---------- */

/** Reads every Unicode cmap subtable (formats 4 and 12) into a set of code points. */
export function readCmap(font: Sfnt): Set<number> {
	const t = font.tables.find((x) => x.tag === 'cmap');
	const cps = new Set<number>();
	if (!t) return cps;
	const d = t.data;
	const dv = new DataView(d.buffer, d.byteOffset, d.byteLength);
	const n = dv.getUint16(2);
	for (let i = 0; i < n; i++) {
		const platform = dv.getUint16(4 + i * 8);
		const off = dv.getUint32(4 + i * 8 + 4);
		if (platform !== 0 && platform !== 3) continue;
		if (off + 4 > d.byteLength) continue;
		const format = dv.getUint16(off);
		if (format === 4) {
			const segX2 = dv.getUint16(off + 6);
			const ends = off + 14;
			const starts = ends + segX2 + 2;
			for (let s = 0; s < segX2 / 2; s++) {
				const end = dv.getUint16(ends + s * 2);
				const start = dv.getUint16(starts + s * 2);
				if (start === 0xffff) continue;
				for (let c = start; c <= end && c < 0xffff; c++) cps.add(c);
			}
		} else if (format === 12) {
			const groups = dv.getUint32(off + 12);
			for (let g = 0; g < groups; g++) {
				const r = off + 16 + g * 12;
				const start = dv.getUint32(r);
				const end = dv.getUint32(r + 4);
				for (let c = start; c <= end && c - start < 70000; c++) cps.add(c);
			}
		}
	}
	return cps;
}

/** Script name (matching languages.json `script`) → a few code points that the script cannot do without. */
const SCRIPT_PROBES: Record<string, number[]> = {
	Latin: [0x61, 0x65, 0x6f, 0x74],
	Cyrillic: [0x430, 0x435, 0x43e, 0x442],
	Greek: [0x3b1, 0x3b5, 0x3bf],
	Bengali: [0x995, 0x9be, 0x9cd],
	Devanagari: [0x915, 0x93e, 0x94d],
	Arabic: [0x627, 0x644, 0x645],
	Hebrew: [0x5d0, 0x5d1, 0x5dc],
	Thai: [0xe01, 0xe32],
	Georgian: [0x10d0, 0x10d4],
	Armenian: [0x561, 0x565],
	Tamil: [0xb95, 0xbbe],
	Telugu: [0xc15, 0xc3e],
	Kannada: [0xc95, 0xcbe],
	Malayalam: [0xd15, 0xd3e],
	Gujarati: [0xa95, 0xabe],
	Gurmukhi: [0xa15, 0xa3e],
	Odia: [0xb15, 0xb3e],
	Sinhala: [0xd9a, 0xdcf],
	Hangul: [0xac00, 0xd55c],
	Hiragana: [0x3042, 0x3044],
	Katakana: [0x30a2, 0x30a4],
	Han: [0x4e00, 0x4e2d],
	Ethiopic: [0x1200, 0x1208],
	Khmer: [0x1780, 0x17b6],
	Lao: [0xe81, 0xead],
	Myanmar: [0x1000, 0x1004],
	Tibetan: [0xf40, 0xf42],
	Mongolian: [0x1820, 0x1828],
	Cherokee: [0x13a0],
	Canadian: [0x1403],
	Emoji: [0x1f600, 0x1f389, 0x2764],
};

export interface ScriptCoverage {
	script: string;
	covered: boolean;
	/** Language ids from the app's registry written in this script. */
	langIds: string[];
}

export function scriptCoverage(cps: Set<number>): ScriptCoverage[] {
	const byScript = new Map<string, string[]>();
	for (const l of LANGUAGES) byScript.set(l.script, [...(byScript.get(l.script) ?? []), l.id]);
	return Object.entries(SCRIPT_PROBES).map(([script, probes]) => ({
		script,
		covered: probes.every((c) => cps.has(c)),
		langIds: byScript.get(script) ?? [],
	}));
}

/* ---------- table strip ---------- */

export interface TableNote {
	tag: string;
	bytes: number;
	note: string;
	/** Safe to drop without changing what the keyboard draws. */
	droppable: boolean;
}

export function describeTables(font: Sfnt): TableNote[] {
	const notes: Record<string, [string, boolean]> = {
		'SVG ': ['SVG colour glyphs: Android never renders these', true],
		DSIG: ['digital signature, meaningless once the file changes', true],
		fpgm: ['TrueType hinting program', true],
		prep: ['TrueType hinting pre-program', true],
		'cvt ': ['TrueType hinting control values', true],
		hdmx: ['device metrics cache', true],
		LTSH: ['linear threshold cache', true],
		VDMX: ['vertical device metrics cache', true],
		kern: ['legacy kerning; GPOS usually carries the same', false],
		GPOS: ['positioning (kerning, marks): keep', false],
		GSUB: ['substitution (ligatures, emoji sequences): keep', false],
		GDEF: ['glyph classes for GPOS/GSUB: keep', false],
		CBDT: ['colour bitmap glyphs (every Android version)', false],
		CBLC: ['bitmap glyph index: keep with CBDT', false],
		COLR: ['colour outlines (v0 Android 8+, v1 Android 13+)', false],
		CPAL: ['colour palettes for COLR: keep with COLR', false],
		sbix: ['Apple bitmap glyphs: Android ignores', true],
		glyf: ['outlines', false],
		'CFF ': ['PostScript outlines', false],
		CFF2: ['variable PostScript outlines', false],
		fvar: ['variation axes: the app has no axis picker, the default instance is what you get', false],
		gvar: ['outline variations', false],
		STAT: ['style attributes for variations', false],
		MATH: ['maths layout; unused by a keyboard', true],
		meta: ['metadata (design/supported languages)', true],
		VORG: ['vertical origins', true],
		vhea: ['vertical metrics header', true],
		vmtx: ['vertical metrics', true],
		BASE: ['baseline table', false],
		JSTF: ['justification', true],
		morx: ['AAT shaping: Android ignores', true],
		feat: ['AAT features: Android ignores', true],
		trak: ['AAT tracking: Android ignores', true],
		bsln: ['AAT baselines: Android ignores', true],
		'Zapf': ['AAT glyph info: Android ignores', true],
	};
	return font.tables.map((t) => {
		const [note, droppable] = notes[t.tag] ?? ['', false];
		return { tag: t.tag, bytes: t.data.byteLength, note, droppable };
	});
}

export function dropTables(font: Sfnt, tags: string[]): Sfnt {
	const set = new Set(tags);
	return { ...font, tables: font.tables.filter((t) => !set.has(t.tag)) };
}

export function totalBytes(font: Sfnt): number {
	return 12 + font.tables.length * 16 + font.tables.reduce((n, t) => n + ((t.data.byteLength + 3) & ~3), 0);
}

export const FONT_CAP = 32 * 1024 * 1024;
