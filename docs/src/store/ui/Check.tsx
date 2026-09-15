/** Repository health check UI over lib/check.ts. */
import { useEffect, useRef, useState } from 'preact/hooks';
import { repos } from '../state';
import { reportToMarkdown, runCheck, type CheckReport, type Finding, type Severity } from '../lib/check';
import { describeManifestUrl, resolveManifestUrl } from '../lib/resolve';
import { CopyButton, Notice } from './common';
import { IconCheck, IconInfo, IconShield, IconWarn } from './icons';

const SEV_PILL: Record<Severity, string> = { error: 'st-pill st-pill-err', warn: 'st-pill st-pill-warn', info: 'st-pill st-pill-muted' };

export function Check({ repoUrl }: { repoUrl?: string }) {
	const [input, setInput] = useState(repoUrl ? (repos.value.find((r) => r.ref.url === repoUrl)?.ref.input ?? repoUrl) : '');
	const [deep, setDeep] = useState(false);
	const [running, setRunning] = useState(false);
	const [progress, setProgress] = useState<{ done: number; total: number; label: string } | null>(null);
	const [report, setReport] = useState<CheckReport | null>(null);
	const [live, setLive] = useState<Finding[]>([]);
	const [filter, setFilter] = useState<Severity | 'all'>('all');
	const ctl = useRef<AbortController | null>(null);
	const resolved = input.trim() ? resolveManifestUrl(input) : null;

	const run = async (target?: string) => {
		const resolvedNow = target ? resolveManifestUrl(target) : resolved;
		if (!resolvedNow || running) return;
		ctl.current?.abort();
		const c = new AbortController();
		ctl.current = c;
		setRunning(true);
		setReport(null);
		setLive([]);
		setProgress(null);
		const r = await runCheck(resolvedNow, {
			deep,
			signal: c.signal,
			onProgress: (done, total, label) => setProgress({ done, total, label }),
			onFinding: (f) => setLive((l) => [...l, f]),
		});
		if (!c.signal.aborted) setReport(r);
		setRunning(false);
	};

	useEffect(() => {
		if (!repoUrl) return;
		const input = repos.value.find((r) => r.ref.url === repoUrl)?.ref.input ?? repoUrl;
		setInput(input);
		void run(input);
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [repoUrl]);
	useEffect(() => () => ctl.current?.abort(), []);

	const findings = report?.findings ?? live;
	const count = (s: Severity) => findings.filter((f) => f.severity === s).length;
	const shown = findings.filter((f) => filter === 'all' || f.severity === filter);
	const grouped = new Map<string, Finding[]>();
	for (const f of shown) grouped.set(f.where, [...(grouped.get(f.where) ?? []), f]);

	return (
		<div class="st-page st-page-narrow">
			<span class="st-kicker">Repository check</span>
			<h1 style="font-size:1.6rem;font-weight:800;margin-bottom:0.4rem">Is this repository healthy?</h1>
			<p class="st-muted" style="max-width:62ch">
				Runs what the app's decoder, the published JSON schema and the sample repository's <code>validate.py</code> enforce, then probes every file for reachability and size. Deep mode downloads each payload to verify its checksum and parse it as its type.
			</p>

			<div class="st-panel" style="margin-top:1.2rem">
				<div class="st-field">
					<label for="st-check-url">Repository</label>
					<div class="st-row" style="flex-wrap:nowrap">
						<input id="st-check-url" class="st-input" list="st-check-list" placeholder="github.com/user/repo or a wmkeyboard-repo.json URL" value={input} onInput={(e) => setInput((e.target as HTMLInputElement).value)} onKeyDown={(e) => e.key === 'Enter' && run()} aria-invalid={!!input.trim() && !resolved} />
						<datalist id="st-check-list">
							{repos.value.map((r) => <option key={r.ref.url} value={r.ref.input}>{r.manifest?.repo.name ?? describeManifestUrl(r.ref.url).label}</option>)}
						</datalist>
						<button class="st-btn st-btn-primary" disabled={!resolved || running} onClick={() => run()}><IconShield /> {running ? 'Checking…' : 'Check'}</button>
						{running && <button class="st-btn st-btn-ghost" onClick={() => ctl.current?.abort()}>Stop</button>}
					</div>
					{resolved && <span class="st-help">Reads <code style="overflow-wrap:anywhere">{resolved}</code></span>}
					{input.trim() && !resolved && <span class="st-err-text">Not an address the app can resolve.</span>}
				</div>
				<label class="st-switch" style="margin-top:0.8rem">
					<input type="checkbox" checked={deep} onChange={(e) => setDeep((e.target as HTMLInputElement).checked)} />
					Deep check: download every payload (up to 12 MB each), verify sha256, parse as its type
				</label>
				{progress && running && (
					<div style="margin-top:0.8rem">
						<div class="st-progress"><i style={{ width: `${progress.total ? (progress.done / progress.total) * 100 : 0}%` }} /></div>
						<div class="st-small st-muted" style="margin-top:0.3rem">{progress.done}/{progress.total} · {progress.label}</div>
					</div>
				)}
			</div>

			{(report || live.length > 0) && (
				<>
					<div class="st-check-summary">
						<div class="st-stat"><strong style="color:var(--st-err)">{count('error')}</strong><span>errors</span></div>
						<div class="st-stat"><strong style="color:var(--st-warn)">{count('warn')}</strong><span>warnings</span></div>
						<div class="st-stat"><strong>{count('info')}</strong><span>notes</span></div>
						{report?.manifest && <div class="st-stat"><strong>{report.manifest.addons.length}</strong><span>addons</span></div>}
						{report && deep && <div class="st-stat"><strong style="color:var(--st-ok)">{Object.values(report.verified).filter((v) => v === 'ok').length}</strong><span>checksums verified</span></div>}
					</div>
					{report && count('error') === 0 && (
						<Notice kind="ok" icon={<IconCheck />}>
							<b>No errors.</b> The app would read every entry in this repository{deep ? ' and every checksum matches' : ''}.
						</Notice>
					)}
					<div class="st-row" style="justify-content:space-between;margin:1rem 0 0.6rem">
						<div class="st-chips" style="padding:0">
							{(['all', 'error', 'warn', 'info'] as const).map((s) => (
								<button key={s} class="st-chip" aria-pressed={filter === s} onClick={() => setFilter(s)}>{s === 'all' ? 'All' : s === 'error' ? 'Errors' : s === 'warn' ? 'Warnings' : 'Notes'}</button>
							))}
						</div>
						{report && <CopyButton text={reportToMarkdown(report)} label="Copy report as Markdown" />}
					</div>
					<div class="st-preview-body">
						{[...grouped.entries()].map(([where, list]) => (
							<div key={where}>
								<div class="st-finding" style="background:var(--sl-color-gray-7)"><span /><b>{where}</b></div>
								{list.map((f, i) => (
									<div class="st-finding" key={i}>
										<span class={SEV_PILL[f.severity]}>{f.severity === 'warn' ? 'warning' : f.severity === 'info' ? 'note' : 'error'}</span>
										<div>
											{f.message}
											{f.field && <div class="st-finding-where">field <code>{f.field}</code></div>}
										</div>
									</div>
								))}
							</div>
						))}
						{shown.length === 0 && <div class="st-pv-pad st-muted st-small">Nothing at this level.</div>}
					</div>
				</>
			)}

			{!report && !running && live.length === 0 && (
				<div style="margin-top:1rem">
					<Notice icon={<IconInfo />}>
						Publishing your own? The sample repository on GitHub ships <code>tools/build_index.py</code> (fills sha256 + sizeBytes) and <code>tools/validate.py</code>; this page is the same checks, run from a browser against any host, plus a reachability sweep. See the <a href="/development/addon-repos/repo-format/">repository format</a>.
					</Notice>
				</div>
			)}
			<p class="st-small st-muted" style="margin-top:1rem"><IconWarn style="width:0.9rem;height:0.9rem;vertical-align:-0.15em" /> A host that blocks browser reads (no CORS header) fails here but may still work in the app, which isn't bound by CORS.</p>
		</div>
	);
}
