/** Static checks over a plugin's main.lua (LuaChecks.kt equivalents); no VM needed. */

export const LUA_DIFFERENCES = [
	'The app runs Lua 5.2 (luaj); the preview runs Lua 5.3. Integer division `//` and bitwise operators exist there but not in the app; `bit32` is provided in both.',
	'Strings cross into the app as UTF-16 units (CESU-8), so `#s` of an emoji is 6 there and 4 here.',
	'`%f[` frontier patterns crash the app\'s matcher; the preview accepts them. The static check flags them.',
];

/* ---------- static checks (LuaChecks.kt equivalents) ---------- */

export interface LuaDiagnostic {
	line: number;
	severity: 'error' | 'warn';
	message: string;
}

export function checkLua(source: string, opts: { storage: boolean }): LuaDiagnostic[] {
	const out: LuaDiagnostic[] = [];
	const lines = source.split('\n');
	if (!/\bfunction\s+render\s*\(|\brender\s*=\s*function/.test(source)) out.push({ line: 1, severity: 'error', message: 'No render() function; the app refuses to load a plugin without one.' });
	lines.forEach((l, i) => {
		const code = l.replace(/--.*$/, '');
		const ln = i + 1;
		if (/%f\[/.test(code)) out.push({ line: ln, severity: 'error', message: 'Frontier pattern %f[ crashes the app\'s Lua matcher.' });
		if (/\bstring\.dump\b/.test(code)) out.push({ line: ln, severity: 'warn', message: 'string.dump works but nothing can load its output in the app (no undumper).' });
		for (const g of ['require', 'dofile', 'loadstring', 'loadfile', 'load']) if (new RegExp(`(^|[^.\\w])${g}\\s*\\(`).test(code)) out.push({ line: ln, severity: 'error', message: `${g} does not exist in the sandbox.` });
		for (const g of ['io', 'coroutine', 'debug', 'package']) if (new RegExp(`(^|[^.\\w])${g}\\.`).test(code)) out.push({ line: ln, severity: 'error', message: `The ${g} library is not available in the sandbox.` });
		if (/\bos\.(execute|exit|getenv|remove|rename|tmpname|setlocale)\b/.test(code)) out.push({ line: ln, severity: 'error', message: 'Only os.time, os.clock and os.date exist in the sandbox.' });
		if (/\bwm\.(text|clipboard|http|net|files)\b/.test(code)) out.push({ line: ln, severity: 'error', message: 'wm.text/clipboard/http/net/files do not exist and never will: plugins cannot read what you type or reach the network.' });
		if (!opts.storage && /\bwm\.storage\b/.test(code)) out.push({ line: ln, severity: 'error', message: 'wm.storage is nil unless plugin.json declares the "storage" permission.' });
		if (/\/\/(?![^"']*["'])/.test(code) && !/https?:\/\//.test(code)) out.push({ line: ln, severity: 'warn', message: 'Integer division // is Lua 5.3; the app runs Lua 5.2. Use math.floor(a / b).' });
		if (/\bgoto\b/.test(code)) out.push({ line: ln, severity: 'warn', message: 'goto is supported by luaj 5.2 but easy to get wrong across versions.' });
		if (/\butf8\./.test(code)) out.push({ line: ln, severity: 'error', message: 'The utf8 library does not exist in Lua 5.2 / the app.' });
	});
	return out;
}
