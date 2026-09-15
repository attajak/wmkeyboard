/** One repository: hero, filters, and the grid. Also used for "All repositories" with `all`. */
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { addRepo, allRepos, everyAddon, markSeen, navigate, prefs, refreshRepo, refreshAll, repoByUrl, repos, seedSeen, setPrefs, showToast, sortAddons, unseenCount, onAndroid } from '../state';
import { describeManifestUrl } from '../lib/resolve';
import { fmtBytes } from '../lib/net';
import { ADDON_TYPES, typeInfo, type AddonEntry, type LoadedRepo } from '../lib/types';
import { appLinkRepo, fmtDate, matchesQuery, relTime } from '../lib/util';
import { AddonCard } from './AddonCard';
import { Empty, Link, Notice, RepoIcon, Spinner } from './common';
import { IconExternal, IconGrid, IconList, IconPhone, IconPlus, IconRefresh, IconSearch, IconShield, IconWarn, TypeIcon } from './icons';
import { RemoveRepoButton } from './TopBar';

export function useSearchHotkey(ref: { current: HTMLInputElement | null }) {
	useEffect(() => {
		const onKey = (e: KeyboardEvent) => {
			if (e.key === '/' && !(e.target instanceof HTMLInputElement) && !(e.target instanceof HTMLTextAreaElement)) {
				e.preventDefault();
				ref.current?.focus();
			}
		};
		document.addEventListener('keydown', onKey);
		return () => document.removeEventListener('keydown', onKey);
	}, [ref]);
}

export function CatalogueGrid({ items, showRepo, initialType }: { items: { repo: LoadedRepo; entry: AddonEntry }[]; showRepo?: boolean; initialType?: string }) {
	const [q, setQ] = useState('');
	const [type, setType] = useState<string | null>(initialType ?? null);
	const input = useRef<HTMLInputElement>(null);
	useSearchHotkey(input);
	const p = prefs.value;

	const counts = useMemo(() => {
		const c: Record<string, number> = {};
		for (const { entry } of items) {
			const cat = typeInfo(entry.type).category;
			c[cat] = (c[cat] ?? 0) + 1;
		}
		return c;
	}, [items]);
	const present = ADDON_TYPES.filter((t) => counts[t]);
	useEffect(() => {
		if (type && !counts[type]) setType(null);
	}, [counts, type]);

	const filtered = useMemo(() => {
		const list = items.filter(({ entry }) => (!type || typeInfo(entry.type).category === type) && matchesQuery(entry, q));
		if (p.sort === 'manifest') return list;
		const order = new Map(sortAddons(list.map((x) => x.entry), p.sort).map((e, i) => [e, i]));
		return [...list].sort((a, b) => order.get(a.entry)! - order.get(b.entry)!);
	}, [items, type, q, p.sort]);

	return (
		<>
			<div class="st-toolbar">
				<div class="st-search">
					<IconSearch />
					<input ref={input} class="st-input" type="search" placeholder="Search name, description, author, tags" value={q} onInput={(e) => setQ((e.target as HTMLInputElement).value)} aria-label="Search addons" />
					<kbd>/</kbd>
				</div>
				<div class="st-toolbar-right">
					<select class="st-select" style="width:auto" value={p.sort} onChange={(e) => setPrefs({ sort: (e.target as HTMLSelectElement).value as typeof p.sort })} aria-label="Sort">
						<option value="manifest">Repository order</option>
						<option value="name">Name</option>
						<option value="type">Type</option>
						<option value="version">Version</option>
						<option value="size">Size</option>
					</select>
					<div class="st-seg" role="group" aria-label="View">
						<button aria-pressed={p.view === 'grid'} onClick={() => setPrefs({ view: 'grid' })} title="Grid"><IconGrid /></button>
						<button aria-pressed={p.view === 'list'} onClick={() => setPrefs({ view: 'list' })} title="List"><IconList /></button>
					</div>
				</div>
				<div class="st-chips" role="group" aria-label="Filter by type">
					<button class="st-chip" aria-pressed={type === null} onClick={() => setType(null)}>
						All <span class="st-count">{items.length}</span>
					</button>
					{present.map((t) => {
						const info = typeInfo(t);
						return (
							<button class="st-chip" key={t} data-type={t} style={{ '--chip-hue': `#${info.hue}` }} aria-pressed={type === t} onClick={() => setType(type === t ? null : t)}>
								<span class="st-dot" /> {info.plural} <span class="st-count">{counts[t]}</span>
							</button>
						);
					})}
				</div>
			</div>
			{filtered.length === 0 ? (
				<Empty icon={<IconSearch />} title="Nothing matches">
					<p class="st-small">Try another word, or clear the type filter.</p>
				</Empty>
			) : (
				<div class={p.view === 'list' ? 'st-list' : 'st-grid'}>
					{filtered.map(({ repo, entry }) => (
						<AddonCard key={`${repo.ref.url}#${entry.id}`} repo={repo} entry={entry} showRepo={showRepo} />
					))}
				</div>
			)}
		</>
	);
}

export function RepoHero({ repo }: { repo: LoadedRepo }) {
	const m = repo.manifest;
	const desc = describeManifestUrl(repo.ref.url);
	const listed = repoByUrl.value.has(repo.ref.url) && repos.value.some((r) => r.ref.url === repo.ref.url);
	const types = m ? new Set(m.addons.map((a) => typeInfo(a.type).category)).size : 0;
	const bytes = m ? m.addons.reduce((n, a) => n + (a.sizeBytes ?? 0), 0) : 0;
	const homepage = m?.repo.homepage && /^https:\/\//i.test(m.repo.homepage) ? m.repo.homepage : desc.page;
	return (
		<header class="st-repo-hero">
			<RepoIcon repo={repo} />
			<div class="st-hero-text">
				<h1>{m?.repo.name ?? desc.label}</h1>
				{m?.repo.description && <p class="st-hero-desc">{m.repo.description}</p>}
				<div class="st-hero-meta">
					{m?.repo.author && <span>by {m.repo.author}</span>}
					{homepage && (
						<a href={homepage} target="_blank" rel="noopener">
							{desc.label} <IconExternal style="width:0.75rem;height:0.75rem;vertical-align:-0.05em" />
						</a>
					)}
					{m?.repo.updatedAt && <span>updated {fmtDate(m.repo.updatedAt)}</span>}
					<span title={repo.ref.url}>id <code>{m?.repo.id ?? '—'}</code></span>
					<span>{repo.loading ? <Spinner label="refreshing…" /> : `fetched ${relTime(repo.fetchedAt)}`}</span>
				</div>
				{m && (
					<div class="st-stats">
						<span class="st-stat"><strong>{m.addons.length}</strong> addons</span>
						<span class="st-stat"><strong>{types}</strong> types</span>
						{bytes > 0 && <span class="st-stat"><strong>{fmtBytes(bytes)}</strong> total</span>}
						{unseenCount(repo) > 0 && <span class="st-stat"><strong>{unseenCount(repo)}</strong> new or updated</span>}
					</div>
				)}
			</div>
			<div class="st-hero-actions">
				{!listed && (
					<button class="st-btn st-btn-primary st-btn-sm" onClick={async () => { const r = await addRepo(repo.ref.input); if (r.ok) showToast('Added to your repositories.'); else showToast(r.error, 'err'); }}>
						<IconPlus /> Add to my repositories
					</button>
				)}
				{onAndroid.value && (
					<a class="st-btn st-btn-sm" href={appLinkRepo(repo.ref.input)}>
						<IconPhone /> Open in app
					</a>
				)}
				<button class="st-btn st-btn-sm st-btn-ghost" onClick={() => refreshRepo(repo.ref.url)} disabled={repo.loading} title="Refetch the manifest">
					<IconRefresh /> Refresh
				</button>
				<Link to={{ view: 'check', repo: repo.ref.url }} class="st-btn st-btn-sm st-btn-ghost" title="Validate this repository">
					<IconShield /> Check
				</Link>
				{listed && <RemoveRepoButton repo={repo} />}
			</div>
		</header>
	);
}

export function Catalogue({ repoUrl }: { repoUrl: string }) {
	const repo = allRepos.value.find((r) => r.ref.url === repoUrl) ?? null;
	useEffect(() => {
		if (repo?.manifest) {
			seedSeen(repoUrl);
			const t = setTimeout(() => markSeen(repoUrl), 4000);
			return () => clearTimeout(t);
		}
	}, [repoUrl, repo?.manifest]);

	if (!repo) {
		return (
			<div class="st-page">
				<Empty icon={<IconWarn />} title="Unknown repository">
					<p class="st-small">This address isn't in your list and couldn't be opened.</p>
					<p style="margin-top:0.8rem"><Link to={{ view: 'home' }} class="st-btn st-btn-sm">Back to the store</Link></p>
				</Empty>
			</div>
		);
	}
	const items = repo.manifest ? repo.manifest.addons.map((entry) => ({ repo, entry })) : [];
	return (
		<div class="st-page">
			<RepoHero repo={repo} />
			{repo.error && (
				<div style="margin-top:1rem">
					<Notice kind="err" icon={<IconWarn />}>
						<b>Couldn't refresh.</b> {repo.error} {repo.manifest && <span class="st-muted">Showing the last copy this browser fetched.</span>}
					</Notice>
				</div>
			)}
			{repo.dropped.length > 0 && (
				<div style="margin-top:0.6rem">
					<Notice kind="warn" icon={<IconWarn />}>
						{repo.dropped.length} entr{repo.dropped.length === 1 ? 'y' : 'ies'} skipped, as the app would: {repo.dropped.map((d) => d.reason).join('; ')}.
					</Notice>
				</div>
			)}
			{!repo.manifest && !repo.error && (
				<div class="st-grid" style="margin-top:1.2rem" aria-busy="true">
					{Array.from({ length: 8 }, (_, i) => <div class="st-skeleton" style="aspect-ratio:4/4.6" key={i} />)}
				</div>
			)}
			{repo.manifest && (repo.manifest.addons.length ? <CatalogueGrid items={items} /> : (
				<Empty icon={<TypeIcon type="theme" />} title="This repository lists no addons yet" />
			))}
		</div>
	);
}

/** The merged catalogue when no repository is selected. */
export function AllCatalogue() {
	const items = everyAddon.value;
	const loading = repos.value.some((r) => r.loading && !r.manifest);
	const failed = repos.value.filter((r) => r.error && !r.manifest);
	return (
		<>
			{failed.map((r) => (
				<div style="margin-top:0.8rem" key={r.ref.url}>
					<Notice kind="err" icon={<IconWarn />}>
						<b>{describeManifestUrl(r.ref.url).label}</b>: {r.error}{' '}
						<button class="st-btn st-btn-sm st-btn-ghost" onClick={() => refreshRepo(r.ref.url)}>Retry</button>
					</Notice>
				</div>
			))}
			{loading && items.length === 0 ? (
				<div class="st-grid" style="margin-top:1.2rem" aria-busy="true">
					{Array.from({ length: 8 }, (_, i) => <div class="st-skeleton" style="aspect-ratio:4/4.6" key={i} />)}
				</div>
			) : items.length ? (
				<CatalogueGrid items={items} showRepo />
			) : (
				<Empty icon={<IconPlus />} title="No addons to show">
					<p class="st-small">Add a repository from the switcher at the top left.</p>
					<p style="margin-top:0.8rem"><button class="st-btn st-btn-sm" onClick={() => refreshAll()}>Refresh all</button></p>
				</Empty>
			)}
		</>
	);
}

export { navigate };
