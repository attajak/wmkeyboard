# Release notes

One file per `wmkb.versionName`, named `<version>.md`. This is the prose at the
top of the GitHub release for that version — the "What's new" block, which
`.github/scripts/release-notes.sh` drops in verbatim under a `<details open>`
and follows with the download grid.

Write it in `###` sections: the script supplies the `<h2>` above it, so a file
that starts with its own `##` ends up with two headings.

A release cut without a file here still publishes — the script falls back to
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, which is a
paragraph. That is fine for a bug-fix release and thin for anything else.

Files for 0.1.0 through 0.5.8 were written after the fact, from the commits in
each tag's range, so they describe what shipped rather than what the release
originally said. 0.1.0 and 0.2.0 predate the store releases and carry no
`versionCode` in `gradle.properties` — the version lived in `app/build.gradle.kts`
until 0.3.0.
