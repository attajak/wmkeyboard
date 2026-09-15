/**
 * Plugin builder: plugin.json form, a Lua editor for main.lua and extra
 * files, templates, static checks, a live sandbox (fengari) that runs the
 * plugin behind a mock of the keyboard's Plugins panel, and a .wmplugin zip
 * with its sha256 (the manifest field the app insists on for plugins).
 */
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { fmtBytes } from '../../lib/net';
import { PLUGIN_API_VERSION, PLUGIN_ID_PATTERN, readPlugin } from '../../lib/payloads';
import { checkLua, LUA_DIFFERENCES, type LuaDiagnostic } from '../../lib/lua-checks';
import type { LuaSandbox, PluginEvent, Widget } from '../../lib/lua-sandbox';
import { sha256Hex, slugify } from '../../lib/util';
import { writeZip } from '../../lib/zip';
import { CopyButton, Notice } from '../common';
import { IconDownload, IconPlay, IconPlus, IconRefresh, IconTrash, IconWarn } from '../icons';
import { CodeEditor } from './CodeEditor';
import { Area, DropZone, ExportPanel, saveBytes, Section, Text, Toggle, useDraft } from './shared';

interface Draft {
	id: string;
	name: string;
	pluginVersion: string;
	author: string;
	description: string;
	storage: boolean;
	files: { name: string; text: string }[];
	open: string;
}

const TEMPLATES: Record<string, { title: string; storage: boolean; lua: string }> = {
	blank: {
		title: 'Blank',
		storage: false,
		lua: `-- Every plugin defines render(); on_event(e) is optional.
local clicks = 0

function on_event(e)
  if e.type == "click" and e.id == "go" then clicks = clicks + 1 end
end

function render()
  return ui.column {
    ui.label { text = "Hello from Lua", style = "title" },
    ui.label { text = "Pressed " .. clicks .. " times", style = "caption" },
    ui.button { id = "go", text = "Press me", style = "primary" },
  }
end
`,
	},
	texttool: {
		title: 'Text tool',
		storage: false,
		lua: `-- Takes text from the plugin's own box, transforms it, offers Insert.
local text, output = "", ""

local ACTIONS = {
  upper = string.upper,
  lower = string.lower,
  reverse = string.reverse,
  words = function(s) local n = 0 for _ in s:gmatch("%S+") do n = n + 1 end return n .. " words" end,
}

function on_event(e)
  if e.type == "input_changed" and e.id == "text" then text = e.value
  elseif e.type == "click" and ACTIONS[e.id] then output = ACTIONS[e.id](text)
  elseif e.type == "click" and e.id == "clear" then text, output = "", "" wm.ui.set_input("text", "") end
end

function render()
  return ui.column {
    ui.input { id = "text", label = "Text", placeholder = "Type or paste here" },
    ui.row {
      ui.button { id = "upper", text = "UPPER" },
      ui.button { id = "lower", text = "lower" },
      ui.button { id = "reverse", text = "esreveR" },
      ui.button { id = "words", text = "Count" },
    },
    ui.divider(),
    output ~= "" and ui.output { id = "out", text = output, mono = true } or ui.label { text = "Result appears here", style = "caption" },
    ui.button { id = "clear", text = "Clear" },
  }
end
`,
	},
	todo: {
		title: 'To-do list (storage)',
		storage: true,
		lua: `-- Remembers items with wm.storage (needs the "storage" permission).
local items, draft = {}, ""

local function load_items()
  local saved = wm.storage.get("items")
  if saved then items = wm.json.decode(saved) or {} end
end

local function save_items()
  local ok, why = wm.storage.set("items", wm.json.encode(items))
  if not ok then wm.log("save failed: " .. tostring(why)) end
end

function on_event(e)
  if e.type == "input_changed" and e.id == "draft" then draft = e.value
  elseif e.type == "click" and e.id == "add" and draft ~= "" then
    table.insert(items, { text = draft, done = false })
    draft = ""
    wm.ui.set_input("draft", "")
    save_items()
  elseif e.type == "toggle" then
    local n = tonumber(e.id:match("^done:(%d+)$"))
    if n and items[n] then items[n].done = e.value save_items() end
  elseif e.type == "click" and e.id == "clear" then
    items = {} save_items()
  end
end

function render()
  local rows = { ui.input { id = "draft", label = "New item" }, ui.button { id = "add", text = "Add", style = "primary" }, ui.divider() }
  for i, it in ipairs(items) do
    rows[#rows + 1] = ui.toggle { id = "done:" .. i, label = it.text, checked = it.done }
  end
  if #items == 0 then rows[#rows + 1] = ui.label { text = "Nothing yet.", style = "caption" } end
  rows[#rows + 1] = ui.button { id = "clear", text = "Clear all" }
  return ui.column(rows)
end

load_items()
`,
	},
};

function blank(): Draft {
	return { id: 'com.example.hello', name: 'Hello', pluginVersion: '1.0.0', author: '', description: '', storage: false, files: [{ name: 'main.lua', text: TEMPLATES.blank!.lua }], open: 'main.lua' };
}

function manifestOf(d: Draft) {
	return { format: 'wmkeyboard-plugin', version: 1, id: d.id.trim().toLowerCase(), name: d.name.trim(), pluginVersion: d.pluginVersion.trim(), author: d.author.trim(), description: d.description.trim(), apiVersion: PLUGIN_API_VERSION, entry: 'main.lua', permissions: d.storage ? ['storage'] : [] };
}

/* ---------- the mock Plugins panel ---------- */

/** Lua tables mixing an array part with named keys (ui.tabs, ui.page) arrive as objects with "1","2"… keys. */
function arr<T = Widget>(v: unknown): T[] {
	if (Array.isArray(v)) return v as T[];
	if (v && typeof v === 'object') return Object.keys(v).filter((k) => /^\d+$/.test(k)).sort((a, b) => Number(a) - Number(b)).map((k) => (v as Record<string, T>)[k]!);
	return [];
}

function PanelWidget({ w, inputs, onEvent, depth }: { w: Widget; inputs: Map<string, string>; onEvent: (e: PluginEvent) => void; depth: number }) {
	const [tab, setTab] = useState(0);
	if (!w || typeof w !== 'object' || depth > 12) return null;
	const kids = (list: unknown) => arr(list).map((k, i) => <PanelWidget key={i} w={k} inputs={inputs} onEvent={onEvent} depth={depth + 1} />);
	const txt = (v: unknown) => String(v ?? '').slice(0, 2048);
	switch (w.type) {
		case 'column': return <div style="display:flex;flex-direction:column;gap:0.5rem">{kids(w.children)}</div>;
		case 'row': return <div style="display:flex;flex-wrap:wrap;gap:0.4rem;align-items:center">{kids(w.children)}</div>;
		case 'label': return <div style={w.style === 'title' ? 'font-weight:700;font-size:1.05rem' : w.style === 'caption' ? 'font-size:0.78rem;color:var(--st-muted)' : 'font-size:0.9rem'}>{txt(w.text)}</div>;
		case 'output': return (
			<div style="border:1px solid var(--st-card-border);border-radius:8px;padding:0.5rem 0.6rem;background:var(--sl-color-gray-7)">
				<div style={`white-space:pre-wrap;font-size:0.86rem;${w.mono ? 'font-family:var(--sl-font-mono)' : ''}`}>{txt(w.text)}</div>
				<div style="display:flex;gap:0.3rem;margin-top:0.4rem">
					{w.insertable !== false && <button class="st-btn st-btn-sm" onClick={() => alert(`The keyboard would insert:\n\n${txt(w.text)}`)}>Insert</button>}
					{w.copyable !== false && <button class="st-btn st-btn-sm st-btn-ghost" onClick={() => navigator.clipboard.writeText(txt(w.text))}>Copy</button>}
				</div>
			</div>
		);
		case 'button': return <button class={`st-btn st-btn-sm ${w.style === 'primary' ? 'st-btn-primary' : ''}`} disabled={w.enabled === false} onClick={() => onEvent({ type: 'click', id: String(w.id ?? '') })}>{txt(w.text) || String(w.id ?? '')}</button>;
		case 'toggle': return <label class="st-switch"><input type="checkbox" checked={!!w.checked} onChange={(e) => onEvent({ type: 'toggle', id: String(w.id ?? ''), value: (e.target as HTMLInputElement).checked })} /> {txt(w.label)}</label>;
		case 'input': {
			const id = String(w.id ?? '');
			return (
				<div class="st-field">
					{w.label && <label>{txt(w.label)}</label>}
					<input class="st-input" placeholder={txt(w.placeholder)} value={inputs.get(id) ?? ''} onInput={(e) => onEvent({ type: 'input_changed', id, value: (e.target as HTMLInputElement).value.slice(0, 8192) })} />
				</div>
			);
		}
		case 'spacer': return <div style={{ height: `${Math.max(0, Math.min(64, Number(w.height ?? 8)))}px` }} />;
		case 'divider': return <hr style="border:0;border-top:1px solid var(--st-card-border);margin:0.2rem 0" />;
		case 'progress': return <div class="st-progress"><i style="width:40%;animation:st-shimmer 1.2s infinite" /></div>;
		case 'tabs': {
			const pages = arr<{ title?: string; children?: unknown }>(w.pages).slice(0, 8);
			const cur = pages[Math.min(tab, pages.length - 1)];
			return (
				<div>
					<div class="st-file-tabs" style="padding:0">{pages.map((p, i) => <button key={i} role="tab" aria-selected={i === tab} onClick={() => { setTab(i); onEvent({ type: 'tab_selected', id: String(w.id ?? ''), index: i }); }}>{String(p.title ?? `Tab ${i + 1}`)}</button>)}</div>
					<div style="display:flex;flex-direction:column;gap:0.5rem;padding-top:0.6rem">{cur && kids(cur.children)}</div>
				</div>
			);
		}
		default: return <div class="st-pill st-pill-err">unknown widget "{String(w.type)}"</div>;
	}
}

function Sandbox({ d }: { d: Draft }) {
	const [tree, setTree] = useState<Widget[] | null>(null);
	const [log, setLog] = useState<string[]>([]);
	const [error, setError] = useState<string | null>(null);
	const [inputs] = useState(() => new Map<string, string>());
	const [, bump] = useState(0);
	const sb = useRef<LuaSandbox | null>(null);
	const main = d.files.find((f) => f.name === 'main.lua')?.text ?? '';

	const start = async () => {
		sb.current?.close();
		setLog([]);
		setError(null);
		inputs.clear();
		try {
			const { LuaSandbox } = await import('../../lib/lua-sandbox');
			const s = new LuaSandbox({
				pluginId: d.id,
				pluginVersion: d.pluginVersion,
				storage: d.storage,
				onLog: (l) => setLog((x) => [...x, l]),
				onSetInput: (id, text) => { inputs.set(id, text); bump((n) => n + 1); },
			});
			sb.current = s;
			setTree(s.load(main));
		} catch (e) {
			setError((e as Error).message);
			setTree(null);
		}
	};
	const onEvent = (e: PluginEvent) => {
		if (e.type === 'input_changed') inputs.set(e.id, e.value);
		try {
			if (sb.current) setTree(sb.current.dispatch(e));
		} catch (err) {
			setError((err as Error).message);
		}
	};
	useEffect(() => () => sb.current?.close(), []);
	const nodes = tree ? countNodes(tree) : 0;
	return (
		<div class="st-panel" style="padding:0.8rem">
			<div class="st-row" style="justify-content:space-between;margin-bottom:0.6rem">
				<b class="st-small">Plugins panel (preview)</b>
				<div class="st-row">
					<button class="st-btn st-btn-sm st-btn-primary" onClick={start}>{tree ? <IconRefresh /> : <IconPlay />} {tree ? 'Reload' : 'Run'}</button>
				</div>
			</div>
			<div style="border:1px solid var(--st-card-border);border-radius:var(--st-radius);padding:0.8rem;background:var(--st-card-bg);min-height:8rem;max-height:32rem;overflow:auto">
				{error && <Notice kind="err" icon={<IconWarn />}><pre style="white-space:pre-wrap;margin:0;background:none;border:0;padding:0">{error}</pre></Notice>}
				{!tree && !error && <div class="st-muted st-small">Press Run to load main.lua in a sandbox with the same rules as the app.</div>}
				{tree && tree.map((w, i) => <PanelWidget key={i} w={w} inputs={inputs} onEvent={onEvent} depth={0} />)}
			</div>
			{tree && nodes > 256 && <div class="st-pill st-pill-err" style="margin-top:0.4rem">{nodes} widgets; the app drops past 256</div>}
			<details style="margin-top:0.6rem" open={log.length > 0}>
				<summary class="st-small st-muted" style="cursor:pointer">Log ({log.length}){sb.current && d.storage ? ` · storage ${sb.current.storage.size} keys` : ''}</summary>
				<pre class="st-code" style="max-height:10rem;border:1px solid var(--st-card-border);border-radius:var(--st-radius-sm);margin-top:0.4rem">{log.join('\n') || '(nothing logged)'}</pre>
			</details>
		</div>
	);
}

function countNodes(list: Widget[]): number {
	let n = 0;
	const walk = (w: Widget) => {
		n++;
		arr(w.children).forEach(walk);
		arr<{ children?: unknown }>(w.pages).forEach((p) => arr(p.children).forEach(walk));
	};
	list.forEach(walk);
	return n;
}

export function PluginBuilder() {
	const [d, setD, reset] = useDraft<Draft>('plugin', blank);
	const [err, setErr] = useState<string | null>(null);
	const [sha, setSha] = useState<string | null>(null);
	const patch = (p: Partial<Draft>) => setD({ ...d, ...p });
	const cur = d.files.find((f) => f.name === d.open) ?? d.files[0]!;
	const setFile = (name: string, text: string) => patch({ files: d.files.map((f) => (f.name === name ? { ...f, text } : f)) });
	const manifest = useMemo(() => manifestOf(d), [d]);
	const diags = useMemo<LuaDiagnostic[]>(() => checkLua(d.files.find((f) => f.name === 'main.lua')?.text ?? '', { storage: d.storage }), [d.files, d.storage]);
	const luaBytes = d.files.filter((f) => f.name.endsWith('.lua')).reduce((n, f) => n + new TextEncoder().encode(f.text).byteLength, 0);
	const problems: string[] = [];
	if (!PLUGIN_ID_PATTERN.test(manifest.id)) problems.push('Id must be 3–64 chars of a-z 0-9 . _ - and start with a letter or digit (com.example.tool).');
	if (!manifest.name) problems.push('Name is required.');
	if (manifest.name.length > 40) problems.push('Name is over 40 characters.');
	if (!manifest.pluginVersion) problems.push('Version is required.');
	if (manifest.description.length > 280) problems.push('Description is truncated at 280 characters.');
	if (luaBytes > 256 * 1024) problems.push('Lua is over 256 KB.');
	if (d.files.length > 15) problems.push('Over 16 archive entries (plugin.json counts).');
	const errors = diags.filter((x) => x.severity === 'error');

	const zip = useMemo(() => writeZip([{ name: 'plugin.json', data: JSON.stringify(manifest, null, 2) + '\n' }, ...d.files.map((f) => ({ name: f.name, data: f.text }))]), [manifest, d.files]);
	useEffect(() => {
		let live = true;
		sha256Hex(zip).then((h) => live && setSha(h));
		return () => { live = false; };
	}, [zip]);

	const importPlugin = (bytes: Uint8Array) => {
		try {
			const p = readPlugin(bytes);
			setD({
				id: p.manifest.id,
				name: p.manifest.name,
				pluginVersion: p.manifest.pluginVersion,
				author: p.manifest.author,
				description: p.manifest.description,
				storage: p.manifest.permissions.includes('storage'),
				files: p.files.filter((f) => f.name !== 'plugin.json' && f.text != null).map((f) => ({ name: f.name, text: f.text! })),
				open: p.manifest.entry,
			});
			setErr(p.problems.join(' ') || null);
		} catch (e) {
			setErr((e as Error).message);
		}
	};

	return (
		<div class="st-creator-layout">
			<div style="display:flex;flex-direction:column;gap:0.8rem">
				<div>
					<span class="st-kicker">Plugin</span>
					<h1 style="font-size:1.5rem;font-weight:800">A tool in Lua</h1>
					<p class="st-muted st-small" style="max-width:62ch;margin-top:0.3rem">
						A plugin draws a panel with <code>ui.*</code> widgets from <code>render()</code> and reacts in <code>on_event(e)</code>. It never sees what you type: only its own input boxes, and its output reaches the field through the keyboard's Insert button. Run it here in the same sandbox before packing it.
					</p>
				</div>
				<Section title="Start from a template or a .wmplugin" open={false}>
					<div class="st-row">
						{Object.entries(TEMPLATES).map(([k, t]) => <button key={k} class="st-btn st-btn-sm" onClick={() => { if (confirm('Replace main.lua with this template?')) patch({ storage: t.storage, files: d.files.map((f) => (f.name === 'main.lua' ? { ...f, text: t.lua } : f)), open: 'main.lua' }); }}>{t.title}</button>)}
					</div>
					<DropZone accept=".wmplugin,.zip" multiple={false} onFiles={([f]) => f && importPlugin(f.bytes)}>Drop a .wmplugin to edit it</DropZone>
					{err && <Notice kind="warn" icon={<IconWarn />}>{err}</Notice>}
				</Section>
				<Section title="plugin.json">
					<div class="st-grid2">
						<Text label="Id" required mono value={d.id} onInput={(v) => patch({ id: v })} placeholder="com.example.tool" />
						<Text label="Name" required value={d.name} onInput={(v) => patch({ name: v })} />
						<Text label="Version" required mono value={d.pluginVersion} onInput={(v) => patch({ pluginVersion: v })} />
						<Text label="Author" value={d.author} onInput={(v) => patch({ author: v })} />
					</div>
					<Area label="Description" value={d.description} onInput={(v) => patch({ description: v })} rows={2} help={`${d.description.length}/280`} />
					<Toggle label='Permission: "storage" (on-device key/value, 128 keys × 8 KB, 64 KB total)' value={d.storage} onInput={(v) => patch({ storage: v })} help="The only permission that exists. Without it wm.storage is nil." />
				</Section>
				<Section title="Code">
					<div class="st-file-tabs" style="padding:0">
						{d.files.map((f) => <button key={f.name} role="tab" aria-selected={d.open === f.name} onClick={() => patch({ open: f.name })}>{f.name}</button>)}
						<button style="margin-left:auto" onClick={() => { const n = prompt('File name (e.g. util.lua)'); if (n && !d.files.some((f) => f.name === n)) patch({ files: [...d.files, { name: n, text: '' }], open: n }); }}><IconPlus style="width:0.9rem;height:0.9rem" /></button>
						{cur.name !== 'main.lua' && <button onClick={() => patch({ files: d.files.filter((f) => f.name !== cur.name), open: 'main.lua' })}><IconTrash style="width:0.9rem;height:0.9rem" /></button>}
					</div>
					<CodeEditor value={cur.text} onChange={(v) => setFile(cur.name, v)} lang={cur.name.endsWith('.lua') ? 'lua' : cur.name.endsWith('.json') ? 'json' : 'text'} height="56vh" />
					{cur.name !== 'main.lua' && <p class="st-small st-muted">Extra files travel in the archive but the sandbox has no <code>require</code>; only main.lua runs. Keep them for data you read with your own parser, or fold the code into main.lua.</p>}
					{diags.length > 0 && (
						<div class="st-preview-body">
							{diags.map((x, i) => (
								<div class="st-finding" key={i}>
									<span class={`st-pill ${x.severity === 'error' ? 'st-pill-err' : 'st-pill-warn'}`}>{x.severity}</span>
									<div class="st-small"><b>line {x.line}</b>: {x.message}</div>
								</div>
							))}
						</div>
					)}
				</Section>
				<Section title="API cheat sheet" open={false}>
					<pre class="st-code" style="border:1px solid var(--st-card-border);border-radius:var(--st-radius-sm)">{`render() -> widget | { widgets }      on_event(e)  e.type: click | toggle(e.value) | input_changed(e.value) | tab_selected(e.index, 0-based); e.id
ui.column{...} ui.row{...} ui.spacer{height} ui.divider() ui.progress()
ui.label{text, style="title"|"body"|"caption"}
ui.output{id, text, mono, insertable=true, copyable=true}
ui.button{id, text, style="primary", enabled=true}   ui.toggle{id, label, checked}
ui.input{id, label, placeholder}   ui.tabs{id, ui.page{title, ...}, ...}  (top level, ≤8)
wm.log(msg)  wm.ui.set_input(id, text)  wm.json.encode(v) / decode(s)  wm.api_version wm.plugin_id wm.plugin_version
wm.storage.get/set/remove/keys  (only with the "storage" permission)
Lua 5.2 (luaj): base, string, table, math, bit32, os.time/clock/date. No io, require, load, coroutine, debug, network.
Budgets: 30M instr / 3 s load · 20M / 2 s event · 4M / 0.5 s render · 256 widgets · 2 KB per text · 8 KB input`}</pre>
					<p class="st-small st-muted">Full reference: <a href="/plugins/api-reference/">/plugins/api-reference</a>. {LUA_DIFFERENCES.join(' ')}</p>
				</Section>
			</div>
			<aside class="st-creator-side">
				<Sandbox d={d} />
				<ExportPanel title="Export" json={manifest}>
					<div class="st-small st-muted">{fmtBytes(zip.byteLength)} · {d.files.length + 1} entries · Lua {fmtBytes(luaBytes)}</div>
					<button class="st-btn st-btn-primary" disabled={problems.length > 0 || errors.length > 0} onClick={() => saveBytes(`${slugify(d.id.split('.').pop() || d.name) || 'plugin'}.wmplugin`, zip)}><IconDownload /> .wmplugin</button>
					{sha && <CopyButton text={JSON.stringify({ id: slugify(d.id.split('.').pop() || d.name), type: 'plugin', name: manifest.name, version: manifest.pluginVersion, author: manifest.author, description: manifest.description, path: `plugins/${slugify(d.id.split('.').pop() || d.name)}.wmplugin`, sha256: sha, sizeBytes: zip.byteLength }, null, 2)} label="Copy repository entry (with sha256)" class="st-btn" />}
					<button class="st-btn st-btn-ghost st-btn-sm" onClick={() => { if (confirm('Discard this draft?')) reset(); }}>Start over</button>
				</ExportPanel>
				{(problems.length > 0 || errors.length > 0) && <Notice kind="err" icon={<IconWarn />}><ul style="padding-left:1rem">{problems.map((p, i) => <li key={i}>{p}</li>)}{errors.length > 0 && <li>{errors.length} error{errors.length === 1 ? '' : 's'} in main.lua.</li>}</ul></Notice>}
				<div class="st-panel st-small st-muted">The app refuses a plugin listed without a <code>sha256</code>, so the repository entry above carries one. Installed plugins start switched off; the user enables them under Plugins.</div>
			</aside>
		</div>
	);
}
