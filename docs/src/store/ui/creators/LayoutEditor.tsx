/**
 * Layout editor: layers and rows edited on the keyboard mock itself. Click a
 * key to edit it; writes the wmkeyboard-layout envelope the app imports.
 */
import { useMemo, useState } from 'preact/hooks';
import { LANGUAGES } from '../../lib/languages';
import { KEY_ACTION_TYPES, LAYER_KEYS, LAYER_LABELS, LAYOUT_FORMAT, PANEL_LAYER_KEYS, readLayout, type LayerSpec, type LayoutKey, type LayoutSpec } from '../../lib/payloads';
import { slugify } from '../../lib/util';
import { CopyButton, Notice } from '../common';
import { IconDownload, IconPlus, IconTrash, IconWarn } from '../icons';
import { KeyboardMock } from '../previews/KeyboardMock';
import { Area, DropZone, ExportPanel, idError, Num, saveJson, Section, Select, Tags, Text, Toggle, useDraft } from './shared';

const ROWS_QWERTY = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm'];

function blank(): LayoutSpec {
	const row = (s: string): LayoutKey[] => [...s].map((c) => ({ label: c }));
	return {
		id: 'my-layout',
		name: 'My layout',
		langId: 'en',
		version: 2,
		layers: {
			letters: {
				rows: [
					row(ROWS_QWERTY[0]!),
					row(ROWS_QWERTY[1]!),
					[{ label: '', action: { type: 'shift' }, width: 1.5 }, ...row(ROWS_QWERTY[2]!), { label: '', action: { type: 'delete' }, width: 1.5 }],
					[{ label: '', action: { type: 'symbols' }, width: 1.5 }, { label: ',' }, { label: '', action: { type: 'space' }, width: 4 }, { label: '.' }, { label: '', action: { type: 'enter' }, width: 1.5 }],
				],
			},
		},
	};
}

/** Drop defaults so the file looks like the app's `encodeForEditing` output. */
function cleanKey(k: LayoutKey): LayoutKey {
	const o: LayoutKey = { label: k.label };
	if (k.output != null && k.output !== '' && k.output !== k.label) o.output = k.output;
	if (k.shiftLabel) o.shiftLabel = k.shiftLabel;
	if (k.action && k.action.type !== 'text') o.action = k.action;
	if (k.width != null && k.width !== 1) o.width = k.width;
	if (k.rowSpan != null && k.rowSpan !== 1) o.rowSpan = k.rowSpan;
	if (k.longPress?.length) o.longPress = k.longPress;
	if (k.actionAlternates?.length) o.actionAlternates = k.actionAlternates;
	if (k.icon) o.icon = k.icon;
	if (k.iconHint) o.iconHint = k.iconHint;
	if (k.hideHint) o.hideHint = true;
	if (k.forceHint) o.forceHint = true;
	if (k.repeatOnHold) o.repeatOnHold = true;
	if (k.flick && Object.keys(k.flick).length) o.flick = k.flick;
	if (k.labelScale != null && k.labelScale !== 1) o.labelScale = k.labelScale;
	if (k.letters) o.letters = k.letters;
	if (k.role) o.role = k.role;
	return o;
}

function finalize(l: LayoutSpec) {
	const layers: Record<string, LayerSpec> = {};
	for (const [k, layer] of Object.entries(l.layers)) {
		const out: LayerSpec = { rows: layer.rows.map((r) => r.map(cleanKey)) };
		if (layer.numberRow) out.numberRow = layer.numberRow.map(cleanKey);
		if (layer.rowHeights?.some((h) => h !== 1)) out.rowHeights = layer.rowHeights;
		if (layer.fontScale != null) out.fontScale = layer.fontScale;
		if (layer.persistent) out.persistent = true;
		if (layer.themeId) out.themeId = layer.themeId;
		layers[k] = out;
	}
	const layout: LayoutSpec = { id: l.id, name: l.name, langId: l.langId ?? '', version: 2, layers };
	if (l.secondary) layout.secondary = true;
	if (l.tabletExpand === false) layout.tabletExpand = false;
	if (l.themeId) layout.themeId = l.themeId;
	if (l.proximityRows?.length) layout.proximityRows = l.proximityRows;
	if (l.appearance && (l.appearance.fontId || l.appearance.fontScale != null)) layout.appearance = l.appearance;
	return { format: LAYOUT_FORMAT, version: 1, appVersion: 0, appVersionName: '', layout };
}

const ACTION_PARAMS: Record<string, { key: string; label: string; kind: 'text' | 'number' | 'select'; options?: string[] }[]> = {
	tool: [{ key: 'tool', label: 'Tool (ToolbarTool name)', kind: 'text' }],
	layout: [{ key: 'id', label: 'Layout id', kind: 'text' }],
	mod: [{ key: 'key', label: 'Modifier', kind: 'select', options: ['CTRL', 'ALT', 'META'] }],
	send_key: [{ key: 'keyCode', label: 'Android keyCode', kind: 'number' }, { key: 'meta', label: 'Meta mask', kind: 'number' }],
	broadcast: [{ key: 'action', label: 'Intent action', kind: 'text' }],
	braille_dot: [{ key: 'dot', label: 'Dot (1–8)', kind: 'number' }],
	keyman_key: [{ key: 'vkey', label: 'Virtual key', kind: 'number' }, { key: 'modifiers', label: 'Modifiers', kind: 'number' }, { key: 'nextLayer', label: 'Next layer', kind: 'text' }],
	edit: [{ key: 'op', label: 'Operation', kind: 'select', options: ['UP', 'DOWN', 'LEFT', 'RIGHT', 'HOME', 'END', 'PAGE_UP', 'PAGE_DOWN', 'WORD_LEFT', 'WORD_RIGHT', 'SELECT_WORD', 'SELECT_LINE', 'SELECT', 'SELECT_ALL', 'COPY', 'PASTE', 'BACKSPACE', 'DOC_START', 'DOC_END', 'CUT'] }],
	field: [{ key: 'kind', label: 'Panel field', kind: 'select', options: ['emoji_tabs', 'emoji_search', 'emoji_grid', 'clipboard_search', 'clipboard_entities', 'clipboard_list', 'trackpad', 'unknown'] }],
};

export function LayoutEditor() {
	const [l, setL, reset] = useDraft<LayoutSpec>('layout', blank);
	const [layer, setLayer] = useState('letters');
	const [sel, setSel] = useState<[number, number] | null>(null);
	const [shifted, setShifted] = useState(false);
	const [err, setErr] = useState<string | null>(null);
	const layerSpec = l.layers[layer] ?? l.layers['letters'] ?? Object.values(l.layers)[0]!;
	const rows = layerSpec?.rows ?? [];
	const key = sel ? rows[sel[0]]?.[sel[1]] ?? null : null;
	const out = useMemo(() => finalize(l), [l]);
	const json = useMemo(() => JSON.stringify(out, null, 2), [out]);

	const setLayerSpec = (ls: LayerSpec) => setL({ ...l, layers: { ...l.layers, [layer]: ls } });
	const setRows = (r: LayoutKey[][]) => setLayerSpec({ ...layerSpec, rows: r });
	const patchKey = (p: Partial<LayoutKey>) => {
		if (!sel) return;
		setRows(rows.map((r, ri) => (ri === sel[0] ? r.map((k, ki) => (ki === sel[1] ? { ...k, ...p } : k)) : r)));
	};
	const removeKey = () => {
		if (!sel) return;
		setRows(rows.map((r, ri) => (ri === sel[0] ? r.filter((_, ki) => ki !== sel[1]) : r)).filter((r) => r.length));
		setSel(null);
	};
	const insertKey = (after: boolean) => {
		if (!sel) return;
		const at = sel[1] + (after ? 1 : 0);
		setRows(rows.map((r, ri) => (ri === sel[0] ? [...r.slice(0, at), { label: '' }, ...r.slice(at)] : r)));
		setSel([sel[0], at]);
	};
	const moveKey = (dir: -1 | 1) => {
		if (!sel) return;
		const r = rows[sel[0]]!;
		const j = sel[1] + dir;
		if (j < 0 || j >= r.length) return;
		const next = [...r];
		[next[sel[1]], next[j]] = [next[j]!, next[sel[1]]!];
		setRows(rows.map((x, ri) => (ri === sel[0] ? next : x)));
		setSel([sel[0], j]);
	};
	const addRow = () => setRows([...rows, [{ label: '' }]]);
	const removeRow = (ri: number) => { setRows(rows.filter((_, i) => i !== ri)); setSel(null); };
	const addLayer = (k: string) => { setL({ ...l, layers: { ...l.layers, [k]: { rows: [[{ label: '' }]] } } }); setLayer(k); setSel(null); };
	const removeLayer = (k: string) => { const layers = { ...l.layers }; delete layers[k]; setL({ ...l, layers }); setLayer('letters'); setSel(null); };
	const action = key?.action?.type ?? 'text';
	const params = ACTION_PARAMS[action] ?? [];
	const problems: string[] = [];
	if (!l.layers['letters']) problems.push('No "letters" layer; the app requires one.');
	if (!l.id) problems.push('Layout id is required.');
	for (const [k, ls] of Object.entries(l.layers)) if (!ls.rows.length || ls.rows.every((r) => !r.length)) problems.push(`Layer "${k}" has no keys.`);

	return (
		<div class="st-creator-layout st-layout-editor">
			<div style="display:flex;flex-direction:column;gap:0.8rem">
				<div>
					<span class="st-kicker">Layout editor</span>
					<h1 style="font-size:1.5rem;font-weight:800">Keys, rows, layers</h1>
					<p class="st-muted st-small" style="max-width:62ch;margin-top:0.3rem">Click a key on the board to edit it. Widths are relative (1 = a letter key); a layer is a full set of rows (letters, symbols…) and the app fills in a default number row when a layer has none.</p>
				</div>
				<Section title="Start from a layout" open={false}>
					<DropZone accept=".json" multiple={false} onFiles={([f]) => { if (!f) return; try { const r = readLayout(new TextDecoder().decode(f.bytes)); setL(r.layout); setLayer(r.layout.layers['letters'] ? 'letters' : Object.keys(r.layout.layers)[0] ?? 'letters'); setSel(null); setErr(r.problems.join(' ') || null); } catch (e) { setErr((e as Error).message); } }}>Drop a .wmlayout.json (the app's export) to edit it</DropZone>
					{err && <Notice kind="warn" icon={<IconWarn />}>{err}</Notice>}
				</Section>
				<div class="st-panel" style="padding:0.8rem">
					<div class="st-kb-layers">
						{Object.keys(l.layers).map((k) => (
							<button key={k} class="st-chip" aria-pressed={layer === k} onClick={() => { setLayer(k); setSel(null); }}>{LAYER_LABELS[k] ?? k}</button>
						))}
						<select class="st-select" style="width:auto;padding:0.3rem 1.8rem 0.3rem 0.6rem;font-size:0.8rem" value="" onChange={(e) => { const v = (e.target as HTMLSelectElement).value; if (v) addLayer(v); }}>
							<option value="">+ layer…</option>
							{[...LAYER_KEYS, ...PANEL_LAYER_KEYS].filter((k) => !l.layers[k]).map((k) => <option key={k} value={k}>{LAYER_LABELS[k] ?? k}</option>)}
						</select>
						<button class="st-chip" aria-pressed={shifted} onClick={() => setShifted(!shifted)} style="margin-left:auto">⇧ Shift</button>
					</div>
					<KeyboardMock spec={null} layout={l} layer={layer} interactive={false} shifted={shifted} selected={sel} onKeyClick={(ri, ki) => setSel(sel && sel[0] === ri && sel[1] === ki ? null : [ri, ki])} />
					<div class="st-row" style="margin-top:0.6rem;justify-content:space-between">
						<div class="st-row">
							<button class="st-btn st-btn-sm" onClick={addRow}><IconPlus /> Row</button>
							{sel && <button class="st-btn st-btn-sm st-btn-ghost st-btn-danger" onClick={() => removeRow(sel[0])}>Remove row {sel[0] + 1}</button>}
						</div>
						{layer !== 'letters' && <button class="st-btn st-btn-sm st-btn-ghost st-btn-danger" onClick={() => removeLayer(layer)}><IconTrash /> Remove layer</button>}
					</div>
				</div>
				{key && sel && (
					<Section title={`Key · row ${sel[0] + 1}, position ${sel[1] + 1}`}>
						<div class="st-row">
							<button class="st-btn st-btn-sm" onClick={() => insertKey(false)}><IconPlus /> Before</button>
							<button class="st-btn st-btn-sm" onClick={() => insertKey(true)}><IconPlus /> After</button>
							<button class="st-btn st-btn-sm" onClick={() => moveKey(-1)}>← Move</button>
							<button class="st-btn st-btn-sm" onClick={() => moveKey(1)}>Move →</button>
							<button class="st-btn st-btn-sm st-btn-ghost st-btn-danger" onClick={removeKey}><IconTrash /> Remove key</button>
						</div>
						<div class="st-grid2">
							<Text label="Label" value={key.label} onInput={(v) => patchKey({ label: v })} help="What's drawn. Blank on an action key uses the icon." />
							<Text label="Output" value={key.output ?? ''} onInput={(v) => patchKey({ output: v || null })} help="Text typed; blank = the label." />
							<Text label="Shift label" value={key.shiftLabel ?? ''} onInput={(v) => patchKey({ shiftLabel: v || null })} />
							<Select label="Action" value={action} onInput={(v) => patchKey({ action: v === 'text' ? undefined : { type: v } })} options={KEY_ACTION_TYPES} />
							{params.map((pr) => pr.kind === 'select' ? (
								<Select key={pr.key} label={pr.label} value={String(key.action?.[pr.key] ?? pr.options![0])} onInput={(v) => patchKey({ action: { ...key.action!, [pr.key]: v } })} options={pr.options!} />
							) : pr.kind === 'number' ? (
								<Num key={pr.key} label={pr.label} value={Number(key.action?.[pr.key] ?? 0)} onInput={(v) => patchKey({ action: { ...key.action!, [pr.key]: v ?? 0 } })} />
							) : (
								<Text key={pr.key} label={pr.label} value={String(key.action?.[pr.key] ?? '')} onInput={(v) => patchKey({ action: { ...key.action!, [pr.key]: v } })} />
							))}
							<Num label="Width" value={key.width ?? 1} onInput={(v) => patchKey({ width: v ?? 1 })} min={0.25} max={10} step={0.25} />
							<Num label="Row span" value={key.rowSpan ?? 1} onInput={(v) => patchKey({ rowSpan: v ?? 1 })} min={1} max={4} />
							<Num label="Label scale" value={key.labelScale} onInput={(v) => patchKey({ labelScale: v })} allowEmpty min={0.3} max={2} step={0.1} />
							<Text label="Letters (T9-style key set)" value={key.letters ?? ''} onInput={(v) => patchKey({ letters: v || null })} placeholder="abc" help="Output must be its first letter." />
						</div>
						<Tags label="Long-press alternates" value={key.longPress ?? []} onInput={(v) => patchKey({ longPress: v })} placeholder="é, è, ê" help="The first one doubles as the corner hint." />
						<div class="st-grid2">
							<Text label="Icon (named)" value={key.icon ?? ''} onInput={(v) => patchKey({ icon: v || null })} help="A catalogue icon id replacing the label glyph." />
							<Text label="Icon hint" value={key.iconHint ?? ''} onInput={(v) => patchKey({ iconHint: v || null })} />
							<Select label="Role" value={key.role ?? ''} onInput={(v) => patchKey({ role: v || null })} options={[['', '(none)'], 'Period', 'Comma']} />
						</div>
						<div class="st-grid2">
							{(['left', 'up', 'right', 'down'] as const).map((dir) => (
								<Text key={dir} label={`Flick ${dir}`} value={key.flick?.[dir] ?? ''} onInput={(v) => { const flick = { ...(key.flick ?? {}) }; if (v) flick[dir] = v; else delete flick[dir]; patchKey({ flick }); }} />
							))}
						</div>
						<Toggle label="Repeat while held" value={!!key.repeatOnHold} onInput={(v) => patchKey({ repeatOnHold: v })} help="Holding the key keeps firing it, the way delete does. It takes the press and hold, so the alternates stop opening." />
						<Toggle label="Hide the corner hint" value={!!key.hideHint} onInput={(v) => patchKey({ hideHint: v })} />
						<Toggle label="Force the corner hint" value={!!key.forceHint} onInput={(v) => patchKey({ forceHint: v })} />
					</Section>
				)}
				<Section title="Layer" open={false}>
					<div class="st-grid2">
						<Tags label="Row heights" value={(layerSpec.rowHeights ?? []).map(String)} onInput={(v) => setLayerSpec({ ...layerSpec, rowHeights: v.map(Number).filter((n) => Number.isFinite(n)) })} placeholder="1, 1, 1, 1" help="Per row, 1 = normal." />
						<Num label="Font scale" value={layerSpec.fontScale} onInput={(v) => setLayerSpec({ ...layerSpec, fontScale: v })} allowEmpty min={0.5} max={2} step={0.05} />
					</div>
					<Toggle label="Persistent (stays after a key press instead of returning to letters)" value={!!layerSpec.persistent} onInput={(v) => setLayerSpec({ ...layerSpec, persistent: v })} />
					<Toggle label="Custom number row" value={!!layerSpec.numberRow} onInput={(v) => setLayerSpec({ ...layerSpec, numberRow: v ? [...'1234567890'].map((c) => ({ label: c })) : null })} help="Off: the app's default digits for this layer." />
				</Section>
				<Section title="Layout">
					<div class="st-grid2">
						<Text label="Id" required mono value={l.id} onInput={(v) => setL({ ...l, id: v })} error={idError(l.id)} />
						<Text label="Name" required value={l.name} onInput={(v) => setL({ ...l, name: v })} />
						<Text label="Language id" mono value={l.langId ?? ''} onInput={(v) => setL({ ...l, langId: v })} list="st-langs-layout" help="Which language's dictionary and rules it uses." />
						<datalist id="st-langs-layout">{LANGUAGES.map((x) => <option value={x.id} key={x.id}>{x.english}</option>)}</datalist>
						<Text label="Theme id" value={l.themeId ?? ''} onInput={(v) => setL({ ...l, themeId: v || null })} help="Optional theme to apply with this layout." />
					</div>
					<Toggle label="Secondary layout (switchable from a key, not a language)" value={!!l.secondary} onInput={(v) => setL({ ...l, secondary: v })} />
					<Toggle label="Expand on tablets" value={l.tabletExpand !== false} onInput={(v) => setL({ ...l, tabletExpand: v })} />
				</Section>
				<Section title="Raw JSON" open={false}>
					<Area label="Edit the file directly (applies when it parses)" value={json} onInput={(v) => { try { const r = readLayout(v); setL(r.layout); setSel(null); } catch { /* keep typing */ } }} rows={14} mono />
				</Section>
			</div>
			<aside class="st-creator-side">
				<ExportPanel title="Export">
					<button class="st-btn st-btn-primary" disabled={problems.length > 0} onClick={() => saveJson(`${slugify(l.id || l.name)}.wmlayout.json`, out)}><IconDownload /> .wmlayout.json</button>
					<CopyButton text={json} label="Copy JSON" class="st-btn" />
					<button class="st-btn st-btn-ghost st-btn-sm" onClick={() => { if (confirm('Discard this draft?')) { reset(); setSel(null); setLayer('letters'); } }}>Start over</button>
				</ExportPanel>
				{problems.length > 0 && <Notice kind="warn" icon={<IconWarn />}><ul style="padding-left:1rem">{problems.map((p, i) => <li key={i}>{p}</li>)}</ul></Notice>}
				<div class="st-panel st-small st-muted">Import on the phone: Settings › Layout & size › Key layouts › Import, or list the file in a repository with type <code>layout</code>.</div>
			</aside>
		</div>
	);
}
