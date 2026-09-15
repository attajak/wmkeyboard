/**
 * Key-sound editing with the Web Audio API: decode anything the browser can,
 * trim, gain, normalise, fade, mix to mono, resample to 44.1 kHz and write a
 * 16-bit PCM WAV, which is one of the containers the app's importer accepts
 * (and what its own built-in sounds are).
 */

export const SAMPLE_RATE = 44100;

export interface Clip {
	/** Mono samples in -1..1 at SAMPLE_RATE. */
	samples: Float32Array;
	sampleRate: number;
}

let ctx: AudioContext | null = null;
export function audioContext(): AudioContext {
	if (!ctx) ctx = new AudioContext();
	return ctx;
}

/** Decode a file (mp3/ogg/wav/m4a/webm…) into a mono 44.1 kHz clip. */
export async function decodeClip(bytes: Uint8Array): Promise<Clip> {
	const ac = audioContext();
	const buf = await ac.decodeAudioData(bytes.slice().buffer);
	return toMono(buf);
}

export async function toMono(buf: AudioBuffer): Promise<Clip> {
	const channels = buf.numberOfChannels;
	const n = buf.length;
	const mixed = new Float32Array(n);
	for (let c = 0; c < channels; c++) {
		const d = buf.getChannelData(c);
		for (let i = 0; i < n; i++) mixed[i]! += d[i]! / channels;
	}
	if (buf.sampleRate === SAMPLE_RATE) return { samples: mixed, sampleRate: SAMPLE_RATE };
	const frames = Math.ceil((n * SAMPLE_RATE) / buf.sampleRate);
	const off = new OfflineAudioContext(1, Math.max(1, frames), SAMPLE_RATE);
	const src = off.createBufferSource();
	const mono = off.createBuffer(1, n, buf.sampleRate);
	mono.copyToChannel(mixed, 0);
	src.buffer = mono;
	src.connect(off.destination);
	src.start();
	const out = await off.startRendering();
	return { samples: out.getChannelData(0).slice(), sampleRate: SAMPLE_RATE };
}

export interface EditOptions {
	/** ms */
	start: number;
	/** ms; Infinity = to the end */
	end: number;
	gain: number;
	normalize: boolean;
	fadeInMs: number;
	fadeOutMs: number;
	/** Cut leading/trailing samples under this level (0 = off). */
	silenceThreshold: number;
}

export const DEFAULT_EDIT: EditOptions = { start: 0, end: Infinity, gain: 1, normalize: false, fadeInMs: 0, fadeOutMs: 5, silenceThreshold: 0 };

export function applyEdit(clip: Clip, o: EditOptions): Clip {
	const sr = clip.sampleRate;
	let a = Math.max(0, Math.floor((o.start / 1000) * sr));
	let b = Math.min(clip.samples.length, o.end === Infinity ? clip.samples.length : Math.ceil((o.end / 1000) * sr));
	if (o.silenceThreshold > 0) {
		while (a < b && Math.abs(clip.samples[a]!) < o.silenceThreshold) a++;
		while (b > a && Math.abs(clip.samples[b - 1]!) < o.silenceThreshold) b--;
	}
	if (b <= a) return { samples: new Float32Array(0), sampleRate: sr };
	const out = clip.samples.slice(a, b);
	let gain = o.gain;
	if (o.normalize) {
		let peak = 0;
		for (let i = 0; i < out.length; i++) peak = Math.max(peak, Math.abs(out[i]!));
		if (peak > 0) gain *= 0.98 / peak;
	}
	const fi = Math.min(out.length, Math.floor((o.fadeInMs / 1000) * sr));
	const fo = Math.min(out.length, Math.floor((o.fadeOutMs / 1000) * sr));
	for (let i = 0; i < out.length; i++) {
		let g = gain;
		if (i < fi) g *= i / fi;
		if (i >= out.length - fo) g *= (out.length - i) / fo;
		let v = out[i]! * g;
		if (v > 1) v = 1;
		else if (v < -1) v = -1;
		out[i] = v;
	}
	return { samples: out, sampleRate: sr };
}

export function peakOf(clip: Clip): number {
	let p = 0;
	for (let i = 0; i < clip.samples.length; i++) p = Math.max(p, Math.abs(clip.samples[i]!));
	return p;
}

export function durationMs(clip: Clip): number {
	return (clip.samples.length / clip.sampleRate) * 1000;
}

/** Peaks for a waveform strip: `bins` pairs of [min, max]. */
export function peaks(clip: Clip, bins: number): [number, number][] {
	const out: [number, number][] = [];
	const per = clip.samples.length / bins;
	for (let b = 0; b < bins; b++) {
		let lo = 0;
		let hi = 0;
		const s = Math.floor(b * per);
		const e = Math.min(clip.samples.length, Math.floor((b + 1) * per));
		for (let i = s; i < e; i++) {
			const v = clip.samples[i]!;
			if (v < lo) lo = v;
			if (v > hi) hi = v;
		}
		out.push([lo, hi]);
	}
	return out;
}

/** 16-bit PCM mono WAV, the way the app writes its own sounds. */
export function encodeWav(clip: Clip): Uint8Array {
	const n = clip.samples.length;
	const out = new Uint8Array(44 + n * 2);
	const dv = new DataView(out.buffer);
	const str = (o: number, s: string) => {
		for (let i = 0; i < s.length; i++) out[o + i] = s.charCodeAt(i);
	};
	str(0, 'RIFF');
	dv.setUint32(4, 36 + n * 2, true);
	str(8, 'WAVE');
	str(12, 'fmt ');
	dv.setUint32(16, 16, true);
	dv.setUint16(20, 1, true);
	dv.setUint16(22, 1, true);
	dv.setUint32(24, clip.sampleRate, true);
	dv.setUint32(28, clip.sampleRate * 2, true);
	dv.setUint16(32, 2, true);
	dv.setUint16(34, 16, true);
	str(36, 'data');
	dv.setUint32(40, n * 2, true);
	for (let i = 0; i < n; i++) {
		const v = Math.max(-1, Math.min(1, clip.samples[i]!));
		dv.setInt16(44 + i * 2, v < 0 ? v * 0x8000 : v * 0x7fff, true);
	}
	return out;
}

export function playClip(clip: Clip): AudioBufferSourceNode {
	const ac = audioContext();
	const buf = ac.createBuffer(1, Math.max(1, clip.samples.length), clip.sampleRate);
	buf.copyToChannel(new Float32Array(clip.samples), 0);
	const src = ac.createBufferSource();
	src.buffer = buf;
	src.connect(ac.destination);
	src.start();
	return src;
}

/* ---------- synthesis: the same families the app's built-in styles use ---------- */

export type SynthKind = 'click' | 'thock' | 'blip' | 'pop' | 'tick';

export function synthesize(kind: SynthKind, seed = 1): Clip {
	const sr = SAMPLE_RATE;
	let rnd = seed;
	const rand = () => {
		rnd = (rnd * 1664525 + 1013904223) >>> 0;
		return rnd / 0xffffffff - 0.5;
	};
	const spec: Record<SynthKind, { ms: number; f: number; noise: number; decay: number; sweep: number }> = {
		click: { ms: 40, f: 2600, noise: 0.8, decay: 60, sweep: 0 },
		thock: { ms: 90, f: 180, noise: 0.35, decay: 25, sweep: -60 },
		blip: { ms: 70, f: 880, noise: 0, decay: 40, sweep: 400 },
		pop: { ms: 60, f: 320, noise: 0.15, decay: 45, sweep: -200 },
		tick: { ms: 25, f: 4000, noise: 0.5, decay: 120, sweep: 0 },
	};
	const s = spec[kind];
	const n = Math.floor((s.ms / 1000) * sr);
	const out = new Float32Array(n);
	let phase = 0;
	for (let i = 0; i < n; i++) {
		const t = i / sr;
		const env = Math.exp(-t * s.decay);
		const f = s.f + s.sweep * t * 10;
		phase += (2 * Math.PI * f) / sr;
		const tone = Math.sin(phase) * (1 - s.noise);
		const noise = rand() * 2 * s.noise;
		out[i] = (tone + noise) * env * 0.9;
	}
	return { samples: out, sampleRate: sr };
}

/* ---------- recording ---------- */

export async function recordClip(maxMs: number, onLevel?: (v: number) => void): Promise<{ stop: () => Promise<Clip> }> {
	const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false } });
	const ac = audioContext();
	const src = ac.createMediaStreamSource(stream);
	const proc = ac.createScriptProcessor(2048, 1, 1);
	const chunks: Float32Array[] = [];
	let total = 0;
	const limit = (maxMs / 1000) * ac.sampleRate;
	proc.onaudioprocess = (e) => {
		if (total >= limit) return;
		const d = e.inputBuffer.getChannelData(0).slice();
		chunks.push(d);
		total += d.length;
		let p = 0;
		for (let i = 0; i < d.length; i++) p = Math.max(p, Math.abs(d[i]!));
		onLevel?.(p);
	};
	src.connect(proc);
	proc.connect(ac.destination);
	return {
		stop: async () => {
			proc.disconnect();
			src.disconnect();
			stream.getTracks().forEach((t) => t.stop());
			const all = new Float32Array(total);
			let o = 0;
			for (const c of chunks) {
				all.set(c, o);
				o += c.length;
			}
			const buf = ac.createBuffer(1, Math.max(1, total), ac.sampleRate);
			buf.copyToChannel(all, 0);
			return toMono(buf);
		},
	};
}
