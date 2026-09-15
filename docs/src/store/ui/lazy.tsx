/** Code-splits a view: the chunk loads on first render, a spinner stands in until then. */
import type { ComponentType } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { Spinner } from './common';

export function lazyView<P>(load: () => Promise<ComponentType<P>>): ComponentType<P> {
	let cached: ComponentType<P> | null = null;
	return function LazyView(props: P) {
		const [C, setC] = useState<ComponentType<P> | null>(cached);
		useEffect(() => {
			if (C) return;
			let live = true;
			load().then((c) => {
				cached = c;
				if (live) setC(() => c);
			});
			return () => {
				live = false;
			};
		}, [C]);
		if (!C) return <div class="st-page"><Spinner /></div>;
		return <C {...(props as P & object)} />;
	};
}
