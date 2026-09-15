/** Port of core/addons `Semver`: same tolerance, same ordering. */

function split(version: string): [string, string] {
	const trimmed = version.trim().replace(/^[vV]/, '').split('+')[0]!;
	const dash = trimmed.indexOf('-');
	return dash < 0 ? [trimmed, ''] : [trimmed.slice(0, dash), trimmed.slice(dash + 1)];
}

function numbers(core: string): number[] {
	return core.split('.').map((part) => {
		const digits = part.trim().match(/^\d*/)![0];
		return digits ? Number(digits) : 0;
	});
}

function comparePrerelease(a: string, b: string): number {
	const pa = a.split('.');
	const pb = b.split('.');
	for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
		const left = pa[i];
		const right = pb[i];
		if (left === undefined) return -1;
		if (right === undefined) return 1;
		const ln = /^\d+$/.test(left) ? Number(left) : null;
		const rn = /^\d+$/.test(right) ? Number(right) : null;
		let r: number;
		if (ln !== null && rn !== null) r = ln < rn ? -1 : ln > rn ? 1 : 0;
		else if (ln !== null) r = -1;
		else if (rn !== null) r = 1;
		else r = left < right ? -1 : left > right ? 1 : 0;
		if (r !== 0) return r;
	}
	return 0;
}

export function compareSemver(a: string, b: string): number {
	const [coreA, preA] = split(a);
	const [coreB, preB] = split(b);
	const na = numbers(coreA);
	const nb = numbers(coreB);
	for (let i = 0; i < Math.max(na.length, nb.length); i++) {
		const x = na[i] ?? 0;
		const y = nb[i] ?? 0;
		if (x !== y) return x < y ? -1 : 1;
	}
	if (!preA && !preB) return 0;
	if (!preA) return 1;
	if (!preB) return -1;
	return comparePrerelease(preA, preB);
}

export function isNewer(candidate: string, installed: string): boolean {
	return compareSemver(candidate, installed) > 0;
}

/** The schema's pattern: what `validate.py` in the sample repository enforces. */
export const SEMVER_PATTERN = /^\d+\.\d+\.\d+(?:[-+].+)?$/;

export function isStrictSemver(v: string): boolean {
	return SEMVER_PATTERN.test(v);
}
