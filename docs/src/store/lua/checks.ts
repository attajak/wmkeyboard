/** Port of LuaChecks.kt: the checks that need to know what names mean. */
import * as Api from './api';
import type { LuaAnalysis, LuaFunction } from './analyse';
import type { LuaDocument } from './document';
import type { LuaDiagnostic, LuaDiagnosticCode, LuaHostShape } from './diagnostics';
import { LuaTokenKind, type LuaSpan, type LuaTokens } from './tokens';

const CONTRACT_NAMES = new Set(['render', 'on_event']);
const CHECKED_ROOTS = new Set(['wm', 'ui', 'string', 'table', 'math', 'bit32', 'os']);
const OS_REMOVED = new Set(['execute', 'exit', 'getenv', 'remove', 'rename', 'tmpname', 'difftime', 'setlocale']);
const LUA51_GLOBALS: Record<string, string | null> = { unpack: 'table.unpack', setfenv: null, getfenv: null, module: null };
const LUA51_MEMBERS: Record<string, string | null> = {
	'table.maxn': '#', 'table.getn': '#', 'table.setn': null, 'table.foreach': 'pairs', 'table.foreachi': 'ipairs',
	'math.log10': 'math.log(x, 10)', 'math.mod': 'math.fmod', 'string.gfind': 'string.gmatch',
};
const MAX_TABS = 8;

interface Key {
	name: string;
	token: number;
	valueToken: number;
	literal: string | null;
}

export function runChecks(document: LuaDocument, analysis: LuaAnalysis, host: LuaHostShape | null, out: LuaDiagnostic[]) {
	new Checks(document, analysis, host, out).run();
}

class Checks {
	private source: string;
	private tokens: LuaTokens;
	private apiGlobals: Set<string>;
	private written: Set<string>;

	constructor(private document: LuaDocument, private analysis: LuaAnalysis, private host: LuaHostShape | null, private out: LuaDiagnostic[]) {
		this.source = document.source;
		this.tokens = document.tokens;
		this.apiGlobals = new Set(Api.children('').map((e) => e.name));
		this.written = new Set(analysis.globals.filter((g) => g.write).map((g) => g.name));
	}

	run() {
		this.globals();
		this.accidentalGlobals();
		this.locals();
		this.members();
		this.contract();
		this.widgets();
		this.patterns();
		this.loops();
	}

	private add(code: LuaDiagnosticCode, span: LuaSpan, arg1?: string, arg2?: string) {
		this.out.push({ code, span, arg1, arg2 });
	}

	// ---- globals and locals ------------------------------------------------

	private globals() {
		let known: Set<string> | null = null;
		for (const use of this.analysis.globals) {
			const name = use.name;
			if (use.write) {
				if (this.apiGlobals.has(name)) this.add('REPLACES_BUILTIN', use.span, name);
				continue;
			}
			if (this.written.has(name) || this.apiGlobals.has(name) || name === '_ENV') continue;
			const reason = Api.nilled[name];
			if (reason) this.add(removed(reason), use.span, name);
			else if (name in LUA51_GLOBALS) this.lua51(name, LUA51_GLOBALS[name] ?? null, use.span);
			else {
				known ??= new Set([...this.apiGlobals, ...this.written, ...this.analysis.symbols.map((s) => s.name)]);
				const near = closest(name, known);
				if (near) this.add('GLOBAL_TYPO', use.span, name, near);
				else this.add('UNDEFINED_GLOBAL', use.span, name);
			}
		}
	}

	private accidentalGlobals() {
		const byName = new Map<string, typeof this.analysis.globals>();
		for (const g of this.analysis.globals) byName.set(g.name, [...(byName.get(g.name) ?? []), g]);
		for (const [name, uses] of byName) {
			if (this.apiGlobals.has(name) || CONTRACT_NAMES.has(name) || name in Api.nilled) continue;
			const first = uses.reduce((a, b) => (b.order < a.order ? b : a));
			if (!first.write || first.function === 0) continue;
			if (uses.every((u) => this.inside(u.function, first.function))) this.add('ACCIDENTAL_GLOBAL', first.span, name);
		}
	}

	private inside(fn: number, home: number): boolean {
		let at = fn;
		while (at !== 0) {
			if (at === home) return true;
			at = this.analysis.function(at)?.parent ?? 0;
		}
		return false;
	}

	private locals() {
		for (const s of this.analysis.symbols) {
			if (!s.declaration) continue;
			const declared = s.kind === 'LOCAL' || s.kind === 'LOCAL_FUNCTION';
			if (declared && s.reads.length === 0 && !s.name.startsWith('_')) this.add('UNUSED_LOCAL', s.declaration, s.name);
			if (CHECKED_ROOTS.has(s.name)) this.add('SHADOWS_LIBRARY', s.declaration, s.name);
		}
	}

	// ---- members of the API ------------------------------------------------

	private members() {
		const defined = this.writtenPaths();
		const t = this.tokens;
		for (const use of this.analysis.globals) {
			if (use.write || !CHECKED_ROOTS.has(use.name) || this.written.has(use.name)) continue;
			let path = use.name;
			let at = t.indexAt(use.span.start);
			for (;;) {
				const dot = t.nextCode(at);
				if (dot < 0 || !t.matches(dot, '.')) break;
				const member = t.nextCode(dot);
				if (member < 0 || t.kind(member) !== LuaTokenKind.NAME) break;
				if (Api.find(path)?.kind !== 'TABLE') break;
				const name = t.text(member);
				const full = `${path}.${name}`;
				if (!Api.find(full)) {
					if (!defined.has(full)) this.unknownMember(path, name, full, t.span(member));
					break;
				}
				if (full === 'wm.storage') {
					if (this.host && !this.host.storage) this.add('STORAGE_NOT_DECLARED', t.span(member));
				} else if (full === 'string.dump') this.add('STRING_DUMP', t.span(member));
				path = full;
				at = member;
			}
		}
	}

	private unknownMember(path: string, name: string, full: string, sp: LuaSpan) {
		if (path === 'wm' && Api.never.has(name)) this.add('NEVER_IN_API', sp, full);
		else if (path === 'os' && OS_REMOVED.has(name)) this.add('OS_REDUCED', sp, full);
		else if (full in LUA51_MEMBERS) this.lua51(full, LUA51_MEMBERS[full] ?? null, sp);
		else {
			const near = closest(name, Api.children(path).map((e) => e.name));
			if (near) this.add('MEMBER_TYPO', sp, full, `${path}.${near}`);
			else this.add('UNKNOWN_MEMBER', sp, full);
		}
	}

	private lua51(name: string, replacement: string | null, sp: LuaSpan) {
		if (replacement !== null) this.add('LUA51_NAME', sp, name, replacement);
		else this.add('LUA51_GONE', sp, name);
	}

	private writtenPaths(): Set<string> {
		const paths = new Set<string>();
		const t = this.tokens;
		for (let index = 0; index < t.size; index++) {
			const kind = t.kind(index);
			if (kind === LuaTokenKind.OPERATOR && t.matches(index, '=')) {
				const c = this.chain(t.prevCode(index), true);
				if (c) paths.add(c);
			} else if (kind === LuaTokenKind.KEYWORD && t.matches(index, 'function')) {
				const c = this.chain(t.nextCode(index), false);
				if (c) paths.add(c);
			}
		}
		return paths;
	}

	private chain(from: number, backwards: boolean): string | null {
		const t = this.tokens;
		const parts: string[] = [];
		let at = from;
		while (at >= 0 && t.kind(at) === LuaTokenKind.NAME) {
			parts.push(t.text(at));
			const dot = backwards ? t.prevCode(at) : t.nextCode(at);
			if (dot < 0 || !(t.matches(dot, '.') || (!backwards && t.matches(dot, ':')))) break;
			at = backwards ? t.prevCode(dot) : t.nextCode(dot);
		}
		if (parts.length < 2) return null;
		return (backwards ? parts.reverse() : parts).join('.');
	}

	// ---- the plugin contract -----------------------------------------------

	private contract() {
		if (!this.written.has('render')) {
			const nl = this.source.indexOf('\n');
			this.add('MISSING_RENDER', { start: 0, end: nl < 0 ? this.source.length : nl });
		}
		const render = this.topFunction('render');
		if (render) {
			if (render.parameters.length) this.add('RENDER_PARAMETERS', render.nameSpan);
			if (!render.returnsValue) this.add('RENDER_RETURNS_NOTHING', render.nameSpan);
		}
		const onEvent = this.topFunction('on_event');
		if (!onEvent) return;
		if (onEvent.parameters.length > 1) this.add('ON_EVENT_PARAMETERS', onEvent.nameSpan);
		this.eventComparisons(onEvent);
	}

	private topFunction(name: string): LuaFunction | null {
		return this.analysis.functions.find((f) => f.name === name && f.kind === 'GLOBAL' && f.parent === 0) ?? null;
	}

	private eventComparisons(onEvent: LuaFunction) {
		const first = onEvent.parameters[0];
		if (!first) return;
		const event = this.analysis.symbols.find((s) => s.kind === 'PARAMETER' && s.function === onEvent.id && s.name === first);
		if (!event) return;
		const ids = this.literalIds();
		const t = this.tokens;
		for (const read of event.reads) {
			const subject = t.indexAt(read.start);
			const dot = t.nextCode(subject);
			const field = dot >= 0 && t.matches(dot, '.') ? t.nextCode(dot) : -1;
			if (field < 0) continue;
			const isType = t.matches(field, 'type');
			if (!isType && !t.matches(field, 'id')) continue;
			const literal = this.comparedLiteral(subject, field);
			if (literal < 0) continue;
			const value = this.literalText(literal);
			if (value === null) continue;
			const sp = t.span(literal);
			if (isType) {
				if (Api.eventTypes.includes(value)) continue;
				const near = closest(value, Api.eventTypes);
				if (near) this.add('EVENT_TYPE_TYPO', sp, value, near);
				else this.add('UNKNOWN_EVENT_TYPE', sp, value);
			} else if (ids && ids.size > 0 && !ids.has(value)) this.add('UNKNOWN_EVENT_ID', sp, value);
		}
	}

	private comparedLiteral(subject: number, field: number): number {
		const t = this.tokens;
		const after = t.nextCode(field);
		if (after >= 0 && (t.matches(after, '==') || t.matches(after, '~='))) {
			const literal = t.nextCode(after);
			if (literal >= 0 && t.kind(literal) === LuaTokenKind.STRING) {
				const next = t.nextCode(literal);
				if (next < 0 || !t.matches(next, '..')) return literal;
			}
		}
		const before = t.prevCode(subject);
		if (before >= 0 && (t.matches(before, '==') || t.matches(before, '~='))) {
			const literal = t.prevCode(before);
			if (literal >= 0 && t.kind(literal) === LuaTokenKind.STRING) {
				const previous = t.prevCode(literal);
				if (previous < 0 || !t.matches(previous, '..')) return literal;
			}
		}
		return -1;
	}

	private literalIds(): Set<string> | null {
		const t = this.tokens;
		const ids = new Set<string>();
		for (let index = 0; index < t.size; index++) {
			if (t.kind(index) !== LuaTokenKind.NAME || !t.matches(index, 'id')) continue;
			const before = t.prevCode(index);
			if (before < 0 || !(t.matches(before, '{') || t.matches(before, ',') || t.matches(before, ';'))) continue;
			const key = this.keyAt(index);
			if (!key) continue;
			if (key.literal === null) return null;
			ids.add(key.literal);
		}
		return ids;
	}

	// ---- widgets -----------------------------------------------------------

	private widgets() {
		if (this.written.has('ui')) return;
		const t = this.tokens;
		const ids = new Set<string>();
		for (const use of this.analysis.globals) {
			if (use.write || use.name !== 'ui') continue;
			const uiToken = t.indexAt(use.span.start);
			const dot = t.nextCode(uiToken);
			if (dot < 0 || !t.matches(dot, '.')) continue;
			const nameToken = t.nextCode(dot);
			if (nameToken < 0 || t.kind(nameToken) !== LuaTokenKind.NAME) continue;
			const shape = Api.uiShapes[t.text(nameToken)];
			if (!shape) continue;
			let open = t.nextCode(nameToken);
			if (open >= 0 && t.matches(open, '(')) open = t.nextCode(open);
			if (open < 0 || !t.matches(open, '{')) continue;
			const table = this.fieldsOf(open);
			const constructor = `ui.${shape.constructor}`;
			const call: LuaSpan = { start: t.start(uiToken), end: t.end(nameToken) };
			for (const key of table.keys) {
				if (shape.fields.includes(key.name)) continue;
				const near = closest(key.name, shape.fields);
				const sp = t.span(key.token);
				if (near) this.add('UI_FIELD_TYPO', sp, key.name, near);
				else this.add('UNKNOWN_UI_FIELD', sp, key.name, constructor);
			}
			const id = table.keys.find((k) => k.name === 'id');
			if (shape.needsId && !id && !table.bracketKeys) this.add('MISSING_ID', call, constructor);
			const idText = id?.literal ?? null;
			if (shape.needsId && idText !== null) {
				if (ids.has(idText)) this.add('DUPLICATE_ID', t.span(id!.valueToken), idText);
				else ids.add(idText);
			}
			const style = table.keys.find((k) => k.name === 'style');
			const styleText = style?.literal ?? null;
			if (style && styleText !== null) {
				const sp = t.span(style.valueToken);
				if (shape.constructor === 'label' && !Api.labelStyles.includes(styleText)) {
					const near = closest(styleText, Api.labelStyles);
					if (near) this.add('LABEL_STYLE_TYPO', sp, styleText, near);
					else this.add('UNKNOWN_LABEL_STYLE', sp, styleText);
				}
				if (shape.constructor === 'button' && !Api.buttonStyles.includes(styleText)) this.add('PLAIN_BUTTON_STYLE', sp, styleText);
			}
			if (shape.constructor === 'tabs' && table.positional > MAX_TABS) this.add('TOO_MANY_TABS', call);
		}
	}

	private fieldsOf(open: number): { keys: Key[]; positional: number; bracketKeys: boolean } {
		const t = this.tokens;
		const blocks = this.document.blocks;
		const keys: Key[] = [];
		let positional = 0;
		let bracketKeys = false;
		let depth = 0;
		let fieldStart = true;
		let at = t.nextCode(open);
		while (at >= 0) {
			if (t.kind(at) === LuaTokenKind.KEYWORD && t.matches(at, 'function')) {
				const end = blocks.closerOf(at);
				if (end > at) {
					fieldStart = false;
					at = t.nextCode(end);
					continue;
				}
			}
			if (depth === 0 && fieldStart) {
				fieldStart = false;
				const key = this.keyAt(at);
				if (key) keys.push(key);
				else if (t.matches(at, '[')) bracketKeys = true;
				else if (!t.matches(at, '}')) positional++;
			}
			if (t.kind(at) === LuaTokenKind.OPERATOR) {
				if (t.matches(at, '(') || t.matches(at, '{') || t.matches(at, '[')) depth++;
				else if (t.matches(at, ')') || t.matches(at, '}') || t.matches(at, ']')) {
					if (depth === 0) break;
					depth--;
				} else if (depth === 0 && (t.matches(at, ',') || t.matches(at, ';'))) fieldStart = true;
			}
			at = t.nextCode(at);
		}
		return { keys, positional, bracketKeys };
	}

	private keyAt(token: number): Key | null {
		const t = this.tokens;
		if (t.kind(token) !== LuaTokenKind.NAME) return null;
		const equals = t.nextCode(token);
		if (equals < 0 || !t.matches(equals, '=')) return null;
		const value = t.nextCode(equals);
		if (value < 0) return null;
		const after = t.nextCode(value);
		const alone = after < 0 || t.matches(after, ',') || t.matches(after, ';') || t.matches(after, '}');
		return { name: t.text(token), token, valueToken: value, literal: alone ? this.literalText(value) : null };
	}

	private literalText(token: number): string | null {
		const t = this.tokens;
		if (t.kind(token) !== LuaTokenKind.STRING || t.unterminated(token)) return null;
		const text = t.text(token);
		if (text.length < 2 || text.includes('\\')) return null;
		return text.substring(1, text.length - 1);
	}

	// ---- patterns and loops ------------------------------------------------

	private patterns() {
		const t = this.tokens;
		let from = this.source.indexOf('%f[');
		while (from >= 0) {
			const token = t.indexAt(from);
			if (token < 0) break;
			const kind = t.kind(token);
			if (kind === LuaTokenKind.STRING || kind === LuaTokenKind.LONG_STRING) this.add('FRONTIER_PATTERN', t.span(token));
			from = this.source.indexOf('%f[', Math.max(t.end(token), from + 1));
		}
	}

	private loops() {
		const t = this.tokens;
		const blocks = this.document.blocks;
		for (let index = 0; index < t.size; index++) {
			if (t.kind(index) !== LuaTokenKind.KEYWORD) continue;
			if (t.matches(index, 'while')) {
				const condition = t.nextCode(index);
				const body = condition >= 0 ? t.nextCode(condition) : -1;
				if (condition < 0 || body < 0 || !t.matches(condition, 'true') || !t.matches(body, 'do')) continue;
				const end = blocks.closerOf(body);
				if (end > body && !this.exits(body, end)) this.add('LOOP_NEVER_ENDS', { start: t.start(index), end: t.end(condition) });
			} else if (t.matches(index, 'until')) {
				const condition = t.nextCode(index);
				if (condition < 0 || !t.matches(condition, 'false')) continue;
				const after = t.nextCode(condition);
				if (after >= 0 && (t.matches(after, 'and') || t.matches(after, 'or') || (t.kind(after) === LuaTokenKind.OPERATOR && !t.matches(after, ';')))) continue;
				const start = blocks.openerOf(index);
				if (start >= 0 && !this.exits(start, index)) this.add('LOOP_NEVER_ENDS', { start: t.start(index), end: t.end(condition) });
			}
		}
	}

	private exits(from: number, to: number): boolean {
		const t = this.tokens;
		const blocks = this.document.blocks;
		let at = from + 1;
		while (at < to) {
			const kind = t.kind(at);
			if (kind === LuaTokenKind.KEYWORD) {
				if (t.matches(at, 'function')) {
					const end = blocks.closerOf(at);
					if (end > at) {
						at = end + 1;
						continue;
					}
				}
				if (t.matches(at, 'break') || t.matches(at, 'return') || t.matches(at, 'goto')) return true;
			} else if (kind === LuaTokenKind.NAME && t.matches(at, 'error')) return true;
			at++;
		}
		return false;
	}
}

function removed(reason: Api.NilReason): LuaDiagnosticCode {
	switch (reason) {
		case 'LOADS_CODE': return 'REMOVED_LOADS_CODE';
		case 'FILES': return 'REMOVED_FILES';
		case 'CODE_FROM_TEXT': return 'REMOVED_CODE_FROM_TEXT';
		case 'COROUTINES': return 'REMOVED_COROUTINES';
		case 'JAVA_OR_DEBUGGER': return 'REMOVED_JAVA_OR_DEBUGGER';
	}
}

/** The candidate within typing distance of `word`, or null. A short word must be nearer. */
export function closest(word: string, candidates: Iterable<string>): string | null {
	if (word.length < 3) return null;
	const limit = word.length <= 4 ? 1 : 2;
	let best: string | null = null;
	let bestDistance = limit + 1;
	for (const candidate of candidates) {
		if (candidate === word || Math.abs(candidate.length - word.length) > limit) continue;
		const distance = editDistance(word, candidate);
		if (distance < bestDistance || (distance === bestDistance && best !== null && candidate < best)) {
			best = candidate;
			bestDistance = distance;
		}
	}
	return best !== null && bestDistance <= limit ? best : null;
}

/** Edits between two words, a swap of neighbours counting as one and case not counting at all. */
export function editDistance(a: string, b: string): number {
	const d: number[][] = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0));
	for (let i = 0; i <= a.length; i++) d[i]![0] = i;
	for (let j = 0; j <= b.length; j++) d[0]![j] = j;
	for (let i = 1; i <= a.length; i++) {
		for (let j = 1; j <= b.length; j++) {
			const cost = a[i - 1]!.toLowerCase() === b[j - 1]!.toLowerCase() ? 0 : 1;
			let value = Math.min(d[i - 1]![j]! + 1, d[i]![j - 1]! + 1, d[i - 1]![j - 1]! + cost);
			if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) value = Math.min(value, d[i - 2]![j - 2]! + 1);
			d[i]![j] = value;
		}
	}
	return d[a.length]![b.length]!;
}
