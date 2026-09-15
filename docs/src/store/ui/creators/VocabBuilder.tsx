/**
 * Vocabulary pack builder: writes the `wmkeyboard-vocab` envelope
 * VocabPackFile.kt reads (pretty JSON, defaults omitted, the way the app
 * exports). Words: lemma, parts of speech, senses with definition/example/
 * synonyms/antonyms, pronunciation, etymology, mnemonic.
 */
import { useMemo, useState } from 'preact/hooks';
import { gzipSync } from 'fflate';
import { LANGUAGES } from '../../lib/languages';
import { fetchBytes, fmtBytes, gunzipMaybe, NetError } from '../../lib/net';
import { slugify, uid } from '../../lib/util';
import { CopyButton, Notice } from '../common';
import { IconDownload, IconPlus, IconTrash, IconWarn } from '../icons';
import { Area, DropZone, ExportPanel, saveBytes, Section, Tags, Text, useDraft } from './shared';

interface Sense {
	key: string;
	pos: string;
	definition: string;
	example: string;
	synonyms: string[];
	antonyms: string[];
	tags: string[];
}

interface Word {
	key: string;
	word: string;
	pos: string[];
	ipaUs: string;
	ipaUk: string;
	respelling: string;
	senses: Sense[];
	synonyms: string[];
	antonyms: string[];
	etymology: string;
	mnemonic: string;
	forms: string[];
}

interface Draft {
	id: string;
	name: string;
	langId: string;
	description: string;
	attribution: { name: string; license: string; url: string }[];
	words: Word[];
}

const POS = ['noun', 'verb', 'adjective', 'adverb', 'pronoun', 'preposition', 'conjunction', 'interjection', 'determiner', 'numeral', 'particle', 'phrase'];
const MAX_WORDS = 5000;
const CAP = 8 * 1024 * 1024;

const blankWord = (word = ''): Word => ({ key: uid(), word, pos: [], ipaUs: '', ipaUk: '', respelling: '', senses: [{ key: uid(), pos: '', definition: '', example: '', synonyms: [], antonyms: [], tags: [] }], synonyms: [], antonyms: [], etymology: '', mnemonic: '', forms: [] });

const strip = <T extends Record<string, unknown>>(o: T): Partial<T> => {
	const out: Record<string, unknown> = {};
	for (const [k, v] of Object.entries(o)) {
		if (v === '' || v === null || v === undefined) continue;
		if (Array.isArray(v) && !v.length) continue;
		if (typeof v === 'object' && !Array.isArray(v) && !Object.keys(v as object).length) continue;
		out[k] = v;
	}
	return out as Partial<T>;
};

function build(d: Draft) {
	const seen = new Set<string>();
	const words = d.words
		.map((w) => ({ ...w, word: w.word.trim().toLowerCase().replace(/\s+/g, ' ') }))
		.filter((w) => w.word && w.word.length <= 64 && !seen.has(w.word) && seen.add(w.word))
		.slice(0, MAX_WORDS)
		.map((w) =>
			strip({
				word: w.word,
				pos: w.pos,
				ipa: strip({ us: w.ipaUs, uk: w.ipaUk }),
				respelling: w.respelling,
				senses: w.senses.filter((s) => s.definition.trim()).map((s) => strip({ pos: s.pos, definition: s.definition.trim(), example: s.example.trim(), synonyms: s.synonyms, antonyms: s.antonyms, tags: s.tags })),
				synonyms: w.synonyms,
				antonyms: w.antonyms,
				forms: w.forms,
				etymology: w.etymology.trim(),
				mnemonic: w.mnemonic.trim(),
			})
		);
	return {
		format: 'wmkeyboard-vocab',
		version: 1,
		appVersion: 0,
		appVersionName: '',
		pack: strip({ id: d.id, name: d.name, langId: d.langId, description: d.description, userCreated: true, attribution: d.attribution.filter((a) => a.name).map((a) => strip(a)) }),
		words,
	};
}

function fromPack(raw: Record<string, unknown>): Draft {
	const pack = (raw.pack ?? {}) as Record<string, unknown>;
	const list = Array.isArray(raw.words) ? (raw.words as Record<string, unknown>[]) : [];
	const s = (v: unknown) => (typeof v === 'string' ? v : '');
	const arr = (v: unknown) => (Array.isArray(v) ? v.map(String) : []);
	return {
		id: s(pack.id),
		name: s(pack.name),
		langId: s(pack.langId) || 'en',
		description: s(pack.description),
		attribution: Array.isArray(pack.attribution) ? (pack.attribution as Record<string, unknown>[]).map((a) => ({ name: s(a.name), license: s(a.license), url: s(a.url) })) : [],
		words: list.map((w) => {
			const ipa = (w.ipa ?? {}) as Record<string, unknown>;
			return {
				key: uid(),
				word: s(w.word),
				pos: arr(w.pos),
				ipaUs: s(ipa.us),
				ipaUk: s(ipa.uk),
				respelling: s(w.respelling),
				senses: (Array.isArray(w.senses) ? (w.senses as Record<string, unknown>[]) : []).map((se) => ({ key: uid(), pos: s(se.pos), definition: s(se.definition), example: s(se.example), synonyms: arr(se.synonyms), antonyms: arr(se.antonyms), tags: arr(se.tags) })),
				synonyms: arr(w.synonyms),
				antonyms: arr(w.antonyms),
				etymology: s(w.etymology),
				mnemonic: s(w.mnemonic),
				forms: arr(w.forms),
			};
		}),
	};
}

export function VocabBuilder() {
	const [d, setD, reset] = useDraft<Draft>('vocabulary', () => ({ id: '', name: '', langId: 'en', description: '', attribution: [], words: [] }));
	const [sel, setSel] = useState<string | null>(null);
	const [q, setQ] = useState('');
	const [err, setErr] = useState<string | null>(null);
	const [busy, setBusy] = useState(false);
	const patch = (p: Partial<Draft>) => setD({ ...d, ...p });
	const out = useMemo(() => build(d), [d]);
	const json = useMemo(() => JSON.stringify(out, null, 2) + '\n', [out]);
	const bytes = json.length;
	const cur = d.words.find((w) => w.key === sel) ?? null;
	const setWord = (key: string, p: Partial<Word>) => patch({ words: d.words.map((w) => (w.key === key ? { ...w, ...p } : w)) });
	const setSense = (wkey: string, skey: string, p: Partial<Sense>) => setWord(wkey, { senses: d.words.find((w) => w.key === wkey)!.senses.map((s) => (s.key === skey ? { ...s, ...p } : s)) });
	const lang = LANGUAGES.find((l) => l.id === d.langId);
	const shown = d.words.filter((w) => !q || w.word.includes(q.toLowerCase()) || w.senses.some((s) => s.definition.toLowerCase().includes(q.toLowerCase())));
	const problems: string[] = [];
	if (d.words.length > MAX_WORDS) problems.push(`${d.words.length} words; the app keeps the first ${MAX_WORDS}.`);
	if (bytes > CAP) problems.push('Over the 8 MB cap.');
	const dupes = d.words.map((w) => w.word.trim().toLowerCase()).filter((w, i, a) => w && a.indexOf(w) !== i);
	if (dupes.length) problems.push(`Duplicate lemmas (first wins): ${[...new Set(dupes)].slice(0, 5).join(', ')}.`);
	const noDef = d.words.filter((w) => w.word.trim() && !w.senses.some((s) => s.definition.trim())).length;
	if (noDef) problems.push(`${noDef} word${noDef === 1 ? '' : 's'} without a definition.`);

	const importFile = async (b: Uint8Array, name: string) => {
		try {
			const text = new TextDecoder().decode(await gunzipMaybe(b, name));
			if (/\.csv$/i.test(name) || /\.tsv$/i.test(name)) {
				const sep = /\.tsv$/i.test(name) ? '\t' : ',';
				const rows = text.split(/\r?\n/).filter((l) => l.trim());
				const words = rows.map((l) => {
					const [word, pos, definition, example] = l.split(sep).map((s) => s.trim().replace(/^"|"$/g, ''));
					const w = blankWord(word ?? '');
					w.pos = pos ? [pos] : [];
					w.senses[0]!.definition = definition ?? '';
					w.senses[0]!.example = example ?? '';
					return w;
				});
				patch({ words: [...d.words, ...words] });
			} else {
				const raw = JSON.parse(text);
				if (raw.format !== 'wmkeyboard-vocab') throw new Error('Not a wmkeyboard-vocab file.');
				setD(fromPack(raw));
			}
			setErr(null);
		} catch (e) {
			setErr((e as Error).message);
		}
	};
	const loadSample = async () => {
		setBusy(true);
		try {
			const b = await fetchBytes('https://raw.githubusercontent.com/wasi-master/wmkeyboard-data/HEAD/vocab/en/mg1000.wmvocab.json.gz', { maxBytes: CAP });
			setD(fromPack(JSON.parse(new TextDecoder().decode(await gunzipMaybe(b, 'x.gz')))));
			setErr(null);
		} catch (e) {
			setErr(e instanceof NetError ? e.message : (e as Error).message);
		}
		setBusy(false);
	};

	return (
		<div class="st-creator-layout">
			<div style="display:flex;flex-direction:column;gap:0.8rem">
				<div>
					<span class="st-kicker">Vocabulary pack</span>
					<h1 style="font-size:1.5rem;font-weight:800">Words worth learning</h1>
					<p class="st-muted st-small" style="max-width:62ch;margin-top:0.3rem">
						The Vocabulary tool teaches from packs like this: a lemma, its parts of speech, senses with examples, pronunciation, a mnemonic. Same file the app exports from Settings › Vocabulary. Up to 5 000 words, 8 MB.
					</p>
				</div>
				<Section title="Start from a file" open={false}>
					<DropZone accept=".json,.gz,.csv,.tsv" multiple={false} onFiles={([f]) => f && importFile(f.bytes, f.name)}>Drop a .wmvocab.json (replaces the draft) or a CSV/TSV of word, pos, definition, example (appends)</DropZone>
					<button class="st-btn st-btn-sm" disabled={busy} onClick={loadSample}>{busy ? 'Loading…' : 'Load the Magoosh 1000 pack from wmkeyboard-data as a starting point'}</button>
					{err && <Notice kind="warn" icon={<IconWarn />}>{err}</Notice>}
				</Section>
				<Section title="Pack">
					<div class="st-grid2">
						<Text label="Id" required mono value={d.id} onInput={(v) => patch({ id: v })} placeholder="gre-500" help="The app renames it to the file's base name on import." />
						<Text label="Name" required value={d.name} onInput={(v) => patch({ name: v })} />
						<Text label="Language id" required mono value={d.langId} onInput={(v) => patch({ langId: v })} list="st-vocab-langs" error={lang ? null : 'Unknown language id.'} />
						<datalist id="st-vocab-langs">{LANGUAGES.map((l) => <option value={l.id} key={l.id}>{l.english}</option>)}</datalist>
					</div>
					<Area label="Description" value={d.description} onInput={(v) => patch({ description: v })} rows={2} />
					<details>
						<summary class="st-small st-muted" style="cursor:pointer">Attribution ({d.attribution.length})</summary>
						{d.attribution.map((a, i) => (
							<div class="st-grid2" key={i} style="margin-top:0.5rem">
								<Text label="Source" value={a.name} onInput={(v) => patch({ attribution: d.attribution.map((x, j) => (j === i ? { ...x, name: v } : x)) })} />
								<Text label="Licence" value={a.license} onInput={(v) => patch({ attribution: d.attribution.map((x, j) => (j === i ? { ...x, license: v } : x)) })} />
								<Text label="URL" value={a.url} onInput={(v) => patch({ attribution: d.attribution.map((x, j) => (j === i ? { ...x, url: v } : x)) })} />
							</div>
						))}
						<button class="st-btn st-btn-sm" style="margin-top:0.5rem" onClick={() => patch({ attribution: [...d.attribution, { name: '', license: '', url: '' }] })}><IconPlus /> Add source</button>
					</details>
				</Section>
				<Section title={`Words (${d.words.length})`}>
					<div class="st-row">
						<input class="st-input" style="flex:1 1 12rem" placeholder="Find a word" value={q} onInput={(e) => setQ((e.target as HTMLInputElement).value)} />
						<button class="st-btn st-btn-sm" onClick={() => { const w = blankWord(); patch({ words: [w, ...d.words] }); setSel(w.key); }}><IconPlus /> Add word</button>
					</div>
					<div class="st-entry-list" style="max-height:24rem;overflow:auto">
						{shown.slice(0, 300).map((w) => (
							<div class="st-entry" key={w.key} aria-selected={sel === w.key}>
								<span class="st-tag">{w.pos[0] ?? '—'}</span>
								<button class="st-entry-text" style="background:none;border:0;padding:0;text-align:left;cursor:pointer" onClick={() => setSel(sel === w.key ? null : w.key)}>
									<span class="st-entry-name">{w.word || '(new word)'}</span>
									<span class="st-entry-sub">{w.senses.find((s) => s.definition)?.definition ?? 'no definition yet'}</span>
								</button>
								<span class="st-entry-actions"><button class="st-btn st-btn-ghost st-btn-icon st-btn-sm" onClick={() => { patch({ words: d.words.filter((x) => x.key !== w.key) }); if (sel === w.key) setSel(null); }}><IconTrash /></button></span>
							</div>
						))}
						{shown.length > 300 && <div class="st-small st-muted">…{shown.length - 300} more; narrow the search.</div>}
					</div>
				</Section>
				{cur && (
					<Section title={`Word: ${cur.word || 'new'}`}>
						<div class="st-grid2">
							<Text label="Lemma" required value={cur.word} onInput={(v) => setWord(cur.key, { word: v })} help="Lowercased on import; 64 characters max." error={cur.word.length > 64 ? 'Too long.' : null} />
							<Tags label="Parts of speech" value={cur.pos} onInput={(v) => setWord(cur.key, { pos: v })} placeholder={POS.slice(0, 4).join(', ')} />
							<Text label="IPA (US)" value={cur.ipaUs} onInput={(v) => setWord(cur.key, { ipaUs: v })} placeholder="/əˈbɛrənt/" />
							<Text label="IPA (UK)" value={cur.ipaUk} onInput={(v) => setWord(cur.key, { ipaUk: v })} />
							<Text label="Respelling" value={cur.respelling} onInput={(v) => setWord(cur.key, { respelling: v })} placeholder="a-BEH-ruhnt" />
							<Tags label="Forms" value={cur.forms} onInput={(v) => setWord(cur.key, { forms: v })} placeholder="aberrantly" />
							<Tags label="Synonyms" value={cur.synonyms} onInput={(v) => setWord(cur.key, { synonyms: v })} />
							<Tags label="Antonyms" value={cur.antonyms} onInput={(v) => setWord(cur.key, { antonyms: v })} />
						</div>
						{cur.senses.map((s, i) => (
							<div class="st-fieldset" key={s.key} style="padding:0.7rem 0.9rem">
								<div class="st-row" style="justify-content:space-between"><b class="st-small">Sense {i + 1}</b>{cur.senses.length > 1 && <button class="st-btn st-btn-ghost st-btn-sm" onClick={() => setWord(cur.key, { senses: cur.senses.filter((x) => x.key !== s.key) })}>Remove</button>}</div>
								<div style="display:flex;flex-direction:column;gap:0.5rem;margin-top:0.4rem">
									<div class="st-grid2">
										<Text label="Part of speech" value={s.pos} onInput={(v) => setSense(cur.key, s.key, { pos: v })} list="st-pos" />
										<Tags label="Tags / topics" value={s.tags} onInput={(v) => setSense(cur.key, s.key, { tags: v })} />
									</div>
									<Area label="Definition" value={s.definition} onInput={(v) => setSense(cur.key, s.key, { definition: v })} rows={2} />
									<Area label="Example" value={s.example} onInput={(v) => setSense(cur.key, s.key, { example: v })} rows={2} />
									<div class="st-grid2">
										<Tags label="Synonyms" value={s.synonyms} onInput={(v) => setSense(cur.key, s.key, { synonyms: v })} />
										<Tags label="Antonyms" value={s.antonyms} onInput={(v) => setSense(cur.key, s.key, { antonyms: v })} />
									</div>
								</div>
							</div>
						))}
						<datalist id="st-pos">{POS.map((p) => <option value={p} key={p} />)}</datalist>
						<button class="st-btn st-btn-sm" onClick={() => setWord(cur.key, { senses: [...cur.senses, { key: uid(), pos: '', definition: '', example: '', synonyms: [], antonyms: [], tags: [] }] })}><IconPlus /> Add sense</button>
						<Area label="Etymology" value={cur.etymology} onInput={(v) => setWord(cur.key, { etymology: v })} rows={2} />
						<Area label="Mnemonic" value={cur.mnemonic} onInput={(v) => setWord(cur.key, { mnemonic: v })} rows={2} />
					</Section>
				)}
			</div>
			<aside class="st-creator-side">
				<ExportPanel title="Export" json={out}>
					<div class="st-small st-muted">{out.words.length} words · {fmtBytes(bytes)}</div>
					<button class="st-btn st-btn-primary" disabled={!d.id || !out.words.length || !lang} onClick={() => saveBytes(`${slugify(d.name || d.id) || 'vocabulary'}.wmvocab.json`, new TextEncoder().encode(json), 'application/json')}><IconDownload /> .wmvocab.json</button>
					<button class="st-btn" disabled={!d.id || !out.words.length || !lang} onClick={() => saveBytes(`${slugify(d.name || d.id) || 'vocabulary'}.wmvocab.json.gz`, gzipSync(new TextEncoder().encode(json), { level: 9 }), 'application/gzip')}><IconDownload /> .wmvocab.json.gz</button>
					<CopyButton text={json} label="Copy JSON" class="st-btn" />
					<button class="st-btn st-btn-ghost st-btn-sm" onClick={() => { if (confirm('Discard this draft?')) { reset(); setSel(null); } }}>Start over</button>
				</ExportPanel>
				{problems.length > 0 && <Notice kind="warn" icon={<IconWarn />}><ul style="padding-left:1rem">{problems.map((p, i) => <li key={i}>{p}</li>)}</ul></Notice>}
				<div class="st-panel st-small st-muted">The app detects gzip by magic bytes on every import path, so either file works from a file manager or a repository (<code>type: vocabulary</code>, <code>langId: {d.langId}</code>).</div>
			</aside>
		</div>
	);
}
