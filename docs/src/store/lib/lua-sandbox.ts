/**
 * An in-browser stand-in for the app's plugin runtime, over fengari (Lua 5.3
 * in JavaScript). Mirrors PluginSandbox.kt: the same stdlib subset, the same
 * removed globals, the prelude, the `wm` host table with the same quotas, and
 * instruction/time budgets enforced with a count hook. Differences from luaj
 * 5.2 that a plugin might hit are listed in LUA_DIFFERENCES.
 */
// The web bundle carries the Node shims (process, Buffer) fengari's sources expect.
import * as fengari from 'fengari-web/dist/fengari-web.js';

const { lua, lauxlib, lualib, to_luastring, to_jsstring } = fengari as unknown as {
	lua: any;
	lauxlib: any;
	lualib: any;
	to_luastring: (s: string) => Uint8Array;
	to_jsstring: (s: Uint8Array) => string;
};

/** The exact text of PluginPrelude.SOURCE (core/plugins). Keep in step. */
export const PRELUDE = `
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

export interface Widget {
	type: string;
	[k: string]: unknown;
}

export type PluginEvent =
	| { type: 'click'; id: string }
	| { type: 'toggle'; id: string; value: boolean }
	| { type: 'input_changed'; id: string; value: string }
	| { type: 'tab_selected'; id: string; index: number };

export interface SandboxOptions {
	pluginId: string;
	pluginVersion: string;
	storage: boolean;
	onLog: (line: string) => void;
	onSetInput: (id: string, text: string) => void;
}

export const LIMITS = {
	load: { instructions: 30_000_000, ms: 3000 },
	event: { instructions: 20_000_000, ms: 2000 },
	render: { instructions: 4_000_000, ms: 500 },
	storage: { keys: 128, keyChars: 64, valueChars: 8192, totalChars: 65536 },
	log: { lines: 200, chars: 512 },
	setInput: 8192,
	json: 256 * 1024,
	ui: { nodes: 256, depth: 12, textChars: 2048, treeChars: 65536, tabs: 8 },
};

export class PluginAbort extends Error {}

export class LuaSandbox {
	private L: any;
	readonly storage = new Map<string, string>();
	private budget = { left: 0, deadline: 0 };
	private abortMsg: string | null = null;
	private pendingInputs: [string, string][] = [];
	logLines = 0;

	constructor(private opts: SandboxOptions) {
		const L = lauxlib.luaL_newstate();
		this.L = L;
		lualib.luaL_openlibs(L);
		this.run(BIT32, '=bit32');
		this.run(PRELUDE, '@prelude.lua');
		for (const g of REMOVED) {
			lua.lua_pushnil(L);
			lua.lua_setglobal(L, to_luastring(g));
		}
		// os: only time/clock/date, like the app.
		this.run(
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
				this.budget.left -= 1000;
				if (this.budget.left <= 0) this.abortMsg = 'Instruction budget exhausted: the app would abort this plugin (30M instructions on load, 20M per event, 4M per render).';
				else if ((this.budget.left & 0x3fff) === 0 && Date.now() > this.budget.deadline) this.abortMsg = 'Time budget exhausted: the app would abort this plugin (3 s load, 2 s event, 0.5 s render).';
				if (this.abortMsg) throw new PluginAbort(this.abortMsg);
			},
			lua.LUA_MASKCOUNT,
			1000
		);
	}

	private log(line: string) {
		if (this.logLines >= LIMITS.log.lines) return;
		this.logLines++;
		this.opts.onLog(line.length > LIMITS.log.chars ? line.slice(0, LIMITS.log.chars) : line);
	}

	private installPrint() {
		const L = this.L;
		lua.lua_pushjsfunction(L, (L: any) => {
			const n = lua.lua_gettop(L);
			const parts: string[] = [];
			for (let i = 1; i <= n; i++) parts.push(to_jsstring(lauxlib.luaL_tolstring(L, i)));
			this.log(parts.join('\t'));
			return 0;
		});
		lua.lua_setglobal(L, to_luastring('print'));
	}

	private pushJsValue(v: unknown, depth = 0) {
		const L = this.L;
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

	/** Lua value at `idx` → JS (tables become arrays when 1..n keyed, else objects). */
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
				if (depth > 24) return null;
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
						obj[kt === lua.LUA_TSTRING ? to_jsstring(lua.lua_tostring(L, -2)) : String(lua.lua_tonumber(L, -2))] = val;
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
		const opts = this.opts;
		lua.lua_newtable(L);
		const set = (name: string, f: (L: any) => number) => {
			lua.lua_pushjsfunction(L, f);
			lua.lua_setfield(L, -2, to_luastring(name));
		};
		lua.lua_pushinteger(L, 1);
		lua.lua_setfield(L, -2, to_luastring('api_version'));
		lua.lua_pushstring(L, to_luastring(opts.pluginId));
		lua.lua_setfield(L, -2, to_luastring('plugin_id'));
		lua.lua_pushstring(L, to_luastring(opts.pluginVersion));
		lua.lua_setfield(L, -2, to_luastring('plugin_version'));
		set('log', (L) => {
			this.log(lua.lua_gettop(L) >= 1 ? to_jsstring(lauxlib.luaL_tolstring(L, 1)) : '');
			return 0;
		});
		// wm.ui
		lua.lua_newtable(L);
		set('set_input', (L) => {
			const id = lua.lua_type(L, 1) === lua.LUA_TSTRING ? to_jsstring(lua.lua_tostring(L, 1)) : '';
			const text = lua.lua_type(L, 2) === lua.LUA_TSTRING ? to_jsstring(lua.lua_tostring(L, 2)) : '';
			if (id) this.pendingInputs.push([id, text.slice(0, LIMITS.setInput)]);
			return 0;
		});
		lua.lua_setfield(L, -2, to_luastring('ui'));
		// wm.json
		lua.lua_newtable(L);
		set('decode', (L) => {
			const text = lua.lua_type(L, 1) === lua.LUA_TSTRING ? to_jsstring(lua.lua_tostring(L, 1)) : '';
			if (text.length > LIMITS.json) {
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
				if (s.length > LIMITS.json) {
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
		// wm.storage (only with the permission)
		if (opts.storage) {
			lua.lua_newtable(L);
			const key = (L: any) => (lua.lua_type(L, 1) === lua.LUA_TSTRING ? to_jsstring(lua.lua_tostring(L, 1)) : '');
			set('get', (L) => {
				const v = this.storage.get(key(L));
				if (v === undefined) lua.lua_pushnil(L);
				else lua.lua_pushstring(L, to_luastring(v));
				return 1;
			});
			set('set', (L) => {
				const k = key(L);
				const v = lua.lua_type(L, 2) === lua.LUA_TSTRING ? to_jsstring(lua.lua_tostring(L, 2)) : null;
				const fail = (why: string) => {
					lua.lua_pushnil(L);
					lua.lua_pushstring(L, to_luastring(why));
					return 2;
				};
				if (!k || k.length > LIMITS.storage.keyChars) return fail('that key is not allowed');
				if (v === null) return fail('the value must be a string');
				if (v.length > LIMITS.storage.valueChars) return fail('that value is too large');
				if (!this.storage.has(k) && this.storage.size >= LIMITS.storage.keys) return fail('too many keys');
				let total = k.length + v.length;
				for (const [kk, vv] of this.storage) if (kk !== k) total += kk.length + vv.length;
				if (total > LIMITS.storage.totalChars) return fail('storage is full');
				this.storage.set(k, v);
				lua.lua_pushboolean(L, true);
				return 1;
			});
			set('remove', (L) => {
				this.storage.delete(key(L));
				return 0;
			});
			set('keys', (L) => {
				this.pushJsValue([...this.storage.keys()]);
				return 1;
			});
			lua.lua_setfield(L, -2, to_luastring('storage'));
		}
		lua.lua_setglobal(L, to_luastring('wm'));
	}

	private run(code: string, chunk: string) {
		const L = this.L;
		const status = lauxlib.luaL_loadbuffer(L, to_luastring(code), code.length, to_luastring(chunk));
		if (status !== lua.LUA_OK) {
			const msg = to_jsstring(lua.lua_tostring(L, -1));
			lua.lua_pop(L, 1);
			throw new Error(msg);
		}
		this.pcall(0);
	}

	private pcall(nargs: number, nresults = 0) {
		const L = this.L;
		let status: number;
		try {
			status = lua.lua_pcall(L, nargs, nresults, 0);
		} catch (e) {
			if (e instanceof PluginAbort) throw e;
			throw new Error(String((e as Error).message ?? e));
		}
		if (status !== lua.LUA_OK) {
			const msg = lua.lua_type(L, -1) === lua.LUA_TSTRING ? to_jsstring(lua.lua_tostring(L, -1)) : 'error';
			lua.lua_pop(L, 1);
			if (this.abortMsg) {
				const m = this.abortMsg;
				this.abortMsg = null;
				throw new PluginAbort(m);
			}
			throw new Error(msg);
		}
	}

	private arm(kind: keyof typeof LIMITS extends infer K ? 'load' | 'event' | 'render' : never) {
		const lim = LIMITS[kind];
		this.budget = { left: lim.instructions, deadline: Date.now() + lim.ms };
		this.abortMsg = null;
	}

	/** Compile + run main.lua, then render. */
	load(source: string): Widget[] {
		this.arm('load');
		this.run(source, '@main.lua');
		this.flushInputs();
		return this.render();
	}

	render(): Widget[] {
		const L = this.L;
		this.arm('render');
		lua.lua_getglobal(L, to_luastring('render'));
		if (lua.lua_type(L, -1) !== lua.LUA_TFUNCTION) {
			lua.lua_pop(L, 1);
			throw new Error('render is not a function. Every plugin must define function render().');
		}
		this.pcall(0, 1);
		const v = this.toJs(-1);
		lua.lua_pop(L, 1);
		if (!v || typeof v !== 'object') throw new Error('render() must return a widget table or a list of them.');
		return Array.isArray(v) ? (v as Widget[]) : [v as Widget];
	}

	dispatch(e: PluginEvent): Widget[] {
		const L = this.L;
		this.arm('event');
		lua.lua_getglobal(L, to_luastring('on_event'));
		if (lua.lua_type(L, -1) === lua.LUA_TFUNCTION) {
			this.pushJsValue(e);
			this.pcall(1, 0);
		} else lua.lua_pop(L, 1);
		this.flushInputs();
		return this.render();
	}

	private flushInputs() {
		const q = this.pendingInputs;
		this.pendingInputs = [];
		for (const [id, text] of q) this.opts.onSetInput(id, text);
	}

	close() {
		try {
			lua.lua_close(this.L);
		} catch {
			/* ignore */
		}
	}
}

