// HAND-OWNED (quick-log). JVM tests for the bounded upload retry policy (TWA-C4).
package io.github.palo007.twa.quicklog

import com.dropbox.core.v2.files.UploadError
import com.dropbox.core.v2.files.UploadErrorException
import com.dropbox.core.v2.files.UploadWriteFailed
import com.dropbox.core.v2.files.WriteConflictError
import com.dropbox.core.v2.files.WriteError
import io.github.palo007.twa.quicklog.UploadPolicy.Action
import io.github.palo007.twa.quicklog.UploadPolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadPolicyTest {
    private fun ex(reason: WriteError) =
        UploadErrorException("2/files/upload", "req", null, UploadError.path(UploadWriteFailed(reason, "s")))

    @Test fun conflictDeletes() {
        assertEquals(Action.DELETE, UploadPolicy.decide(Kind.CONFLICT, 0))
        assertEquals(Action.DELETE, UploadPolicy.decide(Kind.CONFLICT, 1000))
    }

    @Test fun disallowedNameRejects() {
        assertEquals(Action.REJECT, UploadPolicy.decide(UploadPolicy.kindOf(ex(WriteError.DISALLOWED_NAME)), 1))
        assertEquals(Action.REJECT, UploadPolicy.decide(Kind.TERMINAL, 1))
    }

    @Test fun otherUploadErrorRejectsAfterMax() {
        val max = UploadPolicy.MAX_ATTEMPTS
        assertEquals(30, max)
        for (k in listOf(Kind.OTHER_UPLOAD, Kind.DBX_OTHER)) {
            assertEquals(Action.RETRY, UploadPolicy.decide(k, 1))
            assertEquals(Action.RETRY, UploadPolicy.decide(k, max - 1))
            assertEquals(Action.REJECT, UploadPolicy.decide(k, max))
            assertEquals(Action.REJECT, UploadPolicy.decide(k, max + 5))
        }
    }

    @Test fun networkNeverRejects() {
        assertEquals(Action.RETRY, UploadPolicy.decide(Kind.NETWORK, 1000))
    }

    @Test fun onlyNonNetworkErrorsCount() {
        assertFalse("network errors never count", UploadPolicy.counts(Kind.NETWORK))
        assertTrue(UploadPolicy.counts(Kind.OTHER_UPLOAD))
        assertTrue(UploadPolicy.counts(Kind.DBX_OTHER))
    }

    // DL-24: a Dropbox outage must never strand taps.
    private fun afterMax(e: com.dropbox.core.DbxException) =
        UploadPolicy.decide(UploadPolicy.kindOfDbx(e), UploadPolicy.MAX_ATTEMPTS * 10)

    @Test fun rateLimitNeverRejects() {
        val e = com.dropbox.core.RateLimitException("req", "429", 5, java.util.concurrent.TimeUnit.SECONDS)
        assertFalse(UploadPolicy.counts(UploadPolicy.kindOfDbx(e)))
        assertEquals(Action.RETRY, afterMax(e))
        assertEquals(Action.RETRY, afterMax(com.dropbox.core.RetryException("req", "503 retry later")))
    }

    @Test fun serverErrorNeverRejects() {
        val e = com.dropbox.core.ServerException("req", "500")
        assertFalse(UploadPolicy.counts(UploadPolicy.kindOfDbx(e)))
        assertEquals(Action.RETRY, afterMax(e))
    }

    @Test fun permanentErrorStillRejectsAfterMax() {
        val e = com.dropbox.core.BadRequestException("req", "400")
        assertTrue(UploadPolicy.counts(UploadPolicy.kindOfDbx(e)))
        assertEquals(Action.RETRY, UploadPolicy.decide(UploadPolicy.kindOfDbx(e), UploadPolicy.MAX_ATTEMPTS - 1))
        assertEquals(Action.REJECT, UploadPolicy.decide(UploadPolicy.kindOfDbx(e), UploadPolicy.MAX_ATTEMPTS))
        assertEquals(Action.RETRY,
            afterMax(com.dropbox.core.NetworkIOException(java.io.IOException("offline"))))
        assertEquals(Action.REJECT, UploadPolicy.decide(UploadPolicy.kindOf(ex(WriteError.DISALLOWED_NAME)), 1))
    }

    // MA-1: only a same-name FILE conflict proves this tap is already on Dropbox.
    @Test fun onlyFileConflictDeletes() {
        assertEquals(Action.DELETE, UploadPolicy.decide(UploadPolicy.kindOf(ex(WriteError.conflict(WriteConflictError.FILE))), 0))
        for (c in listOf(WriteConflictError.FOLDER, WriteConflictError.FILE_ANCESTOR, WriteConflictError.OTHER)) {
            val k = UploadPolicy.kindOf(ex(WriteError.conflict(c)))
            for (n in listOf(0, 1, UploadPolicy.MAX_ATTEMPTS - 1, UploadPolicy.MAX_ATTEMPTS, 1000)) {
                assertTrue("$c must never delete (attempts $n)", UploadPolicy.decide(k, n) != Action.DELETE)
            }
            assertTrue("$c counts as a permanent error", UploadPolicy.counts(k))
            assertEquals(Action.REJECT, UploadPolicy.decide(k, UploadPolicy.MAX_ATTEMPTS))
        }
    }

    // MA-2 / DL-29: only an error that says THIS file can never be accepted may count.
    @Test fun outageAndAccountErrorsNeverCount() {
        val errs = listOf(
            com.dropbox.core.BadResponseCodeException("req", "500", 500),
            com.dropbox.core.BadResponseCodeException("req", "503", 503),
            com.dropbox.core.oauth.DbxOAuthException("req",
                com.dropbox.core.oauth.DbxOAuthError(com.dropbox.core.oauth.DbxOAuthError.INVALID_GRANT, "refresh failed")),
            com.dropbox.core.InvalidAccessTokenException("req", "401", com.dropbox.core.v2.auth.AuthError.EXPIRED_ACCESS_TOKEN),
            com.dropbox.core.AccessErrorException("req", "403", com.dropbox.core.v2.auth.AccessError.OTHER),
        )
        for (e in errs) {
            val k = UploadPolicy.kindOfDbx(e)
            assertFalse("${e.javaClass.simpleName} ${e.message} must not count", UploadPolicy.counts(k))
            assertEquals(Action.RETRY, afterMax(e))
        }
    }

    @Test fun fullDropboxAndWriteThrottleNeverCount() {
        for (w in listOf(WriteError.INSUFFICIENT_SPACE, WriteError.TOO_MANY_WRITE_OPERATIONS)) {
            val k = UploadPolicy.kindOf(ex(w))
            assertFalse("$w must not count: taps upload after the user frees space", UploadPolicy.counts(k))
            assertEquals(Action.RETRY, UploadPolicy.decide(k, UploadPolicy.MAX_ATTEMPTS * 10))
        }
    }

    @Test fun badResponse4xxStillCounts() {
        assertTrue(UploadPolicy.counts(UploadPolicy.kindOfDbx(com.dropbox.core.BadResponseCodeException("req", "400", 400))))
    }

    @Test fun kindOfMapsSdkErrors() {
        assertEquals(Kind.CONFLICT, UploadPolicy.kindOf(ex(WriteError.conflict(WriteConflictError.FILE))))
        assertEquals(Kind.TERMINAL, UploadPolicy.kindOf(ex(WriteError.DISALLOWED_NAME)))
        assertEquals(Kind.TERMINAL, UploadPolicy.kindOf(ex(WriteError.malformedPath("x"))))
        assertEquals(Kind.OTHER_UPLOAD, UploadPolicy.kindOf(ex(WriteError.conflict(WriteConflictError.FOLDER))))
        assertEquals(Kind.OTHER_UPLOAD,
            UploadPolicy.kindOf(UploadErrorException("2/files/upload", "req", null, UploadError.PAYLOAD_TOO_LARGE)))
    }
}
