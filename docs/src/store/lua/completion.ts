/** Port of LuaCompletion.kt: what can be typed at the caret, from tokens plus an analysis when at hand. */
import * as Api from './api';
import type { LuaAnalysis } from './analyse';
import type { LuaDocument } from './document';
import type { LuaHostShape } from './diagnostics';
import { LuaTokenKind, type LuaSpan, type LuaTokens } from './tokens';

export type LuaCompletionKind = 'LOCAL' | 'PARAMETER' | 'GLOBAL' | 'FUNCTION' | 'TABLE' | 'CONSTANT' | 'FIELD' | 'KEYWORD' | 'SNIPPET' | 'VALUE';

export interface LuaCompletionItem {
	label: string;
	kind: LuaCompletionKind;
	insert: string;
	/** Where the caret lands inside `insert`. */
	caret: number;
	detail?: string | null;
	docPath?: string | null;
}

export interface LuaCompletions {
	replace: LuaSpan;
	items: LuaCompletionItem[];
}

export const MAX_ITEMS = 50;
const MAX_BACK_TOKENS = 4000;

const KEYWORDS = ['and', 'break', 'do', 'else', 'elseif', 'end', 'false', 'for', 'function', 'goto', 'if', 'in', 'local', 'nil', 'not', 'or', 'repeat', 'return', 'then', 'true', 'until', 'while'];
const EVENT_FIELDS: [string, string][] = [['type', 'string'], ['id', 'string'], ['value', 'string or boolean'], ['index', 'number']];
const SNIPPETS: [string, string][] = [
	['function render()', 'function render()\n  return ui.column {\n    |\n  }\nend'],
	['function on_event(e)', 'function on_event(e)\n  if e.type == "click" then\n    |\n  end\nend'],
	['local function', 'local function |()\n  \nend'],
	['for i = 1, n', 'for i = 1, | do\n  \nend'],
	['for key, value in pairs', 'for key, value in pairs(|) do\n  \nend'],
	['for index, value in ipairs', 'for index, value in ipairs(|) do\n  \nend'],
	['if ... then', 'if | then\n  \nend'],
	['while ... do', 'while | do\n  \nend'],
	['repeat ... until', 'repeat\n  \nuntil |'],
];

export function completionsAt(document: LuaDocument, caret: number, host: LuaHostShape | null = null, explicit = false, analysis: LuaAnalysis | null = document.analysis): LuaCompletions | null {
	return completionsFromTokens(document.tokens, caret, host, explicit, analysis);
}

export function completionsFromTokens(tokens: LuaTokens, caret: number, host: LuaHostShape | null = null, explicit = false, analysis: LuaAnalysis | null = null): LuaCompletions | null {
	if (caret < 0 || caret > tokens.source.length) return null;
	return new Context(tokens, caret, host, explicit, analysis).complete();
}

const isWordChar = (c: string) => c === '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');

class Context {
	private source: string;
	private wordStart: number;
	private wordEnd: number;
	private prefix: string;
	private localNames = new Set<string>();
	private frequencyCache: Map<string, number> | null = null;

	constructor(private tokens: LuaTokens, private caret: number, _host: LuaHostShape | null, private explicit: boolean, private analysis: LuaAnalysis | null) {
		this.source = tokens.source;
		let at = caret;
		while (at > 0 && isWordChar(this.source[at - 1]!)) at--;
		this.wordStart = at;
		at = caret;
		while (at < this.source.length && isWordChar(this.source[at]!)) at++;
		this.wordEnd = at;
		this.prefix = this.source.substring(this.wordStart, caret);
	}

	private get frequency(): Map<string, number> {
		if (!this.frequencyCache) {
			const counts = new Map<string, number>();
			for (let i = 0; i < this.tokens.size; i++) {
				if (this.tokens.kind(i) === LuaTokenKind.NAME) {
					const t = this.tokens.text(i);
					counts.set(t, (counts.get(t) ?? 0) + 1);
				}
			}
			this.frequencyCache = counts;
		}
		return this.frequencyCache;
	}

	complete(): LuaCompletions | null {
		const t = this.tokens;
		if (this.prefix && this.prefix[0]! >= '0' && this.prefix[0]! <= '9') return null;
		if (this.caret > 0) {
			const token = t.indexAt(this.caret - 1);
			if (token >= 0) {
				const kind = t.kind(token);
				const start = t.start(token);
				const end = t.end(token);
				const inside = this.caret > start && (this.caret < end || t.unterminated(token));
				if (kind === LuaTokenKind.COMMENT || kind === LuaTokenKind.SHEBANG) return null;
				if ((kind === LuaTokenKind.LONG_COMMENT || kind === LuaTokenKind.LONG_STRING) && inside) return null;
				if (kind === LuaTokenKind.STRING && inside) return this.inString(token);
			}
		}
		const before = this.codeBefore(this.wordStart);
		if (before >= 0 && t.matches(before, '.')) return this.members(before);
		if (before >= 0 && t.matches(before, ':')) return this.methods();
		const ui = this.uiKeys(before);
		if (ui) return ui;
		if (!this.prefix && !this.explicit) return null;
		if (before >= 0 && t.kind(before) === LuaTokenKind.KEYWORD) {
			switch (t.text(before)) {
				case 'local': return this.finish([item('function', 'KEYWORD')]);
				case 'function': return this.finish(this.contractNames());
				case 'for': case 'goto': return null;
			}
		}
		return this.finish(this.names(), true);
	}

	// ---- contexts ----------------------------------------------------------

	private inString(token: number): LuaCompletions | null {
		const t = this.tokens;
		const start = t.start(token) + 1;
		let end = this.caret;
		while (end < t.end(token) && isWordChar(this.source[end]!)) end++;
		const typed = this.source.substring(start, this.caret);
		const before = t.prevCode(token);
		if (before < 0) return null;
		let values: string[] = [];
		if (t.matches(before, '=')) {
			const key = t.prevCode(before);
			if (key >= 0 && t.kind(key) === LuaTokenKind.NAME) {
				if (t.matches(key, 'style')) {
					const c = this.constructorAround(key);
					values = c === 'label' ? Api.labelStyles : c === 'button' ? Api.buttonStyles : [];
				} else if (t.matches(key, 'type') && this.isTableKey(key)) values = [...Api.widgetTypes].sort();
			}
		} else if (t.matches(before, '==') || t.matches(before, '~=')) {
			const field = t.prevCode(before);
			const dot = field >= 0 ? t.prevCode(field) : -1;
			if (dot >= 0 && t.matches(dot, '.')) {
				if (t.matches(field, 'type')) values = Api.eventTypes;
				else if (t.matches(field, 'id')) values = this.literalIds();
			}
		}
		const items = values.filter((v) => matchClass(v, typed) >= 0 && v !== typed).map((v): LuaCompletionItem => ({ label: v, kind: 'VALUE', insert: v, caret: v.length }));
		return items.length ? { replace: { start, end }, items: items.slice(0, MAX_ITEMS) } : null;
	}

	private members(dot: number): LuaCompletions | null {
		const chain = this.chainBefore(dot);
		const root = chain[0];
		const items: LuaCompletionItem[] = [];
		if (root !== undefined && !this.isLocal(root)) {
			const path = chain.join('.');
			if (Api.find(path)?.kind === 'TABLE') for (const e of Api.children(path)) items.push(apiItem(e));
		}
		if (chain.length === 1 && root === this.eventParameter()) {
			for (const [name, type] of EVENT_FIELDS) items.push({ label: name, kind: 'FIELD', insert: name, caret: name.length, detail: type });
		}
		if (chain.length) for (const name of this.fieldsUsed(chain)) items.push({ label: name, kind: 'FIELD', insert: name, caret: name.length });
		return this.finish(items);
	}

	private methods(): LuaCompletions | null {
		const t = this.tokens;
		const items: LuaCompletionItem[] = [];
		for (const e of Api.children('string')) items.push({ label: e.name, kind: 'FUNCTION', insert: e.name + '()', caret: e.name.length + 1, detail: e.signature, docPath: e.path });
		for (let i = 0; i < t.size; i++) {
			if (!t.matches(i, ':')) continue;
			const name = t.nextCode(i);
			if (name >= 0 && t.kind(name) === LuaTokenKind.NAME && !this.isBeingTyped(name)) {
				const text = t.text(name);
				items.push({ label: text, kind: 'FUNCTION', insert: `${text}()`, caret: text.length + 1 });
			}
		}
		return this.finish(items);
	}

	private uiKeys(before: number): LuaCompletions | null {
		const t = this.tokens;
		if (before < 0 || !(t.matches(before, '{') || t.matches(before, ',') || t.matches(before, ';'))) return null;
		const open = t.matches(before, '{') ? before : this.openBrace(t.prevCode(before));
		if (open < 0) return null;
		const name = this.constructorBefore(open);
		if (!name) return null;
		const shape = Api.uiShapes[name];
		if (!shape) return null;
		if (!this.prefix && !this.explicit) return null;
		const written = this.keysIn(open);
		const items: LuaCompletionItem[] = [];
		for (const field of shape.fields) {
			if (written.has(field)) continue;
			const template = fieldTemplate(field);
			items.push({ label: field, kind: 'FIELD', insert: template.replace('|', ''), caret: template.indexOf('|'), docPath: `ui.${shape.constructor}` });
		}
		if (shape.takesChildren) {
			for (const child of Object.values(Api.uiShapes)) {
				if ((child.constructor === 'page') !== (shape.constructor === 'tabs')) continue;
				const entry = Api.find(`ui.${child.constructor}`);
				if (!entry) continue;
				const template = 'ui.' + constructorTemplate(child);
				items.push({ label: child.constructor, kind: 'FUNCTION', insert: template.replace('|', ''), caret: template.indexOf('|'), detail: entry.signature, docPath: entry.path });
			}
		}
		return this.finish(items);
	}

	private names(): LuaCompletionItem[] {
		const items: LuaCompletionItem[] = [];
		const locals: [string, LuaCompletionKind][] = this.analysis
			? this.analysis.visibleAt(this.caret).map((s) => [s.name, s.kind === 'PARAMETER' || s.kind === 'SELF' ? 'PARAMETER' : s.kind === 'LOCAL_FUNCTION' ? 'FUNCTION' : 'LOCAL'])
			: this.declaredBefore();
		for (const [name, kind] of locals) {
			items.push(item(name, kind));
			this.localNames.add(name);
		}
		for (const name of this.scriptGlobals()) items.push(item(name, 'GLOBAL'));
		for (const e of Api.children('')) items.push(apiItem(e));
		for (const k of KEYWORDS) items.push(item(k, 'KEYWORD'));
		const indent = this.indentAt(this.caret);
		const defined = this.definedFunctions();
		for (const [label, template] of SNIPPETS) {
			if (label === 'function render()' && defined.has('render')) continue;
			if (label === 'function on_event(e)' && defined.has('on_event')) continue;
			if (!this.prefix || !label.split(' ')[0]!.toLowerCase().startsWith(this.prefix.toLowerCase())) continue;
			const indented = template.replace(/\n/g, `\n${indent}`);
			items.push({ label, kind: 'SNIPPET', insert: indented.replace('|', ''), caret: indented.indexOf('|') });
		}
		return items;
	}

	private contractNames(): LuaCompletionItem[] {
		const defined = this.definedFunctions();
		const out: LuaCompletionItem[] = [];
		if (!defined.has('render')) out.push({ label: 'render', kind: 'FUNCTION', insert: 'render()', caret: 7 });
		if (!defined.has('on_event')) out.push({ label: 'on_event', kind: 'FUNCTION', insert: 'on_event(e)', caret: 11 });
		return out;
	}

	// ---- ranking -----------------------------------------------------------

	private finish(items: LuaCompletionItem[], ranked = false): LuaCompletions | null {
		const best = new Map<string, [LuaCompletionItem, number]>();
		for (const c of items) {
			const match = c.kind === 'SNIPPET' ? 0 : matchClass(c.label, this.prefix);
			if (match < 0) continue;
			if (c.label === this.prefix && c.insert === this.prefix) continue;
			const score = match * 10 + (ranked ? this.rankOf(c) : 0);
			const held = best.get(c.label);
			if (!held || score < held[1]) best.set(c.label, [c, score]);
		}
		if (!best.size) return null;
		const freq = this.frequency;
		const sorted = [...best.values()].sort((a, b) => a[1] - b[1] || (freq.get(b[0].label) ?? 0) - (freq.get(a[0].label) ?? 0) || a[0].label.length - b[0].label.length || (a[0].label < b[0].label ? -1 : a[0].label > b[0].label ? 1 : 0));
		return { replace: { start: this.wordStart, end: this.wordEnd }, items: sorted.slice(0, MAX_ITEMS).map((p) => p[0]) };
	}

	private rankOf(c: LuaCompletionItem): number {
		return this.localNames.has(c.label) && c.kind !== 'KEYWORD' && c.kind !== 'SNIPPET' ? 0 : kindRank(c.kind);
	}

	// ---- reading the file --------------------------------------------------

	private codeBefore(offset: number): number {
		if (offset <= 0) return -1;
		const token = this.tokens.indexAt(offset - 1);
		if (token < 0) return -1;
		return this.tokens.isCode(token) ? token : this.tokens.prevCode(token);
	}

	private chainBefore(dot: number): string[] {
		const t = this.tokens;
		const parts: string[] = [];
		let at = t.prevCode(dot);
		while (at >= 0 && t.kind(at) === LuaTokenKind.NAME) {
			parts.push(t.text(at));
			const previous = t.prevCode(at);
			if (previous < 0 || !t.matches(previous, '.')) break;
			at = t.prevCode(previous);
		}
		if (at >= 0 && t.kind(at) !== LuaTokenKind.NAME) return [];
		return parts.reverse();
	}

	private isLocal(name: string): boolean {
		return this.analysis ? this.analysis.visibleAt(this.caret).some((s) => s.name === name) : this.declaredBefore().some((d) => d[0] === name);
	}

	private declaredBefore(): [string, LuaCompletionKind][] {
		const t = this.tokens;
		const found: [string, LuaCompletionKind][] = [];
		for (let index = 0; index < t.size; index++) {
			if (t.start(index) >= this.wordStart) break;
			if (t.kind(index) !== LuaTokenKind.KEYWORD) continue;
			const word = t.text(index);
			if (word === 'local' || word === 'for') {
				let at = t.nextCode(index);
				if (at >= 0 && t.matches(at, 'function')) {
					const name = t.nextCode(at);
					if (name >= 0 && t.kind(name) === LuaTokenKind.NAME) found.push([t.text(name), 'FUNCTION']);
					continue;
				}
				while (at >= 0 && t.kind(at) === LuaTokenKind.NAME && t.start(at) < this.wordStart) {
					found.push([t.text(at), 'LOCAL']);
					const comma = t.nextCode(at);
					if (comma < 0 || !t.matches(comma, ',')) break;
					at = t.nextCode(comma);
				}
			} else if (word === 'function') {
				let at = t.nextCode(index);
				while (at >= 0 && !t.matches(at, '(') && t.start(at) < this.wordStart) at = t.nextCode(at);
				if (at < 0) continue;
				at = t.nextCode(at);
				while (at >= 0 && t.kind(at) === LuaTokenKind.NAME && t.start(at) < this.wordStart) {
					found.push([t.text(at), 'PARAMETER']);
					const comma = t.nextCode(at);
					if (comma < 0 || !t.matches(comma, ',')) break;
					at = t.nextCode(comma);
				}
			}
		}
		return found;
	}

	private scriptGlobals(): Set<string> {
		if (this.analysis) return new Set(this.analysis.globals.filter((g) => g.write).map((g) => g.name));
		const t = this.tokens;
		const names = new Set<string>();
		for (let index = 0; index < t.size; index++) {
			if (t.kind(index) !== LuaTokenKind.NAME) continue;
			const previous = t.prevCode(index);
			const next = t.nextCode(index);
			const atLineStart = previous < 0 || this.source.lastIndexOf('\n', t.start(index)) > t.end(previous) - 1;
			const assigned = next >= 0 && t.matches(next, '=') && atLineStart;
			const declared = previous >= 0 && t.matches(previous, 'function') && !(next >= 0 && t.matches(next, '.'));
			if ((assigned || declared) && !this.isBeingTyped(index)) names.add(t.text(index));
		}
		return names;
	}

	private definedFunctions(): Set<string> {
		const t = this.tokens;
		const names = new Set<string>();
		for (let index = 0; index < t.size; index++) {
			if (!t.matches(index, 'function')) continue;
			const name = t.nextCode(index);
			if (name >= 0 && t.kind(name) === LuaTokenKind.NAME && !this.isBeingTyped(name)) names.add(t.text(name));
		}
		return names;
	}

	private eventParameter(): string | null {
		const t = this.tokens;
		for (let index = 0; index < t.size; index++) {
			if (!t.matches(index, 'function')) continue;
			const name = t.nextCode(index);
			if (name < 0 || !t.matches(name, 'on_event')) continue;
			const open = t.nextCode(name);
			const first = open >= 0 && t.matches(open, '(') ? t.nextCode(open) : -1;
			return first >= 0 && t.kind(first) === LuaTokenKind.NAME ? t.text(first) : null;
		}
		return null;
	}

	private fieldsUsed(chain: string[]): Set<string> {
		const t = this.tokens;
		const names = new Set<string>();
		const last = chain[chain.length - 1]!;
		for (let index = 0; index < t.size; index++) {
			if (t.kind(index) !== LuaTokenKind.NAME || !t.matches(index, last)) continue;
			const ending = this.chainEndingAt(index);
			if (ending.length !== chain.length || ending.some((p, i) => p !== chain[i])) continue;
			const next = t.nextCode(index);
			if (next >= 0 && t.matches(next, '.')) {
				const field = t.nextCode(next);
				if (field >= 0 && t.kind(field) === LuaTokenKind.NAME && !this.isBeingTyped(field)) names.add(t.text(field));
			} else if (next >= 0 && t.matches(next, '=')) {
				const open = t.nextCode(next);
				if (open >= 0 && t.matches(open, '{')) for (const k of this.keysIn(open)) names.add(k);
			}
		}
		return names;
	}

	private chainEndingAt(last: number): string[] {
		const t = this.tokens;
		const parts: string[] = [];
		let at = last;
		while (at >= 0 && t.kind(at) === LuaTokenKind.NAME) {
			parts.push(t.text(at));
			const previous = t.prevCode(at);
			if (previous < 0 || !t.matches(previous, '.')) break;
			at = t.prevCode(previous);
		}
		return parts.reverse();
	}

	private openBrace(token: number): number {
		const t = this.tokens;
		let depth = 0;
		let at = token;
		let steps = 0;
		while (at >= 0 && steps++ < MAX_BACK_TOKENS) {
			if (t.kind(at) === LuaTokenKind.OPERATOR) {
				if (t.matches(at, ')') || t.matches(at, '}') || t.matches(at, ']')) depth++;
				else if (t.matches(at, '(') || t.matches(at, '{') || t.matches(at, '[')) {
					if (depth === 0) return t.matches(at, '{') ? at : -1;
					depth--;
				}
			}
			at = t.prevCode(at);
		}
		return -1;
	}

	private constructorBefore(open: number): string | null {
		const t = this.tokens;
		let name = t.prevCode(open);
		if (name >= 0 && t.matches(name, '(')) name = t.prevCode(name);
		if (name < 0 || t.kind(name) !== LuaTokenKind.NAME) return null;
		const dot = t.prevCode(name);
		const root = dot >= 0 && t.matches(dot, '.') ? t.prevCode(dot) : -1;
		return root >= 0 && t.matches(root, 'ui') ? t.text(name) : null;
	}

	private constructorAround(key: number): string | null {
		const t = this.tokens;
		const before = t.prevCode(key);
		if (before < 0) return null;
		const open = t.matches(before, '{') ? before : this.openBrace(t.prevCode(before));
		return open >= 0 ? this.constructorBefore(open) : null;
	}

	private isTableKey(key: number): boolean {
		const t = this.tokens;
		const before = t.prevCode(key);
		return before >= 0 && (t.matches(before, '{') || t.matches(before, ',') || t.matches(before, ';'));
	}

	private keysIn(open: number): Set<string> {
		const t = this.tokens;
		const keys = new Set<string>();
		let depth = 0;
		let at = t.nextCode(open);
		while (at >= 0) {
			if (t.kind(at) === LuaTokenKind.OPERATOR) {
				if (t.matches(at, '(') || t.matches(at, '{') || t.matches(at, '[')) depth++;
				else if (t.matches(at, ')') || t.matches(at, '}') || t.matches(at, ']')) {
					if (depth === 0) break;
					depth--;
				}
			} else if (depth === 0 && t.kind(at) === LuaTokenKind.NAME && !this.isBeingTyped(at)) {
				const next = t.nextCode(at);
				if (next >= 0 && t.matches(next, '=') && this.isTableKey(at)) keys.add(t.text(at));
			}
			at = t.nextCode(at);
		}
		return keys;
	}

	private literalIds(): string[] {
		const t = this.tokens;
		const ids = new Set<string>();
		for (let index = 0; index < t.size; index++) {
			if (!t.matches(index, 'id') || !this.isTableKey(index)) continue;
			const equals = t.nextCode(index);
			const value = equals >= 0 && t.matches(equals, '=') ? t.nextCode(equals) : -1;
			if (value >= 0 && t.kind(value) === LuaTokenKind.STRING && !t.unterminated(value)) {
				const text = t.text(value);
				if (text.length >= 2 && !text.includes('\\')) ids.add(text.substring(1, text.length - 1));
			}
		}
		return [...ids];
	}

	private indentAt(offset: number): string {
		let start = offset;
		while (start > 0 && this.source[start - 1] !== '\n') start--;
		let end = start;
		while (end < this.source.length && (this.source[end] === ' ' || this.source[end] === '\t')) end++;
		return this.source.substring(start, Math.min(end, offset));
	}

	private isBeingTyped(token: number): boolean {
		return this.wordEnd > this.wordStart && this.tokens.start(token) === this.wordStart && this.tokens.end(token) === this.wordEnd;
	}
}

/** 0 for a prefix in the same case, 1 in any case, 2 for the letters in order, -1 for no match. */
export function matchClass(label: string, typed: string): number {
	if (!typed || label.startsWith(typed)) return 0;
	if (label.toLowerCase().startsWith(typed.toLowerCase())) return 1;
	if (label[0]!.toLowerCase() !== typed[0]!.toLowerCase()) return -1;
	let at = 0;
	for (const c of label) if (at < typed.length && c.toLowerCase() === typed[at]!.toLowerCase()) at++;
	return at === typed.length ? 2 : -1;
}

function kindRank(kind: LuaCompletionKind): number {
	switch (kind) {
		case 'LOCAL': case 'PARAMETER': return 0;
		case 'GLOBAL': return 1;
		case 'FUNCTION': case 'TABLE': case 'CONSTANT': case 'FIELD': return 2;
		case 'KEYWORD': case 'VALUE': return 3;
		case 'SNIPPET': return 4;
	}
}

const item = (name: string, kind: LuaCompletionKind): LuaCompletionItem => ({ label: name, kind, insert: name, caret: name.length });

function apiItem(e: Api.LuaApiEntry): LuaCompletionItem {
	const kind: LuaCompletionKind = e.kind === 'FUNCTION' ? 'FUNCTION' : e.kind === 'TABLE' ? 'TABLE' : 'CONSTANT';
	const shape = e.parent === 'ui' ? Api.uiShapes[e.name] : undefined;
	const template = shape ? constructorTemplate(shape) : e.kind !== 'FUNCTION' ? e.name + '|' : e.signature?.endsWith('()') ? e.name + '()|' : e.name + '(|)';
	return { label: e.name, kind, insert: template.replace('|', ''), caret: template.indexOf('|'), detail: e.signature, docPath: e.path };
}

export function constructorTemplate(shape: Api.LuaUiShape): string {
	const name = shape.constructor;
	let wanted: string[];
	switch (name) {
		case 'button': wanted = ['id', 'text']; break;
		case 'toggle': case 'input': wanted = ['id', 'label']; break;
		case 'label': case 'output': wanted = ['text']; break;
		case 'tabs': wanted = ['id']; break;
		case 'page': wanted = ['title']; break;
		case 'spacer': return 'spacer { height = 8 }|';
		default: wanted = [];
	}
	if (!wanted.length) return shape.takesChildren ? `${name} { | }` : `${name}()|`;
	const fields = wanted.map(fieldTemplate).join(', ');
	const first = fields.indexOf('|');
	return `${name} { ` + fields.substring(0, first + 1) + fields.substring(first + 1).replace(/\|/g, '') + ' }';
}

function fieldTemplate(field: string): string {
	switch (field) {
		case 'mono': case 'insertable': case 'copyable': case 'enabled': case 'checked': return `${field} = |`;
		case 'height': return `${field} = |8`;
		default: return `${field} = "|"`;
	}
}
