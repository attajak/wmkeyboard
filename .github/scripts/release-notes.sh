#!/usr/bin/env bash
# Builds the body of a GitHub release: highlights, a download grid with real
# file sizes, and a grouped changelog of every commit since the previous tag.
#
#   release-notes.sh <version> <versionCode> <tag> <dist-dir> [<owner/repo>]
#
# Writes markdown to stdout. Needs the repo checked out with full history
# (fetch-depth: 0) so the previous tag can be found.
set -euo pipefail

version=${1:?version}
code=${2:?versionCode}
tag=${3:?tag}
dist=${4:?dist directory}
repo=${5:-${GITHUB_REPOSITORY:-wasi-master/wmkeyboard}}

base="https://github.com/${repo}/releases/download/${tag}"
prefix="wmkeyboard-${version}-vc${code}"

# ---------------------------------------------------------------- helpers

# Human size for a file in dist/, or "not built" when the build did not
# produce it (lite has no native symbols, for one).
size_of() {
  local f="$dist/$1"
  [ -f "$f" ] || { printf 'not built'; return; }
  # stat's flags differ between BSD and GNU; wc -c is the same everywhere.
  local bytes
  bytes=$(wc -c < "$f" | tr -d ' ')
  awk -v b="$bytes" 'BEGIN {
    if (b >= 1073741824) printf "%.1f GB", b / 1073741824
    else if (b >= 1048576) printf "%.0f MB", b / 1048576
    else if (b >= 1024) printf "%.0f KB", b / 1024
    else printf "%d B", b
  }'
}

# A download cell: a linked size, or plain "not built" when the file is
# absent, so a missing artifact says so instead of offering a broken link.
cell() {
  local file="$1" emphasis="${2:-}" sz
  sz=$(size_of "$file")
  if [ "$sz" = 'not built' ]; then printf 'not built'; return; fi
  printf '%s[%s](%s/%s)%s' "$emphasis" "$sz" "$base" "$file" "$emphasis"
}

# ---------------------------------------------------------------- highlights

# Hand-written notes for this version win. Without them, fall back to the
# store changelog, which every release has to write anyway.
notes_file="release-notes/${version}.md"
store_changelog="fastlane/metadata/android/en-US/changelogs/${code}.txt"

# Open by default: a reader who wants the prose gets it without a click, and
# one who came for the download can fold it away.
echo '<details open>'
echo "<summary><h2>What's new in ${version}</h2></summary>"
echo
if [ -f "$notes_file" ]; then
  cat "$notes_file"
else
  if [ -f "$store_changelog" ]; then
    cat "$store_changelog"
  else
    echo "_No notes were written for this release._"
  fi
  echo
fi
echo
echo '</details>'
echo

# ---------------------------------------------------------------- downloads

# A release with no APKs attached, such as a tag cut before the build
# pipeline existed, gets notes and nothing else rather than an empty grid.
have_apk=false
for f in "$dist"/*.apk; do
  [ -e "$f" ] && have_apk=true && break
done

if [ "$have_apk" = true ]; then
cat <<EOF
## Download

Pick the row that matches your phone, then the build you want. **⭐ arm64-v8a is
the right answer for essentially every phone sold since 2017.** Take that row
unless you know you need another.

| Architecture | full | lite |
|:---|:---|:---|
| **⭐ arm64-v8a**<br><sub>Essentially every phone since 2017</sub> | **$(cell "${prefix}-full-arm64-v8a.apk")** | **$(cell "${prefix}-lite-arm64-v8a.apk")** |
| armeabi-v7a<br><sub>Older 32-bit phones and watches</sub> | $(cell "${prefix}-full-armeabi-v7a.apk") | $(cell "${prefix}-lite-armeabi-v7a.apk") |
| x86_64<br><sub>Emulators, ChromeOS, x86 tablets</sub> | $(cell "${prefix}-full-x86_64.apk") | $(cell "${prefix}-lite-x86_64.apk") |
| universal<br><sub>All three in one file. Only if the others refuse to install</sub> | $(cell "${prefix}-full-universal.apk") | $(cell "${prefix}-lite-universal.apk") |

**full** is the whole keyboard. **lite** leaves out ML Kit and the on-device
models (handwriting, OCR, document scanning, Whisper voice input and the local
LLM) for a far smaller download. Everything else is the same build.

EOF

# The extras block lists only what this release actually carries: mappings and
# native symbols were first attached in 0.5.8, so an older release has nothing
# under the checksums but the checksums.
extras=''
row() { # <file> <what it is>
  [ -f "$dist/$1" ] || return 0
  extras="${extras}| [\`$1\`](${base}/$1) | $(size_of "$1") | $2 |
"
}
row SHA256SUMS.txt 'Checksums for everything above'
row "${prefix}-full-mapping.txt.gz" 'R8 mapping, **full**. Retraces a stack trace'
row "${prefix}-lite-mapping.txt.gz" 'R8 mapping, **lite**'
row "${prefix}-full-native-symbols.zip" 'Native debug symbols, **full**'

if [ -n "$extras" ]; then
  # Name only what is in the block. Mappings and symbols were first attached in
  # 0.5.8, so an older release's block is checksums alone.
  if [ -f "$dist/${prefix}-full-mapping.txt.gz" ]; then
    summary='Checksums, R8 mappings and native symbols'
    blurb='For verifying a download, and for reading a crash report from these APKs.'
  else
    summary='Checksums'
    blurb='For verifying what you downloaded.'
  fi
  echo '<details>'
  echo "<summary><b>${summary}</b></summary>"
  echo
  echo "$blurb"
  echo
  echo '| File | Size | What it is |'
  echo '|:---|:---|:---|'
  printf '%s' "$extras"
  echo
  if [ -f "$dist/SHA256SUMS.txt" ]; then
    cat <<'EOF'
Verify a download before installing it:

```sh
sha256sum -c SHA256SUMS.txt --ignore-missing
```

EOF
  fi
  if [ -f "$dist/${prefix}-full-mapping.txt.gz" ]; then
    cat <<EOF
Retrace a crash with the mapping from the same flavour and version:

\`\`\`sh
gunzip -k ${prefix}-full-mapping.txt.gz
retrace ${prefix}-full-mapping.txt stacktrace.txt
\`\`\`

A crash from the Play build needs neither file: that build is compiled with
different flags, so R8 renames it differently, and Play Console reads the
mapping out of the bundle by itself.

EOF
  fi
  echo '</details>'
  echo
fi
fi

# ---------------------------------------------------------------- footer

# The grouped commit list that used to live here is gone: the prose above is
# the changelog, and the compare view is a better place to read every commit
# than a wall of subjects in the release body.
prev=$(git describe --tags --abbrev=0 "${tag}^" 2>/dev/null || true)
if [ -n "$prev" ]; then
  count=$(git log --no-merges --oneline "${prev}..${tag}" | wc -l | tr -d ' ')
  echo "${count} commits since [${prev#v}](https://github.com/${repo}/releases/tag/${prev}). [See every one](https://github.com/${repo}/compare/${prev}...${tag})."
fi
