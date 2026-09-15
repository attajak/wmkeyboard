/**
 * The plugin editor's runtime on the web: a port of PluginPreviewSession,
 * PluginRuntime, PluginUiCodec, PluginStorage and PluginBudget over fengari
 * (Lua in JavaScript). It keeps what the editor shows: the panel the plugin
 * drew, the console, what each phase spent, and why it failed.
 *
 * Everything runs on the page's thread, so a runaway loop holds the page until
 * the instruction or time budget aborts it, as the app's watchdog would.
 */
import { signal, type Signal } from '@preact/signals';
// The web bundle carries the Node shims (process, Buffer) fengari's sources expect.
import * as fengari from 'fengari-web/dist/fengari-web.js';
import { S } from '../ui/creators/ide/strings';

const { lua, lauxlib, lualib, to_luastring, to_jsstring } = fengari as unknown as {
	lua: any;
	lauxlib: any;
	lualib: any;
	to_luastring: (s: string) => Uint8Array;
	to_jsstring: (s: Uint8Array) => string;
};

/** The exact text of PluginPrelude.SOURCE (core/plugins). Keep in step. */
export const PRELUDE_SOURCE = `
-- WM Keyboard plugin UI helpers.
ui = {}
function ui.column(t) return { type = "column", children = t } end
function ui.row(t) return { type = "row", children = t } end
function ui.label(t) return { type = "label", text = t.text, style = t.style } end
function ui.output(t)
  return {
    type = "output", id = t.id, text = t.text, mono = t.mono,
    insertable = t.insertable, copyable = t.copyable,
  }
end
function ui.button(t)
  return { type = "button", id = t.id, text = t.text, style = t.style, enabled = t.enabled }
end
function ui.toggle(t)
  return { type = "toggle", id = t.id, label = t.label, checked = t.checked }
end
function ui.input(t)
  return { type = "input", id = t.id, label = t.label, placeholder = t.placeholder }
end
function ui.spacer(t) return { type = "spacer", height = t and t.height } end
function ui.divider() return { type = "divider" } end
function ui.progress() return { type = "progress" } end
function ui.tabs(t) return { type = "tabs", id = t.id, pages = t } end
function ui.page(t) return { title = t.title, children = t } end
`;

export const MAIN_CHUNK = 'main.lua';
export const PRELUDE_CHUNK = 'prelude.lua';

/** bit32 for Lua 5.3, since luaj 5.2 ships it and plugins may use it. */
const BIT32 = `
bit32 = {}
local function norm(x) return x & 0xFFFFFFFF end
function bit32.band(...) local r = 0xFFFFFFFF for _, v in ipairs({...}) do r = r & v end return norm(r) end
function bit32.bor(...) local r = 0 for _, v in ipairs({...}) do r = r | v end return norm(r) end
function bit32.bxor(...) local r = 0 for _, v in ipairs({...}) do r = r ~ v end return norm(r) end
function bit32.bnot(x) return norm(~x) end
function bit32.lshift(x, n) if n >= 32 then return 0 end return norm(x << n) end
function bit32.rshift(x, n) if n >= 32 then return 0 end return norm(x) >> n end
function bit32.arshift(x, n) x = norm(x) if x >= 0x80000000 then return norm((x >> n) | ~(0xFFFFFFFF >> n)) end return x >> n end
function bit32.lrotate(x, n) n = n % 32 return norm((x << n) | (norm(x) >> (32 - n))) end
function bit32.rrotate(x, n) n = n % 32 return norm((norm(x) >> n) | (x << (32 - n))) end
function bit32.btest(...) return bit32.band(...) ~= 0 end
function bit32.extract(x, f, w) w = w or 1 return (norm(x) >> f) & ((1 << w) - 1) end
function bit32.replace(x, v, f, w) w = w or 1 local m = ((1 << w) - 1) << f return norm((x & ~m) | ((v << f) & m)) end
`;

const REMOVED = ['load', 'loadstring', 'loadfile', 'dofile', 'require', 'package', 'io', 'coroutine', 'debug', 'utf8'];

// ---------------------------------------------------------------------------
// Budgets (PluginBudget.kt)
// ---------------------------------------------------------------------------

export type Phase = 'LOAD' | 'EVENT' | 'RENDER';

export interface PluginLimit {
	instructions: number;
	wallMillis: number;
}

export const LIMITS: Record<Phase, PluginLimit> = {
	LOAD: { instructions: 30_000_000, wallMillis: 3_000 },
	EVENT: { instructions: 20_000_000, wallMillis: 2_000 },
	RENDER: { instructions: 4_000_000, wallMillis: 500 },
};

export interface Usage {
	instructions: number;
	instructionLimit: number;
	elapsedMillis: number;
	wallLimitMillis: number;
	running: boolean;
}

export type AbortReason = 'INSTRUCTIONS' | 'DEADLINE' | 'CANCELLED';

export class PluginAbort extends Error {
	constructor(readonly reason: AbortReason) {
		super(reason);
	}
}

/** The hook fires every this many instructions; the count lags by at most that. */
const HOOK_EVERY = 100;

class Budget {
	private remaining = 0;
	private deadline = Number.POSITIVE_INFINITY;
	private cancelled = false;
	private limitInstructions = 0;
	private limitWall = 0;
	private startAt = 0;
	private endAt = 0;
	private sampledUsed = 0;
	private running = false;

	begin(limit: PluginLimit) {
		this.remaining = limit.instructions;
		this.startAt = performance.now();
		this.deadline = this.startAt + limit.wallMillis;
		this.limitInstructions = limit.instructions;
		this.limitWall = limit.wallMillis;
		this.sampledUsed = 0;
		this.running = true;
	}

	end() {
		this.deadline = Number.POSITIVE_INFINITY;
		if (this.running) {
			this.sampledUsed = this.limitInstructions - Math.max(this.remaining, 0);
			this.endAt = performance.now();
			this.running = false;
		}
	}

	/** Called every HOOK_EVERY instructions. */
	onInstructions() {
		this.remaining -= HOOK_EVERY;
		this.sampledUsed = this.limitInstructions - this.remaining;
		if (this.remaining <= 0) throw new PluginAbort('INSTRUCTIONS');
		if (this.cancelled) throw new PluginAbort('CANCELLED');
		if (performance.now() > this.deadline) throw new PluginAbort('DEADLINE');
	}

	cancel() {
		this.cancelled = true;
	}

	usage(): Usage {
		if (this.limitInstructions === 0) return { instructions: 0, instructionLimit: 0, elapsedMillis: 0, wallLimitMillis: 0, running: false };
		const stop = this.running ? performance.now() : this.endAt;
		return {
			instructions: Math.min(Math.max(this.sampledUsed, 0), this.limitInstructions),
			instructionLimit: this.limitInstructions,
			elapsedMillis: Math.max(stop - this.startAt, 0),
			wallLimitMillis: this.limitWall,
			running: this.running,
		};
	}
}

// ---------------------------------------------------------------------------
// Widgets and the UI codec (PluginWidget.kt, PluginUiCodec.kt)
// ---------------------------------------------------------------------------

export type LabelStyle = 'TITLE' | 'BODY' | 'CAPTION';

export type PluginWidget =
	| { kind: 'column'; children: PluginWidget[] }
	| { kind: 'row'; children: PluginWidget[] }
	| { kind: 'label'; text: string; style: LabelStyle }
	| { kind: 'output'; id: string; text: string; mono: boolean; insertable: boolean; copyable: boolean }
	| { kind: 'button'; id: string; text: string; primary: boolean; enabled: boolean }
	| { kind: 'toggle'; id: string; label: string; checked: boolean }
	| { kind: 'input'; id: string; label: string; placeholder: string }
	| { kind: 'spacer'; height: number }
	| { kind: 'divider' }
	| { kind: 'progress' }
	| { kind: 'tabs'; id: string; pages: { title: string; children: PluginWidget[] }[] };

export type RepairCode = 'NOT_A_WIDGET' | 'TOO_DEEP' | 'TOO_MANY_WIDGETS' | 'NO_TYPE' | 'UNKNOWN_TYPE' | 'TABS_NO_PAGES' | 'TOO_MANY_TABS' | 'TEXT_BUDGET' | 'TEXT_SHORTENED';

export interface RenderedUi {
	root: PluginWidget[];
	repairs: string[];
	repairCodes: RepairCode[];
}

export const EMPTY_UI: RenderedUi = { root: [], repairs: [], repairCodes: [] };

export const UI = { MAX_NODES: 256, MAX_DEPTH: 12, MAX_TEXT: 2048, MAX_TOTAL_TEXT: 64 * 1024, MAX_TABS: 8, MAX_REPAIRS: 8, MAX_ID: 64, MAX_SPACER: 64 };

/** A Lua table as `toJs` hands it over: an array when 1..n keyed, else an object, with "1","2" keys for a mixed one. */
type LuaTable = Record<string, unknown> | unknown[];

function isTable(v: unknown): v is LuaTable {
	return v !== null && typeof v === 'object';
}

function field(t: LuaTable, key: string): unknown {
	return Array.isArray(t) ? undefined : (t as Record<string, unknown>)[key];
}

/** `table.length()`: the border of the array part. */
function arrayLength(t: LuaTable): number {
	if (Array.isArray(t)) return t.length;
	let n = 0;
	while (Object.prototype.hasOwnProperty.call(t, String(n + 1))) n++;
	return n;
}

function at(t: LuaTable, i: number): unknown {
	return Array.isArray(t) ? t[i - 1] : (t as Record<string, unknown>)[String(i)];
}

function optString(v: unknown, fallback: string): string {
	return typeof v === 'string' ? v : fallback;
}

function optBoolean(v: unknown, fallback: boolean): boolean {
	return typeof v === 'boolean' ? v : v === null || v === undefined ? fallback : true;
}

function toBoolean(v: unknown): boolean {
	return !(v === null || v === undefined || v === false);
}

function optInt(v: unknown, fallback: number): number {
	return typeof v === 'number' && Number.isFinite(v) ? Math.trunc(v) : fallback;
}

/** `LuaValue.tojstring()` for a number: integers without a decimal point. */
function luaNumberString(n: number): string {
	return Number.isInteger(n) ? String(n) : String(n);
}

class Walk {
	repairs: string[] = [];
	codes: RepairCode[] = [];
	nodes = 0;
	totalText = 0;

	repair(code: RepairCode, message: string) {
		if (this.repairs.length < UI.MAX_REPAIRS && !this.repairs.includes(message)) {
			this.repairs.push(message);
			this.codes.push(code);
		}
	}

	children(value: unknown, depth: number): PluginWidget[] {
		if (!isTable(value)) return [];
		if (field(value, 'type') !== undefined && field(value, 'type') !== null) {
			const w = this.widget(value, depth);
			return w ? [w] : [];
		}
		const out: PluginWidget[] = [];
		const n = arrayLength(value);
		for (let i = 1; i <= n; i++) {
			const child = at(value, i);
			if (isTable(child)) {
				const w = this.widget(child, depth);
				if (w) out.push(w);
			} else if (child !== null && child !== undefined) this.repair('NOT_A_WIDGET', S.repairNotAWidget);
		}
		return out;
	}

	widget(table: LuaTable, depth: number): PluginWidget | null {
		if (depth > UI.MAX_DEPTH) {
			this.repair('TOO_DEEP', S.repairTooDeep);
			return null;
		}
		if (++this.nodes > UI.MAX_NODES) {
			this.repair('TOO_MANY_WIDGETS', S.repairTooManyWidgets(UI.MAX_NODES));
			return null;
		}
		const type = optString(field(table, 'type'), '');
		switch (type) {
			case 'column': return { kind: 'column', children: this.children(field(table, 'children'), depth + 1) };
			case 'row': return { kind: 'row', children: this.children(field(table, 'children'), depth + 1) };
			case 'label': return { kind: 'label', text: this.text(table, 'text'), style: labelStyle(table) };
			case 'output': return { kind: 'output', id: this.id(table), text: this.text(table, 'text'), mono: toBoolean(field(table, 'mono')), insertable: optBoolean(field(table, 'insertable'), true), copyable: optBoolean(field(table, 'copyable'), true) };
			case 'button': return { kind: 'button', id: this.id(table), text: this.text(table, 'text'), primary: optString(field(table, 'style'), '') === 'primary', enabled: optBoolean(field(table, 'enabled'), true) };
			case 'toggle': return { kind: 'toggle', id: this.id(table), label: this.text(table, 'label'), checked: toBoolean(field(table, 'checked')) };
			case 'input': return { kind: 'input', id: this.id(table), label: this.text(table, 'label'), placeholder: this.text(table, 'placeholder') };
			case 'spacer': return { kind: 'spacer', height: Math.min(Math.max(optInt(field(table, 'height'), 8), 0), UI.MAX_SPACER) };
			case 'divider': return { kind: 'divider' };
			case 'progress': return { kind: 'progress' };
			case 'tabs': return this.tabs(table, depth);
			default:
				if (!type) this.repair('NO_TYPE', S.repairNoType);
				else this.repair('UNKNOWN_TYPE', S.repairUnknownType(type.substring(0, 24)));
				return null;
		}
	}

	private tabs(table: LuaTable, depth: number): PluginWidget | null {
		const pagesValue = field(table, 'pages');
		if (!isTable(pagesValue)) {
			this.repair('TABS_NO_PAGES', S.repairTabsNoPages);
			return null;
		}
		const pages: { title: string; children: PluginWidget[] }[] = [];
		const n = arrayLength(pagesValue);
		for (let i = 1; i <= n && pages.length < UI.MAX_TABS; i++) {
			const page = at(pagesValue, i);
			if (isTable(page)) pages.push({ title: this.text(page, 'title'), children: this.children(field(page, 'children'), depth + 1) });
		}
		if (n > UI.MAX_TABS) this.repair('TOO_MANY_TABS', S.repairTooManyTabs(UI.MAX_TABS));
		if (!pages.length) {
			this.repair('TABS_NO_PAGES', S.repairTabsNoPages);
			return null;
		}
		return { kind: 'tabs', id: this.id(table), pages };
	}

	private id(table: LuaTable): string {
		return optString(field(table, 'id'), '').substring(0, UI.MAX_ID);
	}

	private text(table: LuaTable, key: string): string {
		const raw = field(table, key);
		let value: string;
		if (typeof raw === 'string') value = raw;
		else if (typeof raw === 'number') value = luaNumberString(raw);
		else if (typeof raw === 'boolean') value = String(raw);
		else return '';
		const remaining = Math.max(UI.MAX_TOTAL_TEXT - this.totalText, 0);
		if (remaining === 0) {
			this.repair('TEXT_BUDGET', S.repairTextBudget);
			return '';
		}
		const capped = value.substring(0, Math.min(UI.MAX_TEXT, remaining));
		if (capped.length < value.length) this.repair('TEXT_SHORTENED', S.repairTextShortened);
		this.totalText += capped.length;
		return capped;
	}
}

function labelStyle(table: LuaTable): LabelStyle {
	switch (optString(field(table, 'style'), '')) {
		case 'title': return 'TITLE';
		case 'caption': return 'CAPTION';
		default: return 'BODY';
	}
}

export function uiFromValue(value: unknown): RenderedUi {
	const walk = new Walk();
	const root = walk.children(value, 0);
	return { root, repairs: walk.repairs, repairCodes: walk.codes };
}

export interface PluginTargets {
	buttons: { id: string; enabled: boolean }[];
	toggles: { id: string; checked: boolean }[];
	inputs: { id: string }[];
	tabs: { id: string; pages: number }[];
}

export const EMPTY_TARGETS: PluginTargets = { buttons: [], toggles: [], inputs: [], tabs: [] };

export const targetsEmpty = (t: PluginTargets) => !t.buttons.length && !t.toggles.length && !t.inputs.length && !t.tabs.length;

/** Every button, toggle, input and tab strip in this tree, in document order. */
export function targetsOf(ui: RenderedUi): PluginTargets {
	const out: PluginTargets = { buttons: [], toggles: [], inputs: [], tabs: [] };
	const walk = (list: PluginWidget[]) => {
		for (const w of list) {
			switch (w.kind) {
				case 'column':
				case 'row': walk(w.children); break;
				case 'button': out.buttons.push({ id: w.id, enabled: w.enabled }); break;
				case 'toggle': out.toggles.push({ id: w.id, checked: w.checked }); break;
				case 'input': out.inputs.push({ id: w.id }); break;
				case 'tabs':
					out.tabs.push({ id: w.id, pages: w.pages.length });
					for (const p of w.pages) walk(p.children);
					break;
				default: break;
			}
		}
	};
	walk(ui.root);
	return out;
}

// ---------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------

export type PluginEvent =
	| { type: 'click'; id: string }
	| { type: 'toggle'; id: string; value: boolean }
	| { type: 'input_changed'; id: string; value: string }
	| { type: 'tab_selected'; id: string; index: number };

// ---------------------------------------------------------------------------
// Storage (PluginStorage.kt)
// ---------------------------------------------------------------------------

export const STORAGE = { MAX_KEYS: 128, MAX_KEY_LENGTH: 64, MAX_VALUE_LENGTH: 8 * 1024, MAX_TOTAL_BYTES: 64 * 1024 };

const utf8 = new TextEncoder();
const bytesOf = (s: string) => utf8.encode(s).byteLength;

/** A key/value store with the app's quotas, kept in localStorage under one key. */
export class PreviewStorage {
	private map = new Map<string, string>();

	constructor(private readonly storageKey: string | null) {
		if (storageKey) {
			try {
				const raw = localStorage.getItem(storageKey);
				if (raw) for (const [k, v] of Object.entries(JSON.parse(raw) as Record<string, string>)) if (typeof v === 'string') this.map.set(k, v);
			} catch {
				/* a fresh store */
			}
		}
	}

	private persist() {
		if (!this.storageKey) return;
		try {
			localStorage.setItem(this.storageKey, JSON.stringify(Object.fromEntries(this.map)));
		} catch {
			/* quota: the preview keeps going in memory */
		}
	}

	keys(): string[] {
		return [...this.map.keys()];
	}

	get(key: string): string | null {
		return this.map.get(key) ?? null;
	}

	/** Null when written, else the reason the plugin itself is told. */
	set(key: string, value: string): string | null {
		if (!key) return "storage keys can't be empty";
		if (key.length > STORAGE.MAX_KEY_LENGTH) return `storage keys can't be longer than ${STORAGE.MAX_KEY_LENGTH} characters`;
		if (bytesOf(value) > STORAGE.MAX_VALUE_LENGTH) return `that value is larger than the ${STORAGE.MAX_VALUE_LENGTH / 1024} KB limit for one key`;
		if (!this.map.has(key) && this.map.size >= STORAGE.MAX_KEYS) return `this plugin already has the maximum of ${STORAGE.MAX_KEYS} stored keys`;
		let total = bytesOf(key) + bytesOf(value);
		for (const [k, v] of this.map) if (k !== key) total += bytesOf(k) + bytesOf(v);
		if (total > STORAGE.MAX_TOTAL_BYTES) return `this plugin has used all ${STORAGE.MAX_TOTAL_BYTES / 1024} KB of its storage`;
		this.map.set(key, value);
		this.persist();
		return null;
	}

	remove(key: string) {
		if (this.map.delete(key)) this.persist();
	}

	clear() {
		this.map.clear();
		this.persist();
	}

	get size() {
		return this.map.size;
	}
}

// ---------------------------------------------------------------------------
// The sandbox (PluginSandbox.kt + PluginHostApi.kt)
// ---------------------------------------------------------------------------

const HOST = { MAX_JSON_DEPTH: 24, MAX_JSON_CHARS: 256 * 1024, MAX_INPUT_CHARS: 8 * 1024, MAX_LOG_LINE: 512, MAX_LOG_ENTRIES: 200, MAX_PRINT_CHARS: 512 };

export interface PluginIdentity {
	id: string;
	name: string;
	version: string;
	storage: boolean;
}

/** A Lua error: the virtual machine's own words, as luaj's LuaError carries them. */
export class LuaError extends Error {}

interface SandboxHost {
	onPrint(line: string): void;
	onSetInput(id: string, text: string): void;
}

class Sandbox {
	private L: any;
	readonly budget = new Budget();
	private abort: PluginAbort | null = null;
	private pendingInputs: [string, string][] = [];
	private logLines = 0;

	constructor(private readonly plugin: PluginIdentity, private readonly storage: PreviewStorage | null, private readonly host: SandboxHost) {
		const L = lauxlib.luaL_newstate();
		this.L = L;
		lualib.luaL_openlibs(L);
		this.runChunk(BIT32, '=bit32');
		for (const g of REMOVED) {
			lua.lua_pushnil(L);
			lua.lua_setglobal(L, to_luastring(g));
		}
		this.runChunk(
			`local time, clock, date = os.time, os.clock, os.date
			os = { time = time, clock = clock, date = date }
			collectgarbage = function() return 0 end`,
			'=os'
		);
		this.installPrint();
		this.installWm();
		lua.lua_sethook(
			L,
			() => {
				try {
					this.budget.onInstructions();
				} catch (e) {
					this.abort = e as PluginAbort;
					throw e;
				}
			},
			lua.LUA_MASKCOUNT,
			HOOK_EVERY
		);
		this.runChunk(PRELUDE_SOURCE, '@' + PRELUDE_CHUNK);
	}

	private print(line: string) {
		const cut = line.length > HOST.MAX_PRINT_CHARS ? line.substring(0, HOST.MAX_PRINT_CHARS) : line;
		this.host.onPrint(cut);
	}

	private log(line: string) {
		if (this.logLines >= HOST.MAX_LOG_ENTRIES) return;
		this.logLines++;
		this.host.onPrint(line.length > HOST.MAX_LOG_LINE ? line.substring(0, HOST.MAX_LOG_LINE) : line);
	}

	private installPrint() {
		const L = this.L;
		lua.lua_pushjsfunction(L, (L: any) => {
			const n = lua.lua_gettop(L);
			const parts: string[] = [];
			for (let i = 1; i <= n; i++) parts.push(to_jsstring(lauxlib.luaL_tolstring(L, i)));
			this.print(parts.join('\t'));
			return 0;
		});
		lua.lua_setglobal(L, to_luastring('print'));
	}

	private pushJsValue(v: unknown, depth = 0) {
		const L = this.L;
		if (depth > HOST.MAX_JSON_DEPTH) {
			lua.lua_pushnil(L);
			return;
		}
		if (v === null || v === undefined) lua.lua_pushnil(L);
		else if (typeof v === 'boolean') lua.lua_pushboolean(L, v);
		else if (typeof v === 'number') {
			if (Number.isInteger(v) && Math.abs(v) < 2 ** 53) lua.lua_pushinteger(L, v);
			else lua.lua_pushnumber(L, v);
		} else if (typeof v === 'string') lua.lua_pushstring(L, to_luastring(v));
		else if (Array.isArray(v)) {
			lua.lua_createtable(L, v.length, 0);
			v.forEach((x, i) => {
				this.pushJsValue(x, depth + 1);
				lua.lua_rawseti(L, -2, i + 1);
			});
		} else if (typeof v === 'object') {
			lua.lua_newtable(L);
			for (const [k, x] of Object.entries(v as object)) {
				lua.lua_pushstring(L, to_luastring(k));
				this.pushJsValue(x, depth + 1);
				lua.lua_settable(L, -3);
			}
		} else lua.lua_pushnil(L);
	}

	/** The Lua value at `idx` as JS: an array when 1..n keyed, else an object with every key as a string. */
	private toJs(idx: number, depth = 0): unknown {
		const L = this.L;
		const t = lua.lua_type(L, idx);
		switch (t) {
			case lua.LUA_TNIL:
			case lua.LUA_TNONE:
				return null;
			case lua.LUA_TBOOLEAN:
				return lua.lua_toboolean(L, idx);
			case lua.LUA_TNUMBER:
				return lua.lua_tonumber(L, idx);
			case lua.LUA_TSTRING:
				return to_jsstring(lua.lua_tostring(L, idx));
			case lua.LUA_TTABLE: {
				if (depth > HOST.MAX_JSON_DEPTH) return null;
				const abs = lua.lua_absindex(L, idx);
				const obj: Record<string, unknown> = {};
				const arr: unknown[] = [];
				let count = 0;
				let arrayLike = true;
				lua.lua_pushnil(L);
				while (lua.lua_next(L, abs) !== 0) {
					count++;
					const kt = lua.lua_type(L, -2);
					const val = this.toJs(-1, depth + 1);
					if (kt === lua.LUA_TNUMBER) {
						const k = lua.lua_tonumber(L, -2);
						if (Number.isInteger(k) && k >= 1) arr[k - 1] = val;
						else arrayLike = false;
						obj[String(k)] = val;
					} else {
						arrayLike = false;
						obj[kt === lua.LUA_TSTRING ? to_jsstring(lua.lua_tostring(L, -2)) : `<${to_jsstring(lua.lua_typename(L, kt))}>`] = val;
					}
					lua.lua_pop(L, 1);
				}
				if (arrayLike && count > 0 && arr.length === count) return arr;
				return obj;
			}
			default:
				return `<${to_jsstring(lua.lua_typename(L, t))}>`;
		}
	}

	private installWm() {
		const L = this.L;
		lua.lua_newtable(L);
		const set = (name: string, f: (L: any) => number) => {
			lua.lua_pushjsfunction(L, f);
			lua.lua_setfield(L, -2, to_luastring(name));
		};
		lua.lua_pushinteger(L, 1);
		lua.lua_setfield(L, -2, to_luastring('api_version'));
		lua.lua_pushstring(L, to_luastring(this.plugin.id));
		lua.lua_setfield(L, -2, to_luastring('plugin_id'));
		lua.lua_pushstring(L, to_luastring(this.plugin.version));
		lua.lua_setfield(L, -2, to_luastring('plugin_version'));
		set('log', (L) => {
			this.log(lua.lua_gettop(L) >= 1 ? to_jsstring(lauxlib.luaL_tolstring(L, 1)) : '');
			return 0;
		});
		lua.lua_newtable(L);
		set('set_input', (L) => {
			const id = to_jsstring(lauxlib.luaL_checklstring(L, 1));
			const text = lua.lua_type(L, 2) === lua.LUA_TSTRING ? to_jsstring(lua.lua_tostring(L, 2)) : '';
			this.pendingInputs.push([id, text.substring(0, HOST.MAX_INPUT_CHARS)]);
			return 0;
		});
		lua.lua_setfield(L, -2, to_luastring('ui'));
		lua.lua_newtable(L);
		set('decode', (L) => {
			const text = to_jsstring(lauxlib.luaL_checklstring(L, 1));
			if (text.length > HOST.MAX_JSON_CHARS) {
				lua.lua_pushnil(L);
				lua.lua_pushstring(L, to_luastring('that JSON is too large'));
				return 2;
			}
			try {
				this.pushJsValue(JSON.parse(text));
				return 1;
			} catch {
				lua.lua_pushnil(L);
				lua.lua_pushstring(L, to_luastring("that isn't valid JSON"));
				return 2;
			}
		});
		set('encode', (L) => {
			try {
				const v = this.toJs(1);
				const s = JSON.stringify(v ?? null);
				if (s.length > HOST.MAX_JSON_CHARS) {
					lua.lua_pushnil(L);
					lua.lua_pushstring(L, to_luastring('that value is too large to encode'));
					return 2;
				}
				lua.lua_pushstring(L, to_luastring(s));
				return 1;
			} catch {
				lua.lua_pushnil(L);
				lua.lua_pushstring(L, to_luastring("that value can't be turned into JSON"));
				return 2;
			}
		});
		lua.lua_setfield(L, -2, to_luastring('json'));
		const storage = this.storage;
		if (storage) {
			lua.lua_newtable(L);
			set('get', (L) => {
				const v = storage.get(to_jsstring(lauxlib.luaL_checklstring(L, 1)));
				if (v === null) lua.lua_pushnil(L);
				else lua.lua_pushstring(L, to_luastring(v));
				return 1;
			});
			set('set', (L) => {
				const k = to_jsstring(lauxlib.luaL_checklstring(L, 1));
				const v = to_jsstring(lauxlib.luaL_checklstring(L, 2));
				const refused = storage.set(k, v);
				if (refused) {
					lua.lua_pushnil(L);
					lua.lua_pushstring(L, to_luastring(refused));
					return 2;
				}
				lua.lua_pushboolean(L, true);
				return 1;
			});
			set('remove', (L) => {
				storage.remove(to_jsstring(lauxlib.luaL_checklstring(L, 1)));
				return 0;
			});
			set('keys', () => {
				this.pushJsValue(storage.keys());
				return 1;
			});
			lua.lua_setfield(L, -2, to_luastring('storage'));
		}
		lua.lua_setglobal(L, to_luastring('wm'));
	}

	/** Loads a chunk; a syntax error is a LuaError in the machine's words. */
	compile(code: string, chunk: string) {
		const L = this.L;
		const bytes = to_luastring(code);
		const status = lauxlib.luaL_loadbuffer(L, bytes, bytes.length, to_luastring(chunk));
		if (status !== lua.LUA_OK) {
			const msg = to_jsstring(lua.lua_tostring(L, -1));
			lua.lua_pop(L, 1);
			throw new LuaError(msg);
		}
	}

	private runChunk(code: string, chunk: string) {
		this.compile(code, chunk);
		this.call(0, 0);
	}

	/** Calls the function under `nargs` arguments with a traceback handler, as luaj reports a runtime error. */
	call(nargs: number, nresults: number) {
		const L = this.L;
		const base = lua.lua_gettop(L) - nargs;
		lua.lua_pushjsfunction(L, (L: any) => {
			const msg = lua.lua_type(L, 1) === lua.LUA_TSTRING ? lua.lua_tostring(L, 1) : to_luastring(to_jsstring(lauxlib.luaL_tolstring(L, 1)));
			lauxlib.luaL_traceback(L, L, msg, 1);
			return 1;
		});
		lua.lua_insert(L, base);
		let status: number;
		try {
			status = lua.lua_pcall(L, nargs, nresults, base);
		} catch (e) {
			lua.lua_remove(L, base);
			if (this.abort) {
				const a = this.abort;
				this.abort = null;
				throw a;
			}
			if (e instanceof PluginAbort) throw e;
			if (e instanceof RangeError) throw new StackOverflow();
			throw new LuaError(String((e as Error)?.message ?? e));
		}
		lua.lua_remove(L, base);
		if (status !== lua.LUA_OK) {
			const msg = lua.lua_type(L, -1) === lua.LUA_TSTRING ? to_jsstring(lua.lua_tostring(L, -1)) : 'error';
			lua.lua_pop(L, 1);
			if (this.abort) {
				const a = this.abort;
				this.abort = null;
				throw a;
			}
			if (/stack overflow/i.test(msg) || /Maximum call stack/i.test(msg)) throw new StackOverflow();
			throw new LuaError(msg);
		}
	}

	globalIsFunction(name: string): boolean {
		const L = this.L;
		lua.lua_getglobal(L, to_luastring(name));
		const is = lua.lua_type(L, -1) === lua.LUA_TFUNCTION;
		lua.lua_pop(L, 1);
		return is;
	}

	pushGlobal(name: string) {
		lua.lua_getglobal(this.L, to_luastring(name));
	}

	pushEvent(e: PluginEvent) {
		this.pushJsValue(e);
	}

	popValue(): unknown {
		const v = this.toJs(-1);
		lua.lua_pop(this.L, 1);
		return v;
	}

	takeInputs(): [string, string][] {
		const q = this.pendingInputs;
		this.pendingInputs = [];
		return q;
	}

	close() {
		try {
			lua.lua_close(this.L);
		} catch {
			/* already gone */
		}
	}
}

class StackOverflow extends Error {}

// ---------------------------------------------------------------------------
// The session (PluginPreviewSession.kt + the runtime's flow)
// ---------------------------------------------------------------------------

export type FailureKind = 'COMPILE' | 'RUNTIME' | 'INSTRUCTIONS' | 'DEADLINE' | 'CANCELLED' | 'ABANDONED' | 'RECURSION' | 'UNEXPECTED';

export interface Failure {
	kind: FailureKind;
	text: string;
	chunk: string | null;
	line: number | null;
	phase: Phase;
}

export type Status = 'IDLE' | 'RUNNING' | 'READY' | 'FAILED' | 'STOPPED';

export type ConsoleKind = 'STARTED' | 'PRINT' | 'REPAIR' | 'ERROR' | 'STOPPED' | 'CANCELLED';

export interface ConsoleEntry {
	at: number;
	kind: ConsoleKind;
	text: string;
	message?: string;
	chunk?: string | null;
	line?: number | null;
	failure?: FailureKind;
	repair?: RepairCode;
}

export interface PreviewState {
	status: Status;
	draftId: string | null;
	ui: RenderedUi;
	busy: boolean;
	failure: Failure | null;
	usage: Partial<Record<Phase, Usage>>;
	targets: PluginTargets;
	inputs: Record<string, string>;
	consoleRevision: number;
	runs: number;
}

export const MAX_CONSOLE = 500;
export const MAX_EVENTS = 200;
export const MAX_ERROR_TEXT = 8 * 1024;
export const MAX_INPUT = 8 * 1024;

const LOCATION = /^\s*@?(main\.lua|prelude\.lua):(\d+)\b/;

/** The chunk and line a Lua message starts with, or nulls when it names none. Only the first line is read. */
export function locate(text: string): [string | null, number | null] {
	const first = text.split('\n', 1)[0] ?? '';
	const m = LOCATION.exec(first);
	if (!m) return [null, null];
	return [m[1]!, Number.parseInt(m[2]!, 10)];
}

interface Session {
	draftId: string;
	sandbox: Sandbox;
	phase: Phase;
	loaded: boolean;
}

export class PluginPreviewSession {
	readonly state: Signal<PreviewState> = signal({ status: 'IDLE', draftId: null, ui: EMPTY_UI, busy: false, failure: null, usage: {}, targets: EMPTY_TARGETS, inputs: {}, consoleRevision: 0, runs: 0 });
	private entries: ConsoleEntry[] = [];
	private events: PluginEvent[] = [];
	private runStartedAt = 0;
	private lastRepairs: string[] = [];
	private current: Session | null = null;
	private storageOwner: string | null = null;
	private sharedStorage: PreviewStorage | null = null;

	constructor(private readonly storageKeyOf: (draftId: string) => string | null) {}

	private update(patch: Partial<PreviewState> | ((s: PreviewState) => Partial<PreviewState>)) {
		const s = this.state.value;
		this.state.value = { ...s, ...(typeof patch === 'function' ? patch(s) : patch) };
	}

	// ---- control ---------------------------------------------------------

	run(draftId: string, source: string, plugin: PluginIdentity) {
		this.closeCurrent(true);
		const storage = plugin.storage ? this.storageOf(draftId) : null;
		this.runStartedAt = performance.now();
		this.lastRepairs = [];
		this.update((s) => ({ status: 'RUNNING', draftId, failure: null, usage: {}, inputs: s.draftId === draftId ? s.inputs : {}, runs: s.runs + 1 }));
		this.append({ at: 0, kind: 'STARTED', text: '' });
		let sandbox: Sandbox;
		try {
			sandbox = new Sandbox(plugin, storage, {
				onPrint: (line) => this.append({ at: this.elapsed(), kind: 'PRINT', text: line }),
				onSetInput: (id, text) => this.update((s) => ({ inputs: { ...s.inputs, [id]: text.substring(0, MAX_INPUT) } })),
			});
		} catch (e) {
			this.append({ at: this.elapsed(), kind: 'ERROR', text: String((e as Error).message ?? e), failure: 'UNEXPECTED' });
			this.update({ status: 'FAILED' });
			return;
		}
		const session: Session = { draftId, sandbox, phase: 'LOAD', loaded: false };
		this.current = session;
		this.phase(session, 'LOAD', () => {
			try {
				sandbox.compile(source, '@' + MAIN_CHUNK);
			} catch (e) {
				if (e instanceof LuaError) throw new CompileFailure(e);
				throw e;
			}
			sandbox.call(0, 0);
			session.loaded = true;
			this.flushInputs(session);
			this.finishPhase(session);
			this.render(session);
		});
	}

	send(event: PluginEvent) {
		const sent: PluginEvent = event.type === 'input_changed' ? { ...event, value: event.value.substring(0, MAX_INPUT) } : event;
		if (sent.type === 'input_changed') this.update((s) => ({ inputs: { ...s.inputs, [sent.id]: sent.value } }));
		const session = this.current;
		if (!session || !session.loaded) return;
		this.phase(session, 'EVENT', () => {
			this.events.push(sent);
			while (this.events.length > MAX_EVENTS) this.events.shift();
			const sandbox = session.sandbox;
			if (sandbox.globalIsFunction('on_event')) {
				sandbox.pushGlobal('on_event');
				sandbox.pushEvent(sent);
				sandbox.call(1, 0);
			}
			this.flushInputs(session);
			this.finishPhase(session);
			this.render(session);
		});
	}

	/** Ends the current run. The panel stays as it last drew. */
	stop() {
		this.closeCurrent(false);
		this.update((s) => ({ status: s.status === 'IDLE' ? 'IDLE' : 'STOPPED', busy: false }));
	}

	shutdown() {
		this.closeCurrent(false);
	}

	private closeCurrent(replaced: boolean) {
		const c = this.current;
		if (!c) return;
		this.current = null;
		if (replaced) this.append({ at: this.elapsed(), kind: 'CANCELLED', text: '', failure: 'CANCELLED' });
		c.sandbox.close();
	}

	storageOf(draftId: string): PreviewStorage {
		if (this.storageOwner !== draftId || !this.sharedStorage) {
			this.storageOwner = draftId;
			this.sharedStorage = new PreviewStorage(this.storageKeyOf(draftId));
		}
		return this.sharedStorage;
	}

	console(): ConsoleEntry[] {
		return this.entries.slice();
	}

	clearConsole() {
		this.entries = [];
		this.bumpConsole();
	}

	recordedEvents(): PluginEvent[] {
		return this.events.slice();
	}

	// ---- what the runtime reports ------------------------------------------

	private append(entry: ConsoleEntry) {
		this.entries.push(entry);
		while (this.entries.length > MAX_CONSOLE) this.entries.shift();
		this.bumpConsole();
	}

	private bumpConsole() {
		this.update((s) => ({ consoleRevision: s.consoleRevision + 1 }));
	}

	private elapsed(): number {
		return Math.max(Math.round(performance.now() - this.runStartedAt), 0);
	}

	private phase(session: Session, phase: Phase, body: () => void) {
		if (this.current !== session) return;
		session.phase = phase;
		session.sandbox.budget.begin(LIMITS[phase]);
		this.update({ busy: true });
		try {
			body();
		} catch (failure) {
			this.onFailure(session, failure);
		} finally {
			session.sandbox.budget.end();
			this.update((s) => ({ usage: { ...s.usage, [session.phase]: session.sandbox.budget.usage() }, busy: false }));
		}
	}

	private finishPhase(session: Session) {
		session.sandbox.budget.end();
		this.update((s) => ({ usage: { ...s.usage, [session.phase]: session.sandbox.budget.usage() } }));
	}

	private render(session: Session) {
		const sandbox = session.sandbox;
		sandbox.budget.begin(LIMITS.RENDER);
		session.phase = 'RENDER';
		if (!sandbox.globalIsFunction('render')) {
			this.append({ at: this.elapsed(), kind: 'ERROR', text: '', message: S.errorNoRender });
			this.update({ status: 'FAILED' });
			return;
		}
		sandbox.pushGlobal('render');
		sandbox.call(0, 1);
		const ui = uiFromValue(sandbox.popValue());
		this.flushInputs(session);
		this.onUi(ui);
	}

	private flushInputs(session: Session) {
		for (const [id, text] of session.sandbox.takeInputs()) this.update((s) => ({ inputs: { ...s.inputs, [id]: text.substring(0, MAX_INPUT) } }));
	}

	private onUi(ui: RenderedUi) {
		this.update((s) => ({ ui, targets: targetsOf(ui), status: s.status === 'FAILED' && s.failure?.kind === 'COMPILE' ? s.status : 'READY' }));
		if (ui.repairs.join('\n') !== this.lastRepairs.join('\n')) {
			this.lastRepairs = ui.repairs;
			ui.repairs.forEach((repair, index) => this.append({ at: this.elapsed(), kind: 'REPAIR', text: '', message: repair, repair: ui.repairCodes[index] }));
		}
	}

	private onFailure(session: Session, failure: unknown) {
		if (failure instanceof PluginAbort) {
			const reason = failure.reason.toLowerCase();
			this.observe(session, failure.reason, `stopped: ${reason}`);
			if (failure.reason !== 'CANCELLED') this.stopSession(session, failure.reason === 'INSTRUCTIONS' ? S.stoppedInstructions : S.stoppedDeadline);
			return;
		}
		if (failure instanceof CompileFailure) return this.scriptFailed(session, failure.error, 'COMPILE');
		if (failure instanceof LuaError) return this.scriptFailed(session, failure, 'RUNTIME');
		if (failure instanceof StackOverflow || failure instanceof RangeError) {
			this.observe(session, 'RECURSION', 'stack overflow');
			this.stopSession(session, S.stoppedRecursion);
			return;
		}
		const e = failure as Error;
		const name = e?.constructor?.name ?? 'Error';
		this.observe(session, 'UNEXPECTED', e?.message ? `${name}: ${e.message}` : name);
		this.stopSession(session, S.stoppedUnexpected);
	}

	private scriptFailed(session: Session, error: LuaError, kind: FailureKind) {
		const text = error.message ?? '';
		const [chunk, line] = locate(text);
		const failure: Failure = { kind, text, chunk, line, phase: session.phase };
		this.append({ at: this.elapsed(), kind: 'ERROR', text: text.substring(0, MAX_ERROR_TEXT), chunk, line, failure: kind });
		this.update({ failure, status: 'FAILED' });
	}

	private observe(session: Session, kind: FailureKind, text: string) {
		const [chunk, line] = locate(text);
		const failure: Failure = { kind, text, chunk, line, phase: session.phase };
		if (kind === 'CANCELLED') this.append({ at: this.elapsed(), kind: 'CANCELLED', text: '', failure: kind });
		this.update({ failure });
	}

	/** Ends the session for a reason the user should see. */
	private stopSession(session: Session, message: string) {
		if (this.current === session) {
			this.current = null;
			session.sandbox.close();
		}
		this.append({ at: this.elapsed(), kind: 'STOPPED', text: '', message });
		this.update({ status: 'STOPPED', busy: false });
	}
}

class CompileFailure extends Error {
	constructor(readonly error: LuaError) {
		super(error.message);
	}
}

/** One render of a script, for a template's thumbnail. Null when it fails. Never keeps a machine. */
export function renderOnce(plugin: PluginIdentity, script: string): RenderedUi | null {
	let sandbox: Sandbox | null = null;
	try {
		sandbox = new Sandbox(plugin, plugin.storage ? new PreviewStorage(null) : null, { onPrint: () => {}, onSetInput: () => {} });
		sandbox.budget.begin(LIMITS.LOAD);
		sandbox.compile(script, '@' + MAIN_CHUNK);
		sandbox.call(0, 0);
		sandbox.budget.end();
		sandbox.budget.begin(LIMITS.RENDER);
		if (!sandbox.globalIsFunction('render')) return null;
		sandbox.pushGlobal('render');
		sandbox.call(0, 1);
		const ui = uiFromValue(sandbox.popValue());
		sandbox.budget.end();
		return ui;
	} catch {
		return null;
	} finally {
		sandbox?.close();
	}
}
