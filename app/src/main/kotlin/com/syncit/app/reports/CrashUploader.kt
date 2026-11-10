package com.syncit.app.reports

import android.content.Context
import android.os.Build
import android.util.Log
import com.syncit.app.BuildConfig
import com.syncit.core.social.AppReports
import com.syncit.core.social.DeviceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Sends the pending crash reports, encrypted for every staff phone (ReportCrypto). Runs after sign-in.
 * Nothing is sent before a staff phone has published its key: reports wait on the phone (up to 7
 * days) rather than being stored in a form nobody can read. The rules allow one upload per 5 s.
 */
class CrashUploader(private val context: Context, private val reports: AppReports) {
    private val lock = Mutex()

    suspend fun run(uid: String, username: String?) = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching { reports.recordDevice(thisDevice()) }
            val files = CrashCatcher.pending(context)
            if (files.isEmpty()) return@withContext
            val keys = try {
                reports.staffKeys()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Staff keys not loaded", e)
                return@withContext
            }
            if (keys.isEmpty()) return@withContext
            files.forEachIndexed { i, file ->
                if (i > 0) delay(UPLOAD_GAP_MS)
                try {
                    val report = JSONObject(file.readText()).put("uid", uid)
                    username?.let { report.put("username", it) }
                    val sealed = ReportCrypto.seal(report.toString().toByteArray(), keys)
                    reports.upload(sealed.iv, sealed.box, sealed.keys, report.optLong("at", System.currentTimeMillis()))
                    file.delete()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A broken file would block the queue forever; anything else is retried next time.
                    if (e is org.json.JSONException) file.delete()
                    Log.d(TAG, "Crash report not sent", e)
                }
            }
        }
    }

    private fun thisDevice() = DeviceInfo(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        model = "${Build.MANUFACTURER} ${Build.MODEL}",
        android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    )

    private companion object {
        const val TAG = "SyncItReports"
        const val UPLOAD_GAP_MS = 6_000L
    }
}
