# KeymanWeb recordings

What KeymanWeb itself types, keystroke by keystroke, for sixteen keyboards.
`KeymanWebConformanceTest` replays each recording through our converter, our
`.kmx` engine and a mirror of the IME's Keyman path, and fails on any
difference in the text or the layer after any keystroke.

Each directory holds the keyboard's `.kmx` and `LICENSE.md` (MIT, from the
keyboard's package on downloads.keyman.com and from keymanapp/keyboards), the
touch layout KeymanWeb ran (`touch.json`, the compiled keyboard's `KVKL`), and
`walks.jsonl`: random walks over that layout — keys, long presses and
four-way flicks from whichever layer was showing — with the text, the layer and
the deadkey positions after each keystroke.

The keyboards were chosen for the behaviour each pins down: cluster
normalisation and PostKeystroke (khmer_angkor), a right-Alt layer
(lao_2008_basic), deadkeys and chirality (basic_kbdfr), key branches shadowing
later rules (sil_moore), deadkeys across backspace (libtralo, basic_kbdgkl,
bj_naskapi_common), two spacebars on one layer (wakhi), `U_` keys and caps that
look like special names (obolo_chwerty, landuma), character rules on shifted
space (sil_dzongkha), `T_` keys that type and switch layers
(fv_southern_carrier), frame keys with a `layer` of their own
(sil_cameroon_qwerty), astral-plane text (basic_kbdadlm), deadkeys in `context()`
output (gautami_devanagari) and a plain deadkey keyboard (sil_euro_latin).

## How they were recorded

`oracle/` holds the recorder: a Node script that bundles KeymanWeb's own
`JSKeyboardProcessor` from keymanapp/keyman (commit 8310abbc833e) with esbuild
and drives it in the order of its headless `InputProcessor`: rules, the key's
`nextlayer`, the rule's store changes, `&newLayer`/`&oldLayer`, PostKeystroke.
Supplementary-plane string handling is switched on, as KeymanWeb's engine does
at startup. `record.sh <keyboard> <walks> <keys>` fetches a package and records.
Setting `KEYMAN_ORACLE` to a directory of such recordings runs the same test
over all of them; over the 811 bundled keyboards whose packages carry a `.kmx`,
800 match on every keystroke and the other 11 use `$keymanonly:` or
`$keymanweb:` rules, which by design differ between the `.kmx` and the `.js`.
