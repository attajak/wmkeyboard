/** Port of LuaFormat.kt: re-indents from tokens; only leading whitespace changes. */
import { lex, LuaTokenKind, type LuaTokens } from './tokens';

const BINARY = new Set(['..', '+', '-', '*', '/', '%', '^', '==', '~=', '<', '<=', '>', '>=']);
const LEADING = new Set([...BINARY, ':', '.']);
const TRAILING = new Set([...BINARY, '=']);
const ITEM_STARTS = new Set([',', '{', '(', '[', ';']);

class OpenItems {
	anchors: number[] = [];
	lines: number[] = [];
	get size() {
		return this.anchors.length;
	}
	get topAnchor() {
		return this.anchors[this.anchors.length - 1]!;
	}
	get topLine() {
		return this.lines[this.lines.length - 1]!;
	}
	push(anchor: number, line: number) {
		this.anchors.push(anchor);
		this.lines.push(line);
	}
	pop() {
		if (this.anchors.length) {
			this.anchors.pop();
			this.lines.pop();
		}
	}
}

export function reindent(source: string, indent = '  '): string {
	const tokens = lex(source);
	let out = '';
	const open = new OpenItems();
	let chainLine = 0;
	let chainLevel = 0;
	let line = 0;
	let lineStart = 0;
	let first = 0;
	for (;;) {
		const newline = source.indexOf('\n', lineStart);
		const lineEnd = newline < 0 ? source.length : newline;
		while (first < tokens.size && tokens.start(first) < lineStart) first++;
		let past = first;
		while (past < tokens.size && tokens.start(past) < lineEnd) past++;

		const code = firstCode(tokens, first, past);
		const closing = code >= 0 && startsByClosing(tokens, code);
		const continuing = code >= 0 && !closing && continues(tokens, code);
		let level = open.size === 0 ? 0 : closing ? open.topAnchor : open.topAnchor + 1;
		if (continuing && !(open.size > 0 && open.topLine >= chainLine)) level++;
		if (code >= 0 && !continuing) {
			chainLine = line;
			chainLevel = level;
		}

		if (startsInsideLongForm(tokens, lineStart)) {
			out += source.substring(lineStart, lineEnd);
		} else {
			const lead = leadingEnd(source, lineStart, lineEnd);
			const content = lineEnd > lead && source[lineEnd - 1] === '\r' ? lineEnd - 1 : lineEnd;
			if (lead < content) {
				out += indent.repeat(level) + source.substring(lead, lineEnd);
			} else {
				out += source.substring(content, lineEnd);
			}
		}
		for (let index = first; index < past; index++) track(tokens, index, line, level, chainLevel, open);
		if (newline < 0) break;
		out += '\n';
		lineStart = newline + 1;
		line++;
	}
	return out;
}

function track(tokens: LuaTokens, index: number, line: number, level: number, chainLevel: number, open: OpenItems) {
	if (!tokens.isCode(index)) return;
	if (isBracketOpener(tokens, index)) open.push(level, line);
	else if (isBlockOpener(tokens, index)) open.push(chainLevel, line);
	else if (isCloser(tokens, index)) open.pop();
	else if (tokens.matches(index, 'else')) {
		open.pop();
		open.push(level, line);
	} else if (tokens.matches(index, 'elseif')) open.pop();
}

function startsInsideLongForm(tokens: LuaTokens, offset: number): boolean {
	const index = tokens.indexAt(offset);
	if (index < 0) return false;
	const kind = tokens.kind(index);
	const spanning = kind === LuaTokenKind.LONG_STRING || kind === LuaTokenKind.LONG_COMMENT || kind === LuaTokenKind.STRING;
	return spanning && tokens.start(index) < offset && offset < tokens.end(index);
}

function leadingEnd(source: string, from: number, to: number): number {
	let i = from;
	while (i < to && (source[i] === ' ' || source[i] === '\t')) i++;
	return i;
}

function firstCode(tokens: LuaTokens, from: number, to: number): number {
	for (let i = from; i < to; i++) if (tokens.isCode(i)) return i;
	return -1;
}

function startsByClosing(tokens: LuaTokens, index: number): boolean {
	return isCloser(tokens, index) || tokens.matches(index, 'else') || tokens.matches(index, 'elseif');
}

function continues(tokens: LuaTokens, index: number): boolean {
	const previous = tokens.prevCode(index);
	if (previous < 0) return false;
	const afterItemStart = tokens.kind(previous) === LuaTokenKind.OPERATOR && ITEM_STARTS.has(tokens.text(previous));
	if (leadsOn(tokens, index) && !afterItemStart) return true;
	return trailsOff(tokens, previous);
}

function leadsOn(tokens: LuaTokens, index: number): boolean {
	const k = tokens.kind(index);
	if (k === LuaTokenKind.KEYWORD) return tokens.matches(index, 'and') || tokens.matches(index, 'or');
	if (k === LuaTokenKind.OPERATOR) return LEADING.has(tokens.text(index));
	return false;
}

function trailsOff(tokens: LuaTokens, index: number): boolean {
	const k = tokens.kind(index);
	if (k === LuaTokenKind.KEYWORD) return tokens.matches(index, 'and') || tokens.matches(index, 'or');
	if (k === LuaTokenKind.OPERATOR) return TRAILING.has(tokens.text(index));
	return false;
}

const isBracketOpener = (t: LuaTokens, i: number) => t.kind(i) === LuaTokenKind.OPERATOR && (t.matches(i, '(') || t.matches(i, '{') || t.matches(i, '['));
const isBlockOpener = (t: LuaTokens, i: number) => t.kind(i) === LuaTokenKind.KEYWORD && (t.matches(i, 'function') || t.matches(i, 'do') || t.matches(i, 'then') || t.matches(i, 'repeat'));
function isCloser(t: LuaTokens, i: number): boolean {
	const k = t.kind(i);
	if (k === LuaTokenKind.KEYWORD) return t.matches(i, 'end') || t.matches(i, 'until');
	if (k === LuaTokenKind.OPERATOR) return t.matches(i, ')') || t.matches(i, '}') || t.matches(i, ']');
	return false;
}
