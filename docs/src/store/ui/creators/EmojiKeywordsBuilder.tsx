/**
 * Emoji keyword pack builder. Writes the TSV EmojiKeywordPack.kt reads:
 * `emoji<TAB>kw,kw<TAB>name`, "# " comment lines, keywords lowercased and
 * deduped per emoji. Picker + "start from the built-in pack" come from the
 * app's own catalog (src/data/emoji-catalog.json).
 */
import { useMemo, useState } from 'preact/hooks';
import { gzipSync } from 'fflate';
import catalog from '../../../data/emoji-catalog.json';
import { LANGUAGES } from '../../lib/languages';
import { fmtBytes, gunzipMaybe } from '../../lib/net';
import { readEmojiKeywords } from '../../lib/payloads';
import { slugify, uid } from '../../lib/util';
import { CopyButton, Notice } from '../common';
import { IconDownload, IconPlus, IconSearch, IconTrash, IconWarn } from '../icons';
import { DropZone, ExportPanel, saveBytes, Section, Text, useDraft } from './shared';

interface CatalogRow {
	e: string;
	c: string;
	k: string[];
	n: string;
	p?: string;
}
const CATALOG = catalog as CatalogRow[];
const CATEGORIES = [...new Set(CATALOG.map((r) => r.c))];

interface Row {
	key: string;
	emoji: string;
	keywords: string;
	name: string;
}

interface Draft {
	name: string;
	langId: string;
	rows: Row[];
	comment: string;
}

const CAP = 8 * 1024 * 1024;

function toTsv(d: Draft): string {
	const lines: string[] = [];
	if (d.comment.trim()) lines.push(`# ${d.comment.trim().replace(/\n/g, ' ')}`);
	lines.push('# emoji\tkeywords\tname');
	for (const r of d.rows) {
		const e = r.emoji.trim();
		if (!e) continue;
		const kws = [...new Set(r.keywords.split(',').map((k) => k.trim().toLowerCase().replace(/[\t\n]/g, ' ')).filter(Boolean))];
		lines.push(`${e}\t${kws.join(',')}\t${r.name.trim().replace(/[\t\n]/g, ' ')}`);
	}
	return lines.join('\n') + '\n';
}

export function EmojiKeywordsBuilder() {
	const [d, setD, reset] = useDraft<Draft>('emoji_keywords', () => ({ name: 'my-emoji-keywords', langId: 'en', rows: [], comment: '' }));
	const [q, setQ] = useState('');
	const [cat, setCat] = useState<string | null>(null);
	const [err, setErr] = useState<string | null>(null);
	const patch = (p: Partial<Draft>) => setD({ ...d, ...p });
	const tsv = useMemo(() => toTsv(d), [d]);
	const bytes = useMemo(() => new TextEncoder().encode(tsv), [tsv]);
	const have = new Set(d.rows.map((r) => r.emoji));
	const lang = LANGUAGES.find((l) => l.id === d.langId);
	const picks = useMemo(() => {
		const needle = q.trim().toLowerCase();
		return CATALOG.filter((r) => (!cat || r.c === cat) && (!needle || r.n.includes(needle) || r.k.some((k) => k.includes(needle)) || r.e === q.trim())).slice(0, 120);
	}, [q, cat]);

	const add = (r: CatalogRow | null) => {
		const row: Row = r ? { key: uid(), emoji: r.e, keywords: '', name: r.n } : { key: uid(), emoji: '', keywords: '', name: '' };
		patch({ rows: [...d.rows, row] });
	};
	const setRow = (key: string, p: Partial<Row>) => patch({ rows: d.rows.map((r) => (r.key === key ? { ...r, ...p } : r)) });
	const empty = d.rows.filter((r) => !r.keywords.trim()).length;
	const dupes = d.rows.map((r) => r.emoji).filter((e, i, a) => e && a.indexOf(e) !== i).length;

	const importFile = async (b: Uint8Array, name: string) => {
		try {
			const r = await readEmojiKeywords(await gunzipMaybe(b, name), name, 5000);
			patch({ rows: [...d.rows, ...r.rows.map((x) => ({ key: uid(), emoji: x.emoji, keywords: x.keywords.join(', '), name: x.name }))] });
			setErr(r.truncated ? 'Only the first 5 000 rows were loaded.' : null);
		} catch (e) {
			setErr((e as Error).message);
		}
	};

	return (
		<div class="st-creator-layout">
			<div style="display:flex;flex-direction:column;gap:0.8rem">
				<div>
					<span class="st-kicker">Emoji keyword pack</span>
					<h1 style="font-size:1.5rem;font-weight:800">Words that find emoji</h1>
					<p class="st-muted st-small" style="max-width:62ch;margin-top:0.3rem">
						Type "party" on the phone and 🎉 shows up because a keyword pack says so. Packs stack on top of the built-in keywords; names replace the built-in name for the pack's language. Any language id works, so this is also how a language gets emoji search in its own words.
					</p>
				</div>
				<Section title="Start from a file or the built-in English pack" open={false}>
					<DropZone accept=".tsv,.txt,.json,.gz" multiple={false} onFiles={([f]) => f && importFile(f.bytes, f.name)}>Drop a .tsv (emoji, keywords, name) or a wmkeyboard-data emoji .json</DropZone>
					<button class="st-btn st-btn-sm" onClick={() => patch({ rows: [...d.rows, ...CATALOG.filter((r) => !have.has(r.e)).map((r) => ({ key: uid(), emoji: r.e, keywords: r.k.join(', '), name: r.n }))] })}>Load all {CATALOG.length} built-in emoji with their English keywords</button>
					{err && <Notice kind="warn" icon={<IconWarn />}>{err}</Notice>}
				</Section>
				<Section title="Pack">
					<div class="st-grid2">
						<Text label="Name" required value={d.name} onInput={(v) => patch({ name: v })} />
						<Text label="Language id" required mono value={d.langId} onInput={(v) => patch({ langId: v })} list="st-emoji-langs" help={lang ? `${lang.english}` : ''} error={lang ? null : 'Unknown language id.'} />
						<datalist id="st-emoji-langs">{LANGUAGES.map((l) => <option value={l.id} key={l.id}>{l.english}</option>)}</datalist>
					</div>
					<Text label="Comment" value={d.comment} onInput={(v) => patch({ comment: v })} help="Written as a # line at the top." />
				</Section>
				<Section title={`Rows (${d.rows.length})`}>
					<div class="st-table-wrap" style="max-height:32rem">
						<table class="st-table">
							<thead><tr><th style="width:4rem">Emoji</th><th>Keywords (comma-separated)</th><th style="width:12rem">Name</th><th style="width:2.5rem" /></tr></thead>
							<tbody>
								{d.rows.map((r) => (
									<tr key={r.key}>
										<td><input class="st-input" style="font-size:1.3rem;padding:0.2rem 0.4rem;width:3.5rem;text-align:center" value={r.emoji} onInput={(e) => setRow(r.key, { emoji: (e.target as HTMLInputElement).value })} /></td>
										<td><input class="st-input" style="padding:0.3rem 0.5rem" value={r.keywords} placeholder="party, celebrate, confetti" onInput={(e) => setRow(r.key, { keywords: (e.target as HTMLInputElement).value })} /></td>
										<td><input class="st-input" style="padding:0.3rem 0.5rem" value={r.name} onInput={(e) => setRow(r.key, { name: (e.target as HTMLInputElement).value })} /></td>
										<td><button class="st-btn st-btn-ghost st-btn-icon st-btn-sm" onClick={() => patch({ rows: d.rows.filter((x) => x.key !== r.key) })}><IconTrash /></button></td>
									</tr>
								))}
							</tbody>
						</table>
					</div>
					<div class="st-row">
						<button class="st-btn st-btn-sm" onClick={() => add(null)}><IconPlus /> Blank row</button>
						{d.rows.length > 0 && <button class="st-btn st-btn-sm st-btn-ghost st-btn-danger" onClick={() => { if (confirm('Remove every row?')) patch({ rows: [] }); }}>Clear</button>}
					</div>
				</Section>
				<Section title="Pick from the catalog">
					<div class="st-row">
						<div class="st-search" style="max-width:none;flex:1 1 14rem"><IconSearch /><input class="st-input" placeholder="Search names and keywords" value={q} onInput={(e) => setQ((e.target as HTMLInputElement).value)} /></div>
					</div>
					<div class="st-chips">
						<button class="st-chip" aria-pressed={cat === null} onClick={() => setCat(null)}>All</button>
						{CATEGORIES.map((c) => <button key={c} class="st-chip" aria-pressed={cat === c} onClick={() => setCat(c)}>{c}</button>)}
					</div>
					<div style="display:flex;flex-wrap:wrap;gap:0.3rem">
						{picks.map((r) => (
							<button key={r.e} class="st-btn st-btn-sm" style={`font-size:1.2rem;padding:0.2rem 0.45rem;${have.has(r.e) ? 'opacity:0.35' : ''}`} title={`${r.n}: ${r.k.join(', ')}`} disabled={have.has(r.e)} onClick={() => add(r)}>{r.e}</button>
						))}
					</div>
				</Section>
			</div>
			<aside class="st-creator-side">
				<ExportPanel title="Export">
					<div class="st-small st-muted">{d.rows.filter((r) => r.emoji.trim()).length} emoji · {fmtBytes(bytes.byteLength)}</div>
					<button class="st-btn st-btn-primary" disabled={!d.rows.length || !lang} onClick={() => saveBytes(`${slugify(d.name) || 'emoji'}.tsv`, bytes, 'text/tab-separated-values')}><IconDownload /> .tsv</button>
					<button class="st-btn" disabled={!d.rows.length || !lang} onClick={() => saveBytes(`${slugify(d.name) || 'emoji'}.tsv.gz`, gzipSync(bytes, { level: 9 }), 'application/gzip')}><IconDownload /> .tsv.gz (repositories only)</button>
					<CopyButton text={tsv} label="Copy TSV" class="st-btn" />
					<button class="st-btn st-btn-ghost st-btn-sm" onClick={() => { if (confirm('Discard this draft?')) reset(); }}>Start over</button>
				</ExportPanel>
				{(empty > 0 || dupes > 0 || bytes.byteLength > CAP) && (
					<Notice kind="warn" icon={<IconWarn />}>
						<ul style="padding-left:1rem">
							{empty > 0 && <li>{empty} row{empty === 1 ? '' : 's'} with no keywords add nothing (a name alone still renames).</li>}
							{dupes > 0 && <li>{dupes} repeated emoji; the app merges their keywords, the last name wins.</li>}
							{bytes.byteLength > CAP && <li>Over the 8 MB cap.</li>}
						</ul>
					</Notice>
				)}
				<div class="st-panel st-small st-muted">A bare <code>#</code> is data (#️⃣ is an emoji); only <code># </code> with a space starts a comment. In a repository entry set <code>type: emoji_keywords</code> and <code>langId: {d.langId}</code>.</div>
			</aside>
		</div>
	);
}
