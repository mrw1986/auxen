package io.github.auxen.provider.tidal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalAuthTest {

    @Test
    fun credentialsConfiguredRequiresBothValues() {
        assertTrue(TidalAuth.credentialsConfigured("id", "secret"))
        assertFalse(TidalAuth.credentialsConfigured("", "secret"))
        assertFalse(TidalAuth.credentialsConfigured("id", ""))
        assertFalse(TidalAuth.credentialsConfigured("  ", "secret"))
        assertFalse(TidalAuth.credentialsConfigured("", ""))
    }
}
