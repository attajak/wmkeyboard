/** /addons/ with no repository selected: a short hero, type tiles, the merged catalogue. */
import { useState } from 'preact/hooks';
import { everyAddon, hydrated, prefs, repos, setPrefs } from '../state';
import { ADDON_TYPES, typeInfo } from '../lib/types';
import { AllCatalogue } from './Catalogue';
import { Link, Notice } from './common';
import { IconInfo, IconPlus, IconWand, TypeIcon } from './icons';
import { AddRepoDialog } from './TopBar';

export function Home() {
	const [adding, setAdding] = useState(false);
	const items = everyAddon.value;
	const counts: Record<string, number> = {};
	for (const { entry } of items) {
		const c = typeInfo(entry.type).category;
		counts[c] = (counts[c] ?? 0) + 1;
	}
	const p = prefs.value;
	return (
		<div class="st-page">
			<header class="st-home-hero">
				<div>
					<span class="st-kicker">Addon store</span>
					<h1>Everything the keyboard can wear.</h1>
					<p>
						Themes, layouts, dictionaries, fonts, sounds, sticker packs, icon packs and plugins, from any repository you point it at.
						Browse here, install on your phone: the app does the downloading, and a link from this page can never install anything by itself.
					</p>
				</div>
				<div class="st-hero-cta">
					<button class="st-btn st-btn-primary" onClick={() => setAdding(true)}>
						<IconPlus /> Add a repository
					</button>
					<Link to={{ view: 'new' }} class="st-btn">
						<IconWand /> Create an addon
					</Link>
				</div>
			</header>

			{hydrated.value && !p.introDismissed && (
				<div style="margin-top:1rem">
					<Notice icon={<IconInfo />}>
						<b>This is the app's Addons screen, on the web.</b> Repositories you add and addons you star are kept in this browser. On Android, every addon page has an <i>Open in app</i> button; elsewhere, scan the QR code or download the file for manual import.{' '}
						<button class="st-btn st-btn-sm st-btn-ghost" onClick={() => setPrefs({ introDismissed: true })}>Got it</button>
					</Notice>
				</div>
			)}

			{items.length > 0 && (
				<section class="st-section">
					<div class="st-section-head">
						<h2>Browse by type</h2>
						<span class="st-sub">{repos.value.length} repositor{repos.value.length === 1 ? 'y' : 'ies'} · {items.length} addons</span>
					</div>
					<div class="st-type-tiles">
						{ADDON_TYPES.filter((t) => counts[t]).map((t) => {
							const info = typeInfo(t);
							return (
								<a class="st-type-tile" key={t} href={`#type-${t}`} style={{ '--type-hue': `#${info.hue}` }} onClick={(e) => { e.preventDefault(); document.querySelector<HTMLButtonElement>(`.st-chip[data-type="${t}"]`)?.click(); document.getElementById('st-catalogue')?.scrollIntoView({ behavior: 'smooth', block: 'start' }); }}>
									<span class="st-tile-icon"><TypeIcon type={t} /></span>
									<span>
										<b>{info.plural}</b>
										<small>{counts[t]} available</small>
									</span>
								</a>
							);
						})}
					</div>
				</section>
			)}

			<section class="st-section" id="st-catalogue">
				<div class="st-section-head">
					<h2>All addons</h2>
				</div>
				<AllCatalogue />
			</section>
			{adding && <AddRepoDialog onClose={() => setAdding(false)} />}
		</div>
	);
}
