/** Shared bits: links that route client-side, repo icons, dialogs, QR codes. */
import type { ComponentChildren, JSX } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { hrefFor, navigate, type Route } from '../state';
import { resolveAsset } from '../lib/resolve';
import type { LoadedRepo } from '../lib/types';
import { IconX } from './icons';

export function Link({ to, children, ...rest }: { to: Route; children?: ComponentChildren } & Omit<JSX.HTMLAttributes<HTMLAnchorElement>, 'href'>) {
	return (
		<a
			href={hrefFor(to)}
			onClick={(e) => {
				if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
				e.preventDefault();
				navigate(to);
			}}
			{...rest}
		>
			{children}
		</a>
	);
}

export function RepoIcon({ repo, size }: { repo: LoadedRepo | null; size?: string }) {
	const name = repo?.manifest?.repo.name ?? repo?.ref.input ?? '?';
	const icon = repo?.manifest?.repo.icon ? resolveAsset(repo.ref.url, repo.manifest.repo.icon) : null;
	const [broken, setBroken] = useState(false);
	const initials = name
		.replace(/^https?:\/\//, '')
		.split(/[\s/._-]+/)
		.filter(Boolean)
		.slice(0, 2)
		.map((w) => w[0])
		.join('');
	return (
		<span class="st-repo-icon" style={size ? { width: size, height: size } : undefined} aria-hidden="true">
			{icon && !broken ? <img src={icon} alt="" loading="lazy" onError={() => setBroken(true)} /> : initials || '?'}
		</span>
	);
}

export function Dialog({ title, desc, onClose, children, wide, actions }: { title: string; desc?: ComponentChildren; onClose: () => void; children?: ComponentChildren; wide?: boolean; actions?: ComponentChildren }) {
	const boxRef = useRef<HTMLDivElement>(null);
	useEffect(() => {
		const onKey = (e: KeyboardEvent) => {
			if (e.key === 'Escape') onClose();
		};
		document.addEventListener('keydown', onKey);
		const prev = document.body.style.overflow;
		document.body.style.overflow = 'hidden';
		boxRef.current?.querySelector<HTMLElement>('input, button, [tabindex]')?.focus();
		return () => {
			document.removeEventListener('keydown', onKey);
			document.body.style.overflow = prev;
		};
	}, [onClose]);
	return (
		<div class="st-dialog" onClick={(e) => e.target === e.currentTarget && onClose()}>
			<div class={`st-dialog-box ${wide ? 'wide' : ''}`} role="dialog" aria-modal="true" aria-label={title} ref={boxRef}>
				<div class="st-row" style="justify-content:space-between;align-items:flex-start">
					<h2>{title}</h2>
					<button class="st-btn st-btn-ghost st-btn-icon" onClick={onClose} aria-label="Close">
						<IconX />
					</button>
				</div>
				{desc && <p class="st-dialog-desc">{desc}</p>}
				{children}
				{actions && <div class="st-dialog-actions">{actions}</div>}
			</div>
		</div>
	);
}

/** QR code for a link; rendered lazily so the library only loads when shown. */
export function Qr({ text, caption }: { text: string; caption?: string }) {
	const ref = useRef<HTMLCanvasElement>(null);
	const [err, setErr] = useState<string | null>(null);
	useEffect(() => {
		let cancelled = false;
		import('qrcode')
			.then((QR) => {
				if (cancelled || !ref.current) return;
				return QR.toCanvas(ref.current, text, { margin: 1, width: 220, errorCorrectionLevel: 'M', color: { dark: '#111111', light: '#ffffff' } });
			})
			.catch((e) => setErr(String(e)));
		return () => {
			cancelled = true;
		};
	}, [text]);
	return (
		<div class="st-qr">
			<canvas ref={ref} width={220} height={220} aria-label={`QR code: ${text}`} />
			{err ? <span>{err}</span> : caption && <span>{caption}</span>}
		</div>
	);
}

export function Spinner({ label }: { label?: string }) {
	return (
		<span class="st-muted st-small">
			<span class="st-spinner" /> {label ?? 'Loading…'}
		</span>
	);
}

export function Empty({ icon, title, children }: { icon?: ComponentChildren; title: string; children?: ComponentChildren }) {
	return (
		<div class="st-empty">
			{icon && <div class="st-empty-icon">{icon}</div>}
			<h3>{title}</h3>
			{children}
		</div>
	);
}

export function Notice({ kind, icon, children }: { kind?: 'warn' | 'err' | 'ok' | 'info'; icon?: ComponentChildren; children: ComponentChildren }) {
	return (
		<div class={`st-notice ${kind && kind !== 'info' ? `st-notice-${kind}` : ''}`}>
			{icon}
			<div>{children}</div>
		</div>
	);
}

/** Copy-to-clipboard button with its own "copied" feedback. */
export function CopyButton({ text, label, class: cls }: { text: string; label: string; class?: string }) {
	const [done, setDone] = useState(false);
	return (
		<button
			class={cls ?? 'st-btn st-btn-sm'}
			onClick={async () => {
				try {
					await navigator.clipboard.writeText(text);
					setDone(true);
					setTimeout(() => setDone(false), 1500);
				} catch {
					prompt('Copy this:', text);
				}
			}}
		>
			{done ? 'Copied' : label}
		</button>
	);
}
