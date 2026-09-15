/** Port of LuaApiLookup.kt: the API name the editor explains under the code. */
import { find, type LuaApiEntry } from './api';
import { LuaTokenKind, type LuaTokens } from './tokens';

const MAX_CALL_TOKENS = 2000;

export function apiAt(tokens: LuaTokens, caret: number): LuaApiEntry | null {
	const name = nameAt(tokens, caret);
	if (name >= 0) {
		const e = entryEndingAt(tokens, name);
		if (e) return e;
	}
	return enclosingCall(tokens, caret);
}

function enclosingCall(tokens: LuaTokens, caret: number): LuaApiEntry | null {
	if (caret <= 0) return null;
	let at = tokens.indexAt(caret - 1);
	if (at >= 0 && !tokens.isCode(at)) at = tokens.prevCode(at);
	let depth = 0;
	let steps = 0;
	while (at >= 0 && steps++ < MAX_CALL_TOKENS) {
		if (tokens.kind(at) === LuaTokenKind.OPERATOR) {
			if (tokens.matches(at, ')') || tokens.matches(at, '}') || tokens.matches(at, ']')) depth++;
			else if (tokens.matches(at, '(') || tokens.matches(at, '{') || tokens.matches(at, '[')) {
				if (depth > 0) depth--;
				else {
					if (tokens.matches(at, '[')) return null;
					const callee = tokens.prevCode(at);
					if (callee < 0 || tokens.kind(callee) !== LuaTokenKind.NAME) return null;
					const e = entryEndingAt(tokens, callee);
					return e && e.kind === 'FUNCTION' ? e : null;
				}
			}
		}
		at = tokens.prevCode(at);
	}
	return null;
}

export function entryEndingAt(tokens: LuaTokens, last: number): LuaApiEntry | null {
	const before = tokens.prevCode(last);
	if (before >= 0 && tokens.matches(before, ':')) return find('string.' + tokens.text(last)) ?? null;
	const parts: string[] = [];
	let at = last;
	while (at >= 0 && tokens.kind(at) === LuaTokenKind.NAME) {
		parts.push(tokens.text(at));
		const dot = tokens.prevCode(at);
		if (dot < 0 || !tokens.matches(dot, '.')) break;
		at = tokens.prevCode(dot);
	}
	return find(parts.reverse().join('.')) ?? null;
}

/** The name token under the caret, or the one just before it when the caret sits at a name's end. */
export function nameAt(tokens: LuaTokens, caret: number): number {
	if (caret < tokens.source.length) {
		const here = tokens.indexAt(caret);
		if (here >= 0 && tokens.kind(here) === LuaTokenKind.NAME) return here;
	}
	const before = caret > 0 ? tokens.indexAt(caret - 1) : -1;
	return before >= 0 && tokens.kind(before) === LuaTokenKind.NAME ? before : -1;
}
