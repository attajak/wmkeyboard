/** Starred addons across repositories: a batch of app links, one QR, a shareable URL. */
import { useEffect, useMemo, useState } from 'preact/hooks';
import { addRepo, clearCollection, collection, findAddon, hydrated, openTransient, repoByUrl, showToast, toggleCollection } from '../state';
import { resolveManifestUrl, describeManifestUrl } from '../lib/resolve';
import { typeInfo } from '../lib/types';
import { absoluteUrl, appLinkAddon, isAndroid } from '../lib/util';
import { AddonCard } from './AddonCard';
import { CopyButton, Empty, Notice, Qr } from './common';
import { IconHeart, IconInfo, IconPhone, IconQr, IconShare, IconTrash } from './icons';

/** Collections travel as `?items=<repoInput>|<id>,…` so a shared link needs no server. */
function encodeShare(items: { repo: string; id: string }[]): string {
	const parts = items.map((k) => `${repoByUrl.value.get(k.repo)?.ref.input ?? k.repo}|${k.id}`);
	return `${absoluteUrl('/addons/collection/')}?items=${encodeURIComponent(parts.join(','))}`;
}

function decodeShare(search: string): { repo: string; id: string; input: string }[] {
	const raw = new URLSearchParams(search).get('items');
	if (!raw) return [];
	return raw
		.split(',')
		.map((p) => {
			const [input, id] = p.split('|');
			const repo = input ? resolveManifestUrl(input) : null;
			return repo && id ? { repo, id, input } : null;
		})
		.filter((x): x is { repo: string; id: string; input: string } => !!x);
}

export function Collection() {
	const [shared, setShared] = useState<{ repo: string; id: string; input: string }[] | null>(null);
	const [qr, setQr] = useState(false);
	useEffect(() => {
		const s = decodeShare(location.search);
		if (s.length) {
			setShared(s);
			for (const k of s) if (!repoByUrl.value.has(k.repo)) openTransient(k.repo);
		}
	}, []);

	const items = shared ?? collection.value;
	const resolved = useMemo(() => items.map((k) => ({ key: k, found: findAddon(k.repo, k.id) })), [items, repoByUrl.value]);
	const links = resolved.filter((r) => r.found).map((r) => appLinkAddon(r.found!.repo.ref.input, r.found!.entry.id));
	const shareUrl = encodeShare(items);

	const importShared = () => {
		for (const k of items) toggleCollection({ repo: k.repo, id: k.id });
		setShared(null);
		history.replaceState(null, '', '/addons/collection/');
		showToast('Added to your collection.');
	};

	return (
		<div class="st-page">
			<div class="st-section-head">
				<div>
					<span class="st-kicker">{shared ? 'Shared collection' : 'Collection'}</span>
					<h1 style="font-size:1.6rem;font-weight:800">{shared ? 'Someone shared these with you' : 'Starred addons'}</h1>
				</div>
				{!shared && items.length > 0 && (
					<div class="st-row">
						<button class="st-btn st-btn-sm" onClick={() => setQr(!qr)}><IconQr /> {qr ? 'Hide QR' : 'QR code'}</button>
						<CopyButton text={shareUrl} label="Copy share link" />
						<button class="st-btn st-btn-sm st-btn-ghost st-btn-danger" onClick={() => { if (confirm('Clear the whole collection?')) clearCollection(); }}><IconTrash /> Clear</button>
					</div>
				)}
				{shared && (
					<div class="st-row">
						<button class="st-btn st-btn-sm st-btn-primary" onClick={importShared}><IconHeart /> Add all to my collection</button>
						<button class="st-btn st-btn-sm st-btn-ghost" onClick={() => { setShared(null); history.replaceState(null, '', '/addons/collection/'); }}>Show mine</button>
					</div>
				)}
			</div>

			{!hydrated.value ? null : items.length === 0 ? (
				<Empty icon={<IconHeart />} title="Nothing starred yet">
					<p class="st-small">Star addons from any repository and they gather here. Then send the whole set to your phone at once.</p>
				</Empty>
			) : (
				<>
					{qr && !shared && (
						<div class="st-panel" style="margin-bottom:1rem;display:flex;flex-wrap:wrap;gap:1rem;align-items:center">
							<Qr text={shareUrl} caption="Opens this collection on the phone. From there, every addon has its Open in app button." />
							<div class="st-small st-muted" style="flex:1 1 16rem">
								A collection is {links.length} separate installs in the app; there is no batch install, by design. This code opens the same list on the phone's browser, one tap from each addon's page in the app.
							</div>
						</div>
					)}
					{isAndroid() && links.length > 0 && !shared && (
						<Notice icon={<IconPhone />}>
							Open each in the app: {resolved.filter((r) => r.found).map((r, i) => (
								<span key={i}>{i > 0 && ' · '}<a href={appLinkAddon(r.found!.repo.ref.input, r.found!.entry.id)}>{r.found!.entry.name}</a></span>
							))}
						</Notice>
					)}
					<div class="st-grid" style="margin-top:1rem">
						{resolved.map(({ key, found }) => found ? (
							<AddonCard key={`${key.repo}#${key.id}`} repo={found.repo} entry={found.entry} showRepo />
						) : (
							<div class="st-card" key={`${key.repo}#${key.id}`} style="padding:0.9rem">
								<b>{key.id}</b>
								<div class="st-small st-muted" style="margin-top:0.3rem">{describeManifestUrl(key.repo).label}</div>
								<div class="st-small st-muted">{repoByUrl.value.get(key.repo)?.loading ? 'Loading repository…' : repoByUrl.value.get(key.repo)?.error ?? 'Not in the repository any more'}</div>
								<div class="st-row" style="margin-top:0.6rem">
									{!repoByUrl.value.get(key.repo)?.manifest && !repoByUrl.value.get(key.repo)?.loading && (
										<button class="st-btn st-btn-sm" onClick={() => addRepo((shared?.find((s) => s.repo === key.repo)?.input) ?? key.repo)}>Add repository</button>
									)}
									{!shared && <button class="st-btn st-btn-sm st-btn-ghost" onClick={() => toggleCollection(key)}>Remove</button>}
								</div>
							</div>
						))}
					</div>
					<p class="st-small st-muted" style="margin-top:1.2rem"><IconInfo style="width:0.9rem;height:0.9rem;vertical-align:-0.15em" /> Kept in this browser only. Share the link to move it to another device.</p>
				</>
			)}
		</div>
	);
}

export { IconShare };
