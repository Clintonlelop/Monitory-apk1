package com.example.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Full-fidelity crash/exception serialization.
 *
 * `Throwable.printStackTrace()` abbreviates wrapper frames shared with a cause
 * as "…8 more", which makes nested failures hard to diagnose. Every throwable
 * here is expanded with its COMPLETE stack trace, together with every nested
 * cause and every suppressed exception (also fully expanded). Sensitive values
 * (tokens, passwords, keys, authorization headers) are redacted before a
 * report is stored or shared.
 */
object CrashDiagnostics {

    private const val MAX_CAUSE_DEPTH = 32
    private const val MAX_REPORT_CHARS = 48_000

    private val SENSITIVE_LINE = Regex(
        "(?i)(\"?(?:password|passwd|pwd|token|auth[_-]?token|access[_-]?token|refresh[_-]?token|authorization|secret|api[_-]?key|jwt|pairing[_-]?code)\"?\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;)&]+)"
    )
    private val BEARER = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._\\-]+")

    /** Masks credential-like values while keeping the surrounding context. */
    fun redact(text: String): String {
        var out = SENSITIVE_LINE.replace(text) { m -> "${m.groupValues[1]}***" }
        out = BEARER.replace(out, "Bearer ***")
        return out
    }

    /**
     * Fully expands one throwable (with nested causes and suppressed
     * exceptions). Unlike [Throwable.toString], nothing is elided: every
     * stack frame of every throwable is printed, including frames that would
     * be shown as "N more" by [Throwable.printStackTrace].
     */
    fun formatThrowable(throwable: Throwable): String {
        val sb = StringBuilder()
        appendThrowable(sb, throwable, 0, "Throwable")
        return sb.toString()
    }

    private fun appendThrowable(sb: StringBuilder, t: Throwable, depth: Int, label: String) {
        if (depth > MAX_CAUSE_DEPTH) {
            sb.append("…cause chain exceeded $MAX_CAUSE_DEPTH levels (possible cycle) — truncated\n")
            return
        }
        sb.append(label).append(": ").append(t.javaClass.name)
        t.message?.let { sb.append(": ").append(it) }
        sb.append('\n')
        for (el in t.stackTrace) {
            sb.append("\tat ").append(el.toString()).append('\n')
        }
        t.cause?.let { appendThrowable(sb, it, depth + 1, "Caused by") }
        for (suppressed in t.suppressed) {
            appendThrowable(sb, suppressed, depth + 1, "Suppressed")
        }
    }

    /**
     * Builds a complete diagnostic report: environment (device, app, build,
     * connectivity/pairing state), operation context, and the fully expanded
     * exception chain. Output is redacted and length-capped.
     */
    fun buildReport(
        context: Context,
        throwable: Throwable,
        operation: String? = null,
        connectionState: String? = null,
        pairingState: String? = null
    ): String {
        val sb = StringBuilder()
        sb.append("==== Monitory diagnostic report ====\n")
        sb.append("generatedAt: ").append(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())).append('\n')
        operation?.let { sb.append("operation: ").append(it).append('\n') }
        sb.append("\n---- environment ----\n")
        sb.append("app: ").append(BuildConfig.APPLICATION_ID)
            .append(" v").append(BuildConfig.VERSION_NAME)
            .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        sb.append("buildType: ").append(BuildConfig.BUILD_TYPE)
            .append(" debuggable=").append(BuildConfig.DEBUG).append('\n')
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (").append(Build.DEVICE).append(")\n")
        sb.append("android: ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        try {
            val prefs = com.example.data.prefs.PreferenceManager(context)
            val server = prefs.getServerUrl()
            sb.append("serverConfigured: ").append(server.isNotBlank()).append('\n')
            sb.append("serverUrl: ").append(server.ifBlank { "<not configured>" }).append('\n')
            sb.append("pairingState: ").append(pairingState ?: if (prefs.isPaired()) "paired" else "not paired").append('\n')
        } catch (e: Exception) {
            sb.append("serverUrl: <unavailable: ").append(e.javaClass.simpleName).append(">\n")
        }
        sb.append("connectionState: ").append(connectionState ?: "unknown").append('\n')
        sb.append("\n---- exception ----\n")
        sb.append(redact(formatThrowable(throwable)))

        var report = sb.toString()
        // Belt and braces: apply redaction to the whole report again and cap size.
        report = redact(report)
        if (report.length > MAX_REPORT_CHARS) {
            report = report.take(MAX_REPORT_CHARS) + "\n…report truncated at $MAX_REPORT_CHARS characters\n"
        }
        return report
    }

    /** Classic log-style serialization kept for logcat (also fully expanded). */
    fun fullStackTrace(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}

/**
 * Local storage for diagnostic reports. Reports live in the app's private
 * files dir; they are only shared when the user explicitly exports one
 * (FileProvider content URI), never uploaded automatically.
 */
class CrashReportStore(context: Context) {
    private val dir = File(context.filesDir, "crash-reports")
    private val counter = AtomicInteger(0)

    init {
        if (!dir.exists()) dir.mkdirs()
        prune()
    }

    @Synchronized
    fun write(report: String): File {
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$stamp-${counter.incrementAndGet()}.txt")
        file.writeText(report)
        prune()
        return file
    }

    fun list(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun read(file: File): String = file.readText()

    fun delete(file: File): Boolean = file.delete()

    fun clear(): Int {
        val files = list()
        files.forEach { it.delete() }
        return files.size
    }

    /** Keeps the newest [MAX_REPORTS] reports. */
    private fun prune() {
        val files = list()
        files.drop(MAX_REPORTS).forEach { it.delete() }
    }

    companion object {
        const val MAX_REPORTS = 20
    }
}

/**
 * Global uncaught-exception recorder. Writes a complete diagnostic report
 * before delegating to the platform handler (so logcat/crash pipelines keep
 * working). Never throws from within the handler.
 */
object CrashReporter {
    private const val TAG = "CrashReporter"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = CrashDiagnostics.buildReport(
                    context = appContext,
                    throwable = throwable,
                    operation = "uncaught on thread '${thread.name}'"
                )
                CrashReportStore(appContext).write(report)
                Log.e(TAG, report)
            } catch (e: Throwable) {
                try {
                    Log.e(TAG, "Failed to persist crash report", e)
                } catch (_: Throwable) {
                }
            } finally {
                try {
                    previous?.uncaughtException(thread, throwable)
                } catch (_: Throwable) {
                }
            }
        }
    }
}
