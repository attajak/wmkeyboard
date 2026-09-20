package com.wasimaster.wmkeyboard.core.debug

import android.content.Context
import android.content.Intent
import android.os.Build
import com.wasimaster.wmkeyboard.app.CrashReportActivity
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Severity of a [LogEntry], in the order the viewer's filter steps through. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** One recorded line: when, how bad, what part of the app, and what happened. */
data class LogEntry(
    val timeMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/**
 * A small in-memory log the user can read and send back, plus a crash record
 * that survives the process.
 *
 * Why an app-side log at all, when Android has one: a keyboard's failures are
 * mostly invisible. The IME crashes and the platform silently swaps in another
 * keyboard; a panel comes up empty; a model does not load. None of that reaches
 * the user as an error they can quote, and getting a logcat off an Android phone
 * needs a computer and developer mode. So the interesting moments are recorded
 * here, and the About screen can show and share them.
 *
 * Two stores, on purpose:
 *
 *  * The **ring** is in memory, [CAPACITY] entries, shared by the keyboard
 *    service and the settings app when they are in the same process. Cheap
 *    enough to leave always on — there is no switch to forget to turn on before
 *    reproducing a problem, which is the failure mode of every opt-in log.
 *  * The **crash file** is the one thing written to disk, because a crash is
 *    exactly the case where the ring dies with the process. It lives in
 *    device-protected storage so a crash on the lock screen — where a keyboard
 *    spends some of its most important seconds — is still recorded.
 *
 * Nothing typed is ever logged. Call sites pass what happened, never what the
 * user wrote: this file is meant to be shareable without a second thought, and
 * that is only true if it never holds their text.
 */
object DebugLog {

    /** Entries kept in memory. A few hundred covers a whole session's events. */
    private const val CAPACITY = 500

    /** Crash records kept on disk; older ones are dropped as new ones land. */
    private const val CRASH_CAPACITY = 10

    private const val CRASH_DIR = "debug"
    private const val CRASH_FILE = "crashes.log"

    /** Separator between crash records in the file, on its own line. */
    private const val CRASH_SEPARATOR = "---- crash ----"

    /**
     * Process the crash screen runs in — see the `android:process` on
     * [CrashReportActivity] in the manifest. It has to be a different process
     * from the one that crashed, because that one is about to be killed.
     */
    const val CRASH_PROCESS_SUFFIX = ":crash"

    /**
     * How long the dying process waits after asking for the crash screen. The
     * activity manager has to spawn a whole process; killing ourselves the
     * instant `startActivity` returns sometimes takes the launch down with us.
     */
    private const val LAUNCH_GRACE_MILLIS = 200L

    private val entries = ArrayDeque<LogEntry>(CAPACITY)

    /** Set by [attach]; device-protected so it is writable from boot. */
    @Volatile
    private var crashFile: File? = null

    /** Set by [attach]; what the crash screen is launched from. */
    @Volatile
    private var appContext: Context? = null

    /**
     * The whole flavour name, which only `:app` knows.
     *
     * Everything in this module compiles against `:core:config`, and that
     * module carries the `capabilities` dimension alone — so `BuildConfig.FLAVOR`
     * here reads `full`, never `fullIntl` or `fullEn`. The two language builds
     * are separate R8 runs whose mappings are not interchangeable, so a record
     * that cannot name its language build cannot be retraced against the right
     * one. `WMApplication` fills this in on every process start, before any
     * handler is installed.
     */
    @Volatile
    private var buildTag: String? = null

    @Volatile
    private var handlerInstalled = false

    /** Guards against a crash *inside* the crash path looping the handler. */
    @Volatile
    private var handlingCrash = false

    /** See [buildTag]. Called once per process from `WMApplication`. */
    fun setBuildTag(flavor: String) {
        buildTag = flavor
    }

    fun d(tag: String, message: String) = record(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = record(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = record(LogLevel.WARN, tag, message)

    fun e(tag: String, message: String, error: Throwable? = null) =
        record(LogLevel.ERROR, tag, if (error == null) message else "$message: ${error.summary()}")

    @Synchronized
    private fun record(level: LogLevel, tag: String, message: String) {
        if (entries.size >= CAPACITY) entries.removeFirst()
        entries.addLast(LogEntry(System.currentTimeMillis(), level, tag, message))
    }

    /** Everything recorded this session, oldest first. */
    @Synchronized
    fun snapshot(): List<LogEntry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
    }

    /**
     * Points the crash record at device-protected storage and installs the
     * uncaught-exception handler. Idempotent — both the keyboard service and the
     * settings app call it on create, and they may share a process.
     *
     * The previous handler is chained rather than replaced: Android's default is
     * what actually ends the process, and skipping it would leave a dead app on
     * screen instead of a crash.
     *
     * The one exception is a diagnostic build ([BuildConfig.ENABLE_CRASH_SCREEN]),
     * where the record also goes to [CrashReportActivity] in another process and
     * *that* screen replaces Android's crash dialog — so this process kills
     * itself instead of chaining, or the platform dialog would come up over the
     * report. Nothing is lost: the trace is already on disk and in the intent
     * before the kill.
     */
    @Synchronized
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        useCrashFile(context)
        if (handlerInstalled) return
        handlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val record = runCatching { writeCrash(thread.name, error) }.getOrNull().orEmpty()
            val reported = if (BuildConfig.ENABLE_CRASH_SCREEN && !handlingCrash) {
                handlingCrash = true
                showCrashScreen(record)
            } else {
                false
            }
            if (reported) {
                // Deliberately not chaining: see the note above. SIGKILL to
                // ourselves rather than System.exit, which would run shutdown
                // hooks and finalizers inside a process that has already lost
                // whatever invariant just threw.
                android.os.Process.killProcess(android.os.Process.myPid())
            } else {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    /**
     * Points [crashes] at the on-disk record without installing a handler —
     * what the crash screen's own process needs, since installing one there
     * would let a failure in the report screen launch another report screen.
     */
    @Synchronized
    fun useCrashFile(context: Context) {
        if (crashFile != null) return
        crashFile = runCatching {
            val dir = File(DirectBoot.deviceContext(context).filesDir, CRASH_DIR)
            dir.mkdirs()
            File(dir, CRASH_FILE)
        }.getOrNull()
    }

    /**
     * Starts the crash screen in its own process and reports whether the launch
     * got through. False for every case where it cannot — no context yet, a
     * background crash the platform refuses to let start an activity, a locked
     * device, or a crash that happened in the crash process itself — and the
     * caller falls back to Android's own handler.
     */
    private fun showCrashScreen(record: String): Boolean {
        val context = appContext ?: return false
        if (isCrashProcess()) return false
        val launched = runCatching {
            context.startActivity(
                Intent(context, CrashReportActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION,
                    )
                    .putExtra(CrashReportActivity.EXTRA_REPORT, record),
            )
        }.isSuccess
        if (!launched) return false
        runCatching { Thread.sleep(LAUNCH_GRACE_MILLIS) }
        return true
    }

    /** This process's name, or "" on a device that will not say. */
    fun processName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.app.Application.getProcessName()
        } else {
            runCatching {
                // cmdline is NUL-padded, so the name has to be cut at the
                // first zero byte; trailing NULs would defeat the
                // endsWith check below.
                File("/proc/self/cmdline").readText().takeWhile { it.code != 0 }.trim()
            }.getOrDefault("")
        }

    /** Whether this is the throwaway process the crash screen runs in. */
    fun isCrashProcess(): Boolean = processName().endsWith(CRASH_PROCESS_SUFFIX)

    /** What a record names as the build: the whole flavour once :app has said. */
    private val flavorTag: String
        get() = buildTag ?: BuildConfig.FLAVOR

    private val channelTag: String
        get() = when {
            BuildConfig.ENABLE_PLAY_STORE -> " (Play Store)"
            BuildConfig.ENABLE_FDROID -> " (F-Droid)"
            else -> ""
        }

    /**
     * Appends a crash record, trimming the file to [CRASH_CAPACITY] records,
     * and hands the record back so the crash screen can be shown it directly —
     * it must not depend on the file, which is exactly the thing that may have
     * failed to write on a locked or full device.
     */
    private fun writeCrash(threadName: String, error: Throwable): String {
        val record = buildString {
            appendLine(CRASH_SEPARATOR)
            appendLine("time: ${timestamp(System.currentTimeMillis())}")
            appendLine("thread: $threadName")
            appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) $flavorTag$channelTag")
            appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine(error.stackTraceToString())
            // What the app was doing on the way in is usually the whole story,
            // and the ring is about to die with the process.
            appendLine("recent:")
            snapshot().takeLast(40).forEach { appendLine("  ${it.format()}") }
        }
        val file = crashFile
        if (file != null) {
            val existing = runCatching { file.readText() }.getOrDefault("")
            val kept = (existing + record)
                .split(CRASH_SEPARATOR)
                .filter { it.isNotBlank() }
                .takeLast(CRASH_CAPACITY)
                .joinToString("") { "$CRASH_SEPARATOR$it" }
            runCatching { file.writeText(kept) }
        }
        return record
    }

    /** Crash records written since the last [clearCrashes], oldest first. */
    fun crashes(): String = runCatching { crashFile?.readText().orEmpty() }.getOrDefault("")

    fun clearCrashes() {
        runCatching { crashFile?.delete() }
    }

    /**
     * Which build and which device — the four lines every report starts with.
     *
     * Separate from [exportText] because a report is often not shared whole.
     * Someone copies the crash out of the viewer and pastes that alone, and
     * without these lines nothing downstream can tell an F-Droid build from a
     * Play one, or 0.5.8 from 0.5.9 — which is exactly what deciding whether a
     * stack trace can be read at all depends on. So whatever hands out a piece
     * of a report puts this in front of it.
     */
    fun headerText(): String = buildString {
        appendLine("WM Keyboard diagnostics")
        appendLine(
            "version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                "$flavorTag$channelTag ${BuildConfig.BUILD_TYPE}",
        )
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("taken: ${timestamp(System.currentTimeMillis())}")
    }

    /**
     * The whole report, ready to share: a header saying what build and device
     * this is, then the crashes, then the session's entries.
     */
    fun exportText(): String = buildString {
        append(headerText())
        appendLine()
        val crashes = crashes()
        if (crashes.isNotBlank()) {
            appendLine("== crashes ==")
            appendLine(crashes)
            appendLine()
        }
        appendLine("== session ==")
        snapshot().forEach { appendLine(it.format()) }
    }

    /**
     * This process's own Android log, which is where anything the app did not
     * record itself ends up — the libraries, the framework's complaints about
     * our windows, the stack trace of a crash Android caught first.
     *
     * Reading it needs no permission since Android 4.1: an app can only ever see
     * its own entries. Returns an empty string on the devices that refuse.
     */
    fun systemLog(maxLines: Int = 500): String = runCatching {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "time", "--pid=${android.os.Process.myPid()}",
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text.lineSequence().toList().takeLast(maxLines).joinToString("\n")
    }.getOrDefault("")

    private fun LogEntry.format(): String =
        "${timestamp(timeMillis)}  ${level.name.padEnd(5)} $tag: $message"

    /** Cause chain flattened to one line — the message alone often says nothing. */
    private fun Throwable.summary(): String {
        val chain = generateSequence(this) { it.cause }.take(4)
        return chain.joinToString(" ← ") { "${it::class.java.simpleName}: ${it.message.orEmpty()}" }
    }

    private fun timestamp(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(java.util.Date(millis))
}
