/**
 * Where to get the app, for the pages that offer to install it (/open/ and
 * the link builder's fallback page). One place so a listing going live is one
 * edit: flip `listed` when the store page exists. Until then the button is
 * drawn disabled, because a link to a listing that is not there yet is a 404
 * with a logo on it.
 *
 * `icon` names a mark in brand-icons.mjs.
 */
export const PACKAGE = 'com.wasimaster.wmkeyboard';

export const INSTALL_SOURCES = [
	{
		id: 'fdroid',
		label: 'F-Droid',
		icon: 'fdroid',
		url: `https://f-droid.org/packages/${PACKAGE}/`,
		listed: true,
		note: 'Built and signed by F-Droid.',
	},
	{
		id: 'github',
		label: 'GitHub',
		icon: 'github',
		url: 'https://github.com/wasi-master/wmkeyboard/releases/latest',
		listed: true,
		note: 'Signed APKs, Full and Lite.',
	},
	{
		id: 'play',
		label: 'Google Play',
		icon: 'googleplay',
		url: `https://play.google.com/store/apps/details?id=${PACKAGE}`,
		listed: false,
		note: 'Coming soon.',
	},
];
