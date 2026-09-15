/** Port of LuaApi.kt: every name a plugin script can reach, and the ui.* shapes. */

export type LuaApiKind = 'FUNCTION' | 'TABLE' | 'CONSTANT';

export interface LuaApiEntry {
	path: string;
	kind: LuaApiKind;
	/** How it is called, `decode(text)`, or null for a table or a constant. */
	signature: string | null;
	/** Present only when the manifest declares this permission. */
	permission: 'storage' | null;
	name: string;
	parent: string;
}

export interface LuaUiShape {
	constructor: string;
	fields: string[];
	takesChildren: boolean;
	/** Null for `ui.page`, part of a tab strip rather than a widget of its own. */
	widgetType: string | null;
	values: Record<string, string[]>;
	needsId: boolean;
}

export type NilReason = 'LOADS_CODE' | 'FILES' | 'CODE_FROM_TEXT' | 'COROUTINES' | 'JAVA_OR_DEBUGGER';

/** Standard Lua globals the sandbox removes, and why. */
export const nilled: Record<string, NilReason> = {
	require: 'LOADS_CODE',
	package: 'LOADS_CODE',
	io: 'FILES',
	loadfile: 'FILES',
	dofile: 'FILES',
	load: 'CODE_FROM_TEXT',
	loadstring: 'CODE_FROM_TEXT',
	coroutine: 'COROUTINES',
	luajava: 'JAVA_OR_DEBUGGER',
	debug: 'JAVA_OR_DEBUGGER',
};

/** `wm` members that do not exist and never will. */
export const never: ReadonlySet<string> = new Set(['text', 'clipboard', 'http', 'net', 'field', 'insert', 'keys', 'input', 'contacts', 'files', 'fs', 'exec']);

export const labelStyles = ['title', 'body', 'caption'];
export const buttonStyles = ['primary'];
export const eventTypes = ['click', 'toggle', 'input_changed', 'tab_selected'];

const shape = (constructor: string, fields: string[], takesChildren: boolean, widgetType: string | null, values: Record<string, string[]> = {}, needsId = false): LuaUiShape => ({ constructor, fields, takesChildren, widgetType, values, needsId });

export const uiShapes: Record<string, LuaUiShape> = Object.fromEntries(
	[
		shape('column', [], true, 'column'),
		shape('row', [], true, 'row'),
		shape('label', ['text', 'style'], false, 'label', { style: labelStyles }),
		shape('output', ['id', 'text', 'mono', 'insertable', 'copyable'], false, 'output'),
		shape('button', ['id', 'text', 'style', 'enabled'], false, 'button', { style: buttonStyles }, true),
		shape('toggle', ['id', 'label', 'checked'], false, 'toggle', {}, true),
		shape('input', ['id', 'label', 'placeholder'], false, 'input', {}, true),
		shape('spacer', ['height'], false, 'spacer'),
		shape('divider', [], false, 'divider'),
		shape('progress', [], false, 'progress'),
		shape('tabs', ['id'], true, 'tabs'),
		shape('page', ['title'], true, null),
	].map((s) => [s.constructor, s])
);

/** Every `type` string the renderer draws. */
export const widgetTypes: ReadonlySet<string> = new Set(Object.values(uiShapes).map((s) => s.widgetType).filter((t): t is string => !!t));

function entry(path: string, kind: LuaApiKind, signature: string | null = null, permission: 'storage' | null = null): LuaApiEntry {
	const dot = path.lastIndexOf('.');
	return { path, kind, signature, permission, name: dot < 0 ? path : path.slice(dot + 1), parent: dot < 0 ? '' : path.slice(0, dot) };
}

const list: LuaApiEntry[] = [];
const fn = (path: string, signature: string, permission: 'storage' | null = null) => list.push(entry(path, 'FUNCTION', signature, permission));
const table = (path: string, permission: 'storage' | null = null) => list.push(entry(path, 'TABLE', null, permission));
const constant = (path: string) => list.push(entry(path, 'CONSTANT'));

table('_G');
constant('_VERSION');
fn('assert', 'assert(value, message)');
fn('error', 'error(message, level)');
fn('getmetatable', 'getmetatable(value)');
fn('setmetatable', 'setmetatable(table, metatable)');
fn('ipairs', 'ipairs(list)');
fn('pairs', 'pairs(table)');
fn('next', 'next(table, key)');
fn('pcall', 'pcall(f, ...)');
fn('xpcall', 'xpcall(f, handler, ...)');
fn('rawequal', 'rawequal(a, b)');
fn('rawget', 'rawget(table, key)');
fn('rawlen', 'rawlen(value)');
fn('rawset', 'rawset(table, key, value)');
fn('select', 'select(n, ...)');
fn('tonumber', 'tonumber(value, base)');
fn('tostring', 'tostring(value)');
fn('type', 'type(value)');
fn('print', 'print(...)');
fn('collectgarbage', 'collectgarbage(...)');
table('string');
table('table');
table('math');
table('bit32');
table('os');
table('ui');
table('wm');

for (const [name, params] of [
	['byte', 's, i, j'], ['char', '...'], ['dump', 'f'], ['find', 's, pattern, init, plain'], ['format', 'format, ...'],
	['gmatch', 's, pattern'], ['gsub', 's, pattern, replacement, n'], ['len', 's'], ['lower', 's'], ['match', 's, pattern, init'],
	['rep', 's, n, separator'], ['reverse', 's'], ['sub', 's, i, j'], ['upper', 's'],
]) fn(`string.${name}`, `${name}(${params})`);

for (const [name, params] of [
	['concat', 'list, separator, i, j'], ['insert', 'list, position, value'], ['pack', '...'], ['remove', 'list, position'],
	['sort', 'list, comparator'], ['unpack', 'list, i, j'],
]) fn(`table.${name}`, `${name}(${params})`);

for (const [name, params] of [
	['abs', 'x'], ['acos', 'x'], ['asin', 'x'], ['atan', 'x'], ['atan2', 'y, x'], ['ceil', 'x'], ['cos', 'x'], ['cosh', 'x'], ['deg', 'x'],
	['exp', 'x'], ['floor', 'x'], ['fmod', 'x, y'], ['frexp', 'x'], ['ldexp', 'm, e'], ['log', 'x, base'], ['max', 'x, ...'], ['min', 'x, ...'],
	['modf', 'x'], ['pow', 'x, y'], ['rad', 'x'], ['random', 'm, n'], ['randomseed', 'x'], ['sin', 'x'], ['sinh', 'x'], ['sqrt', 'x'],
	['tan', 'x'], ['tanh', 'x'],
]) fn(`math.${name}`, `${name}(${params})`);
constant('math.huge');
constant('math.pi');

for (const [name, params] of [
	['arshift', 'x, shift'], ['band', '...'], ['bnot', 'x'], ['bor', '...'], ['btest', '...'], ['bxor', '...'], ['extract', 'n, field, width'],
	['lrotate', 'x, shift'], ['lshift', 'x, shift'], ['replace', 'n, value, field, width'], ['rrotate', 'x, shift'], ['rshift', 'x, shift'],
]) fn(`bit32.${name}`, `${name}(${params})`);

fn('os.clock', 'clock()');
fn('os.date', 'date(format, time)');
fn('os.time', 'time()');

constant('wm.api_version');
constant('wm.plugin_id');
constant('wm.plugin_version');
fn('wm.log', 'log(message)');
table('wm.json');
fn('wm.json.decode', 'decode(text)');
fn('wm.json.encode', 'encode(value)');
table('wm.ui');
fn('wm.ui.set_input', 'set_input(id, text)');
table('wm.storage', 'storage');
fn('wm.storage.get', 'get(key)', 'storage');
fn('wm.storage.set', 'set(key, value)', 'storage');
fn('wm.storage.remove', 'remove(key)', 'storage');
fn('wm.storage.keys', 'keys()', 'storage');

for (const s of Object.values(uiShapes)) {
	const fields = s.fields.map((f) => `${f} = ...`).join(', ');
	const signature =
		s.fields.length === 0 && !s.takesChildren ? `${s.constructor}()`
		: s.takesChildren && s.fields.length === 0 ? `${s.constructor} { ... }`
		: s.takesChildren ? `${s.constructor} { ${fields}, ... }`
		: `${s.constructor} { ${fields} }`;
	fn(`ui.${s.constructor}`, signature);
}

export const entries: readonly LuaApiEntry[] = list;
const byPath = new Map(list.map((e) => [e.path, e]));
const byParent = new Map<string, LuaApiEntry[]>();
for (const e of list) byParent.set(e.parent, [...(byParent.get(e.parent) ?? []), e]);

export function find(path: string): LuaApiEntry | undefined {
	return byPath.get(path);
}

/** The names directly inside `parent`, or the globals for an empty one. */
export function children(parent: string): LuaApiEntry[] {
	return byParent.get(parent) ?? [];
}
