package de.pdfcraft.android

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UpdateCheckTest {
    private class MemoryState : UpdateState {
        override var lastSuccess = 0L
        override var release: AppRelease? = null
        override var dismissedVersion = 0
        override fun saveSuccess(time: Long, release: AppRelease?) {
            lastSuccess = time
            this.release = release
        }
    }
    private val now = 1_800_000_000_000L
    private fun release(code: Int) = AppRelease(code, AppRelease.downloadUrl(code))

    @Test fun manualCheckBypassesDailyIntervalAndDismissedNotice() {
        val state = MemoryState().apply { saveSuccess(now, release(42)); dismissedVersion = 42 }
        var requests = 0
        val checker = UpdateCheck(state) { requests++; release(42) }
        assertNull(checker.check(now + 1000, 41, true))
        assertEquals(0, requests)
        assertEquals(UpdateResult(UpdateStatus.AVAILABLE, release(42)), checker.checkManually(now + 1000, 41, true))
        assertEquals(1, requests)
        assertEquals(now + 1000, state.lastSuccess)
        assertEquals(42, state.dismissedVersion)
    }

    @Test fun manualCheckReportsOfflineFailureAndCurrentWithoutConsumingFailedAttempts() {
        val state = MemoryState()
        val checker = UpdateCheck(state) { throw IOException() }
        assertEquals(UpdateStatus.OFFLINE, checker.checkManually(now, 41, false).status)
        assertEquals(UpdateStatus.FAILED, checker.checkManually(now, 41, true).status)
        assertEquals(0L, state.lastSuccess)
        assertEquals(UpdateStatus.CURRENT, UpdateCheck(state) { release(41) }.checkManually(now, 41, true).status)
        assertEquals(now, state.lastSuccess)
    }

    @Test fun successfulCheckRunsOnceInTwentyFourHoursAndReusesCachedNotice() {
        val state = MemoryState()
        var requests = 0
        val checker = UpdateCheck(state) { requests++; release(42) }
        assertEquals(release(42), checker.check(now, 41, true))
        assertEquals(now, state.lastSuccess)
        assertEquals(release(42), checker.check(now + UpdateCheck.DAY - 1L, 41, true))
        assertEquals(1, requests)
        assertEquals(release(42), checker.check(now + UpdateCheck.DAY, 41, true))
        assertEquals(2, requests)
    }

    @Test fun offlineLaunchDoesNotStartRequestOrConsumeDailyAttempt() {
        val state = MemoryState()
        var requests = 0
        val checker = UpdateCheck(state) { requests++; release(42) }
        assertNull(checker.check(now, 41, false))
        assertNull(checker.check(now + 1000, 41, false))
        assertEquals(0, requests)
        assertEquals(0L, state.lastSuccess)
        assertEquals(release(42), checker.check(now + 2000, 41, true))
        assertEquals(1, requests)
    }

    @Test fun timeoutRateLimitAndMalformedResponseCanRetryOnNextOpening() {
        val state = MemoryState()
        var requests = 0
        val checker = UpdateCheck(state) {
            requests++
            if (requests < 3) throw IOException("Network or response failure")
            release(42)
        }
        assertNull(checker.check(now, 41, true))
        assertNull(checker.check(now + 1000, 41, true))
        assertEquals(0L, state.lastSuccess)
        assertEquals(release(42), checker.check(now + 2000, 41, true))
        assertEquals(3, requests)
    }

    @Test fun failedRefreshKeepsCachedReleaseAndLastSuccessfulTime() {
        val state = MemoryState().apply { saveSuccess(now - UpdateCheck.DAY, release(42)) }
        val checker = UpdateCheck(state) { throw IOException() }
        assertEquals(release(42), checker.check(now, 41, true))
        assertEquals(now - UpdateCheck.DAY, state.lastSuccess)
        assertEquals(release(42), checker.check(now + 1000, 41, false))
    }

    @Test fun upToDateOrNewerInstallationNeverGetsDowngradeNotice() {
        for (installed in listOf(42, 43)) {
            val state = MemoryState()
            val checker = UpdateCheck(state) { release(42) }
            assertNull(checker.check(now, installed, true))
            assertEquals(now, state.lastSuccess)
        }
    }

    @Test fun dismissPersistsAcrossLaunchesButDoesNotHideTheNextRelease() {
        val state = MemoryState()
        assertEquals(release(42), UpdateCheck(state) { release(42) }.check(now, 41, true))
        state.dismissedVersion = 42
        val nextLaunch = UpdateCheck(state) { release(43) }
        assertNull(nextLaunch.check(now + 1000, 41, true))
        assertEquals(release(43), nextLaunch.check(now + UpdateCheck.DAY, 41, true))
        assertEquals(42, state.dismissedVersion)
        state.dismissedVersion = 44
        assertNull(nextLaunch.available(41))
    }

    @Test fun noPublishedReleaseIsASuccessfulDailyCheckAndClearsStaleNotice() {
        val state = MemoryState().apply { release = release(42) }
        assertNull(UpdateCheck(state) { null }.check(now, 41, true))
        assertEquals(now, state.lastSuccess)
        assertNull(state.release)
    }

    @Test fun clockRollbackDoesNotPreventFutureChecks() {
        val state = MemoryState().apply { saveSuccess(now + UpdateCheck.DAY, null) }
        assertEquals(release(42), UpdateCheck(state) { release(42) }.check(now, 41, true))
        assertEquals(now, state.lastSuccess)
    }

    @Test fun onlyOfficialVersionSpecificDownloadUrlsAreOffered() {
        val state = MemoryState().apply { release = AppRelease(42, "https://example.com/PDFCraft.apk") }
        assertNull(UpdateCheck(state) { null }.available(41))
        assertEquals("0.1.41", release(42).versionName)
        assertEquals("https://github.com/marcushoja/PDFCraft_Android/releases/download/build-42/PDFCraft.apk",
            release(42).downloadUrl)
    }
}
