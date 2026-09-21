import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    id("wmkeyboard.detekt")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Same channel flag :app reads. It decides where the LiteRT-LM runtime lives:
// non-Play full builds compile src/llmbridge (and the litertlm dependency)
// straight into this module, exactly as before the split; Play builds leave
// both out of the base APK — the on-demand :feature:llm module carries them
// instead, so the ~20 MB per ABI runtime only reaches devices that use
// On-device AI. See LlmRuntime.kt for the seam.
val playStoreChannel = run {
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    (providers.gradleProperty("wmkb.enablePlayStore").orNull
        ?: localProperties.getProperty("wmkb.enablePlayStore")
        ?: System.getenv("WMKB_ENABLE_PLAY_STORE")
        ?: "false").toBoolean()
}

android {
    namespace = "com.wasimaster.wmkeyboard.intelligence"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 24
    }

    flavorDimensions += "capabilities"
    productFlavors {
        create("full") { dimension = "capabilities" }
        create("lite") { dimension = "capabilities" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }
    lint { lintConfig = rootProject.file("config/lint/lint.xml") }
}

androidComponents {
    onVariants { variant ->
        // Static-source-dir mechanism rather than a flavour folder for the
        // same AGP 9 reason as the channel seams in :app — and conditional,
        // which a flavour folder cannot be.
        if (!playStoreChannel && variant.flavorName == "full") {
            variant.sources.kotlin?.addStaticSourceDirectory("src/llmbridge/java")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        extraWarnings.set(true)
        freeCompilerArgs.addAll("-Xreport-all-warnings")
        allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").map { it.toBoolean() }.orElse(false))
    }
}

dependencies {
    api(project(":core:settings"))
    api(project(":core:prediction"))
    api(project(":core:language"))
    api(project(":core:common"))
    api(project(":core:config"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    "fullImplementation"(libs.mlkit.digital.ink)
    // On-device translation for the translate tool, and the bundled language
    // identifier that stands in for the online services' "detect language":
    // ML Kit's translator has to be told what it is reading.
    "fullImplementation"(libs.mlkit.translate)
    "fullImplementation"(libs.mlkit.language.id)
    if (!playStoreChannel) {
        "fullImplementation"(libs.litertlm.android)
    }

    testImplementation(libs.junit)
}
