/** Port of LuaDiagnostics.kt + strings_lua_editor.xml (LuaDiagnosticText). */
import type { LuaDocument } from './document';
import type { LuaSyntax } from './parse';
import { LuaTokenKind, type LuaSpan, type LuaTokens } from './tokens';
import { runChecks } from './checks';

export type LuaSeverity = 'ERROR' | 'WARNING' | 'INFO';
const SEV_ORDER: Record<LuaSeverity, number> = { ERROR: 0, WARNING: 1, INFO: 2 };

export const CODES = {
	UNKNOWN_CHARACTER: ['ERROR', 'Lua cannot read %1 here.'],
	UNCLOSED_STRING: ['ERROR', 'This string has no closing quote before the end of the line.'],
	UNCLOSED_LONG_STRING: ['ERROR', 'This long string has no closing brackets.'],
	UNCLOSED_COMMENT: ['ERROR', 'This comment has no closing brackets.'],
	UNCLOSED_BRACKET: ['ERROR', 'Nothing closes this %1.'],
	WRONG_CLOSER: ['ERROR', 'This is %1, but the open bracket needs %2.'],
	STRAY_CLOSER: ['ERROR', 'No open bracket is here for this %1 to close.'],
	UNCLOSED_BLOCK: ['ERROR', 'This %1 has no %2.'],
	SYNTAX_EXPECTED: ['ERROR', 'Lua expected %2 here, and found %1.'],
	SYNTAX: ['ERROR', 'Lua did not expect %1 here.'],
	SYNTAX_AT_END_EXPECTED: ['ERROR', 'The script stops too soon. Lua expected %1 next.'],
	SYNTAX_AT_END: ['ERROR', 'The script stops too soon.'],
	REMOVED_LOADS_CODE: ['ERROR', '%1 is not available. A plugin cannot load other code.'],
	REMOVED_FILES: ['ERROR', '%1 is not available. A plugin cannot open files.'],
	REMOVED_CODE_FROM_TEXT: ['ERROR', '%1 is not available. A plugin cannot run code made from text.'],
	REMOVED_COROUTINES: ['ERROR', '%1 is not available. Plugins have no coroutines.'],
	REMOVED_JAVA_OR_DEBUGGER: ['ERROR', '%1 is not available. A plugin cannot reach Java or the debugger.'],
	NEVER_IN_API: ['ERROR', '%1 does not exist. No plugin can read text, the clipboard, files or the network.'],
	OS_REDUCED: ['ERROR', '%1 is not available. Plugins get os.time, os.clock and os.date only.'],
	UNKNOWN_MEMBER: ['WARNING', '%1 does not exist.'],
	MEMBER_TYPO: ['WARNING', '%1 does not exist. Did you mean %2?'],
	LUA51_NAME: ['WARNING', '%1 is from Lua 5.1. Use %2 instead.'],
	LUA51_GONE: ['WARNING', '%1 is from Lua 5.1, and Lua 5.2 has nothing in its place.'],
	STRING_DUMP: ['WARNING', 'string.dump always fails in a plugin.'],
	STORAGE_NOT_DECLARED: ['WARNING', 'wm.storage is nil because the manifest does not declare the storage permission.'],
	FRONTIER_PATTERN: ['WARNING', 'Frontier patterns stop the plugin with an error on this keyboard.'],
	MISSING_RENDER: ['ERROR', 'The script has no render function, so the panel stays empty.'],
	RENDER_RETURNS_NOTHING: ['WARNING', 'render returns nothing, so the panel stays empty.'],
	RENDER_PARAMETERS: ['INFO', 'render gets no arguments, so these parameters are always nil.'],
	ON_EVENT_PARAMETERS: ['INFO', 'on_event gets one argument, the event. The other parameters are always nil.'],
	UNKNOWN_EVENT_TYPE: ['WARNING', 'No event has the type %1.'],
	EVENT_TYPE_TYPO: ['WARNING', 'No event has the type %1. Did you mean %2?'],
	UNKNOWN_EVENT_ID: ['INFO', 'No widget in this script has the id %1.'],
	UNKNOWN_UI_FIELD: ['WARNING', '%2 ignores the field %1.'],
	UI_FIELD_TYPO: ['WARNING', 'This widget has no field %1. Did you mean %2?'],
	MISSING_ID: ['WARNING', '%1 needs an id, or on_event cannot tell which widget the event is from.'],
	DUPLICATE_ID: ['INFO', 'Another widget already has the id %1.'],
	UNKNOWN_LABEL_STYLE: ['WARNING', 'A label has no style %1. It shows as body text.'],
	LABEL_STYLE_TYPO: ['WARNING', 'A label has no style %1. Did you mean %2?'],
	PLAIN_BUTTON_STYLE: ['INFO', 'A button has no style %1. It shows as a plain button.'],
	TOO_MANY_TABS: ['WARNING', 'The keyboard shows only the first eight pages of a tab strip.'],
	UNDEFINED_GLOBAL: ['WARNING', 'Nothing in the script defines %1.'],
	GLOBAL_TYPO: ['WARNING', 'Nothing in the script defines %1. Did you mean %2?'],
	ACCIDENTAL_GLOBAL: ['WARNING', '%1 is global. Write local in front of it if only this function uses it.'],
	REPLACES_BUILTIN: ['WARNING', 'This replaces the built-in %1 for the whole plugin.'],
	UNUSED_LOCAL: ['INFO', 'The script never reads %1.'],
	SHADOWS_LIBRARY: ['INFO', 'This local hides the %1 library until the end of the block.'],
	LOOP_NEVER_ENDS: ['WARNING', 'Nothing ends this loop, so the plugin stops when its time runs out.'],
} as const satisfies Record<string, readonly [LuaSeverity, string]>;

export type LuaDiagnosticCode = keyof typeof CODES;

export interface LuaDiagnostic {
	code: LuaDiagnosticCode;
	span: LuaSpan;
	arg1?: string;
	arg2?: string;
}

export function severityOf(code: LuaDiagnosticCode): LuaSeverity {
	return CODES[code][0];
}

/** The sentence for a diagnostic, the way LuaDiagnosticText formats it: both slots always filled. */
export function textOf(d: LuaDiagnostic): string {
	return CODES[d.code][1].replace(/%1/g, d.arg1 ?? '').replace(/%2/g, d.arg2 ?? '');
}

export interface LuaHostShape {
	storage: boolean;
}

export const MAX_DIAGNOSTICS = 200;
const MAX_EXPECTED = 4;
const MAX_SHOWN = 40;
const MAX_HEAD_TOKENS = 64;
const OPENERS = '({[';
const CLOSERS = ')}]';
const EXPRESSION_KEYWORDS = new Set(['and', 'or', 'not', 'nil', 'true', 'false', 'in']);

export function diagnosticsOf(document: LuaDocument, host: LuaHostShape | null = null): LuaDiagnostic[] {
	const out: LuaDiagnostic[] = [];
	lexical(document.tokens, out);
	const syntax = document.syntax;
	if (syntax.kind === 'invalid' && out.length === 0) out.push(structure(document, syntax));
	if (document.analysis) runChecks(document, document.analysis, host, out);
	const seen = new Set<string>();
	return out
		.filter((d) => {
			const key = `${d.code}|${d.span.start}|${d.span.end}|${d.arg1 ?? ''}|${d.arg2 ?? ''}`;
			if (seen.has(key)) return false;
			seen.add(key);
			return true;
		})
		.sort((a, b) => a.span.start - b.span.start || SEV_ORDER[severityOf(a.code)] - SEV_ORDER[severityOf(b.code)])
		.slice(0, MAX_DIAGNOSTICS);
}

function lexical(tokens: LuaTokens, out: LuaDiagnostic[]) {
	let index = 0;
	while (index < tokens.size) {
		const kind = tokens.kind(index);
		const start = tokens.start(index);
		const end = tokens.end(index);
		if (kind === LuaTokenKind.UNKNOWN) {
			let last = index;
			while (last + 1 < tokens.size && tokens.kind(last + 1) === LuaTokenKind.UNKNOWN) last++;
			const until = tokens.end(last);
			out.push({ code: 'UNKNOWN_CHARACTER', span: { start, end: until }, arg1: tokens.source.substring(start, Math.min(until, start + MAX_SHOWN)) });
			index = last;
		} else if (tokens.unterminated(index)) {
			if (kind === LuaTokenKind.STRING) out.push({ code: 'UNCLOSED_STRING', span: { start, end: Math.min(end, start + 1) } });
			else if (kind === LuaTokenKind.LONG_STRING) out.push({ code: 'UNCLOSED_LONG_STRING', span: { start, end: Math.min(end, start + tokens.level(index) + 2) } });
			else if (kind === LuaTokenKind.LONG_COMMENT) out.push({ code: 'UNCLOSED_COMMENT', span: { start, end: Math.min(end, start + tokens.level(index) + 4) } });
		}
		index++;
	}
}

function structure(document: LuaDocument, syntax: Extract<LuaSyntax, { kind: 'invalid' }>): LuaDiagnostic {
	const tokens = document.tokens;
	const bracket = brackets(tokens);
	if (bracket) return bracket;
	const atEnd = syntax.found === '<EOF>';
	if (atEnd) {
		const block = unclosedBlock(document);
		if (block) return block;
	}
	const expectedList = syntax.expected.filter((e) => !e.startsWith('<')).slice(0, MAX_EXPECTED).join(', ');
	const expected = expectedList || null;
	if (atEnd) {
		let last = -1;
		for (let i = tokens.size - 1; i >= 0; i--) if (tokens.isCode(i)) { last = i; break; }
		const sp = last >= 0 ? tokens.span(last) : syntax.at;
		return expected ? { code: 'SYNTAX_AT_END_EXPECTED', span: sp, arg1: expected } : { code: 'SYNTAX_AT_END', span: sp };
	}
	const found = syntax.found.slice(0, MAX_SHOWN);
	return expected ? { code: 'SYNTAX_EXPECTED', span: syntax.at, arg1: found, arg2: expected } : { code: 'SYNTAX', span: syntax.at, arg1: found };
}

function brackets(tokens: LuaTokens): LuaDiagnostic | null {
	const open: number[] = [];
	for (let index = 0; index < tokens.size; index++) {
		if (tokens.kind(index) !== LuaTokenKind.OPERATOR || tokens.end(index) - tokens.start(index) !== 1) continue;
		const c = tokens.source[tokens.start(index)]!;
		if (OPENERS.includes(c)) {
			open.push(index);
			continue;
		}
		if (!CLOSERS.includes(c)) continue;
		if (!open.length) return { code: 'STRAY_CLOSER', span: tokens.span(index), arg1: c };
		const top = open[open.length - 1]!;
		const wanted = CLOSERS[OPENERS.indexOf(tokens.source[tokens.start(top)]!)]!;
		if (wanted !== c) return { code: 'WRONG_CLOSER', span: tokens.span(index), arg1: c, arg2: wanted };
		open.pop();
	}
	if (!open.length) return null;
	const unclosed = open[open.length - 1]!;
	return { code: 'UNCLOSED_BRACKET', span: tokens.span(unclosed), arg1: tokens.text(unclosed) };
}

function unclosedBlock(document: LuaDocument): LuaDiagnostic | null {
	const tokens = document.tokens;
	const blocks = document.blocks;
	if (!blocks.unclosed.length) return null;
	let opener = blocks.unclosed[blocks.unclosed.length - 1]!;
	for (let index = 0; index < tokens.size; index++) {
		const closer = blocks.closerOf(index);
		if (closer < 0) continue;
		if (indentOf(document.source, tokens.start(closer)) < indentOf(document.source, tokens.start(index))) opener = index;
	}
	const head = headOf(tokens, opener);
	const closer = tokens.matches(opener, 'repeat') ? 'until' : 'end';
	return { code: 'UNCLOSED_BLOCK', span: tokens.span(head), arg1: tokens.text(head), arg2: closer };
}

function headOf(tokens: LuaTokens, opener: number): number {
	let heads: Set<string>;
	if (tokens.matches(opener, 'then')) heads = new Set(['if', 'elseif']);
	else if (tokens.matches(opener, 'do')) heads = new Set(['while', 'for']);
	else return opener;
	let at = tokens.prevCode(opener);
	let steps = 0;
	while (at >= 0 && steps++ < MAX_HEAD_TOKENS) {
		if (tokens.kind(at) === LuaTokenKind.KEYWORD) {
			const word = tokens.text(at);
			if (heads.has(word)) return at;
			if (!EXPRESSION_KEYWORDS.has(word)) break;
		}
		at = tokens.prevCode(at);
	}
	return opener;
}

function indentOf(source: string, offset: number): number {
	let start = offset;
	while (start > 0 && source[start - 1] !== '\n' && source[start - 1] !== '\r') start--;
	let end = start;
	while (end < source.length && (source[end] === ' ' || source[end] === '\t')) end++;
	return end - start;
}
