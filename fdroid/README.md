# F-Droid build recipe

`com.wasimaster.wmkeyboard.yml` is the recipe F-Droid builds the app from. It is
**not** read by F-Droid from this repository: it is copied to
`metadata/com.wasimaster.wmkeyboard.yml` inside a fork of
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) and submitted as a merge
request. The copy lives here so the recipe is versioned next to the code that
has to keep satisfying it. The walk-through is in
[docs/development/releasing](../docs/src/content/docs/development/releasing.mdx).

The file itself carries no comments, and it cannot. fdroiddata's CI runs
`fdroid rewritemeta` on every changed metadata file and fails the pipeline if
the result differs by a byte; `rewritemeta` strips comments and reorders fields.
So the recipe is kept in exactly the form `rewritemeta` emits, and everything
that would have been a comment is written down here instead.

## Why each field is what it is

**`gradle: [lite]`** — the lite flavour. Every proprietary dependency in the
tree (ML Kit, LiteRT, Play in-app updates, `play-services-auth`) is declared
`fullImplementation` or sits behind the `enablePlayStore`/`enableGms` flags, so
this variant has no Google artifact on its compile classpath at all.

It names one flavour although `:app` has had two dimensions since 0.5.10, and
that is deliberate. fdroidserver turns the list into `assembleLiteRelease`,
which AGP no longer registers, so `app/build.gradle.kts` registers it as an
alias: it builds `assembleLiteIntlRelease`, the `lite` build with all 48
interface languages, and mirrors the APK to `app/build/outputs/apk/lite/release/`,
the only flavoured directory fdroidserver will look in for a recipe that says
`lite`. The alternative, listing `lite` and `intl`, is a merge request to
fdroiddata that has to land before their bot writes the next entry, because
`AutoUpdateMode` copies the previous entry as it stands and a build of the old
list fails.

**`gradleprops`** — the first two default to `false` when `local.properties` is
absent, which it is in a clean checkout; they are stated anyway so the recipe
does not depend on that. `wmkb.enableFdroid=true` is not a default and has to be
set here. It is what tells the built app it is an F-Droid install, which decides
the channel line on bug reports and diagnostics, and which suppresses the "get it
on F-Droid" row in About that would otherwise point an F-Droid user at their own
install.

**`scandelete`** — prebuilt native libraries belonging to variants this build
does not produce. The Harper grammar engine lives in the full source set (its
Rust sources are under `native/harper-jni`), and the LLM module is only assembled
for the Play channel. Deleting them keeps F-Droid's scanner quiet and proves
neither reaches the built APK.

**`prebuild`** — two `sed` commands, both for the source scanner. It is a text
search over the build files, run before the build and fatal on a match, and it
does not read build logic, so it flags strings this build never resolves:

* `org.gradle.toolchains.foojay-resolver-convention` in `settings.gradle.kts`,
  which would fetch a JDK over the network. Their tooling deletes the
  `gradle-daemon-jvm.properties` the plugin maintains, and auto-provisioning is
  switched off on their builders regardless, so the plugin can do nothing there
  but fail the scan. **Consequence worth keeping in mind: with foojay gone, any
  `jvmToolchain(n)` in the tree is fatal unless `n` is a JDK their image already
  carries.** `:tools:dictc` learned this the hard way in 0.5.4; it sets a
  jvmTarget instead now, which needs no particular JDK installed.
* `libs.play.app.update`, `libs.play.feature.delivery` and
  `libs.play.services.auth` in `app/build.gradle.kts`. All three sit inside
  `if (playStoreChannel)` / `if (gmsChannel)` blocks, which are false for this
  recipe, so they are never on the compile classpath. The blocks are left empty
  by the `sed`, which is valid Kotlin.

Deleting the lines rather than listing the files in `scanignore` is deliberate:
`scanignore` asks a packager to take the build file on trust, and this way the
scanner reads a tree that genuinely does not mention them. `prebuild` runs before
the scanner (`prepare_source` precedes `scan_source` in fdroidserver's
`build.py`), with the working directory set to `subdir`, which is why the first
command reaches up with `../`.

The 0.5.4 entry carried two more, both since fixed in the source: one deleted the
`.takeIf` line their signing strip orphaned, and one deleted `jvmToolchain(17)`
from `:tools:dictc`. Neither is needed from 0.5.5 on. They are in this file's git
history if a build of an older tag ever has to be reproduced.

**One `Builds:` entry** — F-Droid's buildserver builds every entry it has not
seen. Shipping the back catalogue in a first submission would spend their build
time on versions nobody can install any more. Entries for 0.3.0 through 0.5.3 are
in this file's git history.

**`commit:` is a full hash, never a tag** — a packager's first review comment
(linsui, on !48225). A tag can be moved after the build is accepted; a hash
cannot. You only write it by hand for the first entry: every later one is added
by their bot, which resolves the tag it found to a hash itself (`vcs.getref` in
fdroidserver's `checkupdates.py`).

**`Categories: Keyboard & IME`** — the same review. The valid names live in
fdroiddata's `config/categories.yml`; `Writing` there means word processors and
journaling, and a keyboard has a category of its own.

**`UpdateCheckMode: Tags` with a file in `UpdateCheckData`** — also suggested in
that review, and it corrects something this file used to claim. Tags mode *on its
own* scans the Gradle build file for a version literal, which `:app` does not
have — both values come from `gradle.properties` through
`providers.gradleProperty()`. But `UpdateCheckData` switches Tags mode to reading
a file instead: `gradle.properties|wmkb.versionCode=(\d+)|.|wmkb.versionName=(.+)`
is resolved **inside the repo as checked out at each tag**, and the `.` in the
third field means "that same file again". Verified by running `fdroid
checkupdates` against the real repo, which walked `v0.5.6` down to `v0.5.2` and
found the right version at each. The earlier recipe used HTTP mode against
`main` instead; Tags is better because it reads the version a tag actually
carries rather than whatever `main` says today.

**`AutoUpdateMode: Version`** — no `v%v` pattern needed, because in Tags mode the
bot already knows which tag it matched and uses that. Every release uses the same
build configuration, so the bot adds the next `Builds:` entry by copying this
one. Verify a release still satisfies the recipe before tagging (`fdroid
checkupdates` is what the bot runs).

**No `Name`, `Summary`, `Description`, icon or screenshots** — F-Droid reads
those from `fastlane/metadata/android/en-US/` in this repo, so they only have to
be right in one place. `AutoName` is written by `fdroid checkupdates`; leaving it
out makes fdroiddata's CI add it and fail on the resulting diff.

## Checking a change before submitting it

fdroiddata's CI runs four jobs against a changed metadata file, and all four can
be run locally. Two things have to be right first.

**Use fdroidserver from git master, and pin `ruamel.yaml` to 0.18.6.** Both
matter, and each cost a failed pipeline to find. Their CI installs Debian's
`fdroidserver` package first and then overlays master's source on `PATH`, so the
*code* is master but the *YAML library* is Debian trixie's. Homebrew's 2.4.5
wrote a `\` line continuation into the `AntiFeatures` string that master does
not; and ruamel 0.19 wraps at a different column than 0.18, which rewrites every
wrapped line. Either one is enough to fail the `rewritemeta` job, whose test is a
byte-for-byte diff. With master plus 0.18.6 the local output matches CI's
artifact exactly.

**`fdroid lint` needs fdroiddata's own `config/` directory** — the valid category
and anti-feature name lists live there, and without them lint rejects both. A
plain `git clone` of fdroiddata fails on some networks; the archive does not.

```sh
python3 -m venv /tmp/fdsvenv
/tmp/fdsvenv/bin/pip install git+https://gitlab.com/fdroid/fdroidserver.git check-jsonschema
/tmp/fdsvenv/bin/pip install 'ruamel.yaml==0.18.6'   # pin AFTER, see below
mkdir -p /tmp/fd && cd /tmp/fd
curl -sS --http1.1 -o config.tar.gz \
  'https://gitlab.com/fdroid/fdroiddata/-/archive/master/fdroiddata-master.tar.gz?path=config'
tar xzf config.tar.gz && mv fdroiddata-master-config/config .
curl -sS --http1.1 -o metadata.json \
  'https://gitlab.com/fdroid/fdroiddata/-/raw/master/schemas/metadata.json'
mkdir -p metadata && cp ~/Work/WMKeyboard/fdroid/com.wasimaster.wmkeyboard.yml metadata/
export PATH=/tmp/fdsvenv/bin:$PATH
fdroid lint com.wasimaster.wmkeyboard        # must exit 0 and print nothing
fdroid rewritemeta com.wasimaster.wmkeyboard # must leave the file unchanged
fdroid checkupdates --auto com.wasimaster.wmkeyboard  # must leave the file unchanged
check-jsonschema --schemafile metadata.json metadata/com.wasimaster.wmkeyboard.yml
```

The fifth job, `fdroid build`, runs the real build in an image provisioned like
the production buildserver. There is no local stand-in for it, but building the
tag in a clean worktree with no `local.properties` and the `scandelete`
directories removed is the same thing in miniature — see the F-Droid steps in
`docs/development/releasing`.

When `rewritemeta` does disagree with you, its job uploads what it wanted under
`tmp/` in the job artifacts, reachable at
`.../-/jobs/<job id>/artifacts/raw/tmp/com.wasimaster.wmkeyboard.yml`. Diffing
against that is faster than guessing.

## Why there is no `AntiFeatures` block

`NonFreeNet` was declared until 0.5.6, for the tools that reached proprietary
services. It is gone because every one of those tools now has a free,
self-hostable option the user can point at, which is the condition F-Droid's own
rule waives the anti-feature on: translate takes a LibreTranslate instance,
search a SearXNG one, photos and GIFs any MediaWiki, and the AI tool has always
taken an Ollama URL. Backups already offered WebDAV beside Dropbox.

Exchange rates needed no instance to point at, because two of their sources are
free end to end: currency-api (`fawazahmed0/exchange-api`) publishes CC0 tables
built by a public script, for fiat and coins alike, and Frankfurter is MIT and
serves European Central Bank rates. This file used to say coins had no free
source; that was wrong, currency-api always carried them. From 0.5.7 the
F-Droid build starts from those two (`CurrencyClient.Provider.fiatDefaults`,
`cryptoDefaults`), and a currency or weather chip on the suggestion strip waits
for a tap before it fetches anything (`RateSourceSettings.autoFetch` and
`WeatherSettings.autoFetch`, both off on F-Droid).
ExchangeRate-API, Coinbase and CoinGecko stay selectable, which is the same
additive shape as the other tools.

One thing still reaches a service with no free instance to swap in, inside an
optional feature that works without it: the **Keyman layout catalogue**
(`api.keyman.com`; the software is open source, the catalogue is theirs). Say
so plainly if a packager asks rather than letting them find it; if they judge
it disqualifying, the field goes back.

## Why there is no `TetheredNet` either

F-Droid applies `TetheredNet` to an app that "depends entirely on a service which
is impossible (or not easy) to replace", and waives it when there is "a simple
configuration option that allows pointing the app to a running instance of an
alternative, publicly available, self-hostable server solution". Wikipedia's own
app carries it because wikipedia.org is hardcoded, although MediaWiki is free
software.

So from 0.5.7, every address this build calls is a setting.
**Advanced › Servers** lists all of them, and each tool's own page shows the same
fields:

* every API: Wikipedia (any MediaWiki), the dictionary, Wiktionary and
  kaikki.org, Open-Meteo, the currency sources, Keyman's catalogue and downloads,
  the Noto animated emoji and the F-Droid repository the update check asks
  (IzzyOnDroid serves the same API). The keyed ones too, for a proxy or a
  compatible clone: Brave, KLIPY, GIPHY, Unsplash, Pexels, Google Translate and
  the cloud AI providers.
* every git repository it downloads from: the language data, the add-on and
  key-sound repositories, and the Espanso Hub, on GitHub, Forgejo or Gitea
  (Codeberg), GitLab, SourceHut, Bitbucket, or a plain HTTPS folder. The app
  builds each forge's raw-file address itself.

The code is `ServiceEndpoint`, `ServiceRepo` and `ServiceEndpoints` in
`:core:common`. The defaults are unchanged, nothing moves until someone edits a
field, and the other builds ignore the overrides entirely.

Google Drive, Dropbox and OneDrive backups are not in the list because this build
cannot use them at all: no Play services, and the recipe passes no client ids.
WebDAV, S3-compatible storage and a local folder were already configurable.

## A note on the wrapped strings

`rewritemeta` wraps any value too long for its line width, and the wrap leaves a
trailing space on each continued line it produces. That is its output, so it
cannot be cleaned up without failing the job, and it is harmless: YAML strips a
trailing space before a folded line break, so the string parses back with single
spaces and no newlines. Keep any long value free of real line breaks — an
earlier `AntiFeatures` description was written as a `|-` block and every wrap
point became a literal `\n` in the middle of a sentence.
