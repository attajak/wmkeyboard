#!/usr/bin/env bash
# Lightweight emulator harness for WM Keyboard.
#
#   scripts/emu.sh boot          # start (or reuse) the AVD, wait for boot
#   scripts/emu.sh install       # build fullIntlDebug, install, restore the IME
#   scripts/emu.sh ime           # make WM Keyboard the enabled + default IME
#   scripts/emu.sh shot [file]   # wake, screencap to scratch (default /tmp/emu.png)
#   scripts/emu.sh logcat [tag]  # tail the app's logcat
#   scripts/emu.sh stop          # save a quick-boot snapshot and shut down
#   scripts/emu.sh run           # boot + install + ime, the everyday command
#
# The AVD is API 30 (google_apis, arm64) so it also stands in for a mid-range
# device; the physical phone stays the final word on SDK 36 behaviour.

set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVD="${WM_AVD:-wmphone}"
PORT="${WM_EMU_PORT:-5554}"
SERIAL="emulator-$PORT"
PKG="com.wasimaster.wmkeyboard"
IME="$PKG/.ime.WMKeyboardService"
# English-only by default: the intl flavour packs 48 locales (+38 MB of arsc)
# that an emulator smoke test never reads. WM_VARIANT=FullIntlDebug for those.
VARIANT="${WM_VARIANT:-FullEnDebug}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

adb() { "$ADB" -s "$SERIAL" "$@"; }

booted() { "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q 1; }

boot() {
  if booted; then echo "emulator already up on $SERIAL"; return; fi
  echo "booting $AVD on port $PORT..."
  "$EMULATOR" -avd "$AVD" -port "$PORT" \
    -no-audio -no-boot-anim -gpu host -netdelay none -netspeed full \
    >/tmp/wm-emulator.log 2>&1 &
  "$ADB" -s "$SERIAL" wait-for-device
  for _ in $(seq 1 180); do booted && break; sleep 1; done
  booted || { echo "boot timed out; see /tmp/wm-emulator.log" >&2; exit 1; }
  # Animations off: faster, and blind tap sequences stop racing transitions.
  adb shell settings put global window_animation_scale 0
  adb shell settings put global transition_animation_scale 0
  adb shell settings put global animator_duration_scale 0
  adb shell input keyevent 82   # dismiss the lock screen
  echo "booted."
}

ime() {
  # Straight after `install -r` the service is not registered yet, so a bare
  # `ime enable` no-ops and the default silently stays Gboard. Wait for it.
  local i cur
  for i in $(seq 1 30); do
    adb shell ime list -a -s | tr -d '\r' | grep -qx "$IME" && break
    sleep 1
  done
  for i in $(seq 1 5); do
    adb shell ime enable "$IME" >/dev/null 2>&1
    adb shell ime set "$IME" >/dev/null 2>&1
    sleep 1
    cur=$(adb shell settings get secure default_input_method | tr -d '\r')
    case "$cur" in *wmkeyboard*) echo "default IME: $cur"; return 0;; esac
  done
  echo "IME did not stick (still $cur)" >&2
  exit 1
}

install() {
  ( cd "$REPO" && JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}" \
      ./gradlew "assemble$VARIANT" )
  # assembleFullEnDebug -> app/build/outputs/apk/fullEn/debug/app-full-en-debug.apk
  local flavour; flavour=$(echo "${VARIANT%Debug}" | perl -pe 's/^(.)/lc($1)/e')
  local apk; apk=$(ls -t "$REPO/app/build/outputs/apk/$flavour/debug/"*.apk 2>/dev/null | head -1)
  [ -n "$apk" ] || { echo "no apk under app/build/outputs/apk/$flavour/debug/" >&2; exit 1; }
  # install -r hands the default IME back to Gboard every time; ime() takes it back.
  adb install -r "$apk"
  ime
}

shot() {
  local out="${1:-/tmp/emu.png}"
  adb shell input keyevent 224 >/dev/null   # WAKEUP; the screen blanks when idle
  adb exec-out screencap -p > "$out"
  local size; size=$(wc -c < "$out")
  echo "$out ($size bytes)"
  [ "$size" -lt 40000 ] && echo "warning: under 40 KB, probably a blank screen" >&2
  return 0
}

case "${1:-run}" in
  boot) boot ;;
  ime) ime ;;
  install) boot; install ;;
  shot) shot "${2:-}" ;;
  logcat) adb logcat --pid="$(adb shell pidof "$PKG" | tr -d '\r')" "${2:-*:D}" ;;
  # Never `am force-stop` the package: it is the live IME, so the system falls
  # back to Gboard mid-flow. Killing the emulator is fine.
  stop) adb emu avd snapshot save default_boot >/dev/null 2>&1 || true; adb emu kill ;;
  run) boot; install ;;
  *) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 1 ;;
esac
