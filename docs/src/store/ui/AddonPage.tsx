/** One addon: gallery, description, actions, details, licence, and the live payload preview. */
import { useEffect, useState } from 'preact/hooks';
import { addRepo, allRepos, appVersion, findAddon, inCollection, navigate, noveltyOf, repos, showToast, toggleCollection } from '../state';
import { fetchText, fmtBytes, MAX_TEXT_BYTES, NetError } from '../lib/net';
import { describeManifestUrl, resolveAsset } from '../lib/resolve';
import { typeInfo, type AddonEntry, type LoadedRepo } from '../lib/types';
import { absoluteUrl, appLinkAddon, canWebShare, isAndroid, payloadFileName, payloadUrl, previewUrls } from '../lib/util';
import { languageName } from '../lib/languages';
import { CopyButton, Dialog, Empty, Link, Notice, Qr, Spinner } from './common';
import { IconBack, IconCopy, IconDownload, IconExternal, IconPhone, IconPlus, IconQr, IconShare, IconStar, IconWarn, TypeIcon } from './icons';
import { Preview } from './previews/Preview';

export function AddonPage({ repoUrl, addonId }: { repoUrl: string; addonId: string }) {
	const repo = allRepos.value.find((r) => r.ref.url === repoUrl) ?? null;
	const found = findAddon(repoUrl, addonId);
	if (!repo) {
		return (
			<div class="st-page st-page-narrow">
				<Empty icon={<IconWarn />} title="Unknown repository">
					<p><Link to={{ view: 'home' }} class="st-btn st-btn-sm">Back to the store</Link></p>
				</Empty>
			</div>
		);
	}
	if (!found) {
		return (
			<div class="st-page st-page-narrow">
				<Link to={{ view: 'repo', repo: repoUrl }} class="st-back"><IconBack /> Back to repository</Link>
				{repo.loading || (!repo.manifest && !repo.error) ? (
					<Spinner label="Fetching the repository…" />
				) : repo.error && !repo.manifest ? (
					<Notice kind="err" icon={<IconWarn />}>{repo.error}</Notice>
				) : (
					<Empty icon={<IconWarn />} title="This addon isn't in the repository">
						<p class="st-small">The repository lists nothing with the id <code>{addonId}</code>. It may have been renamed or removed.</p>
					</Empty>
				)}
			</div>
		);
	}
	return <AddonDetail repo={found.repo} entry={found.entry} />;
}

function AddonDetail({ repo, entry }: { repo: LoadedRepo; entry: AddonEntry }) {
	const info = typeInfo(entry.type);
	const shots = previewUrls(repo.ref.url, entry);
	const [lightbox, setLightbox] = useState<string | null>(null);
	const [qr, setQr] = useState(false);
	const [license, setLicense] = useState(false);
	const key = { repo: repo.ref.url, id: entry.id };
	const starred = inCollection(key);
	const listed = repos.value.some((r) => r.ref.url === repo.ref.url);
	const appLink = appLinkAddon(repo.ref.input, entry.id);
	const webUrl = absoluteUrl(hrefOf(repo, entry));
	const payload = payloadUrl(repo.ref.url, entry);
	const desc = describeManifestUrl(repo.ref.url);
	const tooOld = entry.minAppVersion != null && entry.minAppVersion > appVersion.value.code;
	const novelty = noveltyOf(repo.ref.url, entry);
	const requires = entry.requires.map((id) => repo.manifest?.addons.find((a) => a.id === id) ?? null);
	const langs = [entry.langId, ...entry.langIds].filter((x): x is string => !!x);

	useEffect(() => {
		document.title = `${entry.name} · ${info.singular} · WM Keyboard addons`;
	}, [entry, info]);

	const share = async () => {
		if (canWebShare()) {
			try {
				await navigator.share({ title: entry.name, text: entry.description, url: webUrl });
				return;
			} catch {
				/* cancelled */
			}
		}
		await navigator.clipboard.writeText(webUrl).then(() => showToast('Link copied.')).catch(() => prompt('Copy this link:', webUrl));
	};

	return (
		<div class="st-page has-sticky" style={{ '--type-hue': `#${info.hue}` }}>
			<Link to={{ view: 'repo', repo: repo.ref.url }} class="st-back">
				<IconBack /> {repo.manifest?.repo.name ?? desc.label}
			</Link>
			<div class="st-detail">
				<div class="st-detail-main">
					<div class="st-detail-head">
						<div style="flex:1 1 20rem;min-width:0">
							<div class="st-row" style="gap:0.4rem;margin-bottom:0.4rem">
								<span class="st-tag st-tag-type">{info.singular}</span>
								{novelty && <span class={`st-pill st-pill-${novelty}`}>{novelty}</span>}
								{entry.type === 'plugin' && <span class="st-pill st-pill-muted">runs Lua, sandboxed</span>}
							</div>
							<h1>{entry.name}</h1>
							<div class="st-detail-meta">
								<span>v{entry.version}</span>
								{entry.sizeBytes != null && <span>{fmtBytes(entry.sizeBytes)}</span>}
								{entry.author && <span>by {entry.author}</span>}
								{entry.license && <button class="st-btn st-btn-ghost st-btn-sm" style="padding:0.1rem 0.5rem" onClick={() => setLicense(true)}>{entry.license}</button>}
							</div>
						</div>
					</div>

					{shots.length > 0 && (
						<div class={`st-gallery ${shots.length === 1 ? 'single' : ''}`}>
							{shots.map((s) => (
								<button key={s} onClick={() => setLightbox(s)} aria-label="Open preview image">
									<img src={s} alt={`${entry.name} preview`} loading="lazy" decoding="async" />
								</button>
							))}
						</div>
					)}
					{shots.length === 0 && (
						<div class="st-card-media" style="aspect-ratio:16/6;border-radius:var(--st-radius);margin-bottom:1.2rem;border:1px solid var(--st-card-border)">
							<span class="st-glyph"><TypeIcon type={entry.type} style="width:18%;height:auto" /></span>
						</div>
					)}

					{entry.description && <p class="st-detail-desc">{entry.description}</p>}
					{entry.tags.length > 0 && (
						<div class="st-tags">
							{entry.tags.map((t) => <span class="st-tag" key={t}>#{t}</span>)}
						</div>
					)}

					{tooOld && (
						<div style="margin-top:1rem">
							<Notice kind="warn" icon={<IconWarn />}>
								Needs app version code {entry.minAppVersion} or newer. The current release is {appVersion.value.name} (code {appVersion.value.code}), so the app refuses to install this until it's updated.
							</Notice>
						</div>
					)}
					{entry.type === 'plugin' && !entry.sha256 && (
						<div style="margin-top:1rem">
							<Notice kind="err" icon={<IconWarn />}>
								A plugin without a <code>sha256</code> is refused by the app before any download. This repository must publish one.
							</Notice>
						</div>
					)}
					{requires.length > 0 && (
						<div style="margin-top:1rem">
							<Notice icon={<IconPlus />}>
								<b>Goes with:</b>{' '}
								{requires.map((r, i) => r ? (
									<span key={r.id}>
										{i > 0 && ', '}
										<Link to={{ view: 'addon', repo: repo.ref.url, id: r.id }}>{r.name}</Link> <span class="st-muted">({typeInfo(r.type).singular.toLowerCase()})</span>
									</span>
								) : (
									<span key={String(i)}>{i > 0 && ', '}<code>{entry.requires[i]}</code> <span class="st-muted">(not in this repository)</span></span>
								))}
								<span class="st-muted">. The app offers to download these alongside.</span>
							</Notice>
						</div>
					)}

					<Preview repo={repo} entry={entry} />
				</div>

				<aside class="st-detail-side">
					<div class="st-panel">
						<div class="st-actions">
							{isAndroid() ? (
								<a class="st-btn st-btn-primary st-btn-block" href={appLink}>
									<IconPhone /> Open in WM Keyboard
								</a>
							) : (
								<button class="st-btn st-btn-primary st-btn-block" onClick={() => setQr(!qr)}>
									<IconQr /> {qr ? 'Hide QR code' : 'Send to phone'}
								</button>
							)}
							{qr && !isAndroid() && <Qr text={appLink} caption="Scan with the phone WM Keyboard is on. The app opens this addon's page; nothing installs until you tap Install there." />}
							<div class="st-actions-row">
								<button class="st-btn" aria-pressed={starred} onClick={() => { toggleCollection(key); showToast(starred ? 'Removed from collection.' : 'Added to collection.'); }}>
									<IconStar filled={starred} style={starred ? 'color:var(--st-warn)' : ''} /> {starred ? 'Starred' : 'Star'}
								</button>
								<button class="st-btn" onClick={share}>
									<IconShare /> Share
								</button>
							</div>
							{payload && (
								<a class="st-btn" href={payload} download={payloadFileName(entry)} target="_blank" rel="noopener">
									<IconDownload /> Download {info.payload.split(' ')[0]}
								</a>
							)}
							<details class="st-small">
								<summary class="st-muted" style="cursor:pointer">Links</summary>
								<div class="st-row" style="margin-top:0.5rem;gap:0.35rem">
									<CopyButton text={webUrl} label="Copy web link" class="st-btn st-btn-sm" />
									<CopyButton text={appLink} label="Copy wmkeyboard:// link" class="st-btn st-btn-sm" />
								</div>
								<p class="st-muted" style="margin-top:0.5rem;font-size:0.76rem">
									The <code>wmkeyboard://</code> link only opens the addon's page in the app. It never adds the repository or installs.
								</p>
							</details>
						</div>
					</div>

					<div class="st-panel">
						<h3>Details</h3>
						<dl class="st-kv">
							<dt>Repository</dt>
							<dd>
								<Link to={{ view: 'repo', repo: repo.ref.url }}>{repo.manifest?.repo.name ?? desc.label}</Link>
								<div class="st-muted st-small">{desc.host}{!listed && ' · not in your list'}</div>
								{!listed && (
									<button class="st-btn st-btn-sm" style="margin-top:0.4rem" onClick={async () => { const r = await addRepo(repo.ref.input); showToast(r.ok ? 'Repository added.' : r.error, r.ok ? 'ok' : 'err'); }}>
										<IconPlus /> Add repository
									</button>
								)}
							</dd>
							<dt>Id</dt>
							<dd><code>{repo.manifest?.repo.id}/{entry.id}</code></dd>
							<dt>Version</dt>
							<dd>{entry.version}</dd>
							<dt>Size</dt>
							<dd>{entry.sizeBytes != null ? fmtBytes(entry.sizeBytes) : <span class="st-muted">not stated</span>} <span class="st-muted">· cap {fmtBytes(info.maxBytes)}</span></dd>
							{langs.length > 0 && (
								<>
									<dt>Language{langs.length > 1 ? 's' : ''}</dt>
									<dd>{langs.map(languageName).join(', ')}</dd>
								</>
							)}
							<dt>Licence</dt>
							<dd>
								{entry.license || entry.licenseText || entry.licenseFile ? (
									<button class="st-btn st-btn-sm st-btn-ghost" style="padding:0 0.4rem;margin:-0.1rem 0" onClick={() => setLicense(true)}>{entry.license ?? 'Read'}</button>
								) : (
									<span class="st-muted">not stated</span>
								)}
							</dd>
							<dt>Checksum</dt>
							<dd>
								{entry.sha256 ? <code title={entry.sha256}>sha256 {entry.sha256.slice(0, 12)}…</code> : <span class="st-muted">none · the app installs it unverified</span>}
							</dd>
							{entry.minAppVersion != null && (
								<>
									<dt>Min app</dt>
									<dd>version code {entry.minAppVersion}{tooOld && <span class="st-pill st-pill-warn" style="margin-left:0.4rem">newer than {appVersion.value.name}</span>}</dd>
								</>
							)}
							<dt>Payload</dt>
							<dd>
								{payload ? (
									<a href={payload} target="_blank" rel="noopener" style="overflow-wrap:anywhere">{payloadFileName(entry)} <IconExternal style="width:0.75rem;height:0.75rem;vertical-align:-0.05em" /></a>
								) : (
									<span class="st-pill st-pill-err">refused path</span>
								)}
							</dd>
							<dt>After install</dt>
							<dd class="st-muted">{info.asksToApply ? `the app asks whether to switch to it` : `it appears under ${info.useLabel}`}</dd>
						</dl>
					</div>
				</aside>
			</div>

			<div class="st-sticky-actions">
				{isAndroid() ? (
					<a class="st-btn st-btn-primary" href={appLink}><IconPhone /> Open in app</a>
				) : (
					<button class="st-btn st-btn-primary" onClick={() => { setQr(true); document.querySelector('.st-detail-side')?.scrollIntoView({ behavior: 'smooth' }); }}><IconQr /> Send to phone</button>
				)}
				<button class="st-btn" onClick={() => toggleCollection(key)} aria-pressed={starred}><IconStar filled={starred} /></button>
				<button class="st-btn" onClick={share}><IconShare /></button>
			</div>

			{lightbox && (
				<div class="st-lightbox" onClick={() => setLightbox(null)} role="dialog" aria-label="Preview">
					<img src={lightbox} alt="" />
				</div>
			)}
			{license && <LicenseDialog repo={repo} entry={entry} onClose={() => setLicense(false)} />}
		</div>
	);
}

function hrefOf(repo: LoadedRepo, entry: AddonEntry): string {
	return repo.ref.slug ? `/addons/${repo.ref.slug}/${encodeURIComponent(entry.id)}/` : `/addons/?repo=${encodeURIComponent(repo.ref.input)}&id=${encodeURIComponent(entry.id)}`;
}

function LicenseDialog({ repo, entry, onClose }: { repo: LoadedRepo; entry: AddonEntry; onClose: () => void }) {
	const [text, setText] = useState<string | null>(entry.licenseText ?? null);
	const [err, setErr] = useState<string | null>(null);
	const fileUrl = entry.licenseFile ? resolveAsset(repo.ref.url, entry.licenseFile) : null;
	useEffect(() => {
		if (text || !fileUrl) return;
		fetchText(fileUrl, { maxBytes: MAX_TEXT_BYTES })
			.then(setText)
			.catch((e) => setErr(e instanceof NetError ? e.message : String(e)));
	}, [fileUrl, text]);
	return (
		<Dialog title={entry.license ?? 'Licence'} onClose={onClose} wide>
			{entry.licenseFile && !fileUrl && <Notice kind="err" icon={<IconWarn />}>The licence file path is one the app refuses (must be https or relative and inside the repository).</Notice>}
			{err && <Notice kind="err" icon={<IconWarn />}>{err}</Notice>}
			{text ? <Linkified text={text} /> : !err && (fileUrl ? <Spinner label="Fetching licence…" /> : <p class="st-muted">No licence text was published{entry.license ? `; the identifier is ${entry.license}.` : '.'}</p>)}
			{text && <div class="st-row" style="margin-top:0.6rem"><CopyButton text={text} label="Copy" /></div>}
		</Dialog>
	);
}

function Linkified({ text }: { text: string }) {
	const parts = text.split(/(https?:\/\/[^\s)]+)/g);
	return (
		<div class="st-license-text">
			{parts.map((p, i) => (/^https?:\/\//.test(p) ? <a key={i} href={p} target="_blank" rel="noopener">{p}</a> : p))}
		</div>
	);
}

export { IconCopy };
