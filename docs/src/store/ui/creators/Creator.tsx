/** /addons/new: the builders hub, and the builder `?type=` names. */
import { navigate } from '../../state';
import { Link } from '../common';
import { IconLayers, IconWand, TypeIcon } from '../icons';
import { RepoBuilder } from './RepoBuilder';
import { SnippetsBuilder } from './SnippetsBuilder';
import { StickersBuilder } from './StickersBuilder';
import { SoundPackBuilder } from './SoundPackBuilder';
import { IconPackBuilder } from './IconPackBuilder';
import { ThemeEditor } from './ThemeEditor';
import { LayoutEditor } from './LayoutEditor';

const BUILDERS: { type: string; title: string; blurb: string; hue: string; icon: string }[] = [
	{ type: 'repo', title: 'Repository', blurb: 'Index your addons into a wmkeyboard-repo.json with checksums and sizes filled in, ready to host.', hue: '4C8DF6', icon: 'repo' },
	{ type: 'theme', title: 'Theme', blurb: 'Colours, shapes, gradients, textures and effects with a live keyboard.', hue: '7E57C2', icon: 'theme' },
	{ type: 'layout', title: 'Layout', blurb: 'Key rows and layers, edited on the keyboard itself.', hue: '3B82F6', icon: 'layout' },
	{ type: 'snippets', title: 'Snippet pack', blurb: 'Triggers, expansions, patterns and folders.', hue: 'F59E0B', icon: 'snippets' },
	{ type: 'stickers', title: 'Sticker pack', blurb: 'Drop images, name them, get a .wmstickers.', hue: 'EC4899', icon: 'stickers' },
	{ type: 'sound_pack', title: 'Sound pack', blurb: 'Press and release samples per key role.', hue: 'F97316', icon: 'sound_pack' },
	{ type: 'icon_pack', title: 'Icon pack', blurb: 'One SVG per slot, every tool and key the app draws.', hue: '22A559', icon: 'icon_pack' },
];

export function Creator({ type }: { type?: string }) {
	if (type) {
		const b = BUILDERS.find((x) => x.type === type);
		return (
			<div class="st-page">
				<div class="st-steps" style="margin-bottom:1.2rem">
					<Link to={{ view: 'new' }} class="st-back" style="margin:0"><IconWand /> All builders</Link>
					{BUILDERS.map((x) => (
						<button key={x.type} aria-current={x.type === type ? 'step' : undefined} onClick={() => navigate({ view: 'new', type: x.type })}>{x.title}</button>
					))}
				</div>
				{type === 'repo' && <RepoBuilder />}
				{type === 'theme' && <ThemeEditor />}
				{type === 'layout' && <LayoutEditor />}
				{type === 'snippets' && <SnippetsBuilder />}
				{type === 'stickers' && <StickersBuilder />}
				{type === 'sound_pack' && <SoundPackBuilder />}
				{type === 'icon_pack' && <IconPackBuilder />}
				{!b && <p class="st-muted">No builder for "{type}".</p>}
			</div>
		);
	}
	return (
		<div class="st-page">
			<header class="st-home-hero">
				<div>
					<span class="st-kicker">Create</span>
					<h1>Make something for the keyboard.</h1>
					<p>
						Every builder writes the exact file the app exports itself, so what you make here imports on the phone or goes straight into a repository. Everything runs in this browser; nothing is uploaded. Drafts are kept locally between visits.
					</p>
				</div>
			</header>
			<section class="st-section">
				<div class="st-type-tiles" style="grid-template-columns:repeat(auto-fill,minmax(15rem,1fr))">
					{BUILDERS.map((b) => (
						<Link key={b.type} to={{ view: 'new', type: b.type }} class="st-type-tile" style={{ '--type-hue': `#${b.hue}`, alignItems: 'flex-start', padding: '1rem' }}>
							<span class="st-tile-icon">{b.icon === 'repo' ? <IconLayers /> : <TypeIcon type={b.icon} />}</span>
							<span>
								<b style="font-size:1rem">{b.title}</b>
								<small style="display:block;margin-top:0.2rem;line-height:1.35">{b.blurb}</small>
							</span>
						</Link>
					))}
				</div>
			</section>
			<section class="st-section">
				<div class="st-panel st-small st-muted">
					Dictionaries, emoji keyword packs, fonts, single key sounds and vocabulary packs need no builder: they are plain files (a word list, a TSV, a .ttf, an .mp3, a .wmvocab.json) you list in a repository as-is. Plugins are written in Lua; see the <a href="/plugins/">plugin docs</a>.
				</div>
			</section>
		</div>
	);
}
