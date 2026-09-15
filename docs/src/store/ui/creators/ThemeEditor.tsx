/**
 * Theme editor: a form over ThemeSpec with the live keyboard mock beside it.
 * Writes a bare ThemeSpec (.wmtheme.json), exactly what the app exports.
 * Fields the mock can't show (effects, textures on modifiers) still edit.
 */
import { useMemo, useState } from 'preact/hooks';
import { GRADIENT_TYPES, KEY_EFFECT_COLORS, KEY_EFFECTS, KEY_SHAPES, readTheme, THEME_ANIMATIONS, type Gradient, type ThemeSpec } from '../../lib/payloads';
import { slugify } from '../../lib/util';
import { CopyButton, Notice } from '../common';
import { IconDownload, IconPlus, IconTrash, IconWarn } from '../icons';
import { KeyboardMock } from '../previews/KeyboardMock';
import { Area, bytesToBase64, Color, DropZone, ExportPanel, idError, Num, Range, saveJson, Section, Select, Text, Toggle, useDraft } from './shared';

const SHAPES = KEY_SHAPES.filter((s) => s !== 'NONE');

function blank(): ThemeSpec {
	return {
		id: 'my-theme',
		name: 'My theme',
		dark: true,
		boardBackground: 0xff17181c,
		boardGradient: null,
		keyShape: 'ROUNDED',
		keyBackground: 0xff303338,
		keyText: 0xffe9e9ee,
		modifierKeyBackground: 0xff222428,
		modifierKeyText: null,
		enterKeyBackground: 0xff4c8df6,
		enterKeyText: 0xff0b1220,
		accent: 0xff8ab4f8,
		keyBorderWidthDp: 0,
		animation: 'NONE',
		animationSpeed: 1,
		decals: [],
		keyOverrides: {},
		assets: {},
	};
}

/** Strip nulls/empties so the file reads like the app's, then round-trip through the reader. */
function finalize(s: ThemeSpec): ThemeSpec {
	const out: Record<string, unknown> = {};
	for (const [k, v] of Object.entries(s)) {
		if (v === null || v === undefined) continue;
		if (Array.isArray(v) && v.length === 0) continue;
		if (typeof v === 'object' && !Array.isArray(v) && Object.keys(v as object).length === 0) continue;
		out[k] = v;
	}
	return out as ThemeSpec;
}

function GradientEditor({ label, value, onInput }: { label: string; value: Gradient | null | undefined; onInput: (g: Gradient | null) => void }) {
	const g = value ?? null;
	return (
		<div class="st-fieldset" style="padding:0.7rem 0.9rem">
			<div class="st-row" style="justify-content:space-between">
				<b class="st-small">{label}</b>
				{g ? <button class="st-btn st-btn-ghost st-btn-sm" onClick={() => onInput(null)}>Remove</button> : <button class="st-btn st-btn-sm" onClick={() => onInput({ colors: [0xff2a1758, 0xff0b1b3a], type: 'LINEAR', angleDeg: 135 })}><IconPlus /> Add gradient</button>}
			</div>
			{g && (
				<div style="display:flex;flex-direction:column;gap:0.6rem;margin-top:0.6rem">
					<div class="st-grid2">
						<Select label="Type" value={g.type ?? 'LINEAR'} onInput={(v) => onInput({ ...g, type: v })} options={GRADIENT_TYPES} />
						{g.type !== 'RADIAL' && <Range label="Angle" value={g.angleDeg ?? 45} min={0} max={360} step={5} onInput={(v) => onInput({ ...g, angleDeg: v })} format={(v) => `${v}°`} />}
					</div>
					{g.colors.map((c, i) => (
						<div class="st-row" key={i} style="flex-wrap:nowrap;align-items:flex-end">
							<div style="flex:1 1 auto"><Color label={`Stop ${i + 1}`} value={c} onInput={(v) => onInput({ ...g, colors: g.colors.map((x, j) => (j === i ? (v ?? 0) : x)) })} /></div>
							{g.colors.length > 2 && <button class="st-btn st-btn-ghost st-btn-icon" onClick={() => onInput({ ...g, colors: g.colors.filter((_, j) => j !== i) })}><IconTrash /></button>}
						</div>
					))}
					<button class="st-btn st-btn-sm" onClick={() => onInput({ ...g, colors: [...g.colors, g.colors[g.colors.length - 1] ?? 0xff000000] })}><IconPlus /> Add stop</button>
				</div>
			)}
		</div>
	);
}

export function ThemeEditor() {
	const [s, setS, reset] = useDraft<ThemeSpec>('theme', blank);
	const [err, setErr] = useState<string | null>(null);
	const [overrideKey, setOverrideKey] = useState('');
	const p = (patch: Partial<ThemeSpec>) => setS({ ...s, ...patch });
	const spec = useMemo(() => finalize(s), [s]);
	const json = useMemo(() => JSON.stringify(spec, null, 2), [spec]);
	const assets = s.assets ?? {};
	const setAsset = (slot: string, b64: string | null) => {
		const next = { ...assets };
		if (b64) next[slot] = b64;
		else delete next[slot];
		p({ assets: next });
	};
	const imageDrop = (onB64: (b64: string | null) => void) => (
		<DropZone accept="image/*" multiple={false} onFiles={([f]) => f && onB64(bytesToBase64(f.bytes))}>Drop an image (PNG, JPEG, WebP, GIF)</DropZone>
	);
	const assetBytes = Object.values(assets).reduce((n, b) => n + Math.floor((b.length * 3) / 4), 0) + Math.floor(((s.backgroundImageBase64?.length ?? 0) * 3) / 4);

	return (
		<div class="st-creator-layout">
			<div style="display:flex;flex-direction:column;gap:0.8rem">
				<div>
					<span class="st-kicker">Theme editor</span>
					<h1 style="font-size:1.5rem;font-weight:800">Paint the keyboard</h1>
					<p class="st-muted st-small" style="max-width:62ch;margin-top:0.3rem">Every colour is ARGB, so opacity is a first-class part of a colour. The mock follows along; the app draws the same fields plus effects and textures the mock skips.</p>
				</div>
				<Section title="Start from a theme" open={false}>
					<DropZone accept=".json" multiple={false} onFiles={([f]) => { if (!f) return; try { const r = readTheme(new TextDecoder().decode(f.bytes)); setS({ ...blank(), ...r.spec }); setErr(r.problems.join(' ') || null); } catch (e) { setErr((e as Error).message); } }}>Drop a .wmtheme.json (the app's export) to edit it</DropZone>
					{err && <Notice kind="warn" icon={<IconWarn />}>{err}</Notice>}
				</Section>
				<Section title="Identity">
					<div class="st-grid2">
						<Text label="Id" required mono value={s.id} onInput={(v) => p({ id: v })} error={idError(s.id)} />
						<Text label="Name" required value={s.name} onInput={(v) => p({ name: v })} />
					</div>
					<Toggle label="Dark theme" value={s.dark !== false} onInput={(v) => p({ dark: v })} help="Tells the app which system icons and status colours suit the board." />
					<Text label="Family name" value={s.familyName ?? ''} onInput={(v) => p({ familyName: v || null })} help="Optional; groups variants under one name." />
				</Section>
				<Section title="Board">
					<Color label="Board background" value={s.boardBackground} onInput={(v) => p({ boardBackground: v ?? 0xff17181c })} />
					<GradientEditor label="Board gradient" value={s.boardGradient} onInput={(g) => p({ boardGradient: g })} />
					<div class="st-grid2">
						<Color label="Suggestion bar" nullable value={s.suggestionBarBackground} onInput={(v) => p({ suggestionBarBackground: v })} />
						<Color label="Navigation bar" nullable value={s.navigationBarBackground as number | null} onInput={(v) => p({ navigationBarBackground: v })} />
					</div>
					<div class="st-fieldset" style="padding:0.7rem 0.9rem">
						<b class="st-small">Background image</b>
						{s.backgroundImageBase64 ? (
							<div class="st-row" style="margin-top:0.5rem"><span class="st-small st-muted">{Math.round((s.backgroundImageBase64.length * 3) / 4 / 1024)} KB embedded</span><button class="st-btn st-btn-ghost st-btn-sm" onClick={() => p({ backgroundImageBase64: null })}>Remove</button></div>
						) : imageDrop((b) => p({ backgroundImageBase64: b }))}
						{s.backgroundImageBase64 && (
							<div class="st-grid2" style="margin-top:0.6rem">
								<Range label="Opacity" value={s.backgroundImageOpacity ?? 1} min={0} max={1} onInput={(v) => p({ backgroundImageOpacity: v })} />
								<Range label="Blur" value={s.backgroundImageBlur ?? 0} min={0} max={25} step={1} onInput={(v) => p({ backgroundImageBlur: v })} format={(v) => `${v}px`} />
							</div>
						)}
					</div>
				</Section>
				<Section title="Keys">
					<div class="st-grid2">
						<Select label="Shape" value={(s.keyShape as string) ?? 'ROUNDED'} onInput={(v) => p({ keyShape: v })} options={SHAPES} />
						<Num label="Corner radius (dp)" value={s.keyCornerRadiusDp} onInput={(v) => p({ keyCornerRadiusDp: v })} allowEmpty min={0} max={40} help="Rounded shape only; empty = default 9." />
						<Color label="Key" value={s.keyBackground} onInput={(v) => p({ keyBackground: v ?? 0xff303338 })} />
						<Color label="Key text" value={s.keyText} onInput={(v) => p({ keyText: v ?? 0xffe9e9ee })} />
						<Color label="Hint text" nullable value={s.hintText} onInput={(v) => p({ hintText: v })} help="Inherits key text at 55%." />
						<Color label="Pressed key" nullable value={s.pressedKeyBackground} onInput={(v) => p({ pressedKeyBackground: v })} />
						<Color label="Border" nullable value={s.keyBorderColor} onInput={(v) => p({ keyBorderColor: v })} />
						<Num label="Border width (dp)" value={s.keyBorderWidthDp ?? 0} onInput={(v) => p({ keyBorderWidthDp: v ?? 0 })} min={0} max={6} step={0.5} />
					</div>
					<GradientEditor label="Key gradient" value={s.keyGradient} onInput={(g) => p({ keyGradient: g })} />
					<div class="st-grid2">
						<Color label="Modifier key" value={s.modifierKeyBackground} onInput={(v) => p({ modifierKeyBackground: v ?? 0xff222428 })} />
						<Color label="Modifier text" nullable value={s.modifierKeyText} onInput={(v) => p({ modifierKeyText: v })} />
						<Color label="Enter key" value={s.enterKeyBackground} onInput={(v) => p({ enterKeyBackground: v ?? 0xff4c8df6 })} />
						<Color label="Enter text" value={s.enterKeyText} onInput={(v) => p({ enterKeyText: v ?? 0xff0b1220 })} />
					</div>
					<div class="st-fieldset" style="padding:0.7rem 0.9rem">
						<b class="st-small">Key texture</b>
						{assets['keyTexture'] ? (
							<div class="st-row" style="margin-top:0.5rem"><span class="st-small st-muted">{Math.round((assets['keyTexture']!.length * 3) / 4 / 1024)} KB</span><button class="st-btn st-btn-ghost st-btn-sm" onClick={() => setAsset('keyTexture', null)}>Remove</button></div>
						) : imageDrop((b) => setAsset('keyTexture', b))}
						{assets['keyTexture'] && (
							<div class="st-grid2" style="margin-top:0.6rem">
								<Select label="Scale" value={(s.keyTextureScale ?? 'crop').toLowerCase()} onInput={(v) => p({ keyTextureScale: v })} options={['crop', 'stretch', 'tile']} />
								<Range label="Opacity" value={s.keyTextureOpacity ?? 1} min={0} max={1} onInput={(v) => p({ keyTextureOpacity: v })} />
							</div>
						)}
					</div>
				</Section>
				<Section title="Accent, popups, toolbar" open={false}>
					<div class="st-grid2">
						<Color label="Accent" value={s.accent} onInput={(v) => p({ accent: v ?? 0xff8ab4f8 })} />
						<Color label="Gesture trail" nullable value={s.gestureTrailColor} onInput={(v) => p({ gestureTrailColor: v })} />
						<Color label="Popup background" nullable value={s.popupBackground} onInput={(v) => p({ popupBackground: v })} />
						<Color label="Popup text" nullable value={s.popupText} onInput={(v) => p({ popupText: v })} />
						<Select label="Popup shape" value={(s.popupShape as string) ?? ''} onInput={(v) => p({ popupShape: v || null })} options={[['', '(follow keys)'], ...SHAPES.map((x) => [x, x] as [string, string])]} />
						<Select label="Popup placement" value={(s.popupPlacement as string) ?? ''} onInput={(v) => p({ popupPlacement: v || null })} options={[['', '(default)'], ['key', 'On the key'], ['float', 'Floating']]} />
						<Color label="Toolbar icons" nullable value={s.toolbarIcon} onInput={(v) => p({ toolbarIcon: v })} />
						<Color label="Tool circle" nullable value={s.toolCircleBackground} onInput={(v) => p({ toolCircleBackground: v })} />
						<Color label="Active tool circle" nullable value={s.toolCircleActiveBackground} onInput={(v) => p({ toolCircleActiveBackground: v })} />
						<Select label="Tool shape" value={(s.toolShape as string) ?? ''} onInput={(v) => p({ toolShape: v || null })} options={[['', '(circle)'], ...SHAPES.map((x) => [x, x] as [string, string])]} />
						<Color label="Chip background" nullable value={s.chipBackground} onInput={(v) => p({ chipBackground: v })} />
						<Color label="Chip text" nullable value={s.chipText} onInput={(v) => p({ chipText: v })} />
						<Color label="Active chip" nullable value={s.chipActiveBackground} onInput={(v) => p({ chipActiveBackground: v })} />
						<Color label="Active chip text" nullable value={s.chipActiveText} onInput={(v) => p({ chipActiveText: v })} />
					</div>
				</Section>
				<Section title="Type, spacing, motion" open={false}>
					<div class="st-grid2">
						<Text label="Font id" value={s.fontId ?? ''} onInput={(v) => p({ fontId: v || null })} placeholder="google:Press Start 2P" help="serif, google:<Name>, installed:<id>, or blank for default." />
						<Num label="Font scale" value={s.fontScale} onInput={(v) => p({ fontScale: v })} allowEmpty min={0.5} max={2} step={0.05} />
						<Num label="Key gap scale" value={s.keyGapScale} onInput={(v) => p({ keyGapScale: v })} allowEmpty min={0} max={3} step={0.1} />
						<Num label="Key height (dp)" value={s.keyHeightDp as number | null} onInput={(v) => p({ keyHeightDp: v })} allowEmpty min={30} max={90} />
						<Select label="Animation" value={(s.animation as string) ?? 'NONE'} onInput={(v) => p({ animation: v })} options={THEME_ANIMATIONS} />
						<Range label="Animation speed" value={s.animationSpeed ?? 1} min={0.2} max={3} step={0.1} onInput={(v) => p({ animationSpeed: v })} />
					</div>
					<Toggle label="Bold key labels" value={!!s.boldKeyLabels} onInput={(v) => p({ boldKeyLabels: v || null })} />
					<div class="st-grid2">
						<Select label="Key sound" value={s.soundStyle ?? ''} onInput={(v) => p({ soundStyle: v || null })} options={[['', '(user setting)'], 'NONE', 'DEFAULT', 'CLICK', 'TYPEWRITER', 'CUSTOM']} help="A theme can pin a sound; CUSTOM names an installed one below." />
						<Text label="Custom sound id" value={s.soundCustomId ?? ''} onInput={(v) => p({ soundCustomId: v || null })} placeholder="Blip" />
					</div>
				</Section>
				<Section title="Key effects" open={false}>
					<div class="st-grid2">
						<Select label="Effect" value={s.keyEffect ?? ''} onInput={(v) => p({ keyEffect: v || null })} options={[['', '(none)'], ...KEY_EFFECTS.map((x) => [x, x] as [string, string])]} />
						<Text label="Emoji (for EMOJI effect)" value={s.keyEffectParam ?? ''} onInput={(v) => p({ keyEffectParam: v || null })} placeholder="🌸✨" />
						<Select label="Colour" value={(s.keyEffectColor as string) ?? ''} onInput={(v) => p({ keyEffectColor: v || null })} options={[['', 'NATURAL'], ...KEY_EFFECT_COLORS.map((x) => [x, x] as [string, string])]} />
						<Color label="Custom effect colour" nullable value={s.keyEffectCustomColor as number | null} onInput={(v) => p({ keyEffectCustomColor: v })} />
						<Range label="Intensity" value={s.keyEffectIntensity ?? 1} min={0.1} max={3} step={0.1} onInput={(v) => p({ keyEffectIntensity: v })} />
						<Range label="Size" value={(s.keyEffectSize as number) ?? 1} min={0.4} max={3} step={0.1} onInput={(v) => p({ keyEffectSize: v })} />
						<Range label="Speed" value={(s.keyEffectSpeed as number) ?? 1} min={0.3} max={2.5} step={0.1} onInput={(v) => p({ keyEffectSpeed: v })} />
						<Range label="Gravity" value={(s.keyEffectGravity as number) ?? 1} min={-1} max={3} step={0.1} onInput={(v) => p({ keyEffectGravity: v })} />
					</div>
					{s.keyEffect === 'CUSTOM_IMAGE' && (
						<div class="st-fieldset" style="padding:0.7rem 0.9rem">
							<b class="st-small">Effect images (up to 6)</b>
							<div class="st-row" style="margin:0.5rem 0">
								{Object.keys(assets).filter((k) => k.startsWith('effectImage:')).map((k) => (
									<span class="st-tag" key={k}>{k} <button class="st-btn st-btn-ghost st-btn-sm" style="padding:0 0.3rem" onClick={() => setAsset(k, null)}>×</button></span>
								))}
							</div>
							{imageDrop((b) => { const n = Object.keys(assets).filter((k) => k.startsWith('effectImage:')).length; if (n < 6) setAsset(`effectImage:${n}`, b); })}
						</div>
					)}
				</Section>
				<Section title="Per-key overrides" open={false}>
					<p class="st-small st-muted">Key = a lowercase label (w, a, s, d) or an action name: ENTER, SHIFT, SPACE, DELETE, SYMBOLS.</p>
					<div class="st-row" style="flex-wrap:nowrap">
						<input class="st-input" placeholder="w" value={overrideKey} onInput={(e) => setOverrideKey((e.target as HTMLInputElement).value)} />
						<button class="st-btn st-btn-sm" disabled={!overrideKey.trim()} onClick={() => { p({ keyOverrides: { ...s.keyOverrides, [overrideKey.trim()]: { background: s.accent ?? 0xff8ab4f8, text: null } } }); setOverrideKey(''); }}><IconPlus /> Add</button>
					</div>
					{Object.entries(s.keyOverrides ?? {}).map(([k, o]) => (
						<div class="st-fieldset" key={k} style="padding:0.6rem 0.8rem">
							<div class="st-row" style="justify-content:space-between"><code>{k}</code><button class="st-btn st-btn-ghost st-btn-sm" onClick={() => { const next = { ...s.keyOverrides }; delete next[k]; p({ keyOverrides: next }); }}>Remove</button></div>
							<div class="st-grid2" style="margin-top:0.4rem">
								<Color label="Background" nullable value={o.background} onInput={(v) => p({ keyOverrides: { ...s.keyOverrides, [k]: { ...o, background: v } } })} />
								<Color label="Text" nullable value={o.text} onInput={(v) => p({ keyOverrides: { ...s.keyOverrides, [k]: { ...o, text: v } } })} />
								<Color label="Border" nullable value={o.border} onInput={(v) => p({ keyOverrides: { ...s.keyOverrides, [k]: { ...o, border: v } } })} />
							</div>
						</div>
					))}
				</Section>
				<Section title="Decals" open={false}>
					<p class="st-small st-muted">Images pinned over the board (a planet on the corner, a branch across the top). Up to 6.</p>
					{(s.decals ?? []).map((dc, i) => (
						<div class="st-fieldset" key={dc.id} style="padding:0.6rem 0.8rem">
							<div class="st-row" style="justify-content:space-between"><code>{dc.id}</code><button class="st-btn st-btn-ghost st-btn-sm" onClick={() => { setAsset(`decal:${dc.id}`, null); p({ decals: s.decals!.filter((_, j) => j !== i) }); }}>Remove</button></div>
							{!assets[`decal:${dc.id}`] && imageDrop((b) => setAsset(`decal:${dc.id}`, b))}
							<div class="st-grid2" style="margin-top:0.4rem">
								<Range label="X" value={dc.x ?? 0.5} min={0} max={1} onInput={(v) => p({ decals: s.decals!.map((x, j) => (j === i ? { ...x, x: v } : x)) })} />
								<Range label="Y" value={dc.y ?? 0.5} min={0} max={1} onInput={(v) => p({ decals: s.decals!.map((x, j) => (j === i ? { ...x, y: v } : x)) })} />
								<Range label="Scale" value={dc.scale ?? 0.25} min={0.05} max={1} onInput={(v) => p({ decals: s.decals!.map((x, j) => (j === i ? { ...x, scale: v } : x)) })} />
								<Range label="Rotation" value={dc.rotationDeg ?? 0} min={-180} max={180} step={5} onInput={(v) => p({ decals: s.decals!.map((x, j) => (j === i ? { ...x, rotationDeg: v } : x)) })} format={(v) => `${v}°`} />
								<Range label="Opacity" value={dc.opacity ?? 1} min={0} max={1} onInput={(v) => p({ decals: s.decals!.map((x, j) => (j === i ? { ...x, opacity: v } : x)) })} />
							</div>
						</div>
					))}
					<button class="st-btn st-btn-sm" disabled={(s.decals?.length ?? 0) >= 6} onClick={() => p({ decals: [...(s.decals ?? []), { id: `decal${(s.decals?.length ?? 0) + 1}`, x: 0.8, y: 0.2, scale: 0.25, rotationDeg: 0, opacity: 1 }] })}><IconPlus /> Add decal</button>
				</Section>
				<Section title="Raw JSON" open={false}>
					<Area label="Edit the spec directly (applies when it parses)" value={json} onInput={(v) => { try { const r = readTheme(v); setS({ ...blank(), ...r.spec }); setErr(r.problems.join(' ') || null); } catch { /* keep typing */ } }} rows={14} mono />
				</Section>
			</div>
			<aside class="st-creator-side">
				<div class="st-panel" style="padding:0.8rem">
					<KeyboardMock spec={spec} />
					<p class="st-kb-caption">Live. Press a key to see the popup.</p>
				</div>
				<ExportPanel title="Export">
					<button class="st-btn st-btn-primary" disabled={!s.id || !s.name} onClick={() => saveJson(`${slugify(s.id || s.name)}.wmtheme.json`, spec)}><IconDownload /> .wmtheme.json</button>
					<CopyButton text={json} label="Copy JSON" class="st-btn" />
					<span class="st-small st-muted">{Math.round(json.length / 1024)} KB{assetBytes ? ` · ${Math.round(assetBytes / 1024)} KB of images embedded` : ''}</span>
					<button class="st-btn st-btn-ghost st-btn-sm" onClick={() => { if (confirm('Discard this draft?')) reset(); }}>Start over</button>
				</ExportPanel>
			</aside>
		</div>
	);
}
