// JNI surface over Tesseract for the keyboard's scan text tool.
// Kotlin side: com.wasimaster.wmkeyboard.core.ocr.TesseractNative.
//
// Deliberately small: create an engine for a language set, read one bitmap,
// cancel, destroy. Nothing else in Tesseract or Leptonica is exported, which
// is what lets the linker drop everything the calls below never reach.

#include <android/bitmap.h>
#include <jni.h>

#include <atomic>
#include <memory>
#include <string>

#include <tesseract/baseapi.h>
#include <tesseract/ocrclass.h>

namespace {

// Camera captures carry no resolution. Without one Tesseract guesses 70 dpi
// and prints a warning for every page; phone photos of print are closer to
// 300.
constexpr int kSourceDpi = 300;

struct Engine {
    tesseract::TessBaseAPI api;
    std::atomic<bool> cancelled{false};
};

bool IsCancelled(void* engine, int /* words */) {
    return static_cast<Engine*>(engine)->cancelled.load();
}

std::string ToString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
}

Engine* FromHandle(jlong handle) {
    return reinterpret_cast<Engine*>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" {

// Returns 0 when the language data is missing or unreadable.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_ocr_TesseractNative_nativeCreate(
    JNIEnv* env, jclass, jstring data_dir, jstring languages) {
    auto engine = std::make_unique<Engine>();
    const std::string dir = ToString(env, data_dir);
    const std::string langs = ToString(env, languages);
    if (engine->api.Init(dir.c_str(), langs.c_str(), tesseract::OEM_LSTM_ONLY) != 0) {
        return 0;
    }
    engine->api.SetPageSegMode(tesseract::PSM_AUTO);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(engine.release()));
}

// Reads an ARGB_8888 bitmap. Returns the text with words split by spaces and
// lines by newlines, or null when the bitmap is unusable or the read was
// cancelled.
JNIEXPORT jstring JNICALL
Java_com_wasimaster_wmkeyboard_core_ocr_TesseractNative_nativeRecognize(
    JNIEnv* env, jclass, jlong handle, jobject bitmap) {
    Engine* engine = FromHandle(handle);
    if (engine == nullptr) return nullptr;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return nullptr;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return nullptr;
    }
    // SetImage copies the pixels, so the bitmap can be unlocked straight away.
    // ARGB_8888 is R, G, B, A in memory, which is the order Tesseract reads.
    engine->api.SetImage(static_cast<const unsigned char*>(pixels),
                         static_cast<int>(info.width), static_cast<int>(info.height),
                         4, static_cast<int>(info.stride));
    AndroidBitmap_unlockPixels(env, bitmap);
    engine->api.SetSourceResolution(kSourceDpi);

    engine->cancelled.store(false);
    tesseract::ETEXT_DESC monitor;
    monitor.cancel = IsCancelled;
    monitor.cancel_this = engine;
    const bool ok = engine->api.Recognize(&monitor) == 0 && !engine->cancelled.load();

    jstring result = nullptr;
    if (ok) {
        std::unique_ptr<char[]> text(engine->api.GetUTF8Text());
        if (text != nullptr) result = env->NewStringUTF(text.get());
    }
    engine->api.Clear();
    return result;
}

// Safe from any thread while nativeRecognize runs on another.
JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_ocr_TesseractNative_nativeCancel(
    JNIEnv*, jclass, jlong handle) {
    Engine* engine = FromHandle(handle);
    if (engine != nullptr) engine->cancelled.store(true);
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_ocr_TesseractNative_nativeDestroy(
    JNIEnv*, jclass, jlong handle) {
    delete FromHandle(handle);
}

JNIEXPORT jstring JNICALL
Java_com_wasimaster_wmkeyboard_core_ocr_TesseractNative_nativeVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF(tesseract::TessBaseAPI::Version());
}

}  // extern "C"
