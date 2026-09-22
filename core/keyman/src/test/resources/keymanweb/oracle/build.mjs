import * as esbuild from 'esbuild';
import path from 'node:path';
const K = path.resolve('../keyman');
await esbuild.build({
  entryPoints: [process.argv[2]], bundle: true, platform: 'node', format: 'esm',
  outfile: process.argv[3], conditions: ['es6-bundling'], logLevel: 'warning', preserveSymlinks: false, nodePaths: [path.resolve('node_modules')],
  alias: {
    '@keymanapp/keyman-version': path.resolve('keyman-version.ts'),
    '@keymanapp/ldml-keyboard-constants': K + '/core/include/ldml/keyman_core_ldml.ts',
  },
  plugins: [{ name: 'stub-schemas', setup(b) {
    b.onResolve({ filter: /\/schemas\// }, a => ({ path: a.path, namespace: 'stub' }));
    b.onLoad({ filter: /.*/, namespace: 'stub' }, () => ({ contents: 'export default {}; export const __stub = 1;', loader: 'js' }));
  } }],
  tsconfigRaw: { compilerOptions: { experimentalDecorators: true } },
});
