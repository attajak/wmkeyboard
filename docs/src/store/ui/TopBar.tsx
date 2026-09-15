/** Sticky bar under the site header: repository switcher (left), nav (right). */
import { useEffect, useRef, useState } from 'preact/hooks';
import {
	activeRepo,
	addRepo,
	allRepos,
	collection,
	navigate,
	removeRepo,
	repos,
	restoreSeeds,
	removedSeeds,
	route,
	setActiveRepo,
	showToast,
	unseenCount,
	type Route,
} from '../state';
import { resolveManifestUrl, describeManifestUrl } from '../lib/resolve';
import { MAX_REPOS } from '../lib/storage';
import type { LoadedRepo } from '../lib/types';
import { Dialog, Link, RepoIcon } from './common';
import { IconChevron, IconCompare, IconHeart, IconLayers, IconPlus, IconPulse, IconRefresh, IconShield, IconSparkle, IconWand } from './icons';

function repoTitle(r: LoadedRepo): string {
	return r.manifest?.repo.name ?? describeManifestUrl(r.ref.url).label;
}

function repoSub(r: LoadedRepo): string {
	if (r.error && !r.manifest) return 'unreadable';
	if (r.loading && !r.manifest) return 'loading…';
	if (!r.manifest) return describeManifestUrl(r.ref.url).host;
	const n = r.manifest.addons.length;
	return `${n} addon${n === 1 ? '' : 's'}${r.manifest.repo.author ? ` · ${r.manifest.repo.author}` : ''}`;
}

export function TopBar() {
	const [open, setOpen] = useState(false);
	const [adding, setAdding] = useState(false);
	const wrap = useRef<HTMLDivElement>(null);
	const r = route.value;
	const current = r.view === 'repo' || r.view === 'addon' ? (allRepos.value.find((x) => x.ref.url === r.repo) ?? null) : activeRepo.value ? (allRepos.value.find((x) => x.ref.url === activeRepo.value) ?? null) : null;

	useEffect(() => {
		if (!open) return;
		const onDoc = (e: MouseEvent) => {
			if (!wrap.current?.contains(e.target as Node)) setOpen(false);
		};
		const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
		document.addEventListener('mousedown', onDoc);
		document.addEventListener('keydown', onKey);
		return () => {
			document.removeEventListener('mousedown', onDoc);
			document.removeEventListener('keydown', onKey);
		};
	}, [open]);

	const choose = (url: string | null) => {
		setOpen(false);
		setActiveRepo(url);
		navigate(url ? { view: 'repo', repo: url } : { view: 'home' });
	};

	const unseen = repos.value.reduce((n, x) => n + unseenCount(x), 0);
	const isCur = (v: Route['view']) => (r.view === v ? 'page' : undefined);

	return (
		<div class="st-topbar">
			<div class="st-switcher" ref={wrap}>
				<button class="st-switcher-btn" aria-haspopup="listbox" aria-expanded={open} onClick={() => setOpen(!open)}>
					{current ? <RepoIcon repo={current} /> : <span class="st-repo-icon" style="width:1.7rem;height:1.7rem"><IconLayers style="width:1rem;height:1rem" /></span>}
					<span style="min-width:0">
						<span class="st-switcher-name">{current ? repoTitle(current) : 'All repositories'}</span>
						<span class="st-switcher-sub">{current ? repoSub(current) : `${repos.value.length} repositor${repos.value.length === 1 ? 'y' : 'ies'}`}</span>
					</span>
					<IconChevron class="chev" />
				</button>
				{open && (
					<div class="st-menu" role="listbox" aria-label="Repositories">
						<button class="st-menu-item" role="option" aria-selected={!current} onClick={() => choose(null)}>
							<span class="st-repo-icon" style="width:1.9rem;height:1.9rem"><IconLayers style="width:1rem;height:1rem" /></span>
							<span class="st-mi-text">
								<span class="st-mi-name">All repositories</span>
								<span class="st-mi-sub">Everything you've added, in one catalogue</span>
							</span>
						</button>
						<div class="st-menu-sep" />
						{repos.value.map((x) => (
							<button class="st-menu-item" role="option" aria-selected={current?.ref.url === x.ref.url} onClick={() => choose(x.ref.url)} key={x.ref.url}>
								<RepoIcon repo={x} />
								<span class="st-mi-text">
									<span class="st-mi-name">{repoTitle(x)}</span>
									<span class="st-mi-sub">{repoSub(x)}</span>
								</span>
								{unseenCount(x) > 0 && <span class="st-badge-dot">{unseenCount(x)}</span>}
							</button>
						))}
						{repos.value.length === 0 && <div class="st-muted st-small" style="padding:0.5rem 0.7rem">No repositories yet.</div>}
						<div class="st-menu-sep" />
						<div class="st-menu-foot">
							<button class="st-btn st-btn-sm st-btn-primary" onClick={() => { setOpen(false); setAdding(true); }}>
								<IconPlus /> Add repository
							</button>
							{removedSeeds.value.length > 0 && (
								<button class="st-btn st-btn-sm st-btn-ghost" onClick={() => { restoreSeeds(); setOpen(false); }}>
									<IconRefresh /> Restore defaults
								</button>
							)}
						</div>
					</div>
				)}
			</div>
			<div class="st-topbar-spacer" />
			<nav class="st-nav" aria-label="Store">
				<Link to={{ view: 'whatsnew' }} aria-current={isCur('whatsnew')} title="What's new">
					<IconSparkle /> <span class="st-nav-label">What's new</span>
					{unseen > 0 && <span class="st-badge-dot">{unseen}</span>}
				</Link>
				<Link to={{ view: 'collection' }} aria-current={isCur('collection')} title="Collection">
					<IconHeart /> <span class="st-nav-label">Collection</span>
					{collection.value.length > 0 && <span class="st-badge-dot">{collection.value.length}</span>}
				</Link>
				<Link to={{ view: 'compare' }} aria-current={isCur('compare')} title="Compare">
					<IconCompare /> <span class="st-nav-label">Compare</span>
				</Link>
				<Link to={{ view: 'check' }} aria-current={isCur('check')} title="Check a repository">
					<IconShield /> <span class="st-nav-label">Check</span>
				</Link>
				<Link to={{ view: 'new' }} aria-current={isCur('new')} title="Create an addon">
					<IconWand /> <span class="st-nav-label">Create</span>
				</Link>
			</nav>
			{adding && <AddRepoDialog onClose={() => setAdding(false)} />}
		</div>
	);
}

export function AddRepoDialog({ onClose, initial }: { onClose: () => void; initial?: string }) {
	const [text, setText] = useState(initial ?? '');
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState<string | null>(null);
	const resolved = text.trim() ? resolveManifestUrl(text) : null;
	const full = repos.value.length >= MAX_REPOS;

	const submit = async () => {
		if (!resolved || busy) return;
		setBusy(true);
		setError(null);
		const res = await addRepo(text);
		setBusy(false);
		if (res.ok) {
			showToast(res.already ? 'Already in your list.' : 'Repository added.');
			onClose();
			navigate({ view: 'repo', repo: res.url });
		} else setError(res.error);
	};

	return (
		<Dialog
			title="Add a repository"
			desc={
				<>
					Paste a repository address. A GitHub, GitLab, Codeberg or SourceHut page works, so does a direct link to a <code>wmkeyboard-repo.json</code>, or a folder that holds one. It stays in this browser only; nothing is sent anywhere but that host.
				</>
			}
			onClose={onClose}
			actions={
				<>
					<button class="st-btn st-btn-ghost" onClick={onClose}>Cancel</button>
					<button class="st-btn st-btn-primary" disabled={!resolved || busy || full} onClick={submit}>
						{busy ? <span class="st-spinner" /> : <IconPlus />} Add
					</button>
				</>
			}
		>
			<div class="st-field">
				<label for="st-add-url">Repository URL</label>
				<input
					id="st-add-url"
					class="st-input"
					type="url"
					inputMode="url"
					placeholder="github.com/user/repo"
					value={text}
					aria-invalid={!!text.trim() && !resolved}
					onInput={(e) => { setText((e.target as HTMLInputElement).value); setError(null); }}
					onKeyDown={(e) => e.key === 'Enter' && submit()}
					autoFocus
				/>
				{text.trim() && (resolved ? (
					<span class="st-help">Will read <code style="overflow-wrap:anywhere">{resolved}</code></span>
				) : (
					<span class="st-err-text">Not an address the app can read. Only https:// is accepted; plain http is refused rather than upgraded.</span>
				))}
				{full && <span class="st-err-text">The app keeps at most {MAX_REPOS} repositories. Remove one first.</span>}
				{error && <span class="st-err-text">{error}</span>}
			</div>
		</Dialog>
	);
}

export function RemoveRepoButton({ repo, class: cls }: { repo: LoadedRepo; class?: string }) {
	const [confirm, setConfirm] = useState(false);
	return (
		<>
			<button class={cls ?? 'st-btn st-btn-sm st-btn-ghost st-btn-danger'} onClick={() => setConfirm(true)}>Remove</button>
			{confirm && (
				<Dialog
					title="Remove this repository?"
					desc="It's only removed from this browser's list. Nothing on the host changes, and you can add it again any time."
					onClose={() => setConfirm(false)}
					actions={
						<>
							<button class="st-btn st-btn-ghost" onClick={() => setConfirm(false)}>Keep</button>
							<button class="st-btn st-btn-danger" onClick={() => { removeRepo(repo.ref.url); setConfirm(false); showToast('Repository removed.'); navigate({ view: 'home' }); }}>Remove</button>
						</>
					}
				/>
			)}
		</>
	);
}
