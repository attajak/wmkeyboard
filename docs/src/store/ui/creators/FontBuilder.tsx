/**
 * Font builder: turn what you have into what the app installs. Reads
 * TTF/OTF/TTC/WOFF, shows names, tables and script coverage, lets you drop
 * tables Android never uses (the SVG colour table alone is 41 MB in Fluent
 * Emoji), rename the family, subset to scripts (outline fonts), and writes a
 * plain TTF/OTF under the 32 MiB cap with the langIds the app's pickers
 * expect already worked out from the cmap.
 */
import { useEffect, useMemo, useState } from 'preact/hooks';
import { fmtBytes } from '../../lib/net';
import { describeTables, dropTables, FONT_CAP, readCmap, readNames, readSfnt, readWoff1, scriptCoverage, sfntKind, totalBytes, writeNames, writeSfnt, NAME_IDS, type Sfnt } from '../../lib/font-tools';
import { blobUrl } from '../../lib/zip';
import { slugify } from '../../lib/util';
import { CopyButton, Notice } from '../common';
import { IconCheck, IconDownload, IconWarn } from '../icons';
import { DropZone, ExportPanel, Section, Text, Toggle, type PickedFile } from './shared';

type Mode = 'font' | 'emoji_font';

const SAMPLES: Record<string, string> = {
	Latin: 'Sphinx of black quartz, judge my vow. 0123456789',
	Cyrillic: 'Съешь же ещё этих мягких французских булок.',
	Greek: 'Ξεσκεπάζω την ψυχοφθόρα βδελυγμία.',
	Bengali: 'আমি বাংলায় গান গাই।',
	Devanagari: 'एक गाँव में एक किसान रहता था।',
	Arabic: 'نص حكيم له سر قاطع',
	Hebrew: 'דג סקרן שט בים מאוכזב',
	Thai: 'เป็นมนุษย์สุดประเสริฐเลิศคุณค่า',
	Hangul: '키스의 고유조건은 입술끼리 만나야 하고',
	Han: '視野無限廣，窗外有藍天',
	Hiragana: 'いろはにほへと ちりぬるを',
	Emoji: '😀 🎉 🐙 🍕 🚀 ❤️ 🌈 🦄 🇧🇩 👩‍💻',
};

let seq = 0;

export function FontBuilder() {
	const [source, setSource] = useState<{ name: string; kind: string; bytes: number; font: Sfnt } | null>(null);
	const [drop, setDrop] = useState<Set<string>>(new Set());
	const [renames, setRenames] = useState<Record<number, string>>({});
	const [mode, setMode] = useState<Mode>('font');
	const [subset, setSubset] = useState<Set<string>>(new Set());
	const [subsetting, setSubsetting] = useState<string | null>(null);
	const [err, setErr] = useState<string | null>(null);
	const [outName, setOutName] = useState('');
	const [langIds, setLangIds] = useState<string[]>([]);
	const [family, setFamily] = useState<string>('');

	const load = async (f: PickedFile) => {
		try {
			const kind = sfntKind(f.bytes);
			let font: Sfnt;
			if (kind === 'woff') font = readWoff1(f.bytes);
			else if (kind === 'woff2') throw new Error('WOFF2 needs a Brotli decoder this page does not ship. Convert it with `woff2_decompress` or fontTools first.');
			else if (kind === 'unknown') throw new Error('Not a font the app accepts: it wants a TrueType/OpenType file (0x00010000, OTTO, true or ttcf header).');
			else font = readSfnt(f.bytes);
			const names = readNames(font);
			const fam = names.find((n) => n.nameId === 16 && n.platform === 3)?.value ?? names.find((n) => n.nameId === 1 && n.platform === 3)?.value ?? names.find((n) => n.nameId === 1)?.value ?? '';
			setSource({ name: f.name, kind, bytes: f.bytes.byteLength, font });
			setFamily(fam);
			setRenames({});
			setDrop(new Set(describeTables(font).filter((t) => t.tag === 'SVG ' || t.tag === 'DSIG').map((t) => t.tag)));
			setSubset(new Set());
			setOutName(f.name.replace(/\.(ttf|otf|ttc|woff|woff2)$/i, ''));
			const tags = new Set(font.tables.map((t) => t.tag));
			const emoji = tags.has('CBDT') || tags.has('COLR') || tags.has('sbix') || tags.has('SVG ');
			setMode(emoji ? 'emoji_font' : 'font');
			const cov = scriptCoverage(readCmap(font));
			setLangIds(emoji ? [] : cov.filter((c) => c.covered && c.script !== 'Emoji').flatMap((c) => c.langIds));
			setErr(kind === 'ttc' ? 'A collection: only its first face is kept.' : null);
		} catch (e) {
			setErr((e as Error).message);
		}
	};

	const result = useMemo(() => {
		if (!source) return null;
		let f = dropTables(source.font, [...drop]);
		if (Object.keys(renames).length) f = writeNames(f, renames);
		return f;
	}, [source, drop, renames]);
	const outBytes = useMemo(() => (result ? writeSfnt(result) : null), [result]);
	const size = outBytes?.byteLength ?? 0;
	const tables = useMemo(() => (source ? describeTables(source.font) : []), [source]);
	const coverage = useMemo(() => (source ? scriptCoverage(readCmap(source.font)) : []), [source]);
	const names = useMemo(() => (source ? readNames(source.font).filter((n) => n.platform === 3 || !readNames(source.font).some((m) => m.platform === 3 && m.nameId === n.nameId)) : []), [source]);
	const [previewUrl, setPreviewUrl] = useState<string | null>(null);
	const [previewFamily, setPreviewFamily] = useState('');
	useEffect(() => {
		if (!outBytes) return;
		const url = blobUrl(outBytes, 'font/ttf');
		const fam = `wm-fb-${++seq}`;
		const face = new FontFace(fam, `url(${url})`);
		face.load().then((ff) => { document.fonts.add(ff); setPreviewUrl(url); setPreviewFamily(fam); }).catch(() => setPreviewUrl(null));
		return () => { document.fonts.delete(face); URL.revokeObjectURL(url); };
	}, [outBytes]);

	const colr = source?.font.tables.find((t) => t.tag === 'COLR');
	const colrV1 = colr ? new DataView(colr.data.buffer, colr.data.byteOffset).getUint16(0) === 1 : false;
	const hasCbdt = !!source?.font.tables.find((t) => t.tag === 'CBDT');
	const isCff = !!source?.font.tables.find((t) => t.tag === 'CFF ' || t.tag === 'CFF2');
	const canSubset = !!source && !hasCbdt && !colr && !!source.font.tables.find((t) => t.tag === 'glyf');

	const doSubset = async () => {
		if (!source || !subset.size) return;
		setSubsetting('Subsetting with opentype.js…');
		try {
			const ot = await import('opentype.js');
			const parsed = ot.parse(writeSfnt(source.font).buffer as ArrayBuffer);
			const keep = new Set<number>();
			const ranges = SCRIPT_RANGES;
			for (const s of subset) for (const [a, b] of ranges[s] ?? []) for (let c = a; c <= b; c++) keep.add(c);
			for (let c = 0x20; c < 0x7f; c++) keep.add(c);
			const glyphs = [parsed.glyphs.get(0)];
			const seen = new Set([0]);
			for (const cp of keep) {
				const g = parsed.charToGlyph(String.fromCodePoint(cp));
				if (g && !seen.has(g.index)) { seen.add(g.index); glyphs.push(g); }
			}
			const out = new ot.Font({ familyName: family || 'Subset', styleName: 'Regular', unitsPerEm: parsed.unitsPerEm, ascender: parsed.ascender, descender: parsed.descender, glyphs });
			const bytes = new Uint8Array(out.toArrayBuffer());
			setSource({ name: source.name, kind: 'ttf', bytes: bytes.byteLength, font: readSfnt(bytes) });
			setErr(`Subset to ${glyphs.length} glyphs. Hinting, kerning (GPOS) and ligatures (GSUB) are not carried over by this subsetter.`);
		} catch (e) {
			setErr(`Subsetting failed: ${(e as Error).message}`);
		}
		setSubsetting(null);
	};

	return (
		<div class="st-creator-layout">
			<div style="display:flex;flex-direction:column;gap:0.8rem">
				<div>
					<span class="st-kicker">Font</span>
					<h1 style="font-size:1.5rem;font-weight:800">Make a face the app installs</h1>
					<p class="st-muted st-small" style="max-width:62ch;margin-top:0.3rem">
						The app takes TrueType/OpenType under 32 MiB, never WOFF. Drop a font to convert, trim tables Android ignores, rename it, and read off the language ids its pickers should list it under.
					</p>
				</div>
				<DropZone accept=".ttf,.otf,.ttc,.woff,.woff2,font/*" multiple={false} onFiles={([f]) => f && load(f)}>Drop a .ttf, .otf, .ttc or .woff</DropZone>
				{err && <Notice kind="warn" icon={<IconWarn />}>{err}</Notice>}
				{source && (
					<>
						<Section title={`Tables (${source.kind.toUpperCase()}, ${fmtBytes(source.bytes)})`}>
							<div class="st-table-wrap" style="max-height:20rem">
								<table class="st-table">
									<thead><tr><th>Keep</th><th>Table</th><th>Size</th><th>What it is</th></tr></thead>
									<tbody>
										{[...tables].sort((a, b) => b.bytes - a.bytes).map((t) => (
											<tr key={t.tag}>
												<td><input type="checkbox" checked={!drop.has(t.tag)} disabled={!t.droppable && !drop.has(t.tag)} onChange={(e) => { const n = new Set(drop); if ((e.target as HTMLInputElement).checked) n.delete(t.tag); else n.add(t.tag); setDrop(n); }} /></td>
												<td class="mono">{t.tag}</td>
												<td class="mono">{fmtBytes(t.bytes)}</td>
												<td class="st-muted">{t.note}{t.droppable ? ' · safe to drop' : ''}</td>
											</tr>
										))}
									</tbody>
								</table>
							</div>
							{hasCbdt && colr && <Notice icon={<IconWarn />} kind="info">This font carries both bitmap (CBDT) and outline (COLR{colrV1 ? 'v1' : 'v0'}) colour. Android draws CBDT on every version{colrV1 ? ' and COLRv1 only on 13+' : ''}; keeping just one of the two halves the size. Uncheck COLR + CPAL to keep the bitmaps.</Notice>}
							{colr && !hasCbdt && colrV1 && <Notice icon={<IconWarn />} kind="warn">COLRv1 only: phones on Android 12 or older show these glyphs without colour.</Notice>}
						</Section>
						<Section title="Names">
							<div class="st-grid2">
								{[1, 2, 4, 6, 16, 17].map((id) => {
									const cur = names.find((n) => n.nameId === id)?.value ?? '';
									return <Text key={id} label={NAME_IDS[id] ?? String(id)} value={renames[id] ?? cur} onInput={(v) => setRenames({ ...renames, [id]: v })} help={renames[id] !== undefined && renames[id] !== cur ? `was "${cur}"` : undefined} />;
								})}
							</div>
							<details><summary class="st-small st-muted" style="cursor:pointer">All name records</summary>
								<dl class="st-kv" style="margin-top:0.5rem">{names.map((n) => <><dt key={`k${n.nameId}`}>{NAME_IDS[n.nameId] ?? n.nameId}</dt><dd key={`v${n.nameId}`}>{n.value}</dd></>)}</dl>
							</details>
						</Section>
						<Section title="Script coverage → language ids">
							<p class="st-small st-muted">A font that declares <code>langIds</code> is offered only in those languages' pickers; empty means everywhere. Detected from the cmap:</p>
							<div class="st-row">
								{coverage.map((c) => (
									<span key={c.script} class={`st-pill ${c.covered ? 'st-pill-ok' : 'st-pill-muted'}`} title={c.langIds.join(', ')}>{c.script}{c.covered && c.langIds.length ? ` · ${c.langIds.length}` : ''}</span>
								))}
							</div>
							<Text label="langIds for the repository entry" mono value={langIds.join(', ')} onInput={(v) => setLangIds(v.split(',').map((s) => s.trim()).filter(Boolean))} help="Comma-separated. Trim to the languages you actually mean; a Latin face covers 200+ ids." />
						</Section>
						{canSubset && (
							<Section title="Subset (outline fonts)" open={false}>
								<p class="st-small st-muted">Keep only the scripts you need. Uses opentype.js, which rebuilds the font from outlines: hinting, kerning and ligatures are lost, so use it for display faces, not for a Bengali face that needs its conjuncts.</p>
								<div class="st-row">
									{Object.keys(SCRIPT_RANGES).map((s) => (
										<label key={s} class="st-switch"><input type="checkbox" checked={subset.has(s)} onChange={(e) => { const n = new Set(subset); if ((e.target as HTMLInputElement).checked) n.add(s); else n.delete(s); setSubset(n); }} /> {s}</label>
									))}
								</div>
								<button class="st-btn st-btn-sm" disabled={!subset.size || !!subsetting} onClick={doSubset}>{subsetting ?? 'Subset now'}</button>
							</Section>
						)}
						{isCff && <p class="st-small st-muted">PostScript (CFF) outlines: table stripping and renaming work; subsetting does not.</p>}
					</>
				)}
			</div>
			<aside class="st-creator-side">
				{outBytes && (
					<div class="st-panel" style="padding:0.8rem">
						<div class="st-font-sample" style="padding:0">
							<div class="st-sample-text" style={{ fontFamily: previewUrl ? `'${previewFamily}', sans-serif` : 'inherit', fontSize: '1.6rem' }}>{SAMPLES[coverage.find((c) => c.covered && (mode === 'emoji_font' ? c.script === 'Emoji' : c.script !== 'Emoji'))?.script ?? 'Latin']}</div>
						</div>
						{!previewUrl && <p class="st-small st-muted">The browser could not load this build; Android may still.</p>}
					</div>
				)}
				<ExportPanel title="Export">
					{outBytes && (
						<>
							<div class="st-small">
								<span class={`st-pill ${size > FONT_CAP ? 'st-pill-err' : 'st-pill-ok'}`}>{fmtBytes(size)}</span> <span class="st-muted">of {fmtBytes(FONT_CAP)} cap{source && size !== source.bytes ? ` · was ${fmtBytes(source.bytes)}` : ''}</span>
							</div>
							<Text label="File name" value={outName} onInput={setOutName} />
							<div class="st-seg" role="group">
								<button aria-pressed={mode === 'font'} onClick={() => setMode('font')}>Text font</button>
								<button aria-pressed={mode === 'emoji_font'} onClick={() => setMode('emoji_font')}>Emoji font</button>
							</div>
							<button class="st-btn st-btn-primary" disabled={size > FONT_CAP} onClick={() => { const a = document.createElement('a'); a.href = blobUrl(outBytes, isCff ? 'font/otf' : 'font/ttf'); a.download = `${slugify(outName) || 'font'}.${isCff ? 'otf' : 'ttf'}`; a.click(); }}><IconDownload /> .{isCff ? 'otf' : 'ttf'}</button>
							<CopyButton text={JSON.stringify({ id: slugify(outName), type: mode, name: renames[16] ?? renames[1] ?? family, version: '1.0.0', path: `fonts/${slugify(outName)}.${isCff ? 'otf' : 'ttf'}`, ...(mode === 'font' && langIds.length ? { langIds } : {}) }, null, 2)} label="Copy repository entry" class="st-btn" />
							{size <= FONT_CAP && <span class="st-small st-muted"><IconCheck style="width:0.9rem;height:0.9rem;vertical-align:-0.15em" /> Fits the app's cap.</span>}
						</>
					)}
					{!outBytes && <span class="st-small st-muted">Drop a font to begin.</span>}
				</ExportPanel>
				<div class="st-panel st-small st-muted">The app never reads the font's own name; the repository entry's <code>name</code> is what users see. It loads the file with Android's <code>Typeface</code>, so a face this page can't preview may still work there, and vice versa.</div>
			</aside>
		</div>
	);
}

const SCRIPT_RANGES: Record<string, [number, number][]> = {
	Latin: [[0x20, 0x24f], [0x1e00, 0x1eff], [0x2000, 0x206f], [0x20a0, 0x20cf], [0x2100, 0x214f], [0xfb00, 0xfb06]],
	Cyrillic: [[0x400, 0x52f], [0x2de0, 0x2dff], [0xa640, 0xa69f]],
	Greek: [[0x370, 0x3ff], [0x1f00, 0x1fff]],
	Bengali: [[0x980, 0x9ff], [0x200c, 0x200d]],
	Devanagari: [[0x900, 0x97f], [0xa8e0, 0xa8ff], [0x200c, 0x200d]],
	Arabic: [[0x600, 0x6ff], [0x750, 0x77f], [0xfb50, 0xfdff], [0xfe70, 0xfeff]],
	Hebrew: [[0x590, 0x5ff]],
	Thai: [[0xe00, 0xe7f]],
	Hangul: [[0x1100, 0x11ff], [0x3130, 0x318f], [0xac00, 0xd7af]],
	Kana: [[0x3040, 0x30ff], [0xff66, 0xff9f]],
};
