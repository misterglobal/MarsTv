package tv.mars.app.data.repository

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideRefreshAttemptTest {
    @Test fun completedGuideRefreshReportsSuccess() = runBlocking {
        assertTrue(attemptGuideRefresh(refresh = { }))
    }

    @Test fun ordinaryGuideFailureRemainsRetryable() = runBlocking {
        assertFalse(attemptGuideRefresh(refresh = { throw IOException("offline") }))
    }

    @Test fun cancellationStillInterruptsRefresh() {
        assertThrows(CancellationException::class.java) {
            runBlocking { attemptGuideRefresh(refresh = { throw CancellationException("closed") }) }
        }
    }
}
