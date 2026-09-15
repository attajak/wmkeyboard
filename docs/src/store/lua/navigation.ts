/** Port of LuaNavigation.kt: definition, references, rename, outline, fold regions. */
import * as Api from './api';
import type { LuaAnalysis, LuaFunctionKind } from './analyse';
import type { LuaDocument } from './document';
import { nameAt } from './lookup';
import { LuaBlocks } from './structure';
import { LuaTokenKind, type LuaSpan, type LuaTokens } from './tokens';

export interface LuaOutlineItem {
	name: string;
	kind: LuaFunctionKind;
	nameSpan: LuaSpan;
	span: LuaSpan;
	depth: number;
}

export type LuaRenameProblem = 'NOT_A_NAME' | 'KEYWORD' | 'TAKEN';

const KEYWORDS = new Set(['and', 'break', 'do', 'else', 'elseif', 'end', 'false', 'for', 'function', 'goto', 'if', 'in', 'local', 'nil', 'not', 'or', 'repeat', 'return', 'then', 'true', 'until', 'while']);
const NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;
const CONTRACT = new Set(['render', 'on_event']);
const MAX_HEAD_TOKENS = 256;
const EXPRESSION_KEYWORDS = new Set(['and', 'or', 'not', 'nil', 'true', 'false', 'in']);

export function definitionAt(document: LuaDocument, caret: number): LuaSpan | null {
	const analysis = document.analysis;
	if (!analysis) return null;
	const token = nameAt(document.tokens, caret);
	if (token < 0) return null;
	const symbol = analysis.symbolAt(token);
	if (symbol) return symbol.declaration;
	const use = analysis.globalAt(token);
	if (!use) return null;
	const writes = analysis.globals.filter((g) => g.name === use.name && g.write);
	if (!writes.length) return null;
	return writes.reduce((a, b) => (b.span.start < a.span.start ? b : a)).span;
}

export function referencesAt(document: LuaDocument, caret: number): LuaSpan[] {
	const analysis = document.analysis;
	if (!analysis) return [];
	const token = nameAt(document.tokens, caret);
	if (token < 0) return [];
	const symbol = analysis.symbolAt(token);
	if (symbol) return [...(symbol.declaration ? [symbol.declaration] : []), ...symbol.reads, ...symbol.writes].sort((a, b) => a.start - b.start);
	const use = analysis.globalAt(token);
	if (!use) return [];
	return analysis.globals.filter((g) => g.name === use.name).map((g) => g.span);
}

export function renameSpans(document: LuaDocument, caret: number): LuaSpan[] | null {
	const analysis = document.analysis;
	if (!analysis) return null;
	const token = nameAt(document.tokens, caret);
	if (token < 0) return null;
	const symbol = analysis.symbolAt(token);
	if (symbol) return symbol.kind === 'SELF' ? null : referencesAt(document, caret);
	const use = analysis.globalAt(token);
	if (!use) return null;
	if (CONTRACT.has(use.name) || Api.find(use.name) || use.name in Api.nilled) return null;
	return referencesAt(document, caret);
}

export function renameNameAt(document: LuaDocument, caret: number): string | null {
	const analysis = document.analysis;
	if (!analysis) return null;
	const token = nameAt(document.tokens, caret);
	if (token < 0) return null;
	return analysis.symbolAt(token)?.name ?? analysis.globalAt(token)?.name ?? null;
}

export function renameProblem(document: LuaDocument, caret: number, newName: string): LuaRenameProblem | null {
	if (!NAME.test(newName)) return 'NOT_A_NAME';
	if (KEYWORDS.has(newName)) return 'KEYWORD';
	const analysis = document.analysis;
	if (!analysis) return null;
	const token = nameAt(document.tokens, caret);
	if (token < 0) return null;
	const symbol = analysis.symbolAt(token);
	const spans = renameSpans(document, caret);
	if (!spans) return null;
	const oldName = symbol?.name ?? analysis.globalAt(token)?.name;
	if (!oldName || newName === oldName) return null;
	for (const s of spans) if (analysis.visibleAt(s.start).some((v) => v.name === newName && v !== symbol)) return 'TAKEN';
	if (symbol) {
		if (analysis.globals.some((g) => g.name === newName && g.span.start >= symbol.scope.start && g.span.start <= symbol.scope.end)) return 'TAKEN';
	} else if (analysis.globalNames.has(newName) || Api.find(newName) || newName in Api.nilled) return 'TAKEN';
	return null;
}

export function outline(analysis: LuaAnalysis): LuaOutlineItem[] {
	const named = analysis.functions.filter((f) => f.kind !== 'ANONYMOUS' && f.name !== null);
	return [...named].sort((a, b) => a.span.start - b.span.start).map((f) => {
		let depth = 0;
		let parent = analysis.function(f.parent);
		while (parent) {
			if (parent.kind !== 'ANONYMOUS') depth++;
			parent = analysis.function(parent.parent);
		}
		return { name: f.name ?? '', kind: f.kind, nameSpan: f.nameSpan, span: f.span, depth };
	});
}

export function foldRegions(tokens: LuaTokens, blocks: LuaBlocks = LuaBlocks.of(tokens)): LuaSpan[] {
	const source = tokens.source;
	const regions: LuaSpan[] = [];
	const braces: number[] = [];
	const spansLines = (start: number, end: number) => {
		const nl = source.indexOf('\n', start);
		return nl >= start && nl < end;
	};
	for (let index = 0; index < tokens.size; index++) {
		const kind = tokens.kind(index);
		if (kind === LuaTokenKind.KEYWORD) {
			const closer = blocks.closerOf(index);
			if (closer > index) {
				const end = tokens.matches(closer, 'end') || tokens.matches(closer, 'until') ? tokens.end(closer) : tokens.start(closer);
				const start = regionStart(tokens, index);
				if (spansLines(start, end)) regions.push({ start, end });
			}
		} else if (kind === LuaTokenKind.OPERATOR && tokens.matches(index, '{')) braces.push(index);
		else if (kind === LuaTokenKind.OPERATOR && tokens.matches(index, '}') && braces.length) {
			const open = braces.pop()!;
			if (spansLines(tokens.start(open), tokens.end(index))) regions.push({ start: tokens.start(open), end: tokens.end(index) });
		} else if ((kind === LuaTokenKind.LONG_COMMENT || kind === LuaTokenKind.LONG_STRING) && spansLines(tokens.start(index), tokens.end(index))) regions.push(tokens.span(index));
	}
	return regions.sort((a, b) => a.start - b.start || b.end - a.end);
}

function regionStart(tokens: LuaTokens, opener: number): number {
	let heads: Set<string>;
	if (tokens.matches(opener, 'then')) heads = new Set(['if', 'elseif']);
	else if (tokens.matches(opener, 'do')) heads = new Set(['while', 'for']);
	else return tokens.start(opener);
	let at = tokens.prevCode(opener);
	let steps = 0;
	while (at >= 0 && steps++ < MAX_HEAD_TOKENS) {
		if (tokens.kind(at) === LuaTokenKind.KEYWORD) {
			const word = tokens.text(at);
			if (heads.has(word)) return tokens.start(at);
			if (!EXPRESSION_KEYWORDS.has(word)) break;
		}
		at = tokens.prevCode(at);
	}
	return tokens.start(opener);
}
