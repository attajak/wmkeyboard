/**
 * Root of the addon store island. Every /addons/* page mounts this with the
 * route the URL names; from then on navigation is client-side (pushState),
 * so a prerendered official addon page and a ?repo= page are the same app.
 */
import { Component, type ComponentChildren } from 'preact';
import { useEffect } from 'preact/hooks';
import { findAddon, hrefFor, hydrate, initStore, navigate, repoByUrl, route, toast, type Route, type StoreInit, wireHistory } from '../state';
import { typeInfo } from '../lib/types';
import { Empty } from './common';
import { IconWarn } from './icons';
import { TopBar } from './TopBar';
import { Catalogue } from './Catalogue';
import { AddonPage } from './AddonPage';
import { Home } from './Home';
import { Collection } from './Collection';
import { WhatsNew } from './WhatsNew';
import { lazyView } from './lazy';

const Check = lazyView(() => import('./Check').then((m) => m.Check));
const Compare = lazyView(() => import('./Compare').then((m) => m.Compare));
const Creator = lazyView(() => import('./creators/Creator').then((m) => m.Creator));

let siteSuffix: string | null = null;

export default function StoreApp(props: { init: StoreInit }) {
	initStore(props.init);
	useEffect(() => {
		hydrate();
		wireHistory();
		document.documentElement.setAttribute('data-wm-store', '');
	}, []);

	const r = route.value;
	const title = titleFor(r);
	useEffect(() => {
		// Keep the site suffix the server rendered (" | WM Keyboard"), read once.
		siteSuffix ??= document.title.match(/ \| [^|]+$/)?.[0] ?? '';
		document.title = title + siteSuffix;
	}, [title]);
	let view;
	switch (r.view) {
		case 'home':
			view = <Home />;
			break;
		case 'repo':
			view = <Catalogue repoUrl={r.repo} />;
			break;
		case 'addon':
			view = <AddonPage repoUrl={r.repo} addonId={r.id} />;
			break;
		case 'collection':
			view = <Collection />;
			break;
		case 'whatsnew':
			view = <WhatsNew />;
			break;
		case 'check':
			view = <Check repoUrl={r.repo} />;
			break;
		case 'compare':
			view = <Compare />;
			break;
		case 'new':
			view = <Creator type={r.type} />;
			break;
	}

	const t = toast.value;
	return (
		<div class="st-root not-content">
			<TopBar />
			<ViewBoundary key={hrefFor(r)}>{view}</ViewBoundary>
			{t && (
				<div class={`st-toast ${t.kind === 'err' ? 'err' : ''}`} role="status">
					{t.text}
				</div>
			)}
		</div>
	);
}

function titleFor(r: Route): string {
	switch (r.view) {
		case 'home':
			return 'Addon store';
		case 'repo':
			return `${repoByUrl.value.get(r.repo)?.manifest?.repo.name ?? 'Repository'} · Addon store`;
		case 'addon': {
			const found = findAddon(r.repo, r.id);
			return found ? `${found.entry.name} · ${typeInfo(found.entry.type).singular}` : 'Addon store';
		}
		case 'collection':
			return 'Collection · Addon store';
		case 'whatsnew':
			return "What's new · Addon store";
		case 'check':
			return 'Check · Addon store';
		case 'compare':
			return 'Compare · Addon store';
		case 'new':
			return 'Create · Addon store';
	}
}

/**
 * A view that throws shows an error card instead of taking the whole island
 * (top bar included) down to a blank page. Keyed by URL, so navigating resets it.
 */
class ViewBoundary extends Component<{ children: ComponentChildren }, { error: string | null }> {
	state = { error: null as string | null };
	static getDerivedStateFromError(e: unknown) {
		return { error: e instanceof Error ? e.message : String(e) };
	}
	componentDidCatch(e: unknown) {
		console.error(e);
	}
	render() {
		if (!this.state.error) return this.props.children;
		return (
			<div class="st-page st-page-narrow">
				<Empty icon={<IconWarn />} title="This page hit a problem">
					<p class="st-small"><code>{this.state.error}</code></p>
					<p>
						<button class="st-btn st-btn-sm" onClick={() => location.reload()}>Reload</button>{' '}
						<button class="st-btn st-btn-sm st-btn-ghost" onClick={() => navigate({ view: 'home' })}>Back to the store</button>
					</p>
				</Empty>
			</div>
		);
	}
}
