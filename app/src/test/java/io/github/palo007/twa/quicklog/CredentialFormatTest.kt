// HAND-OWNED (quick-log). The stored credential must survive Writer -> Reader.
package io.github.palo007.twa.quicklog

import com.dropbox.core.oauth.DbxCredential
import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialFormatTest {
    @Test fun roundTrip() {
        val c = DbxCredential("tok", 4102444800000L, "refresh", DropboxAuth.APP_KEY)
        val json = DbxCredential.Writer.writeToString(c)
        val back = DbxCredential.Reader.readFully(json)
        assertEquals("refresh", back.refreshToken)
        assertEquals(DropboxAuth.APP_KEY, back.appKey)
    }
}
