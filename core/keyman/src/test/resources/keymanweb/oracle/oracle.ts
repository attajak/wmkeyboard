// KeymanWeb oracle: drives random touch-key walks through the real KMW
// JSKeyboardProcessor, in the headless InputProcessor's order, and prints one
// JSON line per keystroke. Usage: node oracle.mjs <keyboard.js> <outdir> <walks> <steps> <seed>
import vm from 'node:vm';
import fs from 'node:fs';
import { JSKeyboardInterface, JSKeyboardProcessor } from 'keyman/engine/js-processor';
import { MinimalKeymanGlobal, DefaultOutputRules, SyntheticTextStore, KeyboardHarness, SystemStoreIDs } from 'keyman/engine/keyboard';
import { DeviceSpec, KMWString } from 'keyman/common/web-utils';
// KeymanEngineBase does this at startup for every page; a bare processor does not.
KMWString.enableSupplementaryPlane(true);

const [,, kbdPath, outDir, walksArg, stepsArg, seedArg] = process.argv;
const walks = +walksArg, steps = +stepsArg;
let seed = +seedArg >>> 0;
const rand = () => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed / 4294967296; };
const pick = <T>(a: T[]) => a[Math.floor(rand() * a.length)];

const serializer = { loadStore: () => undefined as any, saveStore: () => {} };
const global = vm.createContext({});
global.String = String;
const kbdInterface = new JSKeyboardInterface(global, MinimalKeymanGlobal, serializer as any);
kbdInterface.install();
new vm.Script(fs.readFileSync(kbdPath, 'utf8')).runInContext(global);
const keyboard: any = kbdInterface.loadedKeyboard;
if (!keyboard) throw new Error('keyboard did not load');

const rawKVKL = keyboard.scriptObject['KVKL'];
if (!rawKVKL) { console.error('NO_KVKL'); process.exit(3); }
const platformName = rawKVKL.phone ? 'phone' : (rawKVKL.tablet ? 'tablet' : null);
if (!platformName) { console.error('NO_TOUCH_PLATFORM'); process.exit(3); }
fs.writeFileSync(outDir + '/touch.json', JSON.stringify({ [platformName]: rawKVKL[platformName] }));
const raw = JSON.parse(JSON.stringify(rawKVKL[platformName]));

const device = new DeviceSpec('native', platformName === 'phone' ? 'phone' : 'tablet', 'android', true);
const processor = new JSKeyboardProcessor(device, {
  keyboardInterface: kbdInterface, defaultOutputRules: new DefaultOutputRules(), baseLayout: 'us',
} as any);
processor.activeKeyboard = keyboard;
const layout = keyboard.layout(device.formFactor);

const SKIP = new Set(['K_ENTER', 'K_LOPT', 'K_ROPT', 'K_TAB', 'K_TABBACK', 'K_TABFWD', '']);
const DIRS = ['n', 's', 'e', 'w'];
function choices(layerId: string) {
  const layer = raw.layer.find((l: any) => l.id === layerId) || raw.layer.find((l: any) => l.id === 'default');
  const out: any[] = [];
  layer.row.forEach((row: any, r: number) => row.key.forEach((key: any, k: number) => {
    const sp = +(key.sp || 0);
    if (sp === 9 || sp === 10 || SKIP.has(key.id || '')) return;
    out.push({ L: layer.id, r, k, g: 'k' });
    (key.sk || []).forEach((sub: any, i: number) => { if (sub.id && !SKIP.has(sub.id)) out.push({ L: layer.id, r, k, g: 'sk', i }); });
    for (const d of DIRS) if (key.flick && key.flick[d] && key.flick[d].id) out.push({ L: layer.id, r, k, g: 'f', i: d });
  }));
  return out;
}

function activeKeyFor(c: any): any {
  const layer = layout.getLayer(c.L);
  const key = layer.row[c.r].key[c.k];
  if (c.g === 'k') return key;
  if (c.g === 'sk') return key.sk[c.i];
  return key.flick[c.i];
}

const lines: string[] = [];
for (let w = 0; w < walks; w++) {
  const store = new SyntheticTextStore('');
  processor.resetContext(store);
  for (let s = 0; s < steps; s++) {
    const start = processor.layerId;
    const opts = choices(start);
    if (!opts.length) break;
    const c = pick(opts);
    const ak = activeKeyFor(c);
    let ev: any;
    try { ev = keyboard.constructKeyEvent(ak, device, processor.stateKeys); }
    catch (e) { lines.push(JSON.stringify({ w, s, error: 'construct ' + e })); continue; }
    let action: any = null;
    try {
      action = processor.processKeystroke(ev, store);
      if (ev.kNextLayer) processor.selectLayer(ev);
      if (action) processor.finalizeProcessorAction(action, store);
      const changed = (action && action.setStore[SystemStoreIDs.TSS_LAYER] !== undefined) || ev.kNextLayer;
      processor.newLayerStore.set(changed ? processor.layerId : '');
      processor.oldLayerStore.set(changed ? start : '');
      const post = processor.processPostKeystroke(device, store);
      if (post) processor.finalizeProcessorAction(post, store);
    } catch (e) { lines.push(JSON.stringify({ w, s, error: 'process ' + e })); break; }
    lines.push(JSON.stringify({ w, s, start, ...c, id: ak.id, text: store.getText(), layer: processor.layerId, dks: (store.deadkeys() as any).toSortedArray().map((d: any) => d.p + ':' + d.d).join(' ') }));
  }
}
fs.writeFileSync(outDir + '/walks.jsonl', lines.join('\n') + '\n');
console.log('ok', lines.length);
