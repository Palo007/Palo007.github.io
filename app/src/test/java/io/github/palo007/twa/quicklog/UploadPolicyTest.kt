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

    @Test fun kindOfMapsSdkErrors() {
        assertEquals(Kind.CONFLICT, UploadPolicy.kindOf(ex(WriteError.conflict(WriteConflictError.FILE))))
        assertEquals(Kind.TERMINAL, UploadPolicy.kindOf(ex(WriteError.DISALLOWED_NAME)))
        assertEquals(Kind.TERMINAL, UploadPolicy.kindOf(ex(WriteError.malformedPath("x"))))
        assertEquals(Kind.OTHER_UPLOAD, UploadPolicy.kindOf(ex(WriteError.INSUFFICIENT_SPACE)))
        assertEquals(Kind.OTHER_UPLOAD,
            UploadPolicy.kindOf(UploadErrorException("2/files/upload", "req", null, UploadError.PAYLOAD_TOO_LARGE)))
    }
}
