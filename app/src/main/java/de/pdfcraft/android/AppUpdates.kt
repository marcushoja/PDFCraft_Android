package de.pdfcraft.android

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** No document information, credentials or device identifiers are sent to GitHub. */
internal object AppUpdates {
    private const val API = "https://api.github.com/repos/marcushoja/PDFCraft_Android/releases/latest"
    private val worker = Executors.newSingleThreadExecutor()
    private val checking = AtomicBoolean(false)
    private val callbacks = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    private class Preferences(context: Context) : UpdateState {
        private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        override val lastSuccess get() = prefs.getLong("last_success", 0L)
        override val release: AppRelease? get() {
            val code = prefs.getInt("release_code", 0)
            return if (code > 0) AppRelease(code, AppRelease.downloadUrl(code)) else null
        }
        override var dismissedVersion: Int
            get() = prefs.getInt("dismissed_code", 0)
            set(value) { prefs.edit().putInt("dismissed_code", maxOf(value, dismissedVersion)).apply() }
        override fun saveSuccess(time: Long, release: AppRelease?) {
            prefs.edit().putLong("last_success", time)
                .putInt("release_code", release?.versionCode ?: 0).apply()
        }
    }

    @Suppress("DEPRECATION")
    private fun installed(context: Context): Int =
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode

    fun available(context: Context): AppRelease? =
        UpdateCheck(Preferences(context), ::fetchLatest).available(installed(context))

    fun dismiss(context: Context, release: AppRelease) {
        Preferences(context).dismissedVersion = release.versionCode
    }

    private fun online(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        return network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun checkManually(context: Context, onComplete: (UpdateResult) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val result = try {
                UpdateCheck(Preferences(app), ::fetchLatest)
                    .checkManually(System.currentTimeMillis(), installed(app), online(app))
            } catch (_: Exception) { UpdateResult(UpdateStatus.FAILED) }
            main.post { onComplete(result) }
        }
    }

    @Synchronized
    fun check(context: Context, onComplete: () -> Unit) {
        callbacks.add(onComplete)
        if (!checking.compareAndSet(false, true)) return
        val app = context.applicationContext
        worker.execute {
            try {
                UpdateCheck(Preferences(app), ::fetchLatest).check(System.currentTimeMillis(), installed(app), online(app))
            } catch (_: Exception) {
                // Connectivity changes and network errors are deliberately silent.
            } finally {
                val complete = synchronized(this) {
                    checking.set(false)
                    callbacks.toList().also { callbacks.clear() }
                }
                main.post { complete.forEach { it() } }
            }
        }
    }

    fun downloadIntent(release: AppRelease): Intent {
        require(release.versionCode > 0 && release.downloadUrl == AppRelease.downloadUrl(release.versionCode))
        return Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)).addCategory(Intent.CATEGORY_BROWSABLE)
    }

    private fun fetchLatest(): AppRelease? {
        val connection = URL(API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "PDFCraft-Android-UpdateCheck")
            // GitHub returns 404 when no published release exists yet.
            if (connection.responseCode == 404) return null
            if (connection.responseCode != 200) throw IOException("Release check failed")
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (text.length + count > 512 * 1024) throw IOException("Release response too large")
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
            return parseRelease(json)
        } finally { connection.disconnect() }
    }

    internal fun parseRelease(json: String): AppRelease? {
        val release = JSONObject(json)
        if (release.getBoolean("draft") || release.getBoolean("prerelease")) return null
        val tag = release.getString("tag_name")
        val code = Regex("build-([1-9][0-9]*)").matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException("Unsupported release tag")
        val expected = AppRelease.downloadUrl(code)
        val assets = release.getJSONArray("assets")
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            if (asset.optString("name") == "PDFCraft.apk" &&
                asset.optString("browser_download_url") == expected && asset.optLong("size") > 0L) {
                return AppRelease(code, expected)
            }
        }
        // Incomplete releases must not suppress another attempt for a whole day.
        throw IOException("Release has no valid APK")
    }
}
