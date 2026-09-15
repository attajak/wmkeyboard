/**
 * Dictionary (word list) builder. The file is what DictionaryLoader.kt
 * reads: one `word frequency` per line, split at the LAST space, `#` lines
 * ignored, frequency an integer (missing → 1). Multi-word entries are legal.
 */
import { useMemo, useState } from 'preact/hooks';
import { gzipSync } from 'fflate';
import { LANGUAGES } from '../../lib/languages';
import { fetchBytes, fmtBytes, gunzipMaybe, NetError } from '../../lib/net';
import { readDictionary } from '../../lib/payloads';
import { slugify } from '../../lib/util';
import { CopyButton, Notice } from '../common';
import { IconDownload, IconWarn } from '../icons';
import { Area, DropZone, ExportPanel, saveBytes, Section, Text, Toggle, useDraft } from './shared';

interface Draft {
	name: string;
	langId: string;
	/** The list as text, exactly what the file will contain (minus normalisation). */
	text: string;
	lowercase: boolean;
	nfc: boolean;
	dedupe: boolean;
	sort: 'keep' | 'freq' | 'alpha';
	header: string;
}

const CAP = 32 * 1024 * 1024;

function blank(): Draft {
	return { name: 'my-words', langId: 'en', text: '', lowercase: false, nfc: true, dedupe: true, sort: 'keep', header: '' };
}

interface Row {
	word: string;
	freq: number;
}

function parse(text: string): { rows: Row[]; problems: string[] } {
	const rows: Row[] = [];
	const problems: string[] = [];
	let tabs = 0;
	let eaten = 0;
	text.split(/\r?\n/).forEach((raw, i) => {
		const line = raw.trim();
		if (!line || line.startsWith('#')) return;
		if (line.includes('\t')) tabs++;
		const sp = line.lastIndexOf(' ');
		if (sp <= 0) return void rows.push({ word: line, freq: 1 });
		const tail = line.slice(sp + 1);
		const n = /^-?\d+$/.test(tail) ? Number(tail) : null;
		if (n === null) {
			eaten++;
			if (eaten <= 3) problems.push(`Line ${i + 1}: "${tail}" after the last space would be read as the frequency and dropped from the word; the app keeps "${line.slice(0, sp)}" with frequency 1.`);
		}
		rows.push({ word: n === null ? line.slice(0, sp).trim() : line.slice(0, sp).trim(), freq: n ?? 1 });
	});
	if (tabs) problems.push(`${tabs} line${tabs === 1 ? '' : 's'} contain a tab; the app splits only at a space, so a tab-separated line imports as one bogus word.`);
	if (eaten > 3) problems.push(`…and ${eaten - 3} more lines with a non-numeric last token.`);
	return { rows, problems };
}

function build(d: Draft): { text: string; rows: Row[]; problems: string[] } {
	const { rows: raw, problems } = parse(d.text);
	let rows = raw.map((r) => ({ word: d.nfc ? r.word.normalize('NFC') : r.word, freq: r.freq })).map((r) => (d.lowercase ? { ...r, word: r.word.toLowerCase() } : r));
	if (d.dedupe) {
		const seen = new Map<string, Row>();
		for (const r of rows) {
			const k = r.word.toLowerCase();
			const prev = seen.get(k);
			if (!prev) seen.set(k, r);
			else prev.freq = Math.max(prev.freq, r.freq);
		}
		rows = [...seen.values()];
	}
	if (d.sort === 'freq') rows = [...rows].sort((a, b) => b.freq - a.freq || a.word.localeCompare(b.word));
	if (d.sort === 'alpha') rows = [...rows].sort((a, b) => a.word.localeCompare(b.word));
	const header = d.header.trim() ? d.header.trim().split('\n').map((l) => `# ${l.replace(/^#\s?/, '')}`).join('\n') + '\n' : '';
	const body = rows.map((r) => (r.freq === 1 ? r.word : `${r.word} ${r.freq}`)).join('\n');
	return { text: header + body + (body ? '\n' : ''), rows, problems };
}

export function DictionaryBuilder() {
	const [d, setD, reset] = useDraft<Draft>('dictionary', blank);
	const [busy, setBusy] = useState<string | null>(null);
	const [err, setErr] = useState<string | null>(null);
	const patch = (p: Partial<Draft>) => setD({ ...d, ...p });
	const out = useMemo(() => build(d), [d]);
	const bytes = useMemo(() => new TextEncoder().encode(out.text), [out.text]);
	const hasFreq = out.rows.some((r) => r.freq !== 1);
	const lang = LANGUAGES.find((l) => l.id === d.langId);

	const importFile = async (bytesIn: Uint8Array, name: string) => {
		try {
			const plain = await gunzipMaybe(bytesIn, name);
			const text = new TextDecoder('utf-8').decode(plain);
			// CSV/TSV: first two columns become word + frequency.
			const lines = text.split(/\r?\n/);
			const looksTsv = lines.slice(0, 20).filter(Boolean).every((l) => l.includes('\t') || l.startsWith('#'));
			const looksCsv = /\.csv$/i.test(name);
			const converted = looksTsv || looksCsv ? lines.map((l) => {
				if (!l.trim() || l.startsWith('#')) return l;
				const [w, f] = l.split(looksTsv ? '\t' : ',').map((s) => s.trim().replace(/^"|"$/g, ''));
				return f && /^\d+$/.test(f) ? `${w} ${f}` : (w ?? '');
			}).join('\n') : text;
			patch({ text: d.text.trim() ? d.text.trimEnd() + '\n' + converted : converted, name: d.name === 'my-words' ? name.replace(/\.(txt|tsv|csv)(\.gz)?$/i, '') : d.name });
			setErr(null);
		} catch (e) {
			setErr((e as Error).message);
		}
	};

	const startFromShipped = async () => {
		const url = `https://raw.githubusercontent.com/wasi-master/wmkeyboard-data/HEAD/data/${d.langId}/${d.langId}_full.txt.gz`;
		setBusy('Fetching the shipped word list…');
		try {
			const b = await fetchBytes(url, { maxBytes: CAP });
			const plain = await gunzipMaybe(b, url);
			const r = await readDictionary(plain, 'x.txt', 200_000);
			patch({ text: r.words.map((w) => `${w.word} ${w.freq}`).join('\n'), sort: 'freq' });
			setErr(r.truncated ? `The shipped list has ${r.total.toLocaleString()} words; the first 200 000 were loaded.` : null);
		} catch (e) {
			setErr(e instanceof NetError ? e.message : (e as Error).message);
		}
		setBusy(null);
	};

	return (
		<div class="st-creator-layout">
			<div style="display:flex;flex-direction:column;gap:0.8rem">
				<div>
					<span class="st-kicker">Dictionary</span>
					<h1 style="font-size:1.5rem;font-weight:800">A word list for suggestions</h1>
					<p class="st-muted st-small" style="max-width:62ch;margin-top:0.3rem">
						One word per line, an optional frequency after a space (raw corpus counts, any size; missing means 1). Higher frequency ranks earlier. The app adds the list to its own dictionary for the language, so ship only what's missing: jargon, names, slang.
					</p>
				</div>
				<Section title="Start from a file or the shipped list" open={false}>
					<DropZone accept=".txt,.gz,.csv,.tsv,text/plain" multiple={false} onFiles={([f]) => f && importFile(f.bytes, f.name)}>Drop a word list (.txt, .txt.gz, .csv, .tsv): appended to what's below</DropZone>
					<div class="st-row">
						<button class="st-btn st-btn-sm" disabled={!!busy} onClick={startFromShipped}>Load the app's {lang?.english ?? d.langId} list from wmkeyboard-data</button>
						{busy && <span class="st-small st-muted"><span class="st-spinner" /> {busy}</span>}
					</div>
					{err && <Notice kind="warn" icon={<IconWarn />}>{err}</Notice>}
				</Section>
				<Section title="List">
					<div class="st-grid2">
						<Text label="Name" required value={d.name} onInput={(v) => patch({ name: v })} help="The app names the list from the repository entry; this is the file name." />
						<Text label="Language id" required mono value={d.langId} onInput={(v) => patch({ langId: v })} list="st-dict-langs" help={lang ? `${lang.english} (${lang.script})` : 'Must be a language the app registers.'} error={lang ? null : 'Unknown language id.'} />
						<datalist id="st-dict-langs">{LANGUAGES.map((l) => <option value={l.id} key={l.id}>{l.english}</option>)}</datalist>
					</div>
					<Area label="Words (one per line, optional frequency after a space)" value={d.text} onInput={(v) => patch({ text: v })} rows={16} mono placeholder={'kubernetes 900\nterraform 700\nnew york 500\nlmao'} />
					<Area label="Header comment (optional)" value={d.header} onInput={(v) => patch({ header: v })} rows={2} help="Written as # lines at the top; the app ignores them." />
				</Section>
				<Section title="Clean-up" open={false}>
					<Toggle label="Deduplicate (case-insensitive, keeps the higher frequency)" value={d.dedupe} onInput={(v) => patch({ dedupe: v })} />
					<Toggle label="Normalise to NFC" value={d.nfc} onInput={(v) => patch({ nfc: v })} help="The app folds lookups to NFC; writing NFC keeps the file's own form consistent (matters for Bengali য়)." />
					<Toggle label="Lowercase every word" value={d.lowercase} onInput={(v) => patch({ lowercase: v })} help="Lookups are case-folded anyway; keep case for proper nouns you want suggested capitalised." />
					<div class="st-grid2">
						<div class="st-field">
							<label>Order</label>
							<select class="st-select" value={d.sort} onChange={(e) => patch({ sort: (e.target as HTMLSelectElement).value as Draft['sort'] })}>
								<option value="keep">As typed</option>
								<option value="freq">By frequency (shipped lists use this)</option>
								<option value="alpha">Alphabetical</option>
							</select>
						</div>
					</div>
				</Section>
			</div>
			<aside class="st-creator-side">
				<ExportPanel title="Export">
					<div class="st-small st-muted">{out.rows.length.toLocaleString()} words · {fmtBytes(bytes.byteLength)}{hasFreq ? '' : ' · no frequencies (all rank equally)'}</div>
					<button class="st-btn st-btn-primary" disabled={!out.rows.length || !lang} onClick={() => saveBytes(`${slugify(d.name) || d.langId}.txt`, bytes, 'text/plain')}><IconDownload /> .txt</button>
					<button class="st-btn" disabled={!out.rows.length || !lang} onClick={() => saveBytes(`${slugify(d.name) || d.langId}.txt.gz`, gzipSync(bytes, { level: 9 }), 'application/gzip')}><IconDownload /> .txt.gz (repositories only)</button>
					<CopyButton text={out.text} label="Copy text" class="st-btn" />
					<button class="st-btn st-btn-ghost st-btn-sm" onClick={() => { if (confirm('Discard this draft?')) reset(); }}>Start over</button>
				</ExportPanel>
				{bytes.byteLength > CAP && <Notice kind="err" icon={<IconWarn />}>Over the 32 MB cap.</Notice>}
				{out.problems.length > 0 && <Notice kind="warn" icon={<IconWarn />}><ul style="padding-left:1rem">{out.problems.map((p, i) => <li key={i}>{p}</li>)}</ul></Notice>}
				<div class="st-panel st-small st-muted">
					A <code>.gz</code> file only works when a repository lists it (the app unwraps by the path's extension); importing from a file manager needs the plain <code>.txt</code>. In a repository entry set <code>type: dictionary</code> and <code>langId: {d.langId}</code>.
				</div>
			</aside>
		</div>
	);
}
