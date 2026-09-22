plugins {
    // By id, not version-catalog alias: AGP is already on the build classpath
    // through build-logic, and Gradle refuses a versioned plugin request for
    // a plugin the classpath already carries.
    id("com.android.dynamic-feature")
}

// The on-demand home of the LiteRT interpreter, Play builds only: :app lists
// this module in `dynamicFeatures` when wmkb.enablePlayStore is on, and no
// other build references it at all — direct-download APKs embed the same
// code in :core:voice and :core:content instead (see the playStoreChannel
// blocks there), and F-Droid builds are lite and have no LiteRT. Built
// exactly like :feature:translate beside it. The module has no sources of
// its own: it compiles the two shared bridge directories, one class each,
// which the base app reaches by reflection, plus the litert dependency.
// libtensorflowlite_jni.so is about 4 MB per ABI, and both things that use it
// are off by default: offline Whisper dictation is an engine the user picks,
// and the sticker editor's own background remover only runs where Play
// services cannot supply Google's, so an install that never touches either
// should not carry it.
android {
    namespace = "com.wasimaster.wmkeyboard.litertfeature"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 24
        // See :feature:llm: :app has a `languages` dimension and this module
        // does not, and Play takes the `en` bundle.
        missingDimensionStrategy("languages", "en", "intl")
        // bundletool requires every module with native libraries to support
        // the SAME ABI set, and LiteRT ships one more than the base does
        // (x86). These are the release base's three; see the `release` build
        // type in app/build.gradle.kts.
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // Must mirror the base app's dimension: each base variant needs a
    // matching variant here (fullRelease base -> fullRelease split).
    flavorDimensions += "capabilities"
    productFlavors {
        create("full") { dimension = "capabilities" }
        create("lite") { dimension = "capabilities" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint { lintConfig = rootProject.file("config/lint/lint.xml") }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

androidComponents {
    onVariants { variant ->
        // Only the full flavour has the LiteRT classes the bridges import; the
        // lite split stays empty (a few KB) and is never requested — the lite
        // app has neither Whisper nor a cutout button to ask for it.
        if (variant.flavorName == "full") {
            variant.sources.kotlin
                ?.addStaticSourceDirectory("../../core/voice/src/whisperbridge/java")
            variant.sources.kotlin
                ?.addStaticSourceDirectory("../../core/content/src/cutoutbridge/java")
        }
    }
}

dependencies {
    // Dynamic-feature convention: every feature depends on the base app.
    implementation(project(":app"))
    // Named directly, not inherited: a feature compiles against the base's
    // *api* surface only, and :app keeps its project modules internal. AGP's
    // feature packaging strips everything the base already carries, so this
    // puts no :core class in the split — it only makes WhisperRuntime,
    // WhisperVocab, CutoutRuntime, CutoutModel and the two R classes visible
    // to the bridge compiles.
    implementation(project(":core:voice"))
    implementation(project(":core:content"))
    "fullImplementation"(libs.litert)
}
