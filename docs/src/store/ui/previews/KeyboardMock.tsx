/**
 * A mock keyboard that renders a real ThemeSpec (colours, shapes, gradients,
 * textures, background image, popups, key overrides, decals, animation) and,
 * given a LayoutSpec, real key rows. The port of docs/ThemePreview.astro,
 * client-side, with the layout half added.
 */
import { useMemo, useState } from 'preact/hooks';
import type { JSX } from 'preact';
import { isModifierAction, keyFallbackLabel, LAYER_LABELS, type LayoutKey, type LayoutReadResult, type LayoutSpec, type ThemeReadResult, type ThemeSpec } from '../../lib/payloads';
import { Problems } from './Preview';

/* ---------- colour helpers ---------- */

export function argbToCss(v: number | null | undefined, fallback: string): string {
	if (v == null || !Number.isFinite(v)) return fallback;
	const n = Number(v) >>> 0;
	const a = ((n >>> 24) & 255) / 255;
	return `rgba(${(n >>> 16) & 255},${(n >>> 8) & 255},${n & 255},${+a.toFixed(3)})`;
}

function gradientCss(g: { colors: number[]; type?: string; angleDeg?: number }): string {
	const stops = (g.colors ?? []).map((c) => argbToCss(c, 'transparent')).join(',');
	switch (g.type ?? 'LINEAR') {
		case 'RADIAL': return `radial-gradient(circle at 50% 35%, ${stops})`;
		case 'SWEEP': return `conic-gradient(from ${g.angleDeg ?? 0}deg at 50% 50%, ${stops})`;
		default: return `linear-gradient(${(g.angleDeg ?? 45) + 90}deg, ${stops})`;
	}
}

function imageMime(b64: string): string {
	if (b64.startsWith('iVBOR')) return 'image/png';
	if (b64.startsWith('R0lGO')) return 'image/gif';
	if (b64.startsWith('UklGR')) return 'image/webp';
	return 'image/jpeg';
}

function dataUrl(b64: string | null | undefined): string | null {
	return b64 ? `data:${imageMime(b64)};base64,${b64}` : null;
}

function clipFor(shape: string): string {
	if (shape === 'CUT') return 'polygon(12% 0,88% 0,100% 22%,100% 78%,88% 100%,12% 100%,0 78%,0 22%)';
	if (shape === 'SLANT') return 'polygon(16% 0,100% 0,84% 100%,0 100%)';
	if (shape === 'HEXAGON') return 'polygon(16% 0,84% 0,100% 50%,84% 100%,16% 100%,0 50%)';
	const pts: string[] = [];
	const arc = (cx: number, cy: number, rx: number, ry: number, from: number, to: number, steps: number) => {
		for (let i = 0; i <= steps; i++) {
			const t = ((from + ((to - from) * i) / steps) * Math.PI) / 180;
			pts.push(`${(cx + rx * Math.cos(t)).toFixed(1)}% ${(cy + ry * Math.sin(t)).toFixed(1)}%`);
		}
	};
	if (shape === 'TICKET') {
		arc(0, 0, 12, 26, 90, 0, 4); arc(100, 0, 12, 26, 180, 90, 4); arc(100, 100, 12, 26, 270, 180, 4); arc(0, 100, 12, 26, 0, -90, 4);
		return `polygon(${pts.join(',')})`;
	}
	if (shape === 'SCALLOP') {
		const across = 3, down = 3, dx = 14, dy = 14;
		const sx = (100 - 2 * dx) / across, sy = (100 - 2 * dy) / down;
		for (let i = 0; i < across; i++) arc(dx + sx * (i + 0.5), dy, sx / 2, dy, 180, 360, 6);
		for (let j = 0; j < down; j++) arc(100 - dx, dy + sy * (j + 0.5), dx, sy / 2, -90, 90, 6);
		for (let i = across - 1; i >= 0; i--) arc(dx + sx * (i + 0.5), 100 - dy, sx / 2, dy, 0, 180, 6);
		for (let j = down - 1; j >= 0; j--) arc(dx, dy + sy * (j + 0.5), dx, sy / 2, 90, 270, 6);
		return `polygon(${pts.join(',')})`;
	}
	return 'none';
}

function radiusFor(shape: string, dp: number | null | undefined): string {
	switch (shape) {
		case 'PILL': case 'CIRCLE': return '999px';
		case 'SQUIRCLE': return '0.95rem';
		case 'SHARP': case 'NONE': return '0px';
		case 'ARCH': return '1.3rem 1.3rem 0.35rem 0.35rem';
		case 'LEAF': return '1.3rem 0.3rem 1.3rem 0.3rem';
		case 'CUT': case 'SLANT': case 'HEXAGON': case 'SCALLOP': case 'TICKET': return '0px';
		default: return `${dp ?? 9}px`;
	}
}

/** ThemeSpec → the CSS custom properties `.st-kb` consumes. */
export function themeVars(s: ThemeSpec): { vars: Record<string, string>; anim: string } {
	const keyText = argbToCss(s.keyText, '#e9e9ee');
	const shape = s.keyShape ?? 'ROUNDED';
	const sweep = s.boardGradient?.type === 'SWEEP';
	const assets = s.assets ?? {};
	const keyTex = dataUrl(assets['keyTexture']);
	const texScale = (s.keyTextureScale ?? 'crop').toLowerCase();
	const v: Record<string, string> = {
		'--kb-fill-color': argbToCss(s.boardBackground, '#17181c'),
		'--kb-fill-img': s.boardGradient ? gradientCss(s.boardGradient) : 'none',
		'--kb-fill-blur': sweep ? '26px' : '0px',
		'--kb-fill-inset': sweep ? '-30%' : '0%',
		'--kb-bgimg': dataUrl(s.backgroundImageBase64) ? `url(${dataUrl(s.backgroundImageBase64)})` : 'none',
		'--kb-bgimg-op': String(s.backgroundImageOpacity ?? 1),
		'--kb-bgimg-blur': `${s.backgroundImageBlur ?? 0}px`,
		'--kb-key': argbToCss(s.keyBackground, '#303338'),
		'--kb-key-img': s.keyGradient ? gradientCss(s.keyGradient) : 'none',
		'--kb-key-tex': keyTex ? `url(${keyTex})` : 'none',
		'--kb-key-tex-size': texScale === 'tile' ? 'auto' : texScale === 'stretch' ? '100% 100%' : 'cover',
		'--kb-key-tex-repeat': texScale === 'tile' ? 'repeat' : 'no-repeat',
		'--kb-keytext': keyText,
		'--kb-mod': argbToCss(s.modifierKeyBackground, '#222428'),
		'--kb-modtext': argbToCss(s.modifierKeyText, keyText),
		'--kb-hinttext': argbToCss(s.hintText, 'currentColor'),
		'--kb-hint-op': s.hintText != null ? '1' : '0.55',
		'--kb-enter': argbToCss(s.enterKeyBackground, '#4c8df6'),
		'--kb-entertext': argbToCss(s.enterKeyText, '#0b1220'),
		'--kb-key-pressed': argbToCss(s.pressedKeyBackground, argbToCss(s.accent, '#8ab4f8')),
		'--kb-radius': radiusFor(shape, s.keyCornerRadiusDp),
		'--kb-clip': clipFor(shape),
		'--kb-bw': `${s.keyBorderWidthDp ?? 0}px`,
		'--kb-bc': argbToCss(s.keyBorderColor, 'transparent'),
		'--kb-accent': argbToCss(s.accent, '#8ab4f8'),
		'--kb-tool-bg': argbToCss(s.toolCircleBackground, argbToCss(s.modifierKeyBackground, '#222428')),
		'--kb-tool-active': argbToCss(s.toolCircleActiveBackground, argbToCss(s.accent, '#8ab4f8')),
		'--kb-tool-icon': argbToCss(s.toolbarIcon, keyText),
		'--kb-tool-radius': s.toolCircleRadiusDp != null ? `${s.toolCircleRadiusDp}px` : radiusFor(s.toolShape ?? 'CIRCLE', null),
		'--kb-tool-bw': `${s.toolBorderWidthDp ?? 0}px`,
		'--kb-tool-bc': argbToCss(s.toolBorderColor, 'transparent'),
		'--kb-sugg-bg': argbToCss(s.suggestionBarBackground, 'transparent'),
		'--kb-chip-bg': argbToCss(s.chipBackground, 'transparent'),
		'--kb-chip-text': argbToCss(s.chipText ?? s.suggestionText, keyText),
		'--kb-chip-active-bg': argbToCss(s.chipActiveBackground, argbToCss(s.accent, '#8ab4f8')),
		'--kb-chip-active-text': argbToCss(s.chipActiveText, '#fff'),
		'--kb-chip-radius': s.chipCornerRadiusDp != null ? `${s.chipCornerRadiusDp}px` : radiusFor(s.chipShape ?? 'ROUNDED', 12),
		'--kb-popup-bg': argbToCss(s.popupBackground, argbToCss(s.keyBackground, '#303338')),
		'--kb-popup-text': argbToCss(s.popupText, keyText),
		'--kb-popup-radius': radiusFor(s.popupShape ?? shape, 10),
		'--kb-font': String(s.fontScale ?? 1),
		'--kb-key-weight': s.boldKeyLabels ? '600' : '400',
		'--kb-gap': String(s.keyGapScale ?? 1),
		'--kb-dur': String(16 / (s.animationSpeed || 1)),
	};
	return { vars: v, anim: (s.animation ?? 'NONE').toLowerCase() };
}

/* ---------- a QWERTY fallback layout for theme previews ---------- */

const QWERTY: LayoutSpec = {
	id: 'qwerty',
	name: 'QWERTY',
	layers: {
		letters: {
			rows: [
				[['q', '1'], ['w', '2'], ['e', '3'], ['r', '4'], ['t', '5'], ['y', '6'], ['u', '7'], ['i', '8'], ['o', '9'], ['p', '0']].map(([l, h]) => ({ label: l!, longPress: [h!] })),
				[['a', '@'], ['s', '#'], ['d', '$'], ['f', '%'], ['g', '&'], ['h', '-'], ['j', '+'], ['k', '('], ['l', ')']].map(([l, h]) => ({ label: l!, longPress: [h!] })),
				[{ label: '', action: { type: 'shift' }, width: 1.5 }, ...[['z', '*'], ['x', '"'], ['c', "'"], ['v', ':'], ['b', ';'], ['n', '!'], ['m', '?']].map(([l, h]) => ({ label: l!, longPress: [h!] })), { label: '', action: { type: 'delete' }, width: 1.5 }],
				[{ label: '', action: { type: 'symbols' }, width: 1.5 }, { label: ',', longPress: ['!'] }, { label: '', action: { type: 'space' }, width: 4.2 }, { label: '.', longPress: ['…'] }, { label: '', action: { type: 'enter' }, width: 1.5 }],
			],
		},
	},
};

/* ---------- key rendering ---------- */

const KEY_ICONS: Record<string, JSX.Element> = {
	shift: <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M12 4 4 13h5v7h6v-7h5z" /></svg>,
	caps_lock: <svg class="icon" viewBox="0 0 24 24" fill="currentColor"><path d="M12 3 4 12h5v5h6v-5h5zM9 19h6v2H9z" /></svg>,
	delete: <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M21 6H9L3 12l6 6h12z" /><path d="m12 9 6 6M18 9l-6 6" stroke-linecap="round" /></svg>,
	forward_delete: <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M3 6h12l6 6-6 6H3z" /><path d="m7 9 6 6M13 9l-6 6" stroke-linecap="round" /></svg>,
	enter: <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 5v6a3 3 0 0 1-3 3H5" /><path d="m9 10-4 4 4 4" /></svg>,
	language_switch: <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="9" /><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18" /></svg>,
	emoji: <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="9" /><path d="M8.5 14.5c1 1.2 2.2 1.8 3.5 1.8s2.5-.6 3.5-1.8" stroke-linecap="round" /><path d="M9 10h.01M15 10h.01" stroke-width="2.4" stroke-linecap="round" /></svg>,
	input_method_picker: <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="6" width="18" height="12" rx="2" /><path d="M7 10h2M11 10h2M15 10h2M7 14h10" stroke-linecap="round" /></svg>,
};

function keyLabel(k: LayoutKey, shifted: boolean): { text: string; icon: JSX.Element | null } {
	const t = k.action?.type ?? 'text';
	if (KEY_ICONS[t] && !k.label) return { text: '', icon: KEY_ICONS[t]! };
	if (t === 'space') return { text: k.label || 'space', icon: null };
	if (k.label) return { text: shifted && k.shiftLabel ? k.shiftLabel : shifted && t === 'text' && k.label.length === 1 ? k.label.toUpperCase() : k.label, icon: null };
	return { text: keyFallbackLabel(k.action), icon: KEY_ICONS[t] ?? null };
}

function keyClass(k: LayoutKey): string {
	const t = k.action?.type ?? 'text';
	const cls = ['st-kb-key'];
	if (t === 'enter') cls.push('enter');
	else if (t === 'space') cls.push('space');
	else if (isModifierAction(k.action)) {
		cls.push('mod');
		if (!KEY_ICONS[t]) cls.push('text-key');
	}
	return cls.join(' ');
}

function overrideFor(spec: ThemeSpec | null, k: LayoutKey): Record<string, string> | undefined {
	if (!spec?.keyOverrides) return undefined;
	const t = k.action?.type ?? 'text';
	const byAction: Record<string, string> = { enter: 'ENTER', shift: 'SHIFT', space: 'SPACE', delete: 'DELETE', symbols: 'SYMBOLS' };
	const o = spec.keyOverrides[(k.label || '').toLowerCase()] ?? (byAction[t] ? spec.keyOverrides[byAction[t]!] : undefined);
	if (!o) return undefined;
	const s: Record<string, string> = {};
	if (o.background != null) s['background-color'] = argbToCss(o.background, '');
	if (o.text != null) s['color'] = argbToCss(o.text, '');
	if (o.border != null) s['border-color'] = argbToCss(o.border, '');
	return s;
}

export function KeyboardMock({ spec, layout, layer = 'letters', interactive = true, onKeyClick, selected, shifted }: {
	spec: ThemeSpec | null;
	layout?: LayoutSpec | null;
	layer?: string;
	interactive?: boolean;
	onKeyClick?: (rowIndex: number, keyIndex: number) => void;
	selected?: [number, number] | null;
	shifted?: boolean;
}) {
	const { vars, anim } = useMemo(() => themeVars(spec ?? { id: '', name: '' }), [spec]);
	const [pressed, setPressed] = useState<[number, number] | null>(null);
	const l = layout ?? QWERTY;
	const ls = l.layers[layer] ?? l.layers['letters'] ?? Object.values(l.layers)[0];
	const rows = ls?.rows ?? [];
	const heights = ls?.rowHeights ?? [];
	const fontScale = ls?.fontScale ?? l.appearance?.fontScale ?? 1;
	const decals = spec?.decals ?? [];
	const assets = spec?.assets ?? {};
	const numberRow = ls?.numberRow ?? null;
	return (
		<div class="st-kb" style={vars} data-anim={anim} onMouseLeave={() => setPressed(null)}>
			<div class="st-kb-bgimg" />
			<div class="st-kb-fill" />
			{decals.map((d) => {
				const img = dataUrl(assets[`decal:${d.id}`]);
				if (!img) return null;
				return <img key={d.id} class="st-kb-decal" src={img} alt="" style={{ left: `${(d.x ?? 0.5) * 100}%`, top: `${(d.y ?? 0.5) * 100}%`, width: `${(d.scale ?? 0.25) * 100}%`, opacity: d.opacity ?? 1, '--rot': `${d.rotationDeg ?? 0}deg` }} />;
			})}
			<div class="st-kb-ui" style={{ '--kb-font': String((Number(vars['--kb-font']) || 1) * fontScale) }}>
				<div class="st-kb-bar">
					<span class="st-kb-chip">keyboard</span>
					<span class="st-kb-chip on">keyboards</span>
					<span class="st-kb-chip">keys</span>
				</div>
				<div class="st-kb-tools">
					{['on', '', '', '', ''].map((on, i) => (
						<span class={`st-kb-tool ${on}`} key={i}>
							<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="12" cy="12" r={i % 2 ? 3 : 7} /><path d={i % 2 ? 'M12 3v3M12 18v3M3 12h3M18 12h3' : 'M12 9v6M9 12h6'} /></svg>
						</span>
					))}
				</div>
				{numberRow && (
					<div class="st-kb-row">
						{numberRow.map((k, ki) => <Key key={ki} k={k} spec={spec} shifted={!!shifted} />)}
					</div>
				)}
				{rows.map((row, ri) => (
					<div class="st-kb-row" key={ri} style={{ '--h': String(heights[ri] ?? 1) }}>
						{row.map((k, ki) => (
							<Key
								key={ki}
								k={k}
								spec={spec}
								shifted={!!shifted}
								pressed={pressed?.[0] === ri && pressed[1] === ki}
								selected={selected?.[0] === ri && selected[1] === ki}
								onDown={interactive ? () => setPressed([ri, ki]) : undefined}
								onUp={interactive ? () => setPressed(null) : undefined}
								onClick={onKeyClick ? () => onKeyClick(ri, ki) : undefined}
							/>
						))}
					</div>
				))}
			</div>
		</div>
	);
}

function Key({ k, spec, shifted, pressed, selected, onDown, onUp, onClick }: { k: LayoutKey; spec: ThemeSpec | null; shifted: boolean; pressed?: boolean; selected?: boolean; onDown?: () => void; onUp?: () => void; onClick?: () => void }) {
	const { text, icon } = keyLabel(k, shifted);
	const hint = k.hideHint ? null : k.iconHint ? null : k.longPress?.[0] ?? (k.actionAlternates?.[0]?.label || null);
	const t = k.action?.type ?? 'text';
	const showPopup = pressed && t === 'text' && !!text;
	return (
		<span
			class={`${keyClass(k)} ${pressed ? 'pressed' : ''} ${selected ? 'sel' : ''}`}
			style={{ '--w': String(k.width ?? 1), '--ls': String(k.labelScale ?? 1), ...(k.rowSpan && k.rowSpan > 1 ? { '--h': String(k.rowSpan) } : {}), ...overrideFor(spec, k) }}
			onMouseDown={onDown}
			onMouseUp={onUp}
			onTouchStart={onDown}
			onTouchEnd={onUp}
			onClick={onClick}
			role={onClick ? 'button' : undefined}
		>
			{k.shiftLabel && !shifted && t === 'text' && <i class="shift-lbl">{k.shiftLabel}</i>}
			{icon ?? (t === 'space' ? <span class="lbl">{text}</span> : k.letters ? <span class="letters">{k.letters}</span> : text)}
			{hint && t === 'text' && <i class="hint">{hint}</i>}
			{showPopup && <span class="st-kb-popup">{text}</span>}
		</span>
	);
}

/* ---------- preview wrappers ---------- */

export function ThemeMock({ read }: { read: ThemeReadResult }) {
	const spec = read.spec;
	const variants = [spec, ...(spec.variants ?? [])];
	const [i, setI] = useState(0);
	const cur = variants[i] ?? spec;
	const fields = [
		['Key shape', spec.keyShape ?? 'ROUNDED'],
		['Animation', spec.animation ?? 'NONE'],
		['Font', spec.fontId ?? 'default'],
		['Sound', spec.soundStyle ? `${spec.soundStyle}${spec.soundCustomId ? ` (${spec.soundCustomId})` : ''}` : 'default'],
		['Key effect', spec.keyEffect ?? 'none'],
		['Decals', String((spec.decals ?? []).length)],
		['Embedded assets', read.assetCount ? `${read.assetCount} (${Math.round(read.assetBytes / 1024)} KB)` : 'none'],
		['Per-key overrides', String(Object.keys(spec.keyOverrides ?? {}).length)],
	];
	return (
		<>
			<Problems items={read.problems} />
			<div class="st-pv-pad">
				{variants.length > 1 && (
					<div class="st-kb-layers">
						{variants.map((v, j) => (
							<button key={j} class="st-chip" aria-pressed={i === j} onClick={() => setI(j)}>{v.name || `Variant ${j + 1}`}</button>
						))}
					</div>
				)}
				<KeyboardMock spec={cur} />
				<p class="st-kb-caption">Press a key. Rendered from the theme file itself; key effects, textures on modifiers and the landscape image are not shown.</p>
				<dl class="st-kv" style="margin-top:1rem">
					{fields.map(([k, v]) => (
						<>
							<dt>{k}</dt>
							<dd>{v}</dd>
						</>
					))}
				</dl>
			</div>
		</>
	);
}

export function LayoutViewer({ read, spec }: { read: LayoutReadResult; spec?: ThemeSpec | null }) {
	const layout = read.layout;
	const keys = Object.keys(layout.layers);
	const [layer, setLayer] = useState(keys.includes('letters') ? 'letters' : keys[0] ?? 'letters');
	const [shifted, setShifted] = useState(false);
	return (
		<>
			<Problems items={read.problems} />
			<div class="st-pv-pad">
				<div class="st-kb-layers">
					{keys.map((k) => (
						<button key={k} class="st-chip" aria-pressed={layer === k} onClick={() => setLayer(k)}>{LAYER_LABELS[k] ?? k}</button>
					))}
					<button class="st-chip" aria-pressed={shifted} onClick={() => setShifted(!shifted)} style="margin-left:auto">⇧ Shift</button>
				</div>
				<KeyboardMock spec={spec ?? null} layout={layout} layer={layer} shifted={shifted} />
				<p class="st-kb-caption">{layout.name} · {read.keyCount} keys across {keys.length} layer{keys.length === 1 ? '' : 's'}{layout.langId ? ` · language ${layout.langId}` : ''}{layout.secondary ? ' · secondary layout' : ''}</p>
			</div>
		</>
	);
}
