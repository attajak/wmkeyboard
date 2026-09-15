/** Port of LuaStructure.kt: line/column mapping the luaj way, and block pairing from tokens. */
import { LuaTokenKind, type LuaTokens } from './tokens';

/**
 * Line and column to character offset, counted the way luaj's JavaCC parser
 * counts them: lines break at `\n`, `\r\n` and a `\r` alone; a column is one
 * UTF-16 character, a tab included.
 */
export class LuaLines {
	private readonly starts: number[];

	constructor(private readonly source: string) {
		const found = [0];
		for (let i = 0; i < source.length; i++) {
			const c = source[i];
			if (c === '\n' || (c === '\r' && source[i + 1] !== '\n')) found.push(i + 1);
		}
		this.starts = found;
	}

	/** The offset of a 1-based line and column, kept inside that line. */
	offset(line: number, column: number): number {
		if (line < 1) return 0;
		if (line > this.starts.length) return this.source.length;
		const start = this.starts[line - 1]!;
		const end = line < this.starts.length ? this.starts[line]! - 1 : this.source.length;
		const at = start + column - 1;
		return Math.min(Math.max(at, start), Math.max(start, end));
	}

	/** 0-based line index of an offset. */
	lineOf(offset: number): number {
		let low = 0;
		let high = this.starts.length - 1;
		while (low < high) {
			const mid = (low + high + 1) >>> 1;
			if (this.starts[mid]! <= offset) low = mid;
			else high = mid - 1;
		}
		return low;
	}

	get lineCount(): number {
		return this.starts.length;
	}

	lineStart(line: number): number {
		return this.starts[Math.min(Math.max(line, 0), this.starts.length - 1)]!;
	}
}

/**
 * The keywords that open and close blocks, paired the way Lua's grammar pairs
 * them: `function`, `do`, `then` and `repeat` open; `end` and `until` close;
 * `elseif` closes the `then` before it; `else` closes one block and opens the next.
 */
export class LuaBlocks {
	private constructor(
		private readonly closers: Int32Array,
		private readonly openers: Int32Array,
		private readonly enclosingOf: Int32Array,
		/** Openers nothing closed, outermost first. */
		readonly unclosed: number[],
		/** Closers with nothing open to close. */
		readonly strays: number[]
	) {}

	closerOf(opener: number): number {
		return opener >= 0 && opener < this.closers.length ? this.closers[opener]! : -1;
	}
	openerOf(closer: number): number {
		return closer >= 0 && closer < this.openers.length ? this.openers[closer]! : -1;
	}
	enclosing(token: number): number {
		return token >= 0 && token < this.enclosingOf.length ? this.enclosingOf[token]! : -1;
	}

	static of(tokens: LuaTokens): LuaBlocks {
		const size = tokens.size;
		const closers = new Int32Array(size).fill(-1);
		const openers = new Int32Array(size).fill(-1);
		const enclosing = new Int32Array(size).fill(-1);
		const stack: number[] = [];
		const strays: number[] = [];
		for (let index = 0; index < size; index++) {
			enclosing[index] = stack.length > 0 ? stack[stack.length - 1]! : -1;
			if (tokens.kind(index) !== LuaTokenKind.KEYWORD) continue;
			const isElse = tokens.matches(index, 'else');
			const closes = isElse || tokens.matches(index, 'end') || tokens.matches(index, 'until') || tokens.matches(index, 'elseif');
			const opens = isElse || tokens.matches(index, 'function') || tokens.matches(index, 'do') || tokens.matches(index, 'then') || tokens.matches(index, 'repeat');
			if (closes) {
				if (stack.length > 0) {
					const opener = stack.pop()!;
					closers[opener] = index;
					openers[index] = opener;
				} else strays.push(index);
			}
			if (opens) stack.push(index);
		}
		return new LuaBlocks(closers, openers, enclosing, stack, strays);
	}
}
