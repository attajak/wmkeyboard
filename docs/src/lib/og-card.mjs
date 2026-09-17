/**
 * The per-page social card, drawn as an SVG and rasterised by sharp.
 *
 * Every page used to link the same `/og-card.png`, so a link to the glide
 * typing guide and a link to the build instructions looked identical in a chat
 * — the card said "WM Keyboard" and nothing about the page. Each page now gets
 * its own, with its section, its title and its description on it.
 *
 * The cards are generated during the build (src/pages/og/[...page].png.ts)
 * rather than committed: 218 PNGs is nine megabytes of binaries in git that go
 * stale the first time a title is edited.
 *
 * Text is laid out here rather than left to the SVG renderer, which has no
 * concept of wrapping. Widths come from a per-character estimate, because the
 * only thing available to draw with is whatever sans-serif font the machine
 * running the build happens to have — Inter locally, something else in CI. The
 * estimate is deliberately generous, and every line is clipped to the card, so
 * a wider fallback face reflows rather than spilling off the edge.
 */

import sharp from 'sharp';

export const WIDTH = 1200;
export const HEIGHT = 630;

// Brand stops, same as src/styles/custom.css and scripts/og-card.mjs.
const BLUE = '#4c8df6';
const VIOLET = '#8b5cf6';
const NAVY = '#17182b';
const INK = '#ffffff';
const MUTED = '#c2c4d7';
const DIM = '#8b8fae';

const FONT = "Inter, 'Helvetica Neue', Helvetica, Arial, sans-serif";

const PAD = 84;
const COLUMN = WIDTH - PAD * 2;

/** SVG is XML — a bare `&` in a title would blow up the parse. */
const xml = (text) =>
	String(text)
		.replace(/&/g, '&amp;')
		.replace(/</g, '&lt;')
		.replace(/>/g, '&gt;')
		.replace(/"/g, '&quot;');

/**
 * Roughly how wide a character is, as a fraction of the font size.
 *
 * Not a substitute for real metrics; enough to wrap a title without measuring
 * a font that may not be installed. The narrow and wide buckets are what keep
 * "Iiil" and "MMMW" from being treated as the same length.
 */
const NARROW = new Set([...'iíìjlItf.,:;\'"`|!()[]{}·-– ']);
const WIDE = new Set([...'mwMW@—…']);
const charWidth = (char) => (NARROW.has(char) ? 0.3 : WIDE.has(char) ? 0.88 : 0.52);
const measure = (text, size) =>
	[...String(text)].reduce((total, char) => total + charWidth(char) * size, 0);

/** Greedy wrap to `maxLines`; the last line is ellipsised if there is more text. */
function wrap(text, size, maxWidth, maxLines) {
	const words = String(text).split(/\s+/).filter(Boolean);
	const lines = [];
	let line = '';

	for (const word of words) {
		const candidate = line ? `${line} ${word}` : word;
		if (measure(candidate, size) <= maxWidth || !line) {
			line = candidate;
			continue;
		}
		lines.push(line);
		line = word;
		if (lines.length === maxLines) break;
	}
	if (lines.length < maxLines && line) lines.push(line);

	// Anything that didn't fit becomes an ellipsis on the last line.
	const used = lines.join(' ');
	if (used.length < String(text).replace(/\s+/g, ' ').length) {
		let last = lines[lines.length - 1] ?? '';
		while (last && measure(`${last}…`, size) > maxWidth) {
			last = last.replace(/\s*\S+$/, '');
		}
		lines[lines.length - 1] = `${last}…`;
	}
	return lines;
}

/**
 * The card's SVG. `logo` is a data URI so the whole thing is one document —
 * librsvg will not load a local file reference out of an in-memory SVG.
 */
function cardSvg({ kicker, title, description, footer, logo }) {
	// A long title takes the bigger share of the card; a short one leaves room
	// for three lines of description.
	const titleSize = title.length > 46 ? 60 : title.length > 28 ? 72 : 84;
	const titleLines = wrap(title, titleSize, COLUMN - 20, 3);
	const descLines = wrap(description ?? '', 30, COLUMN - 20, titleLines.length >= 3 ? 2 : 3);

	// The baseline has to clear the kicker's descenders by the title's own
	// ascent, or an 84px title lands on top of the section name — which is what
	// a fixed baseline did to every page with a two-word title.
	const titleTop = Math.round(214 + titleSize * 0.74);
	const titleLeading = Math.round(titleSize * 1.16);
	const descTop = titleTop + titleLines.length * titleLeading + 34;

	return Buffer.from(`
<svg xmlns="http://www.w3.org/2000/svg" width="${WIDTH}" height="${HEIGHT}" viewBox="0 0 ${WIDTH} ${HEIGHT}">
	<defs>
		<linearGradient id="brand" x1="0" y1="0" x2="1" y2="0">
			<stop offset="0%" stop-color="${BLUE}"/>
			<stop offset="100%" stop-color="${VIOLET}"/>
		</linearGradient>
		<radialGradient id="glow" cx="0.5" cy="0.5" r="0.5">
			<stop offset="0%" stop-color="${VIOLET}" stop-opacity="0.42"/>
			<stop offset="100%" stop-color="${VIOLET}" stop-opacity="0"/>
		</radialGradient>
		<clipPath id="card"><rect width="${WIDTH}" height="${HEIGHT}"/></clipPath>
	</defs>

	<g clip-path="url(#card)">
		<rect width="${WIDTH}" height="${HEIGHT}" fill="${NAVY}"/>
		<circle cx="${WIDTH - 90}" cy="${HEIGHT - 40}" r="360" fill="url(#glow)"/>
		<rect x="0" y="0" width="${WIDTH}" height="8" fill="url(#brand)"/>

		<g font-family="${FONT}">
			${logo ? `<image x="${PAD}" y="74" width="68" height="68" href="${logo}"/>` : ''}
			<text x="${PAD + (logo ? 90 : 0)}" y="120" font-size="31" font-weight="700" fill="${INK}">WM&#160;Keyboard</text>

			${
				kicker
					? `<text x="${PAD}" y="196" font-size="25" font-weight="600" fill="${BLUE}" letter-spacing="2.4">${xml(
							kicker.toUpperCase(),
						)}</text>`
					: ''
			}

			<g font-size="${titleSize}" font-weight="700" fill="${INK}">
				${titleLines
					.map((line, i) => `<text x="${PAD}" y="${titleTop + i * titleLeading}">${xml(line)}</text>`)
					.join('\n\t\t\t\t')}
			</g>

			<g font-size="30" font-weight="400" fill="${MUTED}">
				${descLines
					.map((line, i) => `<text x="${PAD}" y="${descTop + i * 42}">${xml(line)}</text>`)
					.join('\n\t\t\t\t')}
			</g>

			<text x="${PAD}" y="${HEIGHT - 52}" font-size="25" font-weight="500" fill="${DIM}">${xml(footer)}</text>
		</g>
	</g>
</svg>`);
}

let logoUri;

/** The logo mark as a data URI, resized once and reused by every card. */
async function logoDataUri(logoPath) {
	if (logoUri !== undefined) return logoUri;
	try {
		const png = await sharp(logoPath).resize(136, 136, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } }).png().toBuffer();
		logoUri = `data:image/png;base64,${png.toString('base64')}`;
	} catch {
		logoUri = null;
	}
	return logoUri;
}

/**
 * A finished 1200×630 PNG.
 *
 * The palette is quantised: a flat-colour card needs nowhere near 24-bit, and
 * 64 colours takes each card from about 90 KB to about 20 KB — which matters,
 * because there are 218 of them and Twitter refuses a card over 5 MB.
 */
export async function renderCard({ kicker, title, description, footer, logoPath }) {
	const svg = cardSvg({ kicker, title, description, footer, logo: await logoDataUri(logoPath) });
	return sharp(svg, { density: 144 })
		.resize(WIDTH, HEIGHT, { fit: 'cover' })
		.png({ palette: true, colors: 64, compressionLevel: 9 })
		.toBuffer();
}
