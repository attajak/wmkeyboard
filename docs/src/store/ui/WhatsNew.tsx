/** New and updated addons since the last visit, per repository, plus a recency feed. */
import { markSeen, noveltyOf, repos, seen, seedSeen } from '../state';
import { compareSemver } from '../lib/semver';
import { AddonCard } from './AddonCard';
import { Empty, Link, RepoIcon } from './common';
import { IconCheck, IconSparkle } from './icons';
import { fmtDate } from '../lib/util';

export function WhatsNew() {
	const groups = repos.value
		.filter((r) => r.manifest)
		.map((r) => ({
			repo: r,
			fresh: r.manifest!.addons.filter((a) => noveltyOf(r.ref.url, a) !== null),
			unseenRepo: !seen.value[r.ref.url],
		}));
	const anyFresh = groups.some((g) => g.fresh.length);
	const firstTime = groups.filter((g) => g.unseenRepo);

	// A "recent" feed for repositories seen for the first time: highest versions and dated repos.
	const recent = repos.value
		.filter((r) => r.manifest)
		.flatMap((r) => r.manifest!.addons.map((entry) => ({ repo: r, entry })))
		.sort((a, b) => compareSemver(b.entry.version, a.entry.version))
		.slice(0, 12);

	return (
		<div class="st-page">
			<div class="st-section-head">
				<div>
					<span class="st-kicker">What's new</span>
					<h1 style="font-size:1.6rem;font-weight:800">Since your last visit</h1>
				</div>
				{anyFresh && (
					<button class="st-btn st-btn-sm" onClick={() => repos.value.forEach((r) => markSeen(r.ref.url))}><IconCheck /> Mark all seen</button>
				)}
			</div>

			{firstTime.length > 0 && (
				<div class="st-panel" style="margin-bottom:1rem">
					<p class="st-small">
						First look at {firstTime.map((g, i) => <span key={g.repo.ref.url}>{i > 0 && ', '}<b>{g.repo.manifest!.repo.name}</b></span>)}. Remembering what's there now, so the next change shows up here.{' '}
						<button class="st-btn st-btn-sm st-btn-ghost" onClick={() => firstTime.forEach((g) => seedSeen(g.repo.ref.url))}>Got it</button>
					</p>
				</div>
			)}

			{!anyFresh ? (
				<Empty icon={<IconSparkle />} title="You're up to date">
					<p class="st-small">Nothing has been added or updated in your repositories since you last looked.</p>
				</Empty>
			) : (
				groups.filter((g) => g.fresh.length).map((g) => (
					<section class="st-section" key={g.repo.ref.url}>
						<div class="st-section-head">
							<h2 style="display:flex;align-items:center;gap:0.5rem">
								<RepoIcon repo={g.repo} size="1.6rem" />
								<Link to={{ view: 'repo', repo: g.repo.ref.url }}>{g.repo.manifest!.repo.name}</Link>
								<span class="st-sub">{g.fresh.length} new or updated{g.repo.manifest!.repo.updatedAt ? ` · repository updated ${fmtDate(g.repo.manifest!.repo.updatedAt)}` : ''}</span>
							</h2>
							<button class="st-btn st-btn-sm st-btn-ghost" onClick={() => markSeen(g.repo.ref.url)}>Mark seen</button>
						</div>
						<div class="st-grid">
							{g.fresh.map((entry) => <AddonCard key={entry.id} repo={g.repo} entry={entry} />)}
						</div>
					</section>
				))
			)}

			{recent.length > 0 && (
				<section class="st-section">
					<div class="st-section-head">
						<h2>Most revised</h2>
						<span class="st-sub">Highest version numbers across your repositories</span>
					</div>
					<div class="st-grid">
						{recent.map(({ repo, entry }) => <AddonCard key={`${repo.ref.url}#${entry.id}`} repo={repo} entry={entry} showRepo />)}
					</div>
				</section>
			)}
		</div>
	);
}
