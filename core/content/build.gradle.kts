plugins {
    alias(libs.plugins.android.library)
    id("wmkeyboard.detekt")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.wasimaster.wmkeyboard.content"
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        extraWarnings.set(true)
        freeCompilerArgs.addAll("-Xreport-all-warnings")
        allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").map { it.toBoolean() }.orElse(false))
    }
}

dependencies {
    api(project(":core:tools"))
    api(project(":core:common"))
    // Numeral tables, FancyStyles and the Avro rules for the selection macros.
    api(project(":core:language"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Reading Espanso match files. Loader only, always under SafeConstructor;
    // see the version catalog and EspansoYaml.
    implementation(libs.snakeyaml)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    // Reads the frames of an APNG, which is what an animated Signal sticker
    // is. Android decodes only the first one. See ApngFrames.
    implementation(libs.apng4android.apng)
    // A supertype of that library's drawable. See the version catalog.
    compileOnly(libs.androidx.vectordrawable.animated)
    // Background removal in the sticker editor. Full only: lite ships a stub
    // with the same signatures, and its editor simply has no cutout button.
    "fullImplementation"(libs.mlkit.subject.segmentation)
    // The same removal without Play services: U2-Net-P on the interpreter
    // :core:voice already links for Whisper, so this adds nothing to the APK.
    "fullImplementation"(libs.litert)
}
