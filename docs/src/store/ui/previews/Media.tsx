/** Font, sound, sound-pack, sticker and icon-pack previews. */
import { useEffect, useRef, useState } from 'preact/hooks';
import { fmtBytes } from '../../lib/net';
import { slotLabel, ICON_SLOT_GROUPS, ICON_SLOTS } from '../../lib/icon-slots';
import type { FontRead, IconPackRead, SoundPackRead, SoundSample, StickerPackRead } from '../../lib/payloads';
import { IconPause, IconPlay } from '../icons';
import { Problems } from './Preview';

/* ---------- font ---------- */

const SAMPLES: Record<string, string> = {
	latin: 'Sphinx of black quartz, judge my vow. 0123456789',
	cyrillic: 'Съешь же ещё этих мягких французских булок, да выпей чаю.',
	greek: 'Ξεσκεπάζω την ψυχοφθόρα βδελυγμία.',
	bengali: 'আমি বাংলায় গান গাই — আমি বাংলার গান গাই।',
	arabic: 'نص حكيم له سر قاطع وذو شأن عظيم مكتوب على ثوب أخضر',
	devanagari: 'ऋषियों को सताने वाले दुष्ट राक्षसों के राजा रावण का सर्वनाश',
	emoji: '😀 🎉 🐙 🍕 🚀 ❤️ 🌈 🧠 🦄 🇧🇩 👩‍💻 🫠',
};

let fontSeq = 0;

export function FontPreview({ read, emoji, sample }: { read: FontRead; emoji: boolean; sample?: string }) {
	const [family] = useState(() => `wm-preview-${++fontSeq}`);
	const [loaded, setLoaded] = useState<'loading' | 'ok' | 'fail'>('loading');
	const [text, setText] = useState(sample ?? (emoji ? SAMPLES['emoji']! : SAMPLES['latin']!));
	useEffect(() => {
		if (sample !== undefined) setText(sample);
	}, [sample]);
	const [size, setSize] = useState(emoji ? 40 : 32);
	useEffect(() => {
		const face = new FontFace(family, `url(${read.url})`);
		face
			.load()
			.then((f) => {
				document.fonts.add(f);
				setLoaded('ok');
			})
			.catch(() => setLoaded('fail'));
		return () => {
			document.fonts.delete(face);
		};
	}, [family, read.url]);
	return (
		<div class="st-font-sample">
			<div class="st-sample-text" style={{ fontFamily: loaded === 'ok' ? `'${family}', sans-serif` : 'inherit', fontSize: `${size}px`, opacity: loaded === 'ok' ? 1 : 0.4 }} contentEditable onInput={(e) => setText((e.target as HTMLElement).textContent ?? '')} spellcheck={false} aria-label="Sample text, editable">
				{text}
			</div>
			{loaded === 'fail' && <p class="st-err-text st-small">The browser couldn't load this font; the app may still, since it uses Android's font loader.</p>}
			<div class="st-sample-controls">
				<label class="st-small st-muted">Size <input type="range" min={14} max={96} value={size} onInput={(e) => setSize(Number((e.target as HTMLInputElement).value))} /></label>
				{Object.entries(SAMPLES).map(([k, v]) => (
					<button key={k} class="st-chip" onClick={() => setText(v)}>{k}</button>
				))}
			</div>
			<dl class="st-kv" style="margin-top:1rem">
				<dt>Family</dt><dd>{read.family || <span class="st-muted">unnamed</span>}</dd>
				<dt>Style</dt><dd>{read.style || '—'}</dd>
				<dt>Version</dt><dd>{read.version || '—'}</dd>
				<dt>Glyphs</dt><dd>{read.glyphs ?? '—'}</dd>
				<dt>Colour</dt><dd>{read.isColor ? `yes (${read.tables.filter((t) => ['COLR', 'CBDT', 'sbix', 'SVG '].includes(t)).join(', ')})` : 'no'}</dd>
			</dl>
			<p class="st-muted st-small" style="margin-top:0.6rem">Edit the sample text to try your own words.</p>
		</div>
	);
}

/* ---------- audio ---------- */

function useAudio() {
	const ctx = useRef<AudioContext | null>(null);
	const get = () => {
		if (!ctx.current) ctx.current = new AudioContext();
		return ctx.current;
	};
	useEffect(() => () => void ctx.current?.close(), []);
	return get;
}

function PlayRow({ sample, label, gain = 1 }: { sample: SoundSample; label: string; gain?: number }) {
	const [playing, setPlaying] = useState(false);
	const audio = useRef<HTMLAudioElement | null>(null);
	const play = () => {
		if (!sample.url) return;
		if (!audio.current) {
			audio.current = new Audio(sample.url);
			audio.current.onended = () => setPlaying(false);
		}
		audio.current.volume = Math.min(1, Math.max(0, gain));
		if (playing) {
			audio.current.pause();
			audio.current.currentTime = 0;
			setPlaying(false);
		} else {
			audio.current.currentTime = 0;
			void audio.current.play();
			setPlaying(true);
		}
	};
	return (
		<div class="st-sound-row">
			<button class="st-btn st-btn-icon" onClick={play} disabled={!sample.url} aria-label={playing ? 'Stop' : 'Play'}>
				{playing ? <IconPause /> : <IconPlay />}
			</button>
			<span style="flex:1 1 auto;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{label}</span>
			<span class="st-muted st-small">{sample.url ? fmtBytes(sample.bytes) : 'missing'}</span>
		</div>
	);
}

export function SoundPreview({ read }: { read: { url: string; kind: string } }) {
	const [busy, setBusy] = useState(false);
	const get = useAudio();
	const buf = useRef<AudioBuffer | null>(null);
	const tap = async () => {
		const ctx = get();
		if (!buf.current) {
			setBusy(true);
			try {
				buf.current = await ctx.decodeAudioData(await (await fetch(read.url)).arrayBuffer());
			} finally {
				setBusy(false);
			}
		}
		const src = ctx.createBufferSource();
		src.buffer = buf.current;
		src.connect(ctx.destination);
		src.start();
	};
	return (
		<>
			<PlayRow sample={{ url: read.url, path: '', bytes: 0 }} label={`Key sound (${read.kind})`} />
			<div class="st-pv-pad" style="padding-top:0">
				<p class="st-small st-muted" style="margin-bottom:0.5rem">Type on the pad to hear it the way the keyboard plays it, once per press.</p>
				<div class="st-keypad" style="padding:0">
					{['q', 'w', 'e', 'r', 't', 'y', 'space', '⌫'].map((k) => (
						<button key={k} onPointerDown={tap} disabled={busy}>{k}</button>
					))}
				</div>
			</div>
		</>
	);
}

export function SoundPackPreview({ read }: { read: SoundPackRead }) {
	const get = useAudio();
	const cache = useRef(new Map<string, AudioBuffer>());
	const [busy, setBusy] = useState(false);
	const pick = (list: SoundSample[]) => list.filter((s) => s.url)[Math.floor(Math.random() * list.filter((s) => s.url).length)];
	const play = async (role: string, phase: 'press' | 'release') => {
		const r = read.roles[role];
		const list = r ? r[phase] : phase === 'press' ? read.press : read.release;
		const fallback = phase === 'press' ? read.press : read.release;
		const s = pick(list.length ? list : fallback);
		if (!s?.url) return;
		const ctx = get();
		let buf = cache.current.get(s.url);
		if (!buf) {
			setBusy(true);
			try {
				buf = await ctx.decodeAudioData(await (await fetch(s.url)).arrayBuffer());
				cache.current.set(s.url, buf);
			} finally {
				setBusy(false);
			}
		}
		const src = ctx.createBufferSource();
		src.buffer = buf;
		const g = ctx.createGain();
		g.gain.value = Math.min(1, (r?.gain ?? read.gain) || 1);
		src.connect(g).connect(ctx.destination);
		src.start();
	};
	const roleOf = (k: string) => (k === 'space' ? 'space' : k === '⏎' ? 'enter' : k === '⌫' ? 'delete' : k === '⇧' ? 'modifier' : 'default');
	return (
		<>
			<Problems items={read.problems} />
			<div class="st-pv-pad">
				<p class="st-small st-muted" style="margin-bottom:0.5rem">
					Type on the pad: a random press sample plays on the way down{read.release.length ? ', a release sample on the way up' : ''}, per role, the way the keyboard does it.
				</p>
				<div class="st-keypad" style="padding:0">
					{['q', 'w', 'e', '⇧', 'space', '⌫', '⏎'].map((k) => (
						<button key={k} disabled={busy} onPointerDown={() => play(roleOf(k), 'press')} onPointerUp={() => play(roleOf(k), 'release')}>{k}</button>
					))}
				</div>
			</div>
			<div style="border-top:1px solid var(--st-card-border)">
				{read.press.map((s, i) => <PlayRow key={`p${i}`} sample={s} label={`press ${i + 1} · ${s.path}`} gain={read.gain} />)}
				{read.release.map((s, i) => <PlayRow key={`r${i}`} sample={s} label={`release ${i + 1} · ${s.path}`} gain={read.gain} />)}
				{Object.entries(read.roles).flatMap(([role, r]) => [
					...r.press.map((s, i) => <PlayRow key={`${role}p${i}`} sample={s} label={`${role} press ${i + 1} · ${s.path}`} gain={r.gain ?? read.gain} />),
					...r.release.map((s, i) => <PlayRow key={`${role}r${i}`} sample={s} label={`${role} release ${i + 1} · ${s.path}`} gain={r.gain ?? read.gain} />),
				])}
			</div>
			<div class="st-pv-pad st-small st-muted" style="border-top:1px solid var(--st-card-border)">
				{read.name || 'Sound pack'} v{read.packVersion}{read.author ? ` by ${read.author}` : ''} · gain {read.gain} · roles: {Object.keys(read.roles).length ? Object.keys(read.roles).join(', ') : 'default only'}
			</div>
		</>
	);
}

/* ---------- stickers ---------- */

export function StickerPreview({ read }: { read: StickerPackRead }) {
	return (
		<>
			<Problems items={read.problems} />
			<div class="st-pv-pad st-small st-muted" style="padding-bottom:0">
				{read.stickers.length} sticker{read.stickers.length === 1 ? '' : 's'} · {fmtBytes(read.totalBytes)}{read.author ? ` · by ${read.author}` : ''}
			</div>
			<div class="st-sticker-grid">
				{read.stickers.map((s) => (
					<figure key={s.id} title={s.name || s.id}>
						{s.url ? <img src={s.url} alt={s.name || s.id} loading="lazy" /> : <span class="st-muted">too big</span>}
						<figcaption>{s.name || s.id}{s.emojis.length ? ` ${s.emojis.slice(0, 3).join('')}` : ''}</figcaption>
					</figure>
				))}
			</div>
		</>
	);
}

/* ---------- icon pack ---------- */

export function IconPackPreview({ read }: { read: IconPackRead }) {
	const [group, setGroup] = useState<string | null>(null);
	const grouped = ICON_SLOT_GROUPS.map((g) => ({ g, ids: new Set(ICON_SLOTS.filter((s) => s.group === g).map((s) => s.id)) }));
	const icons = read.icons.filter((i) => !group || (group === 'Other' ? !i.known : grouped.find((x) => x.g === group)?.ids.has(i.slot)));
	const covered = read.icons.filter((i) => i.known).length;
	return (
		<>
			<Problems items={read.problems} />
			<div class="st-pv-pad" style="padding-bottom:0">
				<div class="st-small st-muted" style="margin-bottom:0.5rem">
					{read.name || 'Icon pack'}{read.version ? ` v${read.version}` : ''}{read.author ? ` by ${read.author}` : ''} · covers {covered} of {ICON_SLOTS.length} slots the app has
				</div>
				<div class="st-chips">
					<button class="st-chip" aria-pressed={group === null} onClick={() => setGroup(null)}>All <span class="st-count">{read.icons.length}</span></button>
					{grouped.map(({ g, ids }) => {
						const n = read.icons.filter((i) => ids.has(i.slot)).length;
						return n ? <button key={g} class="st-chip" aria-pressed={group === g} onClick={() => setGroup(g)}>{g} <span class="st-count">{n}</span></button> : null;
					})}
					{read.icons.some((i) => !i.known) && <button class="st-chip" aria-pressed={group === 'Other'} onClick={() => setGroup('Other')}>Unknown slots</button>}
				</div>
			</div>
			<div class="st-icon-grid">
				{icons.map((i) => (
					<figure key={i.slot} data-known={String(i.known)} title={`${i.slot} · ${fmtBytes(i.bytes)}`}>
						<span class="st-svg" dangerouslySetInnerHTML={{ __html: sanitizeSvg(i.svg) }} />
						<figcaption>{slotLabel(i.slot)}</figcaption>
					</figure>
				))}
			</div>
		</>
	);
}

/** Strips scripts/handlers/external refs from an SVG before inlining it. */
export function sanitizeSvg(svg: string): string {
	return svg
		.replace(/<\?xml[^>]*\?>/g, '')
		.replace(/<!--[\s\S]*?-->/g, '')
		.replace(/<script[\s\S]*?<\/script>/gi, '')
		.replace(/<foreignObject[\s\S]*?<\/foreignObject>/gi, '')
		.replace(/\son\w+="[^"]*"/gi, '')
		.replace(/\s(xlink:)?href="(?!#)[^"]*"/gi, '')
		.replace(/<svg\b([^>]*)>/i, (_, attrs: string) => `<svg${attrs.replace(/\s(width|height)="[^"]*"/gi, '')}>`);
}
