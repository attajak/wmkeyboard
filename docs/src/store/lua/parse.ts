/**
 * Port of LuaParse.kt over luaparse (Lua 5.2 mode) instead of luaj's JavaCC
 * parser. The verdict shape is the same: Valid, Invalid(at, found, expected)
 * or Skipped. luaparse reports one expected token per failure; JavaCC lists
 * several, so `expected` here has at most one entry.
 */
import luaparse from 'luaparse';
import { LuaTokenKind, type LuaSpan, type LuaTokens } from './tokens';

export const MAX_SCRIPT_BYTES = 256 * 1024;
export const MAX_NESTING = 200;
const MAX_FOUND = 40;

export type LuaSyntax =
	| { kind: 'valid' }
	| { kind: 'invalid'; at: LuaSpan; found: string; expected: string[] }
	| { kind: 'skipped'; reason: 'TOO_LARGE' | 'TOO_DEEP' };

export interface LuaParsed {
	syntax: LuaSyntax;
	chunk: LuaChunk | null;
}

/* ---------- the luaparse tree, as much of it as the analysis reads ---------- */

export interface Node {
	type: string;
	range: [number, number];
}
export interface Identifier extends Node {
	type: 'Identifier';
	name: string;
}
export interface LuaChunk extends Node {
	type: 'Chunk';
	body: Node[];
}
export type FieldNode = (Node & { type: 'TableKey'; key: Node; value: Node }) | (Node & { type: 'TableKeyString'; key: Identifier; value: Node }) | (Node & { type: 'TableValue'; value: Node });

export function parse(source: string, tokens: LuaTokens): LuaParsed {
	if (source.length > MAX_SCRIPT_BYTES) return { syntax: { kind: 'skipped', reason: 'TOO_LARGE' }, chunk: null };
	if (deepestNesting(tokens) > MAX_NESTING) return { syntax: { kind: 'skipped', reason: 'TOO_DEEP' }, chunk: null };
	try {
		const chunk = luaparse.parse(source, { luaVersion: '5.2', ranges: true, locations: false, comments: false, scope: false }) as unknown as LuaChunk;
		return { syntax: { kind: 'valid' }, chunk };
	} catch (e) {
		const err = e as { message?: string; index?: number; line?: number; column?: number; name?: string };
		if (err && typeof err.message === 'string' && err.name === 'SyntaxError') return { syntax: invalid(source, tokens, err), chunk: null };
		if (e instanceof RangeError) return { syntax: { kind: 'skipped', reason: 'TOO_DEEP' }, chunk: null };
		// A parser bug is not the author's error. Say nothing rather than point at a place that is not wrong.
		return { syntax: { kind: 'valid' }, chunk: null };
	}
}

function invalid(source: string, tokens: LuaTokens, err: { message?: string; index?: number }): LuaSyntax {
	const message = (err.message ?? '').replace(/^\[\d+:\d+\]\s*/, '');
	let found = '';
	let expected: string[] = [];
	let m: RegExpMatchArray | null;
	if ((m = message.match(/^'(.+)' expected near '(.*)'$/))) {
		expected = [m[1]!];
		found = m[2]!;
	} else if ((m = message.match(/^(<\w+>) expected near '(.*)'$/))) {
		expected = [m[1]!.toUpperCase()];
		found = m[2]!;
	} else if ((m = message.match(/^unexpected \w+ '(.*)' near '(.*)'$/))) {
		found = m[1]!;
	} else if ((m = message.match(/^unexpected symbol near '(.*)'$/))) {
		found = m[1]!;
	} else if (/unfinished|malformed|invalid|expected|escape|code ?point|hexadecimal|brace/i.test(message)) {
		return lexicalFailure(source, tokens);
	} else {
		return lexicalFailure(source, tokens);
	}
	const atEnd = found === '<eof>';
	if (atEnd) return { kind: 'invalid', at: { start: source.length, end: source.length }, found: '<EOF>', expected };
	const index = typeof err.index === 'number' ? Math.min(Math.max(err.index, 0), source.length) : source.length;
	const token = tokens.indexAt(index);
	const at: LuaSpan = token >= 0 && tokens.isCode(token) ? tokens.span(token) : { start: index, end: Math.min(source.length, index + Math.max(1, found.length)) };
	return { kind: 'invalid', at, found: (token >= 0 && tokens.isCode(token) ? tokens.text(token) : found).slice(0, MAX_FOUND), expected };
}

function lexicalFailure(source: string, tokens: LuaTokens): LuaSyntax {
	for (let i = 0; i < tokens.size; i++) {
		if (tokens.kind(i) === LuaTokenKind.UNKNOWN || tokens.unterminated(i)) {
			return { kind: 'invalid', at: tokens.span(i), found: tokens.text(i).slice(0, MAX_FOUND), expected: [] };
		}
	}
	let last = -1;
	for (let i = tokens.size - 1; i >= 0; i--) if (tokens.isCode(i)) { last = i; break; }
	const at = last >= 0 ? tokens.span(last) : { start: source.length, end: source.length };
	return { kind: 'invalid', at, found: '', expected: [] };
}

function deepestNesting(tokens: LuaTokens): number {
	let depth = 0;
	let deepest = 0;
	for (let i = 0; i < tokens.size; i++) {
		const k = tokens.kind(i);
		if (k === LuaTokenKind.OPERATOR) {
			if (tokens.matches(i, '(') || tokens.matches(i, '{') || tokens.matches(i, '[')) depth++;
			else if (tokens.matches(i, ')') || tokens.matches(i, '}') || tokens.matches(i, ']')) depth--;
		} else if (k === LuaTokenKind.KEYWORD) {
			if (tokens.matches(i, 'function') || tokens.matches(i, 'do') || tokens.matches(i, 'then') || tokens.matches(i, 'repeat')) depth++;
			else if (tokens.matches(i, 'end') || tokens.matches(i, 'until')) depth--;
		}
		if (depth < 0) depth = 0;
		if (depth > deepest) deepest = depth;
	}
	return deepest;
}
