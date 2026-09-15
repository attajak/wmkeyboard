/** Form primitives and helpers every builder shares. */
import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { dropDraft, loadDraft, saveDraft } from '../../lib/storage';
import { downloadBlob } from '../../lib/util';
import { IconUpload } from '../icons';

/* ---------- drafts ---------- */

/** Keeps a builder's state in localStorage so a reload doesn't lose work. */
export function useDraft<T>(kind: string, initial: () => T): [T, (next: T | ((prev: T) => T)) => void, () => void] {
	const [state, setState] = useState<T>(() => loadDraft<T | null>(kind, null) ?? initial());
	const first = useRef(true);
	useEffect(() => {
		if (first.current) {
			first.current = false;
			return;
		}
		const t = setTimeout(() => saveDraft(kind, state), 400);
		return () => clearTimeout(t);
	}, [kind, state]);
	const reset = () => {
		dropDraft(kind);
		setState(initial());
	};
	return [state, setState, reset];
}

/* ---------- fields ---------- */

export function Text({ label, value, onInput, placeholder, help, error, mono, required, type = 'text', list }: { label: string; value: string; onInput: (v: string) => void; placeholder?: string; help?: ComponentChildren; error?: string | null; mono?: boolean; required?: boolean; type?: string; list?: string }) {
	return (
		<div class="st-field">
			<label>{label}{required && <span style="color:var(--st-err)"> *</span>}</label>
			<input class="st-input" type={type} value={value} placeholder={placeholder} list={list} style={mono ? 'font-family:var(--sl-font-mono);font-size:0.84rem' : ''} onInput={(e) => onInput((e.target as HTMLInputElement).value)} aria-invalid={!!error} />
			{error ? <span class="st-err-text">{error}</span> : help && <span class="st-help">{help}</span>}
		</div>
	);
}

export function Area({ label, value, onInput, placeholder, help, rows = 3, mono }: { label: string; value: string; onInput: (v: string) => void; placeholder?: string; help?: ComponentChildren; rows?: number; mono?: boolean }) {
	return (
		<div class="st-field">
			<label>{label}</label>
			<textarea class="st-textarea" rows={rows} value={value} placeholder={placeholder} style={mono ? 'font-family:var(--sl-font-mono);font-size:0.82rem' : ''} onInput={(e) => onInput((e.target as HTMLTextAreaElement).value)} />
			{help && <span class="st-help">{help}</span>}
		</div>
	);
}

export function Num({ label, value, onInput, min, max, step = 1, help, allowEmpty }: { label: string; value: number | null | undefined; onInput: (v: number | null) => void; min?: number; max?: number; step?: number; help?: ComponentChildren; allowEmpty?: boolean }) {
	return (
		<div class="st-field">
			<label>{label}</label>
			<input class="st-input" type="number" value={value ?? ''} min={min} max={max} step={step} onInput={(e) => { const v = (e.target as HTMLInputElement).value; onInput(v === '' ? (allowEmpty ? null : 0) : Number(v)); }} />
			{help && <span class="st-help">{help}</span>}
		</div>
	);
}

export function Range({ label, value, onInput, min, max, step = 0.05, format }: { label: string; value: number; onInput: (v: number) => void; min: number; max: number; step?: number; format?: (v: number) => string }) {
	return (
		<div class="st-field">
			<label style="display:flex;justify-content:space-between"><span>{label}</span><span class="st-muted">{format ? format(value) : value}</span></label>
			<input type="range" min={min} max={max} step={step} value={value} onInput={(e) => onInput(Number((e.target as HTMLInputElement).value))} style="accent-color:var(--wm-blue)" />
		</div>
	);
}

export function Select<T extends string>({ label, value, onInput, options, help }: { label: string; value: T; onInput: (v: T) => void; options: readonly (T | [T, string])[]; help?: ComponentChildren }) {
	return (
		<div class="st-field">
			<label>{label}</label>
			<select class="st-select" value={value} onChange={(e) => onInput((e.target as HTMLSelectElement).value as T)}>
				{options.map((o) => (Array.isArray(o) ? <option value={o[0]} key={o[0]}>{o[1]}</option> : <option value={o} key={o}>{o}</option>))}
			</select>
			{help && <span class="st-help">{help}</span>}
		</div>
	);
}

export function Toggle({ label, value, onInput, help }: { label: string; value: boolean; onInput: (v: boolean) => void; help?: ComponentChildren }) {
	return (
		<label class="st-switch" style="align-items:flex-start">
			<input type="checkbox" checked={value} onChange={(e) => onInput((e.target as HTMLInputElement).checked)} style="margin-top:0.15rem" />
			<span>{label}{help && <span class="st-help" style="display:block">{help}</span>}</span>
		</label>
	);
}

export function Tags({ label, value, onInput, placeholder, help }: { label: string; value: string[]; onInput: (v: string[]) => void; placeholder?: string; help?: ComponentChildren }) {
	return (
		<Text label={label} value={value.join(', ')} placeholder={placeholder ?? 'comma, separated'} help={help} onInput={(v) => onInput(v.split(',').map((s) => s.trim()).filter(Boolean))} />
	);
}

/* ---------- colour (ARGB long ↔ #rrggbb + alpha) ---------- */

export function argbToHex(v: number | null | undefined): { hex: string; alpha: number } {
	if (v == null) return { hex: '#000000', alpha: 100 };
	const n = Number(v) >>> 0;
	return { hex: '#' + (n & 0xffffff).toString(16).padStart(6, '0'), alpha: Math.round((((n >>> 24) & 255) / 255) * 100) };
}

export function hexToArgb(hex: string, alphaPct: number): number {
	const rgb = parseInt(hex.replace('#', '').padEnd(6, '0').slice(0, 6), 16) || 0;
	const a = Math.round((Math.min(100, Math.max(0, alphaPct)) / 100) * 255);
	return ((a << 24) >>> 0) + rgb;
}

export function Color({ label, value, onInput, nullable, help }: { label: string; value: number | null | undefined; onInput: (v: number | null) => void; nullable?: boolean; help?: ComponentChildren }) {
	const { hex, alpha } = argbToHex(value);
	const css = value == null ? 'transparent' : `rgba(${(value >>> 16) & 255},${(value >>> 8) & 255},${value & 255},${alpha / 100})`;
	return (
		<div class="st-field">
			<label style="display:flex;justify-content:space-between;align-items:center">
				<span>{label}</span>
				{nullable && value != null && <button class="st-btn st-btn-ghost st-btn-sm" style="padding:0 0.4rem" onClick={() => onInput(null)}>inherit</button>}
				{nullable && value == null && <span class="st-muted" style="font-weight:400">inherits</span>}
			</label>
			<div class="st-color">
				<span class="st-swatch" style={{ '--sw': css }}>
					<input type="color" value={hex} onInput={(e) => onInput(hexToArgb((e.target as HTMLInputElement).value, value == null ? 100 : alpha))} aria-label={`${label} colour`} />
				</span>
				<input class="st-input" value={value == null ? '' : hex} placeholder="#rrggbb" onInput={(e) => { const v = (e.target as HTMLInputElement).value; if (/^#?[0-9a-fA-F]{6}$/.test(v)) onInput(hexToArgb(v.startsWith('#') ? v : '#' + v, value == null ? 100 : alpha)); }} />
				<input class="st-input st-alpha" type="number" min={0} max={100} value={value == null ? '' : alpha} placeholder="α%" title="Opacity %" onInput={(e) => onInput(hexToArgb(hex, Number((e.target as HTMLInputElement).value)))} />
			</div>
			{help && <span class="st-help">{help}</span>}
		</div>
	);
}

/* ---------- files ---------- */

export interface PickedFile {
	name: string;
	bytes: Uint8Array;
	type: string;
}

export async function readFiles(list: FileList | File[]): Promise<PickedFile[]> {
	const out: PickedFile[] = [];
	for (const f of Array.from(list)) out.push({ name: f.name, bytes: new Uint8Array(await f.arrayBuffer()), type: f.type });
	return out;
}

export function DropZone({ onFiles, accept, multiple = true, children }: { onFiles: (files: PickedFile[]) => void; accept?: string; multiple?: boolean; children?: ComponentChildren }) {
	const [over, setOver] = useState(false);
	const input = useRef<HTMLInputElement>(null);
	return (
		<label
			class="st-drop"
			data-over={over}
			onDragOver={(e) => { e.preventDefault(); setOver(true); }}
			onDragLeave={() => setOver(false)}
			onDrop={async (e) => { e.preventDefault(); setOver(false); if (e.dataTransfer?.files.length) onFiles(await readFiles(e.dataTransfer.files)); }}
		>
			<IconUpload />
			<span>{children ?? 'Drop files here, or click to choose'}</span>
			<input ref={input} type="file" accept={accept} multiple={multiple} onChange={async (e) => { const t = e.target as HTMLInputElement; if (t.files?.length) onFiles(await readFiles(t.files)); t.value = ''; }} />
		</label>
	);
}

export function bytesToBase64(bytes: Uint8Array): string {
	let s = '';
	const chunk = 0x8000;
	for (let i = 0; i < bytes.length; i += chunk) s += String.fromCharCode(...bytes.subarray(i, i + chunk));
	return btoa(s);
}

export function base64ToBytes(b64: string): Uint8Array {
	const s = atob(b64);
	const out = new Uint8Array(s.length);
	for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
	return out;
}

/* ---------- output ---------- */

export function saveJson(name: string, value: unknown) {
	downloadBlob(name, new Blob([JSON.stringify(value, null, 2) + '\n'], { type: 'application/json' }));
}

export function saveBytes(name: string, bytes: Uint8Array, mime = 'application/zip') {
	downloadBlob(name, new Blob([bytes as BlobPart], { type: mime }));
}

export function ExportPanel({ title, children, json }: { title: string; children?: ComponentChildren; json?: unknown }) {
	const [show, setShow] = useState(false);
	return (
		<div class="st-panel">
			<h3>{title}</h3>
			<div class="st-actions">{children}</div>
			{json !== undefined && (
				<details style="margin-top:0.8rem" open={show} onToggle={(e) => setShow((e.target as HTMLDetailsElement).open)}>
					<summary class="st-small st-muted" style="cursor:pointer">Show JSON</summary>
					{show && <pre class="st-json-out"><code>{JSON.stringify(json, null, 2)}</code></pre>}
				</details>
			)}
		</div>
	);
}

export function Section({ title, children, open = true }: { title: string; children: ComponentChildren; open?: boolean }) {
	return (
		<details class="st-fieldset" open={open}>
			<summary>{title}</summary>
			<div style="display:flex;flex-direction:column;gap:0.8rem;margin-top:0.6rem">{children}</div>
		</details>
	);
}

export const APP_VERSION_FIELDS = { appVersion: 0, appVersionName: '' };

export function idError(id: string): string | null {
	if (!id) return 'Required.';
	if (!/^[A-Za-z0-9._-]+$/.test(id)) return 'Letters, digits, dots, dashes and underscores only.';
	return null;
}
