/**
 * Port of core/plugins LuaTokens.kt + LuaLexer.kt. Splits Lua 5.2 source into
 * tokens that tile the source exactly, never throws, and draws the same token
 * boundaries as luaj's grammar (long brackets at any level, a `#` line at the
 * very start, strings that stop at a line break unterminated).
 */

export interface LuaSpan {
	start: number;
	end: number;
}

export const span = (start: number, end: number): LuaSpan => ({ start, end });
export const spanLength = (s: LuaSpan) => s.end - s.start;
export const spanContains = (s: LuaSpan, offset: number) => offset >= s.start && offset < s.end;

export enum LuaTokenKind {
	WHITESPACE,
	COMMENT,
	LONG_COMMENT,
	STRING,
	LONG_STRING,
	NUMBER,
	NAME,
	KEYWORD,
	OPERATOR,
	/** A `#` line at the very start of a file, which Lua skips. */
	SHEBANG,
	/** A character that starts no Lua token: `$`, `@`, a lone `~`, a non-ASCII letter. */
	UNKNOWN,
}

export function isCode(kind: LuaTokenKind): boolean {
	return kind !== LuaTokenKind.WHITESPACE && kind !== LuaTokenKind.COMMENT && kind !== LuaTokenKind.LONG_COMMENT && kind !== LuaTokenKind.SHEBANG;
}

const UNTERMINATED = 1;
const MAX_LEVEL = 127;

export class LuaTokens {
	constructor(
		readonly source: string,
		private readonly kinds: Uint8Array,
		private readonly starts: Int32Array,
		private readonly ends: Int32Array,
		private readonly flags: Uint8Array,
		readonly size: number
	) {}

	kind(index: number): LuaTokenKind {
		return this.kinds[index] as LuaTokenKind;
	}
	start(index: number): number {
		return this.starts[index]!;
	}
	end(index: number): number {
		return this.ends[index]!;
	}
	span(index: number): LuaSpan {
		return { start: this.starts[index]!, end: this.ends[index]! };
	}
	/** True when a string or long bracket reached a line end or the end of the file without closing. */
	unterminated(index: number): boolean {
		return (this.flags[index]! & UNTERMINATED) !== 0;
	}
	/** How many `=` a long bracket opens with: 0 for `[[`, 2 for `[==[`. */
	level(index: number): number {
		return (this.flags[index]! & 0xff) >>> 1;
	}
	text(index: number): string {
		return this.source.substring(this.starts[index]!, this.ends[index]!);
	}
	matches(index: number, word: string): boolean {
		const start = this.starts[index]!;
		if (this.ends[index]! - start !== word.length) return false;
		return this.source.startsWith(word, start);
	}
	isCode(index: number): boolean {
		return isCode(this.kind(index));
	}

	/** The token containing `offset`, or the last token when `offset` is the end of the source. -1 outside. */
	indexAt(offset: number): number {
		if (this.size === 0 || offset < 0 || offset > this.source.length) return -1;
		if (offset === this.source.length) return this.size - 1;
		let low = 0;
		let high = this.size - 1;
		while (low <= high) {
			const mid = (low + high) >>> 1;
			if (this.ends[mid]! <= offset) low = mid + 1;
			else if (this.starts[mid]! > offset) high = mid - 1;
			else return mid;
		}
		return -1;
	}

	nextCode(index: number): number {
		for (let i = index + 1; i < this.size; i++) if (isCode(this.kind(i))) return i;
		return -1;
	}

	prevCode(index: number): number {
		for (let i = index - 1; i >= 0; i--) if (isCode(this.kind(i))) return i;
		return -1;
	}
}

export const KEYWORDS: ReadonlySet<string> = new Set([
	'and', 'break', 'do', 'else', 'elseif', 'end', 'false', 'for', 'function', 'goto', 'if',
	'in', 'local', 'nil', 'not', 'or', 'repeat', 'return', 'then', 'true', 'until', 'while',
]);

class Builder {
	private capacity: number;
	private kinds: Uint8Array;
	private starts: Int32Array;
	private ends: Int32Array;
	private flags: Uint8Array;
	private size = 0;

	constructor(private source: string) {
		this.capacity = Math.floor(source.length / 3) + 16;
		this.kinds = new Uint8Array(this.capacity);
		this.starts = new Int32Array(this.capacity);
		this.ends = new Int32Array(this.capacity);
		this.flags = new Uint8Array(this.capacity);
	}

	add(kind: LuaTokenKind, start: number, end: number, level = 0, unterminated = false) {
		if (end <= start) return;
		if (this.size === this.capacity) this.grow();
		this.kinds[this.size] = kind;
		this.starts[this.size] = start;
		this.ends[this.size] = end;
		const levelBits = Math.min(level, MAX_LEVEL) << 1;
		this.flags[this.size] = levelBits | (unterminated ? UNTERMINATED : 0);
		this.size++;
	}

	private grow() {
		this.capacity *= 2;
		const k = new Uint8Array(this.capacity); k.set(this.kinds); this.kinds = k;
		const s = new Int32Array(this.capacity); s.set(this.starts); this.starts = s;
		const e = new Int32Array(this.capacity); e.set(this.ends); this.ends = e;
		const f = new Uint8Array(this.capacity); f.set(this.flags); this.flags = f;
	}

	build(): LuaTokens {
		return new LuaTokens(this.source, this.kinds, this.starts, this.ends, this.flags, this.size);
	}
}

const isSpace = (c: string) => c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === '' || c === '';
const isDigit = (c: string) => c >= '0' && c <= '9';
const isNameStart = (c: string) => (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c === '_';
const isNameChar = (c: string) => isNameStart(c) || isDigit(c);

function lineEnd(source: string, from: number): number {
	let i = from;
	while (i < source.length && source[i] !== '\n' && source[i] !== '\r') i++;
	return i;
}

function spaceEnd(source: string, from: number): number {
	let i = from;
	while (i < source.length && isSpace(source[i]!)) i++;
	return i;
}

/** The level of the long bracket opening at `at`, or -1 when the `[` there opens no long bracket. */
function openerLevel(source: string, at: number): number {
	let i = at + 1;
	while (i < source.length && source[i] === '=') i++;
	return i < source.length && source[i] === '[' ? i - at - 1 : -1;
}

function closesAt(source: string, at: number, level: number): boolean {
	const close = at + level + 1;
	if (close >= source.length) return false;
	for (let i = at + 1; i < close; i++) if (source[i] !== '=') return false;
	return source[close] === ']';
}

function longBracket(source: string, tokenStart: number, openAt: number, kind: LuaTokenKind, out: Builder): number {
	const level = openerLevel(source, openAt);
	let i = source.indexOf(']', openAt + level + 2);
	while (i >= 0) {
		if (closesAt(source, i, level)) {
			const end = i + level + 2;
			out.add(kind, tokenStart, end, level);
			return end;
		}
		i = source.indexOf(']', i + 1);
	}
	out.add(kind, tokenStart, source.length, level, true);
	return source.length;
}

function comment(source: string, start: number, out: Builder): number {
	const bracket = start + 2;
	if (bracket < source.length && source[bracket] === '[' && openerLevel(source, bracket) >= 0) {
		return longBracket(source, start, bracket, LuaTokenKind.LONG_COMMENT, out);
	}
	const end = lineEnd(source, start);
	out.add(LuaTokenKind.COMMENT, start, end);
	return end;
}

function escapeEnd(source: string, at: number): number {
	const length = source.length;
	const next = at + 1;
	if (next >= length) return length;
	const after = next + 1;
	switch (source[next]) {
		case 'z': return spaceEnd(source, after);
		case '\r': return after < length && source[after] === '\n' ? after + 1 : after;
		case '\n': return after < length && source[after] === '\r' ? after + 1 : after;
		default: return after;
	}
}

function shortString(source: string, start: number, out: Builder): number {
	const quote = source[start];
	const length = source.length;
	let i = start + 1;
	while (i < length) {
		const c = source[i];
		if (c === quote) {
			out.add(LuaTokenKind.STRING, start, i + 1);
			return i + 1;
		}
		if (c === '\n' || c === '\r') {
			out.add(LuaTokenKind.STRING, start, i, 0, true);
			return i;
		}
		if (c === '\\') i = escapeEnd(source, i);
		else i++;
	}
	out.add(LuaTokenKind.STRING, start, length, 0, true);
	return length;
}

function numberEnd(source: string, start: number): number {
	const length = source.length;
	const hex = start + 1 < length && source[start] === '0' && (source[start + 1] === 'x' || source[start + 1] === 'X');
	const lower = hex ? 'p' : 'e';
	const upper = hex ? 'P' : 'E';
	let i = hex ? start + 2 : start;
	while (i < length) {
		const c = source[i]!;
		if ((c === lower || c === upper) && i + 1 < length && (source[i + 1] === '+' || source[i + 1] === '-')) i += 2;
		else if (isNameChar(c) || c === '.') i++;
		else break;
	}
	return i;
}

function operatorLength(source: string, at: number): number {
	const next = at + 1 < source.length ? source[at + 1] : ' ';
	switch (source[at]) {
		case '.':
			if (next !== '.') return 1;
			return at + 2 < source.length && source[at + 2] === '.' ? 3 : 2;
		case ':': return next === ':' ? 2 : 1;
		case '=': case '<': case '>': return next === '=' ? 2 : 1;
		case '~': return next === '=' ? 2 : 0;
		case '+': case '-': case '*': case '/': case '%': case '^': case '#': case '(': case ')': case '{': case '}': case '[': case ']': case ';': case ',':
			return 1;
		default: return 0;
	}
}

export function lex(source: string): LuaTokens {
	const out = new Builder(source);
	const length = source.length;
	let i = 0;
	if (length > 0 && source[0] === '#') {
		i = lineEnd(source, 0);
		out.add(LuaTokenKind.SHEBANG, 0, i);
	}
	while (i < length) {
		const start = i;
		const c = source[i]!;
		if (isSpace(c)) {
			i = spaceEnd(source, i);
			out.add(LuaTokenKind.WHITESPACE, start, i);
		} else if (c === '-' && i + 1 < length && source[i + 1] === '-') {
			i = comment(source, i, out);
		} else if (c === '"' || c === "'") {
			i = shortString(source, i, out);
		} else if (c === '[' && openerLevel(source, i) >= 0) {
			i = longBracket(source, i, i, LuaTokenKind.LONG_STRING, out);
		} else if (isDigit(c) || (c === '.' && i + 1 < length && isDigit(source[i + 1]!))) {
			i = numberEnd(source, i);
			out.add(LuaTokenKind.NUMBER, start, i);
		} else if (isNameStart(c)) {
			while (i < length && isNameChar(source[i]!)) i++;
			out.add(KEYWORDS.has(source.substring(start, i)) ? LuaTokenKind.KEYWORD : LuaTokenKind.NAME, start, i);
		} else {
			const op = operatorLength(source, i);
			if (op > 0) {
				i += op;
				out.add(LuaTokenKind.OPERATOR, start, i);
			} else {
				const cp = source.codePointAt(i)!;
				i += cp > 0xffff ? 2 : 1;
				out.add(LuaTokenKind.UNKNOWN, start, i);
			}
		}
	}
	return out.build();
}
