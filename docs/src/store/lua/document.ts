/** Port of LuaDocument.kt: lex → parse → blocks → analyse, with a two-entry cache by source. */
import { analyse, type LuaAnalysis } from './analyse';
import { parse, type LuaSyntax } from './parse';
import { LuaBlocks } from './structure';
import { lex, type LuaTokens } from './tokens';

export interface LuaDocument {
	source: string;
	tokens: LuaTokens;
	blocks: LuaBlocks;
	syntax: LuaSyntax;
	/** Only for a script that parses. */
	analysis: LuaAnalysis | null;
}

export function documentOf(source: string): LuaDocument {
	const tokens = lex(source);
	const parsed = parse(source, tokens);
	const blocks = LuaBlocks.of(tokens);
	const analysis = parsed.chunk ? analyse(source, tokens, blocks, parsed.chunk) : null;
	return { source, tokens, blocks, syntax: parsed.syntax, analysis };
}

const cache: LuaDocument[] = [];

/** The last two documents by source, for the editor's many readers of one text. */
export function cachedDocument(source: string): LuaDocument {
	for (const d of cache) if (d.source === source) return d;
	const d = documentOf(source);
	cache.unshift(d);
	if (cache.length > 2) cache.pop();
	return d;
}
