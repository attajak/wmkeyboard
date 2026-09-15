/** Side-by-side comparison of addons from any repository: same-type payloads rendered live. */
import { useEffect, useState } from 'preact/hooks';
import { everyAddon, findAddon, repoByUrl } from '../state';
import { fmtBytes } from '../lib/net';
import { typeInfo, type AddonEntry, type AddonKey, type LoadedRepo } from '../lib/types';
import { matchesQuery } from '../lib/util';
import { Dialog, Empty, Link, RepoIcon } from './common';
import { IconCompare, IconPlus, IconX, TypeIcon } from './icons';
import { PayloadLoader } from './previews/Preview';

const MAX = 4;

function encode(items: AddonKey[]): string {
	return items.map((k) => `${repoByUrl.value.get(k.repo)?.ref.input ?? k.repo}|${k.id}`).join(',');
}

function decode(search: string): AddonKey[] {
	const raw = new URLSearchParams(search).get('items');
	if (!raw) return [];
	return raw
		.split(',')
		.map((p) => {
			const [input, id] = p.split('|');
			const repo = [...repoByUrl.value.values()].find((r) => r.ref.input === input || r.ref.url === input)?.ref.url;
			return repo && id ? { repo, id } : null;
		})
		.filter((x): x is AddonKey => !!x);
}

export function Compare() {
	const [items, setItems] = useState<AddonKey[]>([]);
	const [picking, setPicking] = useState(false);
	const [sample, setSample] = useState('Sphinx of black quartz, judge my vow.');
	useEffect(() => {
		setItems(decode(location.search));
	}, []);
	useEffect(() => {
		const q = items.length ? `?items=${encodeURIComponent(encode(items))}` : '';
		history.replaceState(null, '', `/addons/compare/${q}`);
	}, [items]);

	const resolved = items.map((k) => findAddon(k.repo, k.id)).filter((x): x is { repo: LoadedRepo; entry: AddonEntry } => !!x);
	const category = resolved[0] ? typeInfo(resolved[0].entry.type).category : null;
	const hasFonts = resolved.some((r) => r.entry.type === 'font' || r.entry.type === 'emoji_font');

	return (
		<div class="st-page">
			<div class="st-section-head">
				<div>
					<span class="st-kicker">Compare</span>
					<h1 style="font-size:1.6rem;font-weight:800">Side by side</h1>
				</div>
				<div class="st-row">
					{items.length > 0 && <button class="st-btn st-btn-sm st-btn-ghost" onClick={() => setItems([])}>Clear</button>}
					<button class="st-btn st-btn-sm st-btn-primary" disabled={items.length >= MAX} onClick={() => setPicking(true)}><IconPlus /> Add addon</button>
				</div>
			</div>
			{hasFonts && (
				<div class="st-field" style="margin-bottom:1rem;max-width:40rem">
					<label>Sample text for every font</label>
					<input class="st-input" value={sample} onInput={(e) => setSample((e.target as HTMLInputElement).value)} />
				</div>
			)}
			{items.length === 0 ? (
				<Empty icon={<IconCompare />} title="Pick up to four addons">
					<p class="st-small">Fonts, themes, sounds, layouts, icon packs, anything with a preview. Each renders live from its file; mixing types is fine.</p>
				</Empty>
			) : (
				<div class="st-compare-grid">
					{resolved.map(({ repo, entry }) => (
						<div class="st-compare-cell" key={`${repo.ref.url}#${entry.id}`} style={{ '--type-hue': `#${typeInfo(entry.type).hue}` }}>
							<div class="st-compare-head">
								<span style="display:flex;align-items:center;gap:0.4rem;min-width:0">
									<RepoIcon repo={repo} size="1.3rem" />
									<Link to={{ view: 'addon', repo: repo.ref.url, id: entry.id }} style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{entry.name}</Link>
								</span>
								<button class="st-btn st-btn-ghost st-btn-icon st-btn-sm" aria-label="Remove" onClick={() => setItems(items.filter((k) => !(k.repo === repo.ref.url && k.id === entry.id)))}><IconX /></button>
							</div>
							<PayloadLoader repo={repo} entry={entry} sample={sample} />
							<dl class="st-kv" style="padding:0.7rem 0.9rem;border-top:1px solid var(--st-card-border)">
								<dt>Type</dt><dd><span class="st-tag st-tag-type">{typeInfo(entry.type).singular}</span></dd>
								<dt>Version</dt><dd>{entry.version}</dd>
								<dt>Size</dt><dd>{fmtBytes(entry.sizeBytes)}</dd>
								<dt>Licence</dt><dd>{entry.license ?? <span class="st-muted">not stated</span>}</dd>
								{entry.author && <><dt>Author</dt><dd>{entry.author}</dd></>}
								{entry.langIds.length > 0 && <><dt>Languages</dt><dd>{entry.langIds.join(', ')}</dd></>}
							</dl>
						</div>
					))}
				</div>
			)}
			{picking && (
				<Picker
					exclude={items}
					hint={category ? `Same type as the first pick (${typeInfo(category).plural.toLowerCase()}) compares best, but anything goes.` : undefined}
					onPick={(k) => { setItems([...items, k]); setPicking(false); }}
					onClose={() => setPicking(false)}
				/>
			)}
		</div>
	);
}

export function Picker({ exclude, hint, onPick, onClose, filterType }: { exclude: AddonKey[]; hint?: string; onPick: (k: AddonKey) => void; onClose: () => void; filterType?: string }) {
	const [q, setQ] = useState('');
	const ex = new Set(exclude.map((k) => `${k.repo}#${k.id}`));
	const list = everyAddon.value.filter(({ repo, entry }) => !ex.has(`${repo.ref.url}#${entry.id}`) && (!filterType || typeInfo(entry.type).category === filterType) && matchesQuery(entry, q));
	return (
		<Dialog title="Pick an addon" desc={hint} onClose={onClose}>
			<input class="st-input" placeholder="Search" value={q} onInput={(e) => setQ((e.target as HTMLInputElement).value)} autoFocus style="margin-bottom:0.6rem" />
			<div class="st-picker-list st-entry-list">
				{list.slice(0, 60).map(({ repo, entry }) => (
					<button class="st-entry" key={`${repo.ref.url}#${entry.id}`} onClick={() => onPick({ repo: repo.ref.url, id: entry.id })} style={{ '--type-hue': `#${typeInfo(entry.type).hue}`, cursor: 'pointer', textAlign: 'left' }}>
						<span class="st-tile-icon" style="display:inline-flex;width:1.8rem;height:1.8rem;align-items:center;justify-content:center;border-radius:30%;background:color-mix(in oklab, var(--type-hue) 18%, transparent);color:var(--type-hue)"><TypeIcon type={entry.type} style="width:1rem;height:1rem" /></span>
						<span class="st-entry-text">
							<span class="st-entry-name">{entry.name}</span>
							<span class="st-entry-sub">{typeInfo(entry.type).singular} · v{entry.version} · {repo.manifest?.repo.name}</span>
						</span>
						<span />
					</button>
				))}
				{list.length === 0 && <div class="st-muted st-small">Nothing matches.</div>}
			</div>
		</Dialog>
	);
}
