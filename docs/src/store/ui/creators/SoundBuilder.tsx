/**
 * Key sound builder: record or drop a sound, trim it on a waveform, set gain,
 * fades and normalisation, or synthesise a click from scratch, and write the
 * 16-bit mono 44.1 kHz WAV the app's own sounds use.
 */
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { applyEdit, DEFAULT_EDIT, decodeClip, durationMs, encodeWav, peakOf, peaks, playClip, recordClip, synthesize, type Clip, type EditOptions, type SynthKind } from '../../lib/audio';
import { fmtBytes } from '../../lib/net';
import { slugify } from '../../lib/util';
import { Notice } from '../common';
import { IconDownload, IconPlay, IconWarn } from '../icons';
import { DropZone, ExportPanel, Range, saveBytes, Section, Text, Toggle } from './shared';

const CAP = 4 * 1024 * 1024;

function Waveform({ clip, edit, onRange }: { clip: Clip; edit: EditOptions; onRange: (start: number, end: number) => void }) {
	const canvas = useRef<HTMLCanvasElement>(null);
	const dragging = useRef<'start' | 'end' | null>(null);
	const total = durationMs(clip);
	const pk = useMemo(() => peaks(clip, 400), [clip]);
	useEffect(() => {
		const c = canvas.current;
		if (!c) return;
		const dpr = devicePixelRatio || 1;
		const w = c.clientWidth;
		const h = 120;
		c.width = w * dpr;
		c.height = h * dpr;
		const g = c.getContext('2d')!;
		g.scale(dpr, dpr);
		g.clearRect(0, 0, w, h);
		const s = (edit.start / total) * w;
		const e = ((edit.end === Infinity ? total : edit.end) / total) * w;
		g.fillStyle = 'rgba(76,141,246,0.12)';
		g.fillRect(s, 0, e - s, h);
		g.fillStyle = getComputedStyle(c).getPropertyValue('--wm-blue') || '#4c8df6';
		const bw = w / pk.length;
		pk.forEach(([lo, hi], i) => {
			const x = i * bw;
			g.globalAlpha = x < s || x > e ? 0.3 : 1;
			g.fillRect(x, h / 2 - hi * (h / 2 - 2), Math.max(1, bw - 0.5), (hi - lo) * (h / 2 - 2) || 1);
		});
		g.globalAlpha = 1;
		g.fillStyle = '#f59e0b';
		g.fillRect(s - 1, 0, 2, h);
		g.fillRect(e - 1, 0, 2, h);
	}, [pk, edit.start, edit.end, total]);
	const pos = (ev: PointerEvent) => {
		const r = canvas.current!.getBoundingClientRect();
		return Math.max(0, Math.min(total, ((ev.clientX - r.left) / r.width) * total));
	};
	return (
		<canvas
			ref={canvas}
			style="width:100%;height:120px;display:block;border-radius:var(--st-radius-sm);background:var(--sl-color-gray-7);cursor:col-resize;touch-action:none"
			onPointerDown={(ev) => {
				const p = pos(ev);
				const end = edit.end === Infinity ? total : edit.end;
				dragging.current = Math.abs(p - edit.start) < Math.abs(p - end) ? 'start' : 'end';
				(ev.target as HTMLElement).setPointerCapture(ev.pointerId);
			}}
			onPointerMove={(ev) => {
				if (!dragging.current) return;
				const p = pos(ev);
				const end = edit.end === Infinity ? total : edit.end;
				if (dragging.current === 'start') onRange(Math.min(p, end - 1), end);
				else onRange(edit.start, Math.max(p, edit.start + 1));
			}}
			onPointerUp={() => (dragging.current = null)}
		/>
	);
}

export function SoundBuilder() {
	const [clip, setClip] = useState<Clip | null>(null);
	const [edit, setEdit] = useState<EditOptions>(DEFAULT_EDIT);
	const [name, setName] = useState('my-key-sound');
	const [err, setErr] = useState<string | null>(null);
	const [rec, setRec] = useState<{ stop: () => Promise<Clip> } | null>(null);
	const [level, setLevel] = useState(0);
	const edited = useMemo(() => (clip ? applyEdit(clip, edit) : null), [clip, edit]);
	const wav = useMemo(() => (edited ? encodeWav(edited) : null), [edited]);
	const dur = edited ? durationMs(edited) : 0;
	const peak = edited ? peakOf(edited) : 0;

	const loadFile = async (bytes: Uint8Array, n: string) => {
		try {
			setClip(await decodeClip(bytes));
			setEdit(DEFAULT_EDIT);
			setName(n.replace(/\.[^.]+$/, ''));
			setErr(null);
		} catch (e) {
			setErr(`Could not decode: ${(e as Error).message}`);
		}
	};
	const startRec = async () => {
		try {
			setRec(await recordClip(5000, setLevel));
			setErr(null);
		} catch (e) {
			setErr(`Microphone: ${(e as Error).message}`);
		}
	};
	const stopRec = async () => {
		if (!rec) return;
		const c = await rec.stop();
		setRec(null);
		setClip(c);
		setEdit({ ...DEFAULT_EDIT, silenceThreshold: 0.02, normalize: true });
		setName('recording');
	};
	const synth = (k: SynthKind) => {
		setClip(synthesize(k, Date.now() & 0xffff));
		setEdit(DEFAULT_EDIT);
		setName(k);
	};
	const tapKeys = ['q', 'w', 'e', 'r', 'space', '⌫'];

	return (
		<div class="st-creator-layout">
			<div style="display:flex;flex-direction:column;gap:0.8rem">
				<div>
					<span class="st-kicker">Key sound</span>
					<h1 style="font-size:1.5rem;font-weight:800">One sound per keystroke</h1>
					<p class="st-muted st-small" style="max-width:62ch;margin-top:0.3rem">
						The app plays the file once per press through SoundPool, so short and dry works best: under ~300 ms, a few tens of KB. It accepts mp3, ogg and wav under 4 MB; this writes wav (16-bit mono, 44.1 kHz), which decodes with no gap.
					</p>
				</div>
				<Section title="Source">
					<DropZone accept="audio/*,.mp3,.ogg,.wav,.m4a,.flac,.webm" multiple={false} onFiles={([f]) => f && loadFile(f.bytes, f.name)}>Drop any audio file the browser can decode</DropZone>
					<div class="st-row">
						{rec ? (
							<button class="st-btn st-btn-danger" onClick={stopRec}>■ Stop recording <span class="st-muted st-small">(auto-stops at 5 s)</span></button>
						) : (
							<button class="st-btn" onClick={startRec}>● Record from the microphone</button>
						)}
						{rec && <div class="st-progress" style="flex:1 1 6rem"><i style={{ width: `${Math.min(100, level * 100)}%` }} /></div>}
						<span class="st-small st-muted">or synthesise:</span>
						{(['click', 'thock', 'blip', 'pop', 'tick'] as SynthKind[]).map((k) => <button key={k} class="st-chip" onClick={() => synth(k)}>{k}</button>)}
					</div>
					{err && <Notice kind="err" icon={<IconWarn />}>{err}</Notice>}
				</Section>
				{clip && (
					<>
						<Section title="Trim">
							<Waveform clip={clip} edit={edit} onRange={(start, end) => setEdit({ ...edit, start, end })} />
							<div class="st-grid2">
								<Range label="Start" value={Math.round(edit.start)} min={0} max={Math.round(durationMs(clip))} step={1} onInput={(v) => setEdit({ ...edit, start: Math.min(v, (edit.end === Infinity ? durationMs(clip) : edit.end) - 1) })} format={(v) => `${v} ms`} />
								<Range label="End" value={Math.round(edit.end === Infinity ? durationMs(clip) : edit.end)} min={0} max={Math.round(durationMs(clip))} step={1} onInput={(v) => setEdit({ ...edit, end: Math.max(v, edit.start + 1) })} format={(v) => `${v} ms`} />
								<Range label="Trim silence below" value={edit.silenceThreshold} min={0} max={0.2} step={0.005} onInput={(v) => setEdit({ ...edit, silenceThreshold: v })} format={(v) => (v ? `${Math.round(v * 100)}%` : 'off')} />
							</div>
						</Section>
						<Section title="Level and shape">
							<div class="st-grid2">
								<Range label="Gain" value={edit.gain} min={0} max={3} step={0.05} onInput={(v) => setEdit({ ...edit, gain: v })} format={(v) => `${v.toFixed(2)}×`} />
								<Range label="Fade in" value={edit.fadeInMs} min={0} max={100} step={1} onInput={(v) => setEdit({ ...edit, fadeInMs: v })} format={(v) => `${v} ms`} />
								<Range label="Fade out" value={edit.fadeOutMs} min={0} max={200} step={1} onInput={(v) => setEdit({ ...edit, fadeOutMs: v })} format={(v) => `${v} ms`} />
							</div>
							<Toggle label="Normalise to −0.2 dB peak" value={edit.normalize} onInput={(v) => setEdit({ ...edit, normalize: v })} help="SoundPool cannot make a quiet file louder; do it here." />
						</Section>
						<Section title="Try it">
							<p class="st-small st-muted">Tap like you'd type; each press plays the edited sound once.</p>
							<div class="st-keypad" style="padding:0">
								{tapKeys.map((k) => <button key={k} onPointerDown={() => edited && playClip(edited)}>{k}</button>)}
							</div>
						</Section>
					</>
				)}
			</div>
			<aside class="st-creator-side">
				<ExportPanel title="Export">
					{edited && wav ? (
						<>
							<div class="st-small">
								<span class="st-muted">{dur.toFixed(0)} ms · {fmtBytes(wav.byteLength)} · peak {(20 * Math.log10(Math.max(peak, 1e-6))).toFixed(1)} dB</span>
								{dur > 300 && <div class="st-pill st-pill-warn" style="margin-top:0.3rem">longer than the ~300 ms the app recommends</div>}
								{peak >= 0.999 && <div class="st-pill st-pill-warn" style="margin-top:0.3rem">clipping: lower the gain</div>}
							</div>
							<button class="st-btn" onClick={() => playClip(edited)}><IconPlay /> Play</button>
							<Text label="File name" value={name} onInput={setName} />
							<button class="st-btn st-btn-primary" disabled={wav.byteLength > CAP || !edited.samples.length} onClick={() => saveBytes(`${slugify(name) || 'sound'}.wav`, wav, 'audio/wav')}><IconDownload /> .wav</button>
						</>
					) : (
						<span class="st-small st-muted">Drop, record or synthesise a sound to begin.</span>
					)}
				</ExportPanel>
				<div class="st-panel st-small st-muted">For several samples the keyboard picks from at random, or per-role sounds (space, enter, delete), build a <a href="/addons/new/?type=sound_pack">sound pack</a> instead. In a repository entry set <code>type: sound</code>.</div>
			</aside>
		</div>
	);
}
