/**
 * Root of the addon store island. Every /addons/* page mounts this with the
 * route the URL names; from then on navigation is client-side (pushState),
 * so a prerendered official addon page and a ?repo= page are the same app.
 */
import { useEffect } from 'preact/hooks';
import { hydrate, initStore, route, toast, type StoreInit, wireHistory } from '../state';
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

export default function StoreApp(props: { init: StoreInit }) {
	initStore(props.init);
	useEffect(() => {
		hydrate();
		wireHistory();
		document.documentElement.setAttribute('data-wm-store', '');
	}, []);

	const r = route.value;
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
			{view}
			{t && (
				<div class={`st-toast ${t.kind === 'err' ? 'err' : ''}`} role="status">
					{t.text}
				</div>
			)}
		</div>
	);
}
