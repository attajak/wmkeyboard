/**
 * Port of LuaAnalyse.kt over the luaparse tree. luaparse places every node,
 * identifiers included, so a declaration or use is taken from the node's own
 * range, then checked against the token's text; block ends still come from
 * LuaBlocks so `until` sees its `repeat` body and `self` is a local.
 */
import type { LuaBlocks } from './structure';
import { LuaTokenKind, type LuaSpan, type LuaTokens } from './tokens';
import type { FieldNode, Identifier, LuaChunk, Node } from './parse';

export type LuaSymbolKind = 'LOCAL' | 'LOCAL_FUNCTION' | 'PARAMETER' | 'FOR_VARIABLE' | 'SELF';

export interface LuaSymbol {
	id: number;
	name: string;
	kind: LuaSymbolKind;
	/** Where it is declared, or null for the `self` a method has without writing it. */
	declaration: LuaSpan | null;
	/** From just after its declaration to the end of its block. */
	scope: LuaSpan;
	/** The LuaFunction id it belongs to; 0 is the file itself. */
	function: number;
	reads: LuaSpan[];
	writes: LuaSpan[];
}

export interface LuaGlobalUse {
	name: string;
	span: LuaSpan;
	write: boolean;
	function: number;
	/** Run order: in `x = x + 1` the read on the right runs before the write on the left. */
	order: number;
}

export type LuaFunctionKind = 'GLOBAL' | 'LOCAL' | 'FIELD' | 'METHOD' | 'ANONYMOUS';

export interface LuaFunction {
	id: number;
	parent: number;
	name: string | null;
	kind: LuaFunctionKind;
	nameSpan: LuaSpan;
	span: LuaSpan;
	parameters: string[];
	vararg: boolean;
	returnsValue: boolean;
}

export class LuaAnalysis {
	private globalNamesCache: Set<string> | null = null;
	constructor(
		readonly symbols: LuaSymbol[],
		readonly globals: LuaGlobalUse[],
		readonly functions: LuaFunction[],
		private readonly symbolByToken: Int32Array,
		private readonly globalByToken: Int32Array
	) {}

	symbolAt(token: number): LuaSymbol | null {
		const i = token >= 0 && token < this.symbolByToken.length ? this.symbolByToken[token]! : -1;
		return i >= 0 ? this.symbols[i]! : null;
	}
	globalAt(token: number): LuaGlobalUse | null {
		const i = token >= 0 && token < this.globalByToken.length ? this.globalByToken[token]! : -1;
		return i >= 0 ? this.globals[i]! : null;
	}
	function(id: number): LuaFunction | null {
		return this.functions[id - 1] ?? null;
	}
	visibleAt(offset: number): LuaSymbol[] {
		const byName = new Map<string, LuaSymbol>();
		for (const s of this.symbols) {
			if (!(offset >= s.scope.start && offset <= s.scope.end)) continue;
			const held = byName.get(s.name);
			if (!held || s.scope.start >= held.scope.start) byName.set(s.name, s);
		}
		return [...byName.values()];
	}
	functionAt(offset: number): LuaFunction | null {
		let best: LuaFunction | null = null;
		for (const f of this.functions) if (offset >= f.span.start && offset < f.span.end && (!best || f.span.start > best.span.start)) best = f;
		return best;
	}
	get globalNames(): Set<string> {
		return (this.globalNamesCache ??= new Set(this.globals.map((g) => g.name)));
	}
}

export function analyse(source: string, tokens: LuaTokens, blocks: LuaBlocks, chunk: LuaChunk): LuaAnalysis | null {
	try {
		return new Resolver(source, tokens, blocks).run(chunk);
	} catch (e) {
		if (e instanceof RangeError) return null;
		throw e;
	}
}

const MAX_HEADER_TOKENS = 64;
const MAX_PARAMETER_TOKENS = 512;

type N = Node & Record<string, any>;

class Resolver {
	private symbols: LuaSymbol[] = [];
	private globals: LuaGlobalUse[] = [];
	private functions: LuaFunction[] = [];
	private symbolByToken: Int32Array;
	private scopes: Map<string, number>[] = [];
	private repeatEnds: number[] = [];
	private fn = 0;
	private nextOrder = 0;

	constructor(private source: string, private tokens: LuaTokens, private blocks: LuaBlocks) {
		this.symbolByToken = new Int32Array(tokens.size).fill(-1);
	}

	run(chunk: LuaChunk): LuaAnalysis {
		this.scopes.push(new Map());
		this.block(chunk.body);
		for (const s of this.symbols) {
			s.reads.sort((a, b) => a.start - b.start);
			s.writes.sort((a, b) => a.start - b.start);
		}
		this.globals.sort((a, b) => a.span.start - b.span.start);
		const globalByToken = new Int32Array(this.tokens.size).fill(-1);
		this.globals.forEach((use, index) => {
			const t = this.tokens.indexAt(use.span.start);
			if (t >= 0) globalByToken[t] = index;
		});
		return new LuaAnalysis(this.symbols, this.globals, this.functions, this.symbolByToken, globalByToken);
	}

	// ---- statements --------------------------------------------------------

	private block(stats: Node[] | undefined) {
		for (const s of stats ?? []) this.statement(s as N);
	}

	private scoped(body: () => void) {
		this.scopes.push(new Map());
		body();
		this.scopes.pop();
	}

	private statement(stat: N) {
		switch (stat.type) {
			case 'DoStatement': this.scoped(() => this.block(stat.body as Node[])); break;
			case 'LocalStatement': this.localAssign(stat); break;
			case 'FunctionDeclaration': stat.isLocal ? this.localFunction(stat) : this.functionStatement(stat); break;
			case 'AssignmentStatement': this.assign(stat); break;
			case 'CallStatement': this.expression(stat.expression as N); break;
			case 'ForNumericStatement':
				this.expression(stat.start as N);
				this.expression(stat.end as N);
				this.expression(stat.step as N | null);
				this.loop(stat, [stat.variable as Identifier], stat.body as Node[]);
				break;
			case 'ForGenericStatement':
				this.expressions(stat.iterators as N[]);
				this.loop(stat, stat.variables as Identifier[], stat.body as Node[]);
				break;
			case 'WhileStatement':
				this.expression(stat.condition as N);
				this.scoped(() => this.block(stat.body as Node[]));
				break;
			case 'RepeatStatement':
				this.scoped(() => {
					this.repeatEnds.push(this.endOffset(stat));
					this.block(stat.body as Node[]);
					this.expression(stat.condition as N);
					this.repeatEnds.pop();
				});
				break;
			case 'IfStatement':
				for (const clause of stat.clauses as N[]) {
					if (clause.condition) this.expression(clause.condition as N);
					this.scoped(() => this.block(clause.body as Node[]));
				}
				break;
			case 'ReturnStatement': {
				const values = (stat.arguments as N[]) ?? [];
				if (values.length) {
					const f = this.functions[this.fn - 1];
					if (f) f.returnsValue = true;
				}
				this.expressions(values);
				break;
			}
			default: break; // break, goto and labels name nothing
		}
	}

	private localAssign(stat: N) {
		const names = stat.variables as Identifier[];
		const keyword = this.startToken(stat, 'local');
		const values = (stat.init as N[]) ?? [];
		values.forEach((value, index) => {
			const name = names[index];
			const nameToken = name ? this.tokenOf(name) : -1;
			if (value.type === 'FunctionDeclaration' && name && nameToken >= 0) {
				this.functionBody(value, name.name, 'LOCAL', this.tokens.span(nameToken), false);
			} else this.expression(value);
		});
		const scope: LuaSpan = { start: this.endOffset(stat), end: this.blockEnd(keyword) };
		for (const name of names) this.declare(name.name, 'LOCAL', this.tokenOf(name), scope);
	}

	private localFunction(stat: N) {
		const ident = stat.identifier as Identifier;
		const name = ident.name;
		const keyword = this.startToken(stat, 'local');
		const nameToken = this.tokenOf(ident);
		const scopeStart = nameToken >= 0 ? this.tokens.end(nameToken) : this.endOffset(stat);
		this.declare(name, 'LOCAL_FUNCTION', nameToken, { start: scopeStart, end: this.blockEnd(keyword) });
		this.functionBody(stat, name, 'LOCAL', nameToken >= 0 ? this.tokens.span(nameToken) : null, false);
	}

	private functionStatement(stat: N) {
		const ident = stat.identifier as N | null;
		if (!ident) {
			this.functionBody(stat, null, 'ANONYMOUS', null, false);
			return;
		}
		// Unwind `a.b:c` into base + dots + method.
		const parts: Identifier[] = [];
		let method: Identifier | null = null;
		let node: N = ident;
		while (node.type === 'MemberExpression') {
			if (node.indexer === ':') method = node.identifier as Identifier;
			else parts.unshift(node.identifier as Identifier);
			node = node.base as N;
		}
		if (node.type !== 'Identifier') {
			this.functionBody(stat, null, 'ANONYMOUS', null, false);
			return;
		}
		const base = node as Identifier;
		const baseToken = this.tokenOf(base);
		const path = parts.length > 0 || method !== null;
		const kind: LuaFunctionKind = method ? 'METHOD' : path ? 'FIELD' : this.lookup(base.name) >= 0 ? 'LOCAL' : 'GLOBAL';
		this.reference(base.name, baseToken, !path);
		let display = base.name;
		for (const p of parts) display += '.' + p.name;
		if (method) display += ':' + method.name;
		const lastIdent = method ?? parts[parts.length - 1] ?? base;
		const lastToken = this.tokenOf(lastIdent);
		const nameSpan = baseToken >= 0 && lastToken >= 0 ? { start: this.tokens.start(baseToken), end: this.tokens.end(lastToken) } : null;
		this.functionBody(stat, display, kind, nameSpan, method !== null);
	}

	private assign(stat: N) {
		const targets = stat.variables as N[];
		((stat.init as N[]) ?? []).forEach((value, index) => {
			const target = targets[index];
			const name = target ? this.chainName(target) ?? (target.type === 'MemberExpression' ? (target.identifier as Identifier).name : null) : null;
			const last = target ? this.endTokenOf(target) : -1;
			if (value.type === 'FunctionDeclaration' && name && last >= 0 && target) {
				const first = this.chainStart(target) ?? this.tokens.start(last);
				const kind: LuaFunctionKind = target.type === 'MemberExpression' ? 'FIELD' : this.lookup(name) >= 0 ? 'LOCAL' : 'GLOBAL';
				this.functionBody(value, name, kind, { start: first, end: this.tokens.end(last) }, false);
			} else this.expression(value);
		});
		for (const target of targets) {
			if (target.type === 'Identifier') this.reference((target as Identifier).name, this.tokenOf(target as Identifier), true);
			else if (target.type === 'MemberExpression') this.expression(target.base as N);
			else if (target.type === 'IndexExpression') {
				this.expression(target.base as N);
				this.expression(target.index as N);
			}
		}
	}

	private loop(stat: N, names: Identifier[], body: Node[]) {
		const end = this.endToken(stat, 'end');
		const opener = end >= 0 ? this.blocks.openerOf(end) : -1;
		const scope: LuaSpan = { start: opener >= 0 ? this.tokens.end(opener) : this.endOffset(stat), end: end >= 0 ? this.tokens.start(end) : this.endOffset(stat) };
		this.scoped(() => {
			for (const name of names) this.declare(name.name, 'FOR_VARIABLE', this.tokenOf(name), scope);
			this.block(body);
		});
	}

	// ---- expressions -------------------------------------------------------

	private expressions(list: N[] | undefined) {
		for (const e of list ?? []) this.expression(e);
	}

	private expression(exp: N | null | undefined) {
		if (!exp) return;
		switch (exp.type) {
			case 'Identifier': this.reference((exp as Identifier).name, this.tokenOf(exp as Identifier), false); break;
			case 'MemberExpression': this.expression(exp.base as N); break;
			case 'IndexExpression': this.expression(exp.base as N); this.expression(exp.index as N); break;
			case 'CallExpression': this.expression(exp.base as N); this.expressions(exp.arguments as N[]); break;
			case 'TableCallExpression': this.expression(exp.base as N); this.expression(exp.arguments as N); break;
			case 'StringCallExpression': this.expression(exp.base as N); break;
			case 'BinaryExpression': case 'LogicalExpression': this.expression(exp.left as N); this.expression(exp.right as N); break;
			case 'UnaryExpression': this.expression(exp.argument as N); break;
			case 'FunctionDeclaration': this.functionBody(exp, null, 'ANONYMOUS', null, false); break;
			case 'TableConstructorExpression': this.table(exp); break;
			default: break; // literals and `...`
		}
	}

	private table(table: N) {
		for (const field of (table.fields as FieldNode[]) ?? []) {
			if (field.type === 'TableKey') {
				this.expression(field.key as N);
				this.expression(field.value as N);
			} else if (field.type === 'TableKeyString') {
				const value = field.value as N;
				if (value.type === 'FunctionDeclaration') {
					const keyToken = this.tokenOf(field.key);
					this.functionBody(value, field.key.name, 'FIELD', keyToken >= 0 ? this.tokens.span(keyToken) : null, false);
				} else this.expression(value);
			} else this.expression(field.value as N);
		}
	}

	private functionBody(node: N, name: string | null, kind: LuaFunctionKind, nameSpan: LuaSpan | null, method: boolean) {
		const end = this.endToken(node, 'end');
		let keyword = end >= 0 ? this.blocks.openerOf(end) : -1;
		if (keyword >= 0 && !this.tokens.matches(keyword, 'function')) keyword = -1;
		const parameters: string[] = [];
		let vararg = false;
		for (const p of (node.parameters as N[]) ?? []) {
			if (p.type === 'Identifier') parameters.push((p as Identifier).name);
			else if (p.type === 'VarargLiteral') vararg = true;
		}
		const open = keyword >= 0 ? this.openParen(keyword) : -1;
		const parameterTokens = open >= 0 ? this.nameList(open, parameters.length) : [];
		const close = open >= 0 ? this.closeParen(open) : -1;
		const keywordSpan = keyword >= 0 ? this.tokens.span(keyword) : null;
		const starts = [keywordSpan?.start, nameSpan?.start].filter((x): x is number => x != null);
		const start = starts.length ? Math.min(...starts) : 0;
		const id = this.functions.length + 1;
		this.functions.push({
			id,
			parent: this.fn,
			name,
			kind,
			nameSpan: nameSpan ?? keywordSpan ?? { start, end: start },
			span: { start, end: end >= 0 ? this.tokens.end(end) : start },
			parameters,
			vararg,
			returnsValue: false,
		});
		const outer = this.fn;
		this.fn = id;
		this.scoped(() => {
			const scope: LuaSpan = { start: close >= 0 ? this.tokens.end(close) : start, end: end >= 0 ? this.tokens.start(end) : start };
			if (method) this.declare('self', 'SELF', -1, scope);
			parameters.forEach((p, i) => this.declare(p, 'PARAMETER', parameterTokens[i] ?? -1, scope));
			this.block(node.body as Node[]);
		});
		this.fn = outer;
	}

	// ---- names -------------------------------------------------------------

	private declare(name: string, kind: LuaSymbolKind, token: number, scope: LuaSpan): number {
		const id = this.symbols.length;
		const placed = token >= 0 && this.isName(token, name) ? token : -1;
		this.symbols.push({ id, name, kind, declaration: placed >= 0 ? this.tokens.span(placed) : null, scope, function: this.fn, reads: [], writes: [] });
		if (placed >= 0) this.symbolByToken[placed] = id;
		this.scopes[this.scopes.length - 1]!.set(name, id);
		return id;
	}

	private lookup(name: string): number {
		for (let i = this.scopes.length - 1; i >= 0; i--) {
			const id = this.scopes[i]!.get(name);
			if (id !== undefined) return id;
		}
		return -1;
	}

	private reference(name: string, token: number, write: boolean) {
		if (token < 0 || !this.isName(token, name)) return;
		const sp = this.tokens.span(token);
		const id = this.lookup(name);
		if (id >= 0) {
			const s = this.symbols[id]!;
			if (write) s.writes.push(sp);
			else s.reads.push(sp);
			this.symbolByToken[token] = id;
		} else this.globals.push({ name, span: sp, write, function: this.fn, order: this.nextOrder++ });
	}

	private isName(token: number, name: string): boolean {
		return token >= 0 && this.tokens.kind(token) === LuaTokenKind.NAME && this.tokens.matches(token, name);
	}

	private chainName(exp: N): string | null {
		if (exp.type === 'Identifier') return (exp as Identifier).name;
		if (exp.type === 'MemberExpression' && exp.indexer === '.') {
			const base = this.chainName(exp.base as N);
			return base ? `${base}.${(exp.identifier as Identifier).name}` : null;
		}
		return null;
	}

	private chainStart(exp: N): number | null {
		if (exp.type === 'Identifier') {
			const t = this.tokenOf(exp as Identifier);
			return t >= 0 ? this.tokens.start(t) : null;
		}
		if (exp.type === 'MemberExpression') return this.chainStart(exp.base as N);
		return null;
	}

	// ---- places ------------------------------------------------------------

	/** The token an identifier node sits on, checked against its own name. */
	private tokenOf(ident: Identifier): number {
		const t = this.tokens.indexAt(ident.range[0]);
		return t >= 0 && this.isName(t, ident.name) ? t : -1;
	}

	/** The first code token of a statement when it is exactly `word`, else -1. */
	private startToken(node: N, word: string): number {
		let t = this.tokens.indexAt(node.range[0]);
		if (t >= 0 && !this.tokens.isCode(t)) t = this.tokens.nextCode(t);
		return t >= 0 && this.tokens.matches(t, word) ? t : -1;
	}

	/** The code token a node ends on when it is exactly `word`, else -1. */
	private endToken(node: N, word: string): number {
		const t = this.endTokenOf(node);
		return t >= 0 && this.tokens.matches(t, word) ? t : -1;
	}

	private endTokenOf(node: N): number {
		const end = node.range[1];
		let t = this.tokens.indexAt(Math.max(0, end - 1));
		if (t >= 0 && !this.tokens.isCode(t)) t = this.tokens.prevCode(t);
		return t;
	}

	private endOffset(node: N): number {
		const t = this.endTokenOf(node);
		return t >= 0 ? this.tokens.end(t) : this.source.length;
	}

	private blockEnd(token: number): number {
		if (token < 0) return this.source.length;
		const opener = this.blocks.enclosing(token);
		if (opener < 0) return this.source.length;
		const closer = this.blocks.closerOf(opener);
		if (closer < 0) return this.source.length;
		if (this.tokens.matches(closer, 'until')) return this.repeatEnds.length ? this.repeatEnds[this.repeatEnds.length - 1]! : this.tokens.end(closer);
		return this.tokens.start(closer);
	}

	private nameList(from: number, count: number): number[] {
		const found: number[] = [];
		let at = this.tokens.nextCode(from);
		while (found.length < count && at >= 0 && this.tokens.kind(at) === LuaTokenKind.NAME) {
			found.push(at);
			const comma = this.tokens.nextCode(at);
			if (comma < 0 || !this.tokens.matches(comma, ',')) break;
			at = this.tokens.nextCode(comma);
		}
		return found;
	}

	private openParen(keyword: number): number {
		let at = this.tokens.nextCode(keyword);
		let steps = 0;
		while (at >= 0 && steps++ < MAX_HEADER_TOKENS) {
			if (this.tokens.matches(at, '(')) return at;
			if (this.tokens.kind(at) !== LuaTokenKind.NAME && !this.tokens.matches(at, '.') && !this.tokens.matches(at, ':')) return -1;
			at = this.tokens.nextCode(at);
		}
		return -1;
	}

	private closeParen(open: number): number {
		let at = this.tokens.nextCode(open);
		let steps = 0;
		while (at >= 0 && steps++ < MAX_PARAMETER_TOKENS) {
			if (this.tokens.matches(at, ')')) return at;
			at = this.tokens.nextCode(at);
		}
		return -1;
	}
}
