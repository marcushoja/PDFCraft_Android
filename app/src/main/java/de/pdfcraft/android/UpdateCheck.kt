package de.pdfcraft.android

data class AppRelease(val versionCode: Int, val downloadUrl: String) {
    val versionName: String get() = "0.1.${versionCode - 1}"

    companion object {
        const val REPOSITORY = "https://github.com/marcushoja/PDFCraft_Android"
        fun downloadUrl(code: Int) = "$REPOSITORY/releases/download/build-$code/PDFCraft.apk"
    }
}

internal interface UpdateState {
    val lastSuccess: Long
    val release: AppRelease?
    var dismissedVersion: Int
    fun saveSuccess(time: Long, release: AppRelease?)
}

internal enum class UpdateStatus { AVAILABLE, CURRENT, OFFLINE, FAILED }
internal data class UpdateResult(val status: UpdateStatus, val release: AppRelease? = null)

/** Only successful checks consume the daily interval. Failed/offline checks can retry next launch. */
internal class UpdateCheck(private val state: UpdateState, private val fetch: () -> AppRelease?) {
    fun checkManually(now: Long, installed: Int, online: Boolean): UpdateResult {
        if (!online) return UpdateResult(UpdateStatus.OFFLINE)
        return try {
            val release = fetch()
            state.saveSuccess(now, release)
            // An explicit check also offers a release whose automatic notice was dismissed.
            if (release != null && release.versionCode > installed &&
                release.downloadUrl == AppRelease.downloadUrl(release.versionCode))
                UpdateResult(UpdateStatus.AVAILABLE, release)
            else UpdateResult(UpdateStatus.CURRENT)
        } catch (_: Exception) { UpdateResult(UpdateStatus.FAILED) }
    }

    fun available(installed: Int): AppRelease? = state.release?.takeIf {
        it.versionCode > installed && it.versionCode > state.dismissedVersion &&
            it.downloadUrl == AppRelease.downloadUrl(it.versionCode)
    }

    fun check(now: Long, installed: Int, online: Boolean): AppRelease? {
        val last = state.lastSuccess
        val recent = last > 0L && now >= last && now - last < DAY
        if (online && !recent) {
            try {
                val release = fetch()
                state.saveSuccess(now, release)
            } catch (_: Exception) {
                // Keep any cached notice, but do not postpone the next attempt.
            }
        }
        return available(installed)
    }

    companion object { const val DAY = 24 * 60 * 60 * 1000L }
}
