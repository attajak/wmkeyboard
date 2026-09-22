package com.wasimaster.wmkeyboard.app.updates

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The five things an asset's file name says about it.
 *
 * The release workflow builds these names, in `.github/workflows/release.yml`:
 * `wmkeyboard-<versionName>-vc<versionCode>-<flavor>-<languages>-<abi>.apk`,
 * where [flavor] is the `capabilities` flavour (full, lite) and [languages] the
 * `languages` one (intl, en). Releases before 0.5.10 had no languages part and
 * were English only, so a name without one reads as `en`. Reading the version
 * out of the name rather than out of the tag is what lets this compare
 * versions exactly: the tag is a version *name*, and names are not ordered.
 */
internal data class AssetName(
    val versionName: String,
    val versionCode: Int,
    val flavor: String,
    val abi: String,
    val languages: String = ReleaseAssets.LANGUAGES_EN,
)

/** An update this device could actually install, with everything needed to do it. */
@Serializable
internal data class UpdateCandidate(
    val versionCode: Int,
    val versionName: String,
    val tag: String,
    val assetName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String?,
    val publishedAtMillis: Long?,
    val releaseUrl: String,
) {
    /** Where this version's long-form release notes live. */
    val releaseNotesUrl: String get() = GithubReleases.releaseNotesUrl(tag, versionName)

    /** The short store changelog, read only when [releaseNotesUrl] is not there. */
    val changelogUrl: String get() = GithubReleases.changelogUrl(tag, versionCode)
}

/** Reading release assets, and choosing the one this device wants. */
internal object ReleaseAssets {

    /**
     * The universal APK, which carries every ABI and is the fallback for a
     * device whose own ABI has no split. Twice the size, and always correct.
     */
    const val UNIVERSAL = "universal"

    /** The `languages` flavour that carries English only. GitHub is the only place it ships. */
    const val LANGUAGES_EN = "en"

    /** The `languages` flavour that carries every translation. */
    const val LANGUAGES_INTL = "intl"

    /**
     * Anchored, and the version part is lazy so that a pre-release name like
     * `wmkeyboard-0.6.0-beta.1-vc15-full-intl-arm64-v8a.apk` splits at the
     * right hyphen. The languages part is optional, for the names releases
     * carried before it existed. The ABI list is spelled out rather than
     * matched loosely so a file this app cannot use never looks like one it can.
     */
    private val ASSET = Regex(
        """^wmkeyboard-(.+?)-vc(\d+)-(full|lite)(?:-(intl|en))?-(arm64-v8a|armeabi-v7a|x86_64|universal)\.apk$""",
    )

    private val SHA256 = Regex("""^[0-9a-f]{64}$""")

    /** What [name] says about itself, or null when it is not one of our APKs. */
    fun parseAssetName(name: String): AssetName? {
        val match = ASSET.matchEntire(name) ?: return null
        val (versionName, versionCode, flavor, languages, abi) = match.destructured
        val code = versionCode.toIntOrNull() ?: return null
        return AssetName(versionName, code, flavor, abi, languages.ifEmpty { LANGUAGES_EN })
    }

    /**
     * The hex digest in a `sha256:…` value, or null when there is not one.
     *
     * Strict about the prefix: a value in some other form is an unknown
     * algorithm, and treating its bytes as a SHA-256 would fail every download
     * rather than skip the check.
     */
    fun sha256Of(digest: String?): String? {
        val value = digest ?: return null
        if (!value.startsWith(SHA256_PREFIX)) return null
        return value.removePrefix(SHA256_PREFIX).lowercase().takeIf { SHA256.matches(it) }
    }

    /**
     * The asset in [release] this device should download, or null when the
     * release carries nothing for it.
     *
     * [supportedAbis] is [android.os.Build.SUPPORTED_ABIS], which is ordered
     * by the device's own preference, so the first one with a matching asset
     * is the right answer. The universal APK is the last resort, and it is why
     * a 32-bit x86 device is not left out even though no x86 split is built.
     *
     * [flavor] and [languages] are the two flavour names on their own
     * (`BuildConfig.FLAVOR_capabilities` and `FLAVOR_languages`), never the
     * combined `BuildConfig.FLAVOR`, which reads `fullIntl` and matches nothing.
     */
    fun pickAsset(
        release: GithubRelease,
        flavor: String,
        supportedAbis: List<String>,
        languages: String = LANGUAGES_EN,
    ): GithubAsset? {
        val ours = release.assets.mapNotNull { asset ->
            parseAssetName(asset.name)
                ?.takeIf { it.flavor == flavor && it.languages == languages }
                ?.let { it to asset }
        }
        if (ours.isEmpty()) return null
        for (abi in supportedAbis) {
            ours.firstOrNull { it.first.abi == abi }?.let { return it.second }
        }
        return ours.firstOrNull { it.first.abi == UNIVERSAL }?.second
    }

    /**
     * The newest release worth offering, or null when this install is current.
     *
     * Drafts never reach an unauthenticated caller, so filtering them is
     * belt and braces; pre-releases do, and whether to offer them is the
     * user's setting. A release that carries no file for this device is
     * skipped rather than offered and then failed, which is why this walks the
     * list instead of only looking at the first entry.
     *
     * [allowSameVersion] is for moving to the other [languages] build of the
     * release already installed: same version code, same key, a different APK.
     */
    @Suppress("LongParameterList")
    fun chooseCandidate(
        releases: List<GithubRelease>,
        installedVersionCode: Int,
        flavor: String,
        supportedAbis: List<String>,
        includePrereleases: Boolean,
        languages: String = LANGUAGES_EN,
        allowSameVersion: Boolean = false,
    ): UpdateCandidate? = releases
        .asSequence()
        .filterNot { it.draft }
        .filter { includePrereleases || !it.prerelease }
        .mapNotNull { release ->
            val asset = pickAsset(release, flavor, supportedAbis, languages) ?: return@mapNotNull null
            val parsed = parseAssetName(asset.name) ?: return@mapNotNull null
            UpdateCandidate(
                versionCode = parsed.versionCode,
                versionName = parsed.versionName,
                tag = release.tagName,
                assetName = asset.name,
                url = asset.browserDownloadUrl,
                sizeBytes = asset.size,
                sha256 = sha256Of(asset.digest),
                publishedAtMillis = publishedAtMillis(release.publishedAt),
                releaseUrl = release.htmlUrl ?: GithubReleases.releasePage(release.tagName),
            )
        }
        .filter {
            val newEnough = if (allowSameVersion) {
                it.versionCode >= installedVersionCode
            } else {
                it.versionCode > installedVersionCode
            }
            newEnough && it.url.startsWith("https://")
        }
        .maxByOrNull { it.versionCode }

    /**
     * GitHub's `published_at` as epoch milliseconds, or null.
     *
     * [SimpleDateFormat] rather than `java.time`: minSdk is 24, this project
     * has no core library desugaring, and `NewApi` is an error.
     */
    fun publishedAtMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        return runCatching { format.parse(iso)?.time }.getOrNull()
    }

    /**
     * How many days old [publishedAtMillis] is, or -1 when it is unknown.
     *
     * -1 rather than 0 because [UpdatePolicy] reads a negative staleness as
     * "no idea" and refuses to prompt on it, which is the right answer for a
     * release whose date did not parse.
     */
    fun stalenessDays(publishedAtMillis: Long?, now: Long): Int {
        if (publishedAtMillis == null || now < publishedAtMillis) return -1
        return ((now - publishedAtMillis) / DAY_MILLIS).toInt()
    }

    /**
     * The digest [assetName] is listed with in a `SHA256SUMS.txt` body, or
     * null. The file is `sha256sum` output: one `<hex>  <name>` line per file.
     */
    fun sha256From(checksums: String, assetName: String): String? = checksums
        .lineSequence()
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("""\s+"""), limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val hex = parts[0].lowercase()
            val name = parts[1].removePrefix("*").trim()
            if (name == assetName && SHA256.matches(hex)) hex else null
        }
        .firstOrNull()

    private const val SHA256_PREFIX = "sha256:"

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
}
