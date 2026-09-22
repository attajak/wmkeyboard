# Khmer Angkor regression suite

Copied unchanged from the keyboard's own upstream tests, except that the two
files named in Khmer were renamed to ASCII (`ស្ប៊ី.xml` to `sbii.xml`, `ហ្វ៊ី.xml` to
`hvii.xml`) so no filesystem's Unicode normalisation can lose them.

Upstream: https://github.com/keymanapp/keyboards
Path:     release/k/khmer_angkor/extras/regression_tests
Fetched:  2026-09-23
Licence:  MIT, the same as the keyboard; see `kmx/khmer_angkor.LICENSE.md`.

Each file is Keyman Developer's regression-test format: a list of keystrokes
with the text the field must hold after each one. They were written against
Keyman for Windows, which is why `KhmerAngkorRegressionTest` runs them with
desktop platform words.
