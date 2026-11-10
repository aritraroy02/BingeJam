package com.syncit.app.reports

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.syncit.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Crash reports from every phone (user's request, 2026-11-10; on for everyone, disclosed in the
 * privacy policy). Catches crashes as they happen and, on Android 11+, freezes ("app not responding")
 * and native crashes that Android recorded, writes each as a small JSON file in the app's private
 * storage, and [CrashUploader] sends them encrypted once someone is signed in. Logs are BingeJam's own
 * lines only, cleaned up and scrubbed of emails, links and tokens.
 */
object CrashCatcher {
    private const val DIR = "crash-reports"
    private const val MAX_PENDING = 10
    private const val KEEP_MS = 7L * 24 * 60 * 60 * 1000
    private const val PREFS = "crash_catcher"
    private const val KEY_LAST_EXIT = "last_exit_at"

    /** The screen on show, set by navigation (a name like "Room", never ids). */
    @Volatile
    var screen: String = "Start"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save(app, fromThrowable(error, thread.name)) }
            previous?.uncaughtException(thread, error)
        }
        runCatching { collectExitReasons(app) }
    }

    // ---- Building reports ----

    private fun fromThrowable(error: Throwable, threadName: String): JSONObject {
        val root = generateSequence(error) { it.cause }.take(8).last()
        val title = root.javaClass.simpleName.ifEmpty { root.javaClass.name }
        val where = root.stackTrace.firstOrNull { it.className.startsWith(APP_PACKAGE) }?.let(::frame)
            ?: root.stackTrace.firstOrNull()?.let(::frame).orEmpty()
        return base("crash", title, where)
            .put("message", LogScrubber.scrub(root.message.orEmpty()).take(500))
            .put("stack", ("Thread: $threadName\n" + cleanStack(error)).take(12_000))
            .put("logs", LogScrubber.recentLogs())
    }

    private fun base(kind: String, title: String, where: String): JSONObject = JSONObject()
        .put("kind", kind)
        .put("sig", signature(kind, title, where))
        .put("title", title.take(200))
        .put("where", where.take(200))
        .put("screen", screen.take(60))
        .put("app", BuildConfig.VERSION_NAME)
        .put("code", BuildConfig.VERSION_CODE)
        .put("model", "${Build.MANUFACTURER} ${Build.MODEL}".take(80))
        .put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        .put("at", System.currentTimeMillis())

    /** The same crash on every phone gets the same signature, so App reports can group them. */
    private fun signature(kind: String, title: String, where: String): String =
        MessageDigest.getInstance("SHA-1").digest("$kind|$title|$where".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)

    /**
     * One block per exception in the cause chain. BingeJam's own frames are kept; runs of Android and
     * library frames are folded into one "… N more" line, which is what makes the trace readable.
     */
    fun cleanStack(error: Throwable): String = buildString {
        generateSequence(error) { it.cause }.take(5).forEachIndexed { i, t ->
            if (i > 0) append("\nCaused by: ")
            append(t.javaClass.name)
            t.message?.let { append(": ").append(LogScrubber.scrub(it).take(300)) }
            append('\n')
            val frames = t.stackTrace
            val hasApp = frames.any { it.className.startsWith(APP_PACKAGE) }
            var folded = 0
            frames.forEachIndexed { index, f ->
                val keep = if (hasApp) f.className.startsWith(APP_PACKAGE) || index == 0 else index < 12
                if (keep) {
                    if (folded > 0) append("    … $folded more\n").also { folded = 0 }
                    append("    at ").append(frame(f)).append('\n')
                } else {
                    folded++
                }
            }
            if (folded > 0) append("    … $folded more\n")
        }
    }

    private fun frame(f: StackTraceElement): String {
        val cls = f.className.removePrefix("$APP_PACKAGE.")
        val line = if (f.lineNumber > 0) "${f.fileName}:${f.lineNumber}" else f.fileName ?: "?"
        return "$cls.${f.methodName} ($line)"
    }

    /** Freezes and native crashes from the last runs (Android 11+), each reported once. */
    private fun collectExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_EXIT, 0L)
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        var newest = last
        for (exit in exits) {
            if (exit.timestamp <= last) continue
            newest = maxOf(newest, exit.timestamp)
            val report = when (exit.reason) {
                ApplicationExitInfo.REASON_ANR -> freezeReport(exit)
                ApplicationExitInfo.REASON_CRASH_NATIVE ->
                    base("native", "Native crash", exit.description.orEmpty().take(200))
                        .put("stack", (exit.description ?: "No details from Android").take(12_000))
                else -> null
            } ?: continue
            report.put("at", exit.timestamp)
            save(context, report)
        }
        if (newest != last) prefs.edit { putLong(KEY_LAST_EXIT, newest) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun freezeReport(exit: ApplicationExitInfo): JSONObject {
        val trace = runCatching { exit.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        // The main thread's block is what froze: from its header to the next blank line.
        val main = trace.lineSequence()
            .dropWhile { !it.startsWith("\"main\"") }
            .takeWhile { it.isNotBlank() }
            .take(80)
            .map { it.trim() }
            .toList()
        val frames = main.filter { it.startsWith("at ") }
        val where = frames.firstOrNull { it.contains(APP_PACKAGE) } ?: frames.firstOrNull().orEmpty()
        return base("freeze", "App not responding", where.removePrefix("at ").removePrefix("$APP_PACKAGE."))
            .put("message", LogScrubber.scrub(exit.description.orEmpty()).take(500))
            .put("stack", (if (main.isEmpty()) "Android kept no trace for this freeze." else main.joinToString("\n")).take(12_000))
    }

    // ---- Pending files ----

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    private fun save(context: Context, report: JSONObject) {
        val d = dir(context)
        File(d, "${System.currentTimeMillis()}-${Process.myPid()}.json").writeText(report.toString())
        d.listFiles()?.sortedByDescending { it.name }?.drop(MAX_PENDING)?.forEach { it.delete() }
    }

    /** Reports waiting to be sent, oldest first; ones older than 7 days are dropped. */
    fun pending(context: Context): List<File> {
        val now = System.currentTimeMillis()
        return dir(context).listFiles().orEmpty()
            .filter { f -> (now - f.lastModified() < KEEP_MS).also { fresh -> if (!fresh) f.delete() } }
            .sortedBy { it.name }
    }

    private const val APP_PACKAGE = "com.syncit"
}

/** BingeJam's own recent log lines, one per line ("12:04:31.207 W SyncItDiag: …"), scrubbed. */
object LogScrubber {
    private const val MAX_LINES = 300
    private const val MAX_CHARS = 24_000
    private val LINE = Regex("""^\d\d-\d\d (\d\d:\d\d:\d\d\.\d{3})\s+\d+\s+\d+\s+([VDIWEF])\s+(.*?)\s*: (.*)$""")
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")
    private val URL = Regex("""https?://\S+""")
    private val BEARER = Regex("""(?i)bearer\s+\S+""")
    private val TOKEN = Regex("""[A-Za-z0-9_\-:]{32,}""")

    fun scrub(text: String): String = text
        .replace(BEARER, "Bearer [token]")
        .replace(URL, "[link]")
        .replace(EMAIL, "[email]")
        .replace(TOKEN, "[token]")

    fun recentLogs(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", MAX_LINES.toString(), "--pid=${Process.myPid()}")
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(2, TimeUnit.SECONDS)
        clean(text)
    }.getOrDefault("")

    fun clean(raw: String): String {
        val lines = raw.lineSequence().mapNotNull { line ->
            val m = LINE.matchEntire(line) ?: return@mapNotNull null
            val (time, level, tag, message) = m.destructured
            "$time $level $tag: ${scrub(message)}"
        }.toList()
        val out = StringBuilder()
        for (line in lines.asReversed()) {
            if (out.length + line.length + 1 > MAX_CHARS) break
            out.insert(0, line + "\n")
        }
        return out.toString().trimEnd()
    }
}
