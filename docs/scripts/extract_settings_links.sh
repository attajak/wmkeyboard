#!/usr/bin/env bash
# Regenerate src/data/settings-links.json from the app's settings search index.
#
# The dump is written by SettingsLinksDump (app/src/test), a JUnit "test" that
# only runs when WM_SETTINGS_LINKS_OUT is set. It builds the real index on the
# JVM from strings*.xml, so every screen and row comes out with the route and
# resource name a wmkeyboard:// link needs. <SettingsPath> reads the result.
#
# Run from docs/:  scripts/extract_settings_links.sh
# Rerun whenever SettingsSearch.kt or a settings title changes.
set -euo pipefail
DOCS="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$DOCS/.." && pwd)"
OUT="$DOCS/src/data/settings-links.json"

if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

cd "$REPO"
WM_SETTINGS_LINKS_OUT="$OUT" ./gradlew :app:testFullIntlDebugUnitTest \
  --tests 'com.wasimaster.wmkeyboard.app.SettingsLinksDump' --rerun
test -s "$OUT" || { echo "no dump written to $OUT" >&2; exit 1; }
echo "wrote $OUT ($(grep -c '"title"' "$OUT") entries)"
