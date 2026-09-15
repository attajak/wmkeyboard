/**
 * Line-for-line port of core/layout/RowSpan.kt (`spanRowWidths`, `spanBands`,
 * `spanSlots`) and `gridWeightOf` from KeyboardLayout.kt. Change the Kotlin →
 * change this. A key with `rowSpan = n` holds its horizontal interval in the n
 * rows starting at its own; keys below flow left to right around it, like cells
 * around an HTML `rowspan`.
 */
import type { LayoutKey } from './payloads';

const EPSILON = 0.001;

export interface KeySlot {
	row: number;
	col: number;
	key: LayoutKey;
	/** Left edge, in grid units. */
	x: number;
	/** Rows covered, clamped to the rows that exist. */
	span: number;
}

const widthOf = (k: LayoutKey) => (typeof k.width === 'number' && Number.isFinite(k.width) ? k.width : 1);

export function spanFrom(k: LayoutKey, row: number, rowCount: number): number {
	const s = typeof k.rowSpan === 'number' && Number.isFinite(k.rowSpan) ? Math.floor(k.rowSpan) : 1;
	return Math.min(Math.max(s, 1), Math.max(rowCount - row, 1));
}

export function hasRowSpans(rows: LayoutKey[][]): boolean {
	return rows.some((row) => row.some((k) => (k.rowSpan ?? 1) > 1));
}

/** Each row's own keys plus the columns held over it by spanning keys above. */
export function spanRowWidths(rows: LayoutKey[][]): number[] {
	const widths = rows.map((row) => row.reduce((n, k) => n + widthOf(k), 0));
	rows.forEach((row, r) => {
		for (const k of row) {
			const span = spanFrom(k, r, rows.length);
			for (let below = r + 1; below < r + span; below++) widths[below]! += widthOf(k);
		}
	});
	return widths;
}

/** The most common row width (ties → the wider), rows under a span counting the held column. */
export function gridWeightOf(rows: LayoutKey[][]): number {
	if (!rows.length) return 0;
	const buckets = new Map<number, number[]>();
	for (const w of spanRowWidths(rows)) {
		const key = Math.round(w * 100);
		buckets.set(key, [...(buckets.get(key) ?? []), w]);
	}
	let best: [number, number[]] | null = null;
	for (const e of buckets) {
		if (!best || e[1].length > best[1].length || (e[1].length === best[1].length && e[0] > best[0])) best = e;
	}
	return best![1][0]!;
}

/** Runs of rows joined by a span, in order, covering every row. */
export function spanBands(rows: LayoutKey[][]): [number, number][] {
	if (!rows.length) return [];
	const reach = rows.map((row, r) => row.reduce((m, k) => Math.max(m, r + spanFrom(k, r, rows.length) - 1), r));
	const bands: [number, number][] = [];
	let start = 0;
	while (start < rows.length) {
		let end = reach[start]!;
		for (let i = start; i <= end; i++) if (reach[i]! > end) end = reach[i]!;
		bands.push([start, end]);
		start = end + 1;
	}
	return bands;
}

/** Every key placed against a `gridWeight`-wide board. */
export function spanSlots(rows: LayoutKey[][], gridWeight: number): KeySlot[] {
	const widths = spanRowWidths(rows);
	const held: number[][] = rows.map(() => []);
	const slots: KeySlot[] = [];
	rows.forEach((row, r) => {
		let x = Math.max((gridWeight - widths[r]!) / 2, 0);
		row.forEach((key, c) => {
			const w = widthOf(key);
			x = firstFree(held[r]!, x, w);
			const span = spanFrom(key, r, rows.length);
			slots.push({ row: r, col: c, key, x, span });
			for (let below = r + 1; below < r + span; below++) held[below]!.push(x, x + w);
			x += w;
		});
	});
	return slots;
}

function firstFree(held: number[], from: number, width: number): number {
	let x = from;
	let moved = held.length > 0;
	while (moved) {
		moved = false;
		for (let i = 0; i < held.length; i += 2) {
			if (x < held[i + 1]! - EPSILON && x + width > held[i]! + EPSILON) {
				x = held[i + 1]!;
				moved = true;
			}
		}
	}
	return x;
}
