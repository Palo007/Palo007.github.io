// HAND-OWNED (quick-log). JVM tests for the credential path (TWA-C5 Keystore failure, TWA-C3 p0/prefix handling).
package io.github.palo007.twa.quicklog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.ProviderException

class CredentialCryptoTest {
    private val secret = "{\"refresh_token\":\"not-a-real-token\"}"

    /** DL-10: a Keystore failure must not crash and must never hand back plain text. */
    @Test fun sealOrNullReturnsNullWhenKeystoreThrows() {
        val logged = mutableListOf<String>()
        val out = DropboxAuth.sealOrNull(secret, sealer = { throw ProviderException("keystore down") }, log = { logged += it })
        assertNull(out)
        assertEquals(1, logged.size)
        assertFalse("log must not carry the credential", logged[0].contains("not-a-real-token"))
    }

    @Test fun sealOrNullPassesThroughOnSuccess() {
        assertEquals("k1:sealed", DropboxAuth.sealOrNull(secret, sealer = { "k1:sealed" }, log = {}))
    }

    /** On the JVM Build.VERSION.SDK_INT reads 0, so seal takes the API 21-22 "p0:" path. */
    @Test fun p0RoundTripOnOldApi() {
        val sealed = CredentialCrypto.seal(secret)
        assertEquals("p0:$secret", sealed)
        assertEquals(secret, CredentialCrypto.open(sealed))
    }

    @Test fun openUnknownPrefixIsNull() {
        assertNull(CredentialCrypto.open("x1:abc"))
        assertNull(CredentialCrypto.open(""))
    }

    @Test fun openK1BelowApi23IsNull() {
        assertNull(CredentialCrypto.open("k1:aXY=:Y3Q="))
    }
}
