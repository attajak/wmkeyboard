#!/usr/bin/env node
/**
 * Fails when src/lib/deep-link-routes.ts drifts from the app.
 *
 *   node scripts/check-deep-link-routes.mjs
 *
 * Compares, in order:
 *   ROUTES        ↔ SettingsRoutes.all        (app/.../SettingsRoutes.kt)
 *   TOOL_NAMES    ↔ enum class ToolbarTool     (core/.../ToolbarTool.kt)
 *   PANEL_NAMES   ↔ enum class PanelKind       (core/.../PanelLayoutSpec.kt)
 *   SCRIPT_NAMES  ↔ LATIN + KeyboardFonts.scriptFontChoices (feature/ime/.../KeyboardFonts.kt)
 *   STORAGE_IDS   ↔ StorageCategories id = ""  (app/.../StorageCategories.kt)
 *   MODE_IDS      ↔ DefaultKeyboardModes       (core/.../KeyboardModes.kt), ids and names
 *   LICENSE_ASSETS↔ app/src/main/assets/licenses/
 *
 * Regex over the Kotlin, like scripts/extract_data.py: the lists are plain
 * string literals and enum entries, nothing a parser is needed for.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');
const read = (rel) => readFileSync(join(root, rel), 'utf8');

function kotlinStringList(source, name) {
	const start = source.indexOf(`val ${name}: List<String> = listOf(`);
	if (start < 0) throw new Error(`no "val ${name}" in source`);
	const end = source.indexOf('\n    )', start);
	return [...source.slice(start, end).matchAll(/"([^"]+)"/g)].map((m) => m[1]);
}

function kotlinEnumEntries(source, name) {
	const start = source.indexOf(`enum class ${name}`);
	if (start < 0) throw new Error(`no "enum class ${name}" in source`);
	let body = source.slice(source.indexOf('{', start) + 1);
	// Entries run up to the first bare ";" line (members follow) or the closing brace.
	const end = body.search(/^\s*;\s*$|^\s*}\s*$/m);
	if (end >= 0) body = body.slice(0, end);
	body = body.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
	// One entry per comma; an entry may carry a constructor call and an annotation.
	return body
		.split(',')
		.map((part) => /(?:@\w+\([^)]*\)\s*)?([A-Z][A-Z0-9_]*)/.exec(part.trim())?.[1])
		.filter(Boolean);
}

/** `id` and `name` of each mode in `val DefaultKeyboardModes`, as "id name", in order. */
function shippedModes(source) {
	const start = source.indexOf('val DefaultKeyboardModes');
	if (start < 0) throw new Error('no "val DefaultKeyboardModes" in KeyboardModes.kt');
	const body = source.slice(start, source.indexOf('\n)', start));
	return [...body.matchAll(/KeyboardMode\(\s*id = "([^"]+)",[\s\S]*?name = "([^"]+)"/g)].map((m) => `${m[1]} ${m[2]}`);
}

/** The scripts `KeyboardFonts.scriptFontChoices` lists, in order, each once. */
function fontScripts(source) {
	const start = source.indexOf('val scriptFontChoices');
	if (start < 0) throw new Error('no "val scriptFontChoices" in KeyboardFonts.kt');
	const end = source.indexOf('\n    )', start);
	const seen = new Set();
	for (const m of source.slice(start, end).matchAll(/ScriptId\.([A-Z_]+)/g)) seen.add(m[1]);
	return [...seen];
}

async function tsLists() {
	// The data module is plain TS with no imports; strip the types and eval.
	const src = read('docs/src/lib/deep-link-routes.ts')
		.replace(/^export interface [\s\S]*?^}/gm, '')
		.replace(/: RouteSpec\[\]/g, '')
		.replace(/: \{ id: string; name: string \}\[\]/g, '')
		.replace(/as const;/g, ';')
		.replace(/^export function humanize[\s\S]*$/m, '')
		.replace(/^export /gm, '');
	const fn = new Function(`${src}\nreturn { ROUTES, TOOL_NAMES, PANEL_NAMES, SCRIPT_NAMES, STORAGE_IDS, LICENSE_ASSETS, MODE_IDS };`);
	return fn();
}

function diff(label, ours, theirs, ordered = true) {
	const a = JSON.stringify(ordered ? ours : [...ours].sort());
	const b = JSON.stringify(ordered ? theirs : [...theirs].sort());
	if (a === b) {
		console.log(`ok    ${label}: ${ours.length} entries`);
		return true;
	}
	const missing = theirs.filter((x) => !ours.includes(x));
	const extra = ours.filter((x) => !theirs.includes(x));
	console.log(`DRIFT ${label}: docs ${ours.length}, app ${theirs.length}`);
	if (missing.length) console.log(`      app has, docs lack: ${missing.join(', ')}`);
	if (extra.length) console.log(`      docs have, app lacks: ${extra.join(', ')}`);
	if (!missing.length && !extra.length) console.log('      same entries, different order');
	return false;
}

const docs = await tsLists();
let ok = true;
// Routes are grouped for the picker, so only the set is compared.
ok &= diff('routes', docs.ROUTES.map((r) => r.pattern), kotlinStringList(read('app/src/main/java/com/wasimaster/wmkeyboard/app/SettingsRoutes.kt'), 'all'), false);
ok &= diff('tools', docs.TOOL_NAMES, kotlinEnumEntries(read('core/common/src/main/java/com/wasimaster/wmkeyboard/core/settings/ToolbarTool.kt'), 'ToolbarTool'));
ok &= diff('panels', docs.PANEL_NAMES, kotlinEnumEntries(read('core/language/src/main/java/com/wasimaster/wmkeyboard/core/layout/PanelLayoutSpec.kt'), 'PanelKind'));
ok &= diff('scripts', docs.SCRIPT_NAMES, ['LATIN', ...fontScripts(read('feature/ime/src/main/java/com/wasimaster/wmkeyboard/ime/ui/KeyboardFonts.kt'))]);
ok &= diff('storage', docs.STORAGE_IDS, [...read('app/src/main/java/com/wasimaster/wmkeyboard/app/storage/StorageCategories.kt').matchAll(/id = "([a-z_]+)"/g)].map((m) => m[1]));
ok &= diff('licenses', docs.LICENSE_ASSETS, readdirSync(join(root, 'app/src/main/assets/licenses')).sort());
ok &= diff('modes', docs.MODE_IDS.map((m) => `${m.id} ${m.name}`), shippedModes(read('core/settings/src/main/java/com/wasimaster/wmkeyboard/core/settings/KeyboardModes.kt')));

// Every {arg} in a pattern must have an ArgSpec, and nothing else may.
for (const r of docs.ROUTES) {
	const inPattern = [...r.pattern.matchAll(/\{(\w+)\}/g)].map((m) => m[1]);
	const declared = (r.args ?? []).map((a) => a.name);
	if (JSON.stringify(inPattern) !== JSON.stringify(declared)) {
		console.log(`DRIFT args of ${r.pattern}: pattern ${inPattern.join(',')} vs args ${declared.join(',')}`);
		ok = false;
	}
}

process.exit(ok ? 0 : 1);
